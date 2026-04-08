package com.android.systemui.axdynamicbar.model

/**
 * UI-facing state for the AxDynamicBar.
 *
 * This is derived from [AxDynamicBarState] via [AxDynamicBarInteractor.toIslandUiState].
 * The `manuallyHidden` and `forceVisible` fields have been removed as they were unused.
 * All state mutations now flow through the intent/reducer pipeline in the interactor.
 *
 * @param events All currently active events, sorted by priority.
 * @param islandState Whether the bar is showing as a chip or hidden.
 * @param pinnedEventIndex Index of the currently pinned event in [events].
 * @param notificationAlert Current heads-up notification alert, if any.
 */
data class IslandUiState(
    val events: List<IslandEvent> = emptyList(),
    val islandState: IslandState = IslandState.HIDDEN,
    val pinnedEventIndex: Int = 0,
    val notificationAlert: IslandEvent.Notification? = null,
) {

    val shouldShow: Boolean
        get() = islandState == IslandState.CHIP || notificationAlert != null

    val isChip: Boolean
        get() = islandState == IslandState.CHIP

    val topEvent: IslandEvent?
        get() = events.getOrNull(pinnedEventIndex) ?: events.firstOrNull()

    val activeEvents: List<IslandEvent>
        get() = events.filter { it !is IslandEvent.Notification }
}

