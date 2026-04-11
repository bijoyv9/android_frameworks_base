/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.axdynamicbar.domain

import android.app.Notification
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings.Global
import com.android.systemui.axdynamicbar.data.IslandEventRepository
import com.android.systemui.axdynamicbar.model.AxDynamicBarIntent
import com.android.systemui.axdynamicbar.model.AxDynamicBarState
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.IslandUiState
import com.android.systemui.axdynamicbar.model.IslandVisibility
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.util.settings.GlobalSettings
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@SysUISingleton
class AxDynamicBarInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: IslandEventRepository,
    val settings: AxDynamicBarSettings,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    val sliderHapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val activityStarter: ActivityStarter,
    private val indicationController: KeyguardIndicationController,
    private val shadeInteractor: ShadeInteractor,
    private val shadeRepository: ShadeRepository,
    private val zenModeController: ZenModeController,
    private val globalSettings: GlobalSettings,
    private val audioManager: AudioManager,
) : IslandActions {

    // ─── State Machine Core ────────────────────────────────────────────────

    /** Single source of truth for all feature state. */
    @Volatile private var _state = AxDynamicBarState()

    /** Public UI state, derived from the internal state machine. */
    private val _uiState = MutableStateFlow(IslandUiState())
    val uiState: StateFlow<IslandUiState> = _uiState.asStateFlow()

    /** Intent channel: all state mutations flow through here. */
    private val intentChannel = Channel<AxDynamicBarIntent>(capacity = Channel.UNLIMITED)

    /** Auto-dismiss jobs keyed by event ID. */
    private val autoDismissJobs = ConcurrentHashMap<String, Job>()

    /** Auto-dismiss job for the notification alert. */
    @Volatile private var notifAlertJob: Job? = null

    // ─── External StateFlows (still needed for UI consumers) ───────────────

    override var onFocusableRequested: ((Boolean) -> Unit)? = null

    var onCollapseRequested: (() -> Unit)? = null

    private var isInitialized = false

    val qsExpansion: StateFlow<Float> = shadeInteractor.qsExpansion

    val legacyShadeExpansion: StateFlow<Float> = shadeRepository.legacyShadeExpansion

    private val _isOnKeyguard = MutableStateFlow(false)
    val isOnKeyguard: StateFlow<Boolean> = _isOnKeyguard.asStateFlow()

    private val _isKeyguardFadingAway = MutableStateFlow(false)
    val isKeyguardFadingAway: StateFlow<Boolean> = _isKeyguardFadingAway.asStateFlow()

    private val _isBouncerShowing = MutableStateFlow(false)
    val isBouncerShowing: StateFlow<Boolean> = _isBouncerShowing.asStateFlow()

    private val _isDozing = MutableStateFlow(statusBarStateController.isDozing)
    val isDozing: StateFlow<Boolean> = _isDozing.asStateFlow()

    private val _dozeAmount = MutableStateFlow(0f)
    val dozeAmount: StateFlow<Float> = _dozeAmount.asStateFlow()

    // Backing property for isPanelExpanded (still exposed for external consumers)
    private val _isPanelExpanded = MutableStateFlow(false)
    val isPanelExpanded: StateFlow<Boolean> = _isPanelExpanded.asStateFlow()

    // Local backing field for dreaming state (callback fires before state is updated)
    @Volatile private var _isDreaming = false

    // ─── Companion ─────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "AxDynamicBarInteractor"
        private const val NOTIF_ALERT_DURATION_MS = 4500L

        private fun IslandEvent.Notification.isActiveCall(): Boolean {
            val extras = sbn.notification?.extras ?: return false
            return extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
                extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
                extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)
        }
    }

    // ─── Initialization ────────────────────────────────────────────────────

    fun init() {
        if (isInitialized) return
        isInitialized = true

        settings.init()

        // Start the state machine loop
        startStateMachine()

        // Wire callbacks that dispatch intents
        setupEventSourceCallbacks()

        // Wire system UI state callbacks
        setupSystemStateCallbacks()

        // Wire indication controller
        setupIndicationController()

        // Wire settings
        wireSettings()
    }

    /**
     * The single state machine loop.
     * Receives intents from the channel, reduces state, and publishes derived UI state.
     */
    private fun startStateMachine() {
        applicationScope.launch {
            intentChannel.receiveAsFlow().collect { intent ->
                val previousState = _state
                _state = reduce(previousState, intent)
                if (_state != previousState) {
                    _uiState.value = toIslandUiState(_state)
                }
            }
        }
    }

    private fun setupEventSourceCallbacks() {
        // Callbacks from repository managers that trigger auto-dismiss
        repository.system.onChargingStarted = { scheduleAutoDismiss(it) }
        repository.system.onRingerChanged = { scheduleAutoDismiss(it) }
        repository.system.onClipboardCopied = { scheduleAutoDismiss(it) }

        repository.privacy.onCamStarted = { scheduleAutoDismiss(it, 10_000L) }

        repository.notification.activeMediaPackageProvider = { repository.media.activeMediaPackage }
        repository.notification.onAlarmEvent = {
            scheduleAutoDismiss(it, if (it.isRinging) 30_000L else 5_000L)
        }

        repository.media.onMediaSessionLost = { repository.media.clear() }

        repository.biometric.onBiometricUnlock = { scheduleAutoDismiss(it) }

        // Audio recording events
        applicationScope.launch {
            repository.notification.audioRecordingEvent.collect { event ->
                if (event?.state == RecordingState.SAVED) {
                    scheduleAutoDismiss(event, 5_000L)
                }
            }
        }

        // Notification flow -> NotificationAlertPosted intent
        applicationScope.launch {
            repository.notification.notificationFlow.collect { notification ->
                intentChannel.send(AxDynamicBarIntent.NotificationAlertPosted(notification))
            }
        }

        // Notification removal -> NotificationAlertDismissed if matching
        applicationScope.launch {
            repository.notification.notificationRemovedFlow.collect { key ->
                val alert = _state.notificationAlert ?: return@collect
                if (alert.sbn.key == key) {
                    intentChannel.send(AxDynamicBarIntent.NotificationAlertDismissed)
                }
            }
        }

        // Media progress polling
        applicationScope.launch {
            combine(
                uiState.map { it.shouldShow && it.events.any { e -> e is IslandEvent.Media && e.isPlaying && e.duration > 0L } },
                _isPanelExpanded,
                qsExpansion.map { it > 0f },
            ) { mediaActive, panelExpanded, qsOpen ->
                mediaActive && !panelExpanded && !qsOpen
            }.distinctUntilChanged().collect { needsPolling ->
                if (needsPolling) repository.media.startProgressPolling()
                else repository.media.stopProgressPolling()
            }
        }

        // Repository events -> EventsUpdated intent
        applicationScope.launch {
            combine(
                repository.events,
                settings.disabledEventTypes,
                _isOnKeyguard,
            ) { raw, _, kg ->
                raw.filter { settings.isEventEnabled(it) } to kg
            }.collect { (rawEvents, onKeyguard) ->
                if (!settings.isEnabled.value) return@collect

                // Prune stale dismissed IDs that are no longer in the raw set,
                // so dismissed events can reappear immediately on a fresh occurrence.
                val rawEventIds = rawEvents.map { it.id }.toSet()
                val dismissedIds = _state.dismissedEventIds.intersect(rawEventIds)

                val filteredEvents = rawEvents.filter { e ->
                    e.id !in dismissedIds &&
                        !(onKeyguard && e is IslandEvent.Charging) &&
                        !(onKeyguard && e is IslandEvent.AppSwitch) &&
                        !(!onKeyguard && e is IslandEvent.KeyguardIndication)
                }

                intentChannel.send(AxDynamicBarIntent.EventsUpdated(filteredEvents, dismissedIds))
            }
        }
    }

    private fun setupSystemStateCallbacks() {
        _isOnKeyguard.value = statusBarStateController.state == StatusBarState.KEYGUARD
        _isKeyguardFadingAway.value = keyguardStateController.isKeyguardFadingAway
        _isBouncerShowing.value = keyguardStateController.isPrimaryBouncerShowing

        keyguardStateController.addCallback(
            object : KeyguardStateController.Callback {
                override fun onKeyguardFadingAwayChanged() {
                    _isKeyguardFadingAway.value = keyguardStateController.isKeyguardFadingAway
                }

                override fun onPrimaryBouncerShowingChanged() {
                    _isBouncerShowing.value = keyguardStateController.isPrimaryBouncerShowing
                }
            }
        )

        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onExpandedChanged(isExpanded: Boolean) {
                    applicationScope.launch {
                        intentChannel.send(AxDynamicBarIntent.PanelExpandedChanged(isExpanded))
                    }
                }

                override fun onStateChanged(newState: Int) {
                    _isOnKeyguard.value = newState == StatusBarState.KEYGUARD
                    dispatchSystemContextChanged()
                }

                override fun onDozingChanged(dozing: Boolean) {
                    _isDozing.value = dozing
                    dispatchSystemContextChanged()
                }

                override fun onDozeAmountChanged(linear: Float, eased: Float) {
                    _dozeAmount.value = linear
                }

                override fun onDreamingChanged(dreaming: Boolean) {
                    _isDreaming = dreaming
                    dispatchSystemContextChanged()
                }
            }
        )
    }

    private fun setupIndicationController() {
        indicationController.addIndicationListener { type, text ->
            val indicationType = mapIndicationType(type) ?: return@addIndicationListener
            if (text != null && text.isNotEmpty()) {
                val event = IslandEvent.KeyguardIndication(
                    text = text.toString(),
                    indicationType = indicationType,
                )
                repository.updateIndicationEvent(event)
                scheduleAutoDismiss(event)
            } else {
                repository.clearIndicationEvent(indicationType)
            }
        }
    }

    private fun wireSettings() {
        applicationScope.launch {
            settings.isEnabled.collect { enabled ->
                val intent = if (enabled) {
                    repository.startListening()
                    AxDynamicBarIntent.FeatureEnabled
                } else {
                    repository.stopListening()
                    autoDismissJobs.values.forEach { it.cancel() }
                    autoDismissJobs.clear()
                    repository.clearAllIndicationEvents()
                    AxDynamicBarIntent.FeatureDisabled
                }
                intentChannel.send(intent)
            }
        }

        applicationScope.launch {
            settings.disabledEventTypes.collect {
                repository.refreshListeners()
            }
        }

        applicationScope.launch {
            settings.isHeadsUpEnabled.collect { enabled ->
                if (!enabled) {
                    intentChannel.send(AxDynamicBarIntent.NotificationAlertDismissed)
                }
            }
        }
    }

    // ─── State Machine: Reducer ────────────────────────────────────────────

    /**
     * Reducer entry point: given a state and an intent, compute the next state.
     *
     * This is the single source of truth for all state transitions, though some
     * reducers still perform small side effects to preserve existing behavior.
     */
    private fun reduce(state: AxDynamicBarState, intent: AxDynamicBarIntent): AxDynamicBarState {
        return when (intent) {
            is AxDynamicBarIntent.EventsUpdated -> reduceEventsUpdated(state, intent)
            is AxDynamicBarIntent.NotificationAlertPosted -> reduceNotificationAlertPosted(state, intent)
            is AxDynamicBarIntent.NotificationAlertDismissed -> reduceNotificationAlertDismissed(state)
            is AxDynamicBarIntent.PanelExpandedChanged -> reducePanelExpandedChanged(state, intent)
            is AxDynamicBarIntent.SystemContextChanged -> reduceSystemContextChanged(state, intent)
            is AxDynamicBarIntent.EventDismissed -> reduceEventDismissed(state, intent)
            is AxDynamicBarIntent.AutoDismissTriggered -> reduceAutoDismissTriggered(state, intent)
            is AxDynamicBarIntent.CycleNext -> reduceCycleNext(state)
            is AxDynamicBarIntent.CyclePrev -> reduceCyclePrev(state)
            is AxDynamicBarIntent.PinEventAt -> reducePinEventAt(state, intent)
            is AxDynamicBarIntent.NotificationAlertInteractionStart -> reduceNotificationAlertInteractionStart(state)
            is AxDynamicBarIntent.NotificationAlertInteractionEnd -> reduceNotificationAlertInteractionEnd(state, intent)
            is AxDynamicBarIntent.FeatureDisabled -> reduceFeatureDisabled(state)
            is AxDynamicBarIntent.FeatureEnabled -> state // Repository start is side effect, handled in wireSettings
            is AxDynamicBarIntent.EventInteractionStart -> reduceEventInteractionStart(state, intent)
            is AxDynamicBarIntent.EventInteractionEnd -> reduceEventInteractionEnd(state, intent)
        }.let { intermediateState ->
            // After any intent, re-derive visibility
            intermediateState.copy(visibility = computeVisibility(intermediateState))
        }
    }

    /**
     * Handles EventsUpdated by re-arbitrating the ordered event list,
     * preserving the user's pinned event when it still exists.
     */
    private fun reduceEventsUpdated(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.EventsUpdated,
    ): AxDynamicBarState {
        val arbitrationResult = EventArbitration.arbitrate(state, intent.events)

        return state.copy(
            events = arbitrationResult.events,
            pinnedEventIndex = arbitrationResult.pinnedEventIndex,
            dismissedEventIds = intent.prunedDismissedIds,
            eventFirstSeenAtMs = arbitrationResult.eventFirstSeenAtMs,
        )
    }

    /**
     * Handles NotificationAlertPosted: shows alert unless suppressed by DND/ringer,
     * panel blocking, keyguard, or an active call is already showing.
     */
    private fun reduceNotificationAlertPosted(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.NotificationAlertPosted,
    ): AxDynamicBarState {
        val notification = intent.alert

        // Suppression checks
        if (state.isPanelExpanded) return state
        if (state.isDozing || state.isDreaming) return state
        if (state.isOnKeyguard) return state
        if (shouldSuppressForDndOrRinger(notification)) return state

        // Don't replace an active call alert with a non-call alert
        val existingAlert = state.notificationAlert
        if (existingAlert != null &&
            existingAlert.isActiveCall() &&
            !notification.isActiveCall()
        ) return state

        // Update alert (progress-based notifications update in place)
        val hasProgress = notification.progress >= 0 || notification.isProgressIndeterminate
        val isSameKey = existingAlert != null && existingAlert.sbn.key == notification.sbn.key

        return if (isSameKey && hasProgress) {
            state.copy(notificationAlert = notification)
        } else {
            // Cancel existing auto-dismiss if any
            notifAlertJob?.cancel()
            notifAlertJob = null

            val newState = state.copy(notificationAlert = notification)

            // Schedule auto-dismiss for non-call, non-progress alerts
            if (!hasProgress && !notification.isActiveCall()) {
                notifAlertJob = applicationScope.launch {
                    delay(NOTIF_ALERT_DURATION_MS)
                    intentChannel.send(AxDynamicBarIntent.NotificationAlertDismissed)
                }
            }

            newState
        }
    }

    private fun reduceNotificationAlertDismissed(state: AxDynamicBarState): AxDynamicBarState {
        notifAlertJob?.cancel()
        notifAlertJob = null
        return if (state.notificationAlert != null) {
            state.copy(notificationAlert = null)
        } else {
            state
        }
    }

    private fun reducePanelExpandedChanged(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.PanelExpandedChanged,
    ): AxDynamicBarState {
        _isPanelExpanded.value = intent.expanded
        if (intent.expanded) {
            notifAlertJob?.cancel()
            notifAlertJob = null
        }
        return state.copy(
            isPanelExpanded = intent.expanded,
            notificationAlert = if (intent.expanded) null else state.notificationAlert,
        )
    }

    private fun reduceSystemContextChanged(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.SystemContextChanged,
    ): AxDynamicBarState {
        _isOnKeyguard.value = intent.onKeyguard
        _isDozing.value = intent.dozing
        return state.copy(
            isOnKeyguard = intent.onKeyguard,
            isDozing = intent.dozing,
            isDreaming = intent.dreaming,
        )
    }

    private fun reduceEventDismissed(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.EventDismissed,
    ): AxDynamicBarState {
        val event = intent.event

        // Cancel auto-dismiss
        autoDismissJobs[event.id]?.cancel()
        autoDismissJobs.remove(event.id)

        // Add to dismissed set if suppressOnDismiss
        val newDismissedIds = if (event.behavior.suppressOnDismiss) {
            state.dismissedEventIds + event.id
        } else {
            state.dismissedEventIds - event.id
        }

        val updatedEvents = state.events.filter { it.id != event.id }
        val newIndex = state.pinnedEventIndex.coerceAtMost((updatedEvents.size - 1).coerceAtLeast(0))

        // Trigger side-effect cleanup in repository
        dispatchEventDismissedSideEffects(event)

        return state.copy(
            events = updatedEvents,
            pinnedEventIndex = newIndex,
            dismissedEventIds = newDismissedIds,
            eventFirstSeenAtMs = state.eventFirstSeenAtMs - event.id,
        )
    }

    private fun reduceAutoDismissTriggered(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.AutoDismissTriggered,
    ): AxDynamicBarState {
        val event = state.events.find { it.id == intent.eventId } ?: return state
        autoDismissJobs.remove(intent.eventId)
        return reduceEventDismissed(state, AxDynamicBarIntent.EventDismissed(event))
    }

    private fun reduceCycleNext(state: AxDynamicBarState): AxDynamicBarState {
        if (state.events.size <= 1) return state
        val next = (state.pinnedEventIndex + 1) % state.events.size
        return state.copy(pinnedEventIndex = next)
    }

    private fun reduceCyclePrev(state: AxDynamicBarState): AxDynamicBarState {
        if (state.events.size <= 1) return state
        val prev = (state.pinnedEventIndex - 1 + state.events.size) % state.events.size
        return state.copy(pinnedEventIndex = prev)
    }

    private fun reducePinEventAt(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.PinEventAt,
    ): AxDynamicBarState {
        if (intent.index < 0 || intent.index >= state.events.size) return state
        return state.copy(pinnedEventIndex = intent.index)
    }

    private fun reduceNotificationAlertInteractionStart(state: AxDynamicBarState): AxDynamicBarState {
        notifAlertJob?.cancel()
        notifAlertJob = null
        return state
    }

    private fun reduceNotificationAlertInteractionEnd(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.NotificationAlertInteractionEnd,
    ): AxDynamicBarState {
        val alert = state.notificationAlert ?: return state
        if (alert.isActiveCall()) return state
        notifAlertJob = applicationScope.launch {
            delay(NOTIF_ALERT_DURATION_MS)
            intentChannel.send(AxDynamicBarIntent.NotificationAlertDismissed)
        }
        return state
    }

    private fun reduceFeatureDisabled(state: AxDynamicBarState): AxDynamicBarState {
        notifAlertJob?.cancel()
        notifAlertJob = null
        return AxDynamicBarState() // Reset everything
    }

    private fun reduceEventInteractionStart(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.EventInteractionStart,
    ): AxDynamicBarState {
        autoDismissJobs[intent.eventId]?.cancel()
        autoDismissJobs.remove(intent.eventId)
        return state
    }

    private fun reduceEventInteractionEnd(
        state: AxDynamicBarState,
        intent: AxDynamicBarIntent.EventInteractionEnd,
    ): AxDynamicBarState {
        val event = state.events.find { it.id == intent.eventId } ?: return state
        scheduleAutoDismiss(event)
        return state
    }

    // ─── Visibility Derivation ─────────────────────────────────────────────

    /**
     * Pure function: compute visibility from current state.
     *
     * This is the SINGLE place where visibility is decided.
     */
    private fun computeVisibility(state: AxDynamicBarState): IslandVisibility {
        // No events and no alert -> hidden
        if (state.events.isEmpty() && state.notificationAlert == null) {
            return IslandVisibility.Hidden
        }

        // System suppression (dozing, dreaming)
        if (state.isDozing || state.isDreaming) {
            return IslandVisibility.SuppressedBySystem
        }

        // Panel expanded -> hide chip (panel shows the content)
        if (state.isPanelExpanded) {
            return IslandVisibility.Expanded
        }

        // Alert only (no events or passive events only)
        if (state.events.isEmpty() && state.notificationAlert != null) {
            return IslandVisibility.AlertOnly
        }

        // Events exist, not suppressed -> show chip as long as at least one event
        // can surface the island. This keeps the chip reachable even if the
        // currently pinned event is passive.
        if (state.events.none { it.behavior.autoShowsIsland }) {
            return if (state.notificationAlert != null) IslandVisibility.AlertOnly
            else IslandVisibility.Hidden
        }

        return IslandVisibility.Chip
    }

    // ─── Helper Functions ──────────────────────────────────────────────────

    private fun dispatchSystemContextChanged() {
        applicationScope.launch {
            intentChannel.send(
                AxDynamicBarIntent.SystemContextChanged(
                    onKeyguard = _isOnKeyguard.value,
                    dozing = _isDozing.value,
                    dreaming = _isDreaming,
                )
            )
        }
    }

    private fun dispatchEventDismissedSideEffects(event: IslandEvent) {
        when (event) {
            is IslandEvent.ScreenRecording -> repository.screenRecord.stopListening()
            is IslandEvent.MicCamActive -> {}
            is IslandEvent.AudioRecording -> repository.notification.clearAudioRecording()
            is IslandEvent.Casting -> repository.connectivity.clearCasting()
            is IslandEvent.Sports -> {
                repository.smartspace.clearSportsEvent(event.key)
                repository.notification.clearSportsEvent(event.key)
            }
            is IslandEvent.NowPlaying -> {}
            is IslandEvent.PromotedOngoing ->
                repository.notification.clearPromotedOngoing(event.sbn.key)
            is IslandEvent.Media -> repository.media.clear()
            is IslandEvent.Bluetooth -> repository.connectivity.clearBluetooth()
            is IslandEvent.Hotspot -> repository.connectivity.clearHotspot()
            is IslandEvent.Charging -> repository.system.clearCharging()
            is IslandEvent.Alarm -> repository.notification.clearAlarm()
            is IslandEvent.Timer -> repository.notification.clearTimer()
            is IslandEvent.Stopwatch -> repository.notification.clearStopwatch()
            is IslandEvent.RingerMode -> repository.system.clearRinger()
            is IslandEvent.Vpn -> repository.connectivity.clearVpn()
            is IslandEvent.Clipboard -> repository.system.clearClipboard()
            is IslandEvent.Notification -> {}
            is IslandEvent.AppSwitch -> repository.appTracking.clear()
            is IslandEvent.Torch -> {
                repository.torch.toggleTorch()
                repository.torch.clear()
            }
            is IslandEvent.BiometricUnlock -> repository.biometric.clear()
            is IslandEvent.KeyguardIndication -> repository.clearIndicationEvent(event.indicationType)
        }
    }

    private fun shouldSuppressForDndOrRinger(notification: IslandEvent.Notification): Boolean {
        if (notification.isActiveCall()) return false
        if (!settings.isHeadsUpEnabled.value) return true
        val category = notification.sbn.notification?.category
        if (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_ALARM) return false
        val zenMode = zenModeController.zen
        if (zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS ||
            zenMode == Global.ZEN_MODE_ALARMS) return true
        val ringerMode = audioManager.ringerMode
        return ringerMode == AudioManager.RINGER_MODE_SILENT
    }

    private fun scheduleAutoDismiss(event: IslandEvent, delayOverride: Long? = null) {
        val ms = delayOverride ?: event.behavior.autoDismissMs ?: return
        val eventId = event.id
        autoDismissJobs[eventId]?.cancel()
        autoDismissJobs[eventId] = applicationScope.launch {
            delay(ms)
            intentChannel.send(AxDynamicBarIntent.AutoDismissTriggered(eventId))
        }
    }

    // ─── Public API (dispatches intents) ───────────────────────────────────

    fun cycleNext() {
        applicationScope.launch { intentChannel.send(AxDynamicBarIntent.CycleNext) }
    }

    fun cyclePrev() {
        applicationScope.launch { intentChannel.send(AxDynamicBarIntent.CyclePrev) }
    }

    fun pinEventAt(index: Int) {
        applicationScope.launch { intentChannel.send(AxDynamicBarIntent.PinEventAt(index)) }
    }

    override fun dismissEvent(event: IslandEvent) {
        applicationScope.launch { intentChannel.send(AxDynamicBarIntent.EventDismissed(event)) }
    }

    fun getTopEvent(): IslandEvent? = _state.topEvent

    override fun collapseIsland() {
        onCollapseRequested?.invoke()
    }

    override fun onNotificationInteraction(eventId: String) {
        applicationScope.launch {
            intentChannel.send(AxDynamicBarIntent.EventInteractionStart(eventId))
        }
    }

    override fun onNotificationInteractionEnd(eventId: String) {
        applicationScope.launch {
            intentChannel.send(AxDynamicBarIntent.EventInteractionEnd(eventId))
        }
    }

    override fun onNotificationAlertInteractionStart() {
        applicationScope.launch {
            intentChannel.send(AxDynamicBarIntent.NotificationAlertInteractionStart)
        }
    }

    override fun onNotificationAlertInteractionEnd() {
        val alert = _state.notificationAlert ?: return
        if (alert.isActiveCall()) return
        applicationScope.launch {
            intentChannel.send(AxDynamicBarIntent.NotificationAlertInteractionEnd(alert.id))
        }
    }

    fun dismissNotificationAlert() {
        applicationScope.launch {
            intentChannel.send(AxDynamicBarIntent.NotificationAlertDismissed)
        }
    }

    // ─── IslandActions implementations (side effects only, no state mutation) ──

    override fun stopScreenRecording() = repository.screenRecord.stopRecording()

    override fun togglePlayPause() = repository.media.togglePlayPause()

    override fun skipNext() = repository.media.skipNext()

    override fun skipPrev() = repository.media.skipPrev()

    override fun sendCustomAction(action: String) = repository.media.sendCustomAction(action)

    override fun openMediaOutputSwitcher() = repository.media.openMediaOutputSwitcher()

    override fun disconnectBluetooth(address: String) = repository.connectivity.disconnectBluetooth(address)

    override fun openUrl(url: String) = repository.system.openUrl(url)

    override fun openMediaApp() = repository.media.openMediaApp()

    override fun seekTo(position: Long) = repository.media.seekTo(position)

    override fun setRingerMode(mode: Int) = repository.system.setRingerMode(mode)

    override fun toggleTorch() = repository.torch.toggleTorch()

    override fun launchNotificationDismissingKeyguard(event: IslandEvent.Notification) {
        val intent = event.sbn.notification?.contentIntent ?: return
        activityStarter.startPendingIntentDismissingKeyguard(intent)
    }

    override fun setTorchLevel(level: Int) = repository.torch.setLevel(level)

    override fun setTorchLevelTemporary(level: Int) = repository.torch.setLevelTemporary(level)

    override fun copyToClipboard(text: String) = repository.system.copyToClipboard(text)

    override fun copyUriToClipboard(uri: Uri) = repository.system.copyUriToClipboard(uri)

    override fun removeClipboardItem(id: Long) = repository.system.removeClipboardItem(id)

    override fun switchToApp(taskId: Int) = repository.appTracking.switchToApp(taskId)

    // ─── Backward Compatibility ────────────────────────────────────────────

    /**
     * Converts the new AxDynamicBarState to the legacy IslandUiState.
     * This allows the UI layer to continue working unchanged while we migrate.
     */
    private fun toIslandUiState(state: AxDynamicBarState): IslandUiState {
        return IslandUiState(
            events = state.events,
            islandState = when (state.visibility) {
                IslandVisibility.Hidden -> com.android.systemui.axdynamicbar.model.IslandState.HIDDEN
                IslandVisibility.Chip -> com.android.systemui.axdynamicbar.model.IslandState.CHIP
                IslandVisibility.Expanded -> com.android.systemui.axdynamicbar.model.IslandState.HIDDEN
                IslandVisibility.AlertOnly -> com.android.systemui.axdynamicbar.model.IslandState.CHIP
                IslandVisibility.SuppressedBySystem -> com.android.systemui.axdynamicbar.model.IslandState.HIDDEN
            },
            pinnedEventIndex = state.pinnedEventIndex,
            notificationAlert = state.notificationAlert,
        )
    }

    // ─── Utility ───────────────────────────────────────────────────────────

    private fun mapIndicationType(type: Int): IslandEvent.KeyguardIndication.IndicationType? =
        when (type) {
            KeyguardIndicationController.AX_TYPE_BIOMETRIC ->
                IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC
            KeyguardIndicationController.AX_TYPE_TRANSIENT ->
                IslandEvent.KeyguardIndication.IndicationType.TRANSIENT
            KeyguardIndicationController.AX_TYPE_TRUST ->
                IslandEvent.KeyguardIndication.IndicationType.TRUST
            KeyguardIndicationController.AX_TYPE_DISCLOSURE ->
                IslandEvent.KeyguardIndication.IndicationType.DISCLOSURE
            KeyguardIndicationController.AX_TYPE_OWNER_INFO ->
                IslandEvent.KeyguardIndication.IndicationType.OWNER_INFO
            KeyguardIndicationController.AX_TYPE_ALIGNMENT ->
                IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT
            KeyguardIndicationController.AX_TYPE_PERSISTENT_UNLOCK ->
                IslandEvent.KeyguardIndication.IndicationType.PERSISTENT_UNLOCK
            else -> null
        }
}
