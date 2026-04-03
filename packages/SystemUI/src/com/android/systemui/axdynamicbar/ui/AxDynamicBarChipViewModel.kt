package com.android.systemui.axdynamicbar.ui

import android.content.Context
import android.os.SystemProperties
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AxDynamicBarChipState(
    val event: IslandEvent,
    val eventCount: Int,
    val pinnedIndex: Int,
    val allEvents: List<IslandEvent>,
    val notificationAlert: IslandEvent.Notification? = null,
)

data class KeyguardBatteryInfo(
    val level: Int,
    val isCharging: Boolean,
    val isPowerSave: Boolean,
    val isWireless: Boolean,
    val timeRemaining: String?,
)

@SysUISingleton
class AxDynamicBarChipViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val context: Context,
    val interactor: AxDynamicBarInteractor,
    batteryInteractor: BatteryInteractor,
    private val batteryController: BatteryController,
    authController: AuthController,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {

    val isCompactKeyguardChip: StateFlow<Boolean> =
        udfpsOverlayInteractor.udfpsOverlayParams
            .map { params ->
                val propOverride = SystemProperties.getInt(PROP_COMPACT_CHIP, -1)
                if (propOverride >= 0) return@map propOverride == 1
                if (!authController.isUdfpsSupported) return@map false
                val sensorBottom = params.sensorBounds.bottom
                val displayHeight = params.naturalDisplayHeight
                if (displayHeight <= 0) return@map false
                sensorBottom > displayHeight * LOW_UDFPS_THRESHOLD
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val chipState: StateFlow<AxDynamicBarChipState?> =
        interactor.uiState
            .map { uiState ->
                if (!uiState.shouldShow) return@map null
                val alert = uiState.notificationAlert
                val topEvent = uiState.topEvent ?: alert ?: return@map null
                AxDynamicBarChipState(
                    event = topEvent,
                    eventCount = uiState.activeEvents.size,
                    pinnedIndex = uiState.pinnedEventIndex,
                    allEvents = uiState.events,
                    notificationAlert = alert,
                )
            }
            .stateIn(applicationScope, SharingStarted.Lazily, null)

    val isEnabled: StateFlow<Boolean> = interactor.settings.isEnabled
    val isKeyguardEnabled: StateFlow<Boolean> = interactor.settings.isKeyguardEnabled

    val keyguardBatteryInfo: StateFlow<KeyguardBatteryInfo> =
        combine(
            batteryInteractor.level,
            batteryInteractor.isCharging,
            batteryInteractor.powerSave,
            batteryInteractor.batteryTimeRemainingEstimate,
        ) { level, charging, powerSave, timeRemaining ->
            KeyguardBatteryInfo(
                level = level ?: 0,
                isCharging = charging,
                isPowerSave = powerSave,
                isWireless = batteryController.isPluggedInWireless,
                timeRemaining = timeRemaining,
            )
        }.stateIn(
            applicationScope,
            SharingStarted.Lazily,
            KeyguardBatteryInfo(0, false, false, false, null),
        )

    val isOnKeyguard: StateFlow<Boolean> = interactor.isOnKeyguard

    val isKeyguardFadingAway: StateFlow<Boolean> = interactor.isKeyguardFadingAway

    val isBouncerShowing: StateFlow<Boolean> = interactor.isBouncerShowing

    private val _keyguardCarrierText = MutableStateFlow("")
    val keyguardCarrierText: StateFlow<String> = _keyguardCarrierText.asStateFlow()

    fun updateKeyguardCarrierText(text: String) {
        _keyguardCarrierText.value = text
    }

    private val _chipCenterXFraction = MutableStateFlow(0.5f)
    val chipCenterXFraction: StateFlow<Float> = _chipCenterXFraction.asStateFlow()

    private val _chipWidthPx = MutableStateFlow(0f)
    val chipWidthPx: StateFlow<Float> = _chipWidthPx.asStateFlow()

    fun updateChipCenterX(fraction: Float) {
        _chipCenterXFraction.value = fraction
    }

    fun updateChipWidth(widthPx: Float) {
        _chipWidthPx.value = widthPx
    }

    private val _clockBoundsRight = MutableStateFlow(0)

    fun updateClockBoundsRight(px: Int) {
        _clockBoundsRight.value = px
    }

    /**
     * Maximum number of notification icons that should be shown in the status bar start side.
     * Only restricts icons when the chip is in center mode; otherwise unbounded.
     *
     * Derived by measuring the actual pixel gap between the clock's right edge and the chip's
     * left edge, then fitting as many icon slots as possible. Works correctly for all screen
     * sizes — tablets naturally get more icons, phones with wide chips or seconds-enabled
     * clocks naturally get fewer.
     */
    val notifIconLimit: StateFlow<Int> = combine(
        interactor.settings.isEnabled,
        interactor.settings.chipPosition,
        _chipWidthPx,
        _chipCenterXFraction,
        _clockBoundsRight,
        chipState,
    ) { args ->
        val enabled = args[0] as Boolean
        val position = args[1] as Int
        val widthPx = args[2] as Float
        val centerFraction = args[3] as Float
        val clockRight = args[4] as Int
        val chip = args[5] as AxDynamicBarChipState?
        if (!enabled || chip == null || position != AxDynamicBarSettings.CHIP_POSITION_CENTER || clockRight == 0) {
            return@combine Int.MAX_VALUE
        }
        val screenWidthPx = context.resources.displayMetrics.widthPixels.toFloat()
        val chipLeftEdgePx = centerFraction * screenWidthPx - widthPx / 2f
        val iconSlotPx = context.resources.getDimensionPixelSize(R.dimen.status_bar_icon_size_sp).toFloat()
        // Reserve one extra icon-slot as a safety gap so icons never sit flush against the chip.
        val safetyPx = iconSlotPx
        val availablePx = (chipLeftEdgePx - clockRight - safetyPx).coerceAtLeast(0f)
        (availablePx / iconSlotPx).toInt()
    }.stateIn(applicationScope, SharingStarted.Eagerly, Int.MAX_VALUE)

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    private val _isSingleMode = MutableStateFlow(false)
    val isSingleMode: StateFlow<Boolean> = _isSingleMode.asStateFlow()

    @Volatile private var collapseOnNullJob: Job? = null

    val isKeyguardExpanded: StateFlow<Boolean> =
        combine(isExpanded, isOnKeyguard) { exp, kg -> exp && kg }
            .stateIn(applicationScope, SharingStarted.Lazily, false)

    init {
        
        chipState.onEach { state ->
            if (state == null) {
                collapseOnNullJob?.cancel()
                collapseOnNullJob = applicationScope.launch {
                    delay(200)
                    _isExpanded.value = false
                }
            } else {
                collapseOnNullJob?.cancel()
                collapseOnNullJob = null
            }
        }.launchIn(applicationScope)
        
        interactor.isPanelExpanded.onEach { if (it) _isExpanded.value = false }.launchIn(applicationScope)
        interactor.qsExpansion.map { it > 0f }.distinctUntilChanged().onEach { if (it) _isExpanded.value = false }.launchIn(applicationScope)
        
        isBouncerShowing.onEach { if (it) _isExpanded.value = false }.launchIn(applicationScope)
        
        isOnKeyguard.onEach { if (!it) _isExpanded.value = false }.launchIn(applicationScope)
        
        combine(interactor.legacyShadeExpansion, isOnKeyguard) { expansion, onKg ->
            onKg && expansion < 0.95f
        }.onEach { dismissing -> if (dismissing) _isExpanded.value = false }.launchIn(applicationScope)
        
        interactor.isDozing.drop(1).onEach { _isExpanded.value = false }.launchIn(applicationScope)
        
        interactor.dozeAmount.map { it > 0f }.distinctUntilChanged().onEach { if (it) _isExpanded.value = false }.launchIn(applicationScope)
    }

    fun expandPanel() {
        if (chipState.value != null) _isExpanded.value = true
    }

    /** Tap: expand showing only the currently-pinned event. */
    fun expandSingle() {
        if (chipState.value != null) {
            _isSingleMode.value = true
            _isExpanded.value = true
        }
    }

    /** Long-press: expand showing all events stacked (original behaviour). */
    fun expandAll() {
        if (chipState.value != null) {
            _isSingleMode.value = false
            _isExpanded.value = true
        }
    }

    fun collapsePanel() {
        _isExpanded.value = false
        // Keep _isSingleMode unchanged so the exit animation renders the same view,
        // preventing a flash of the stacked-cards layout during the fade-out.
    }

    fun togglePanel() {
        if (_isExpanded.value) collapsePanel() else expandSingle()
    }

    fun cycleNext() = interactor.cycleNext()

    fun cyclePrev() = interactor.cyclePrev()

    fun dismissEvent(event: IslandEvent) = interactor.dismissEvent(event)

    fun togglePlayPause() = interactor.togglePlayPause()

    fun skipNext() = interactor.skipNext()

    fun skipPrev() = interactor.skipPrev()

    fun toggleTorch() = interactor.toggleTorch()

    fun stopScreenRecording() = interactor.stopScreenRecording()

    fun launchNotificationFromKeyguard(event: IslandEvent.Notification) {
        interactor.launchNotificationDismissingKeyguard(event)
    }

    companion object {
        private const val PROP_COMPACT_CHIP = "persist.sys.axdb.compact_chip"
        private const val LOW_UDFPS_THRESHOLD = 0.75f
    }
}
