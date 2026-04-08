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

package com.android.systemui.axdynamicbar.model

/**
 * Explicit visibility states for the AxDynamicBar.
 * Replaces the implicit boolean-based visibility logic.
 */
sealed interface IslandVisibility {
    /** The bar is completely hidden (no events, or system is blocking). */
    data object Hidden : IslandVisibility

    /** The bar is showing as a compact chip/pill in the status bar. */
    data object Chip : IslandVisibility

    /** The expanded panel is open, overlaying the status bar. */
    data object Expanded : IslandVisibility

    /**
     * Only a heads-up notification alert is showing.
     * Used when the chip should stay hidden but an alert needs display.
     */
    data object AlertOnly : IslandVisibility

    /**
     * Temporarily suppressed by system UI (dozing, dreaming, QS expansion).
     * Events remain active but the chip is not rendered.
     */
    data object SuppressedBySystem : IslandVisibility
}

/**
 * Canonical state of the AxDynamicBar feature.
 *
 * This is the single source of truth for all feature state, replacing the
 * scattered boolean fields that previously lived in AxDynamicBarInteractor.
 */
data class AxDynamicBarState(
    /** All currently active events, sorted by priority (highest first). */
    val events: List<IslandEvent> = emptyList(),

    /** Derived visibility state (computed by reducer, not directly set). */
    val visibility: IslandVisibility = IslandVisibility.Hidden,

    /** Index of the currently pinned event in [events]. */
    val pinnedEventIndex: Int = 0,

    /** Event IDs that have been explicitly dismissed by the user. */
    val dismissedEventIds: Set<String> = emptySet(),

    /** Current heads-up notification alert, if any. */
    val notificationAlert: IslandEvent.Notification? = null,

    /** System context: whether the panel is expanded. */
    val isPanelExpanded: Boolean = false,

    /** System context: whether we're on the keyguard. */
    val isOnKeyguard: Boolean = false,

    /** System context: whether the device is dozing. */
    val isDozing: Boolean = false,

    /** System context: whether the device is dreaming (screensaver). */
    val isDreaming: Boolean = false,
) {
    val shouldShowChip: Boolean
        get() = visibility == IslandVisibility.Chip

    val shouldShowAlert: Boolean
        get() = visibility == IslandVisibility.AlertOnly || notificationAlert != null

    val shouldShow: Boolean
        get() = shouldShowChip || shouldShowAlert

    val topEvent: IslandEvent?
        get() = events.getOrNull(pinnedEventIndex) ?: events.firstOrNull()

    val activeEvents: List<IslandEvent>
        get() = events.filter { it !is IslandEvent.Notification }
}

/**
 * Sealed interface representing all possible intents that can drive state transitions.
 *
 * Each intent represents a discrete event from any source: repository updates,
 * user actions, system state changes, or timer triggers.
 */
sealed interface AxDynamicBarIntent {

    /** Repository emitted a new list of active events (with stale dismissed IDs pruned). */
    data class EventsUpdated(
        val events: List<IslandEvent>,
        val prunedDismissedIds: Set<String> = emptySet(),
    ) : AxDynamicBarIntent

    /** A heads-up notification alert was posted. */
    data class NotificationAlertPosted(val alert: IslandEvent.Notification) : AxDynamicBarIntent

    /** The current heads-up notification alert was dismissed. */
    data object NotificationAlertDismissed : AxDynamicBarIntent

    /** The expanded panel's expanded state changed. */
    data class PanelExpandedChanged(val expanded: Boolean) : AxDynamicBarIntent

    /** System context changed (keyguard, dozing, dreaming). */
    data class SystemContextChanged(
        val onKeyguard: Boolean,
        val dozing: Boolean,
        val dreaming: Boolean,
    ) : AxDynamicBarIntent

    /** User dismissed a specific event. */
    data class EventDismissed(val event: IslandEvent) : AxDynamicBarIntent

    /** Auto-dismiss timer fired for a specific event. */
    data class AutoDismissTriggered(val eventId: String) : AxDynamicBarIntent

    /** User wants to cycle to the next event. */
    data object CycleNext : AxDynamicBarIntent

    /** User wants to cycle to the previous event. */
    data object CyclePrev : AxDynamicBarIntent

    /** User explicitly pinned an event at a specific index. */
    data class PinEventAt(val index: Int) : AxDynamicBarIntent

    /** User started interacting with a notification alert (pause auto-dismiss). */
    data object NotificationAlertInteractionStart : AxDynamicBarIntent

    /** User ended interaction with a notification alert (resume auto-dismiss). */
    data class NotificationAlertInteractionEnd(val eventId: String) : AxDynamicBarIntent

    /** AxDynamicBar feature was disabled. */
    data object FeatureDisabled : AxDynamicBarIntent

    /** AxDynamicBar feature was enabled. */
    data object FeatureEnabled : AxDynamicBarIntent

    /** User started interacting with a specific event card (pause its auto-dismiss timer). */
    data class EventInteractionStart(val eventId: String) : AxDynamicBarIntent

    /** User ended interaction with a specific event card (resume its auto-dismiss timer). */
    data class EventInteractionEnd(val eventId: String) : AxDynamicBarIntent
}
