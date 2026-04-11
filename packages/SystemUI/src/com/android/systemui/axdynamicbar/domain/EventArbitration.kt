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

import com.android.systemui.axdynamicbar.model.AxDynamicBarState
import com.android.systemui.axdynamicbar.model.IslandEvent

enum class EventTier {
    CRITICAL,
    INTERRUPTING,
    ONGOING,
    PASSIVE,
}

data class EventArbitrationResult(
    val events: List<IslandEvent>,
    val pinnedEventIndex: Int,
    val eventFirstSeenAtMs: Map<String, Long>,
)

object EventArbitration {
    fun arbitrate(
        state: AxDynamicBarState,
        incomingEvents: List<IslandEvent>,
        nowMs: Long = System.currentTimeMillis(),
    ): EventArbitrationResult {
        val previouslyPinnedId = state.topEvent?.id
        val eventFirstSeenAtMs = buildFirstSeenMap(state, incomingEvents, nowMs)
        if (incomingEvents.isEmpty()) {
            return EventArbitrationResult(
                events = emptyList(),
                pinnedEventIndex = 0,
                eventFirstSeenAtMs = emptyMap(),
            )
        }

        val sortedEvents = incomingEvents.sortedWith(queueComparator)
        val currentFrontId = state.topEvent?.id
        val currentFront = sortedEvents.firstOrNull { it.id == currentFrontId }
        val newlyArrivedEvent = highestNewEvent(state.events, sortedEvents)

        val chosenFront =
            chooseFront(currentFront, newlyArrivedEvent, sortedEvents, eventFirstSeenAtMs, nowMs)
                ?: sortedEvents.first()

        val orderedEvents = listOf(chosenFront) + sortedEvents.filterNot { it.id == chosenFront.id }
        val pinnedEventIndex = resolvePinnedIndex(previouslyPinnedId, orderedEvents)

        return EventArbitrationResult(
            events = orderedEvents,
            pinnedEventIndex = pinnedEventIndex,
            eventFirstSeenAtMs = eventFirstSeenAtMs,
        )
    }

    private fun resolvePinnedIndex(
        previouslyPinnedId: String?,
        orderedEvents: List<IslandEvent>,
    ): Int {
        if (orderedEvents.isEmpty()) return 0
        if (previouslyPinnedId == null) return 0
        return orderedEvents.indexOfFirst { it.id == previouslyPinnedId }.takeIf { it >= 0 } ?: 0
    }

    private fun buildFirstSeenMap(
        state: AxDynamicBarState,
        incomingEvents: List<IslandEvent>,
        nowMs: Long,
    ): Map<String, Long> {
        val existing = state.eventFirstSeenAtMs.toMutableMap()
        val activeIds = incomingEvents.map { it.id }.toSet()
        existing.keys.retainAll(activeIds)
        incomingEvents.forEach { event ->
            existing.putIfAbsent(event.id, nowMs)
        }
        return existing
    }

    private fun highestNewEvent(
        oldEvents: List<IslandEvent>,
        newEvents: List<IslandEvent>,
    ): IslandEvent? {
        val oldIds = oldEvents.map { it.id }.toSet()
        return newEvents
            .asSequence()
            .filter { it.id !in oldIds }
            .sortedWith(queueComparator)
            .firstOrNull()
    }

    private fun chooseFront(
        currentFront: IslandEvent?,
        incomingEvent: IslandEvent?,
        sortedEvents: List<IslandEvent>,
        eventFirstSeenAtMs: Map<String, Long>,
        nowMs: Long,
    ): IslandEvent? {
        val provisionalFront = when {
            currentFront == null -> incomingEvent
            incomingEvent == null -> currentFront
            currentFront.id == incomingEvent.id -> incomingEvent
            shouldPreempt(currentFront, incomingEvent, eventFirstSeenAtMs, nowMs) -> incomingEvent
            else -> currentFront
        }

        if (provisionalFront == null) return null

        return if (shouldRemainFront(provisionalFront, sortedEvents, eventFirstSeenAtMs, nowMs)) {
            provisionalFront
        } else {
            null
        }
    }

    private fun shouldPreempt(
        currentFront: IslandEvent,
        incomingEvent: IslandEvent,
        eventFirstSeenAtMs: Map<String, Long>,
        nowMs: Long,
    ): Boolean {
        val currentTier = currentFront.eventTier
        val incomingTier = incomingEvent.eventTier

        return when {
            incomingTier == EventTier.PASSIVE -> false
            incomingTier == EventTier.CRITICAL && currentTier != EventTier.CRITICAL -> true
            incomingTier == EventTier.INTERRUPTING && currentTier == EventTier.ONGOING -> true
            incomingTier == EventTier.INTERRUPTING && currentTier == EventTier.PASSIVE -> true
            incomingTier == EventTier.ONGOING && currentTier == EventTier.PASSIVE -> true
            incomingTier != currentTier -> false
            incomingTier == EventTier.CRITICAL ->
                incomingEvent.arbitrationPriority > currentFront.arbitrationPriority
            incomingTier == EventTier.INTERRUPTING ->
                !isStickyInterrupting(currentFront, eventFirstSeenAtMs, nowMs) &&
                    incomingEvent.arbitrationPriority > currentFront.arbitrationPriority
            else -> false
        }
    }

    private fun isStickyInterrupting(
        event: IslandEvent,
        eventFirstSeenAtMs: Map<String, Long>,
        nowMs: Long,
    ): Boolean {
        val stickyWindowMs = event.interruptingStickinessMs ?: return false
        val firstSeenAt = eventFirstSeenAtMs[event.id] ?: return false
        return nowMs - firstSeenAt < stickyWindowMs
    }

    private fun shouldRemainFront(
        frontEvent: IslandEvent,
        sortedEvents: List<IslandEvent>,
        eventFirstSeenAtMs: Map<String, Long>,
        nowMs: Long,
    ): Boolean {
        val competingEvents = sortedEvents.filterNot { it.id == frontEvent.id }
        return when (frontEvent.eventTier) {
            EventTier.CRITICAL ->
                competingEvents.none {
                    it.eventTier == EventTier.CRITICAL &&
                        it.arbitrationPriority > frontEvent.arbitrationPriority
                }
            EventTier.INTERRUPTING ->
                isStickyInterrupting(frontEvent, eventFirstSeenAtMs, nowMs) ||
                    competingEvents.none { it.eventTier != EventTier.PASSIVE }
            EventTier.ONGOING ->
                competingEvents.none { it.eventTier == EventTier.CRITICAL || it.eventTier == EventTier.INTERRUPTING } &&
                    competingEvents.none {
                        it.eventTier == EventTier.ONGOING &&
                            it.arbitrationPriority > frontEvent.arbitrationPriority
                    }
            EventTier.PASSIVE -> competingEvents.all { it.eventTier == EventTier.PASSIVE }
        }
    }

    private val queueComparator =
        compareByDescending<IslandEvent> { it.eventTier.rank }
            .thenByDescending { it.arbitrationPriority }
            .thenByDescending { it.priority }
            .thenBy { it.id }

    private val EventTier.rank: Int
        get() = when (this) {
            EventTier.CRITICAL -> 4
            EventTier.INTERRUPTING -> 3
            EventTier.ONGOING -> 2
            EventTier.PASSIVE -> 1
        }

    private val IslandEvent.eventTier: EventTier
        get() = when (this) {
            is IslandEvent.ScreenRecording,
            is IslandEvent.MicCamActive,
            is IslandEvent.AudioRecording,
            is IslandEvent.Torch -> EventTier.CRITICAL
            is IslandEvent.Notification ->
                if (isActiveCallNotification) EventTier.CRITICAL else EventTier.INTERRUPTING
            is IslandEvent.BiometricUnlock,
            is IslandEvent.Alarm,
            is IslandEvent.Casting,
            is IslandEvent.Charging,
            is IslandEvent.RingerMode -> EventTier.INTERRUPTING
            is IslandEvent.Media,
            is IslandEvent.Sports,
            is IslandEvent.Timer,
            is IslandEvent.Stopwatch,
            is IslandEvent.Bluetooth,
            is IslandEvent.Hotspot,
            is IslandEvent.PromotedOngoing,
            is IslandEvent.NowPlaying,
            is IslandEvent.Vpn -> EventTier.ONGOING
            is IslandEvent.Clipboard,
            is IslandEvent.AppSwitch,
            is IslandEvent.KeyguardIndication -> EventTier.PASSIVE
        }

    private val IslandEvent.arbitrationPriority: Int
        get() = when (this) {
            is IslandEvent.ScreenRecording -> 500
            is IslandEvent.MicCamActive -> 490
            is IslandEvent.AudioRecording -> 480
            is IslandEvent.Notification -> if (isActiveCallNotification) 470 else 360
            is IslandEvent.Torch -> 460
            is IslandEvent.Alarm -> 390
            is IslandEvent.BiometricUnlock -> 380
            is IslandEvent.Casting -> 370
            is IslandEvent.Charging -> 360
            is IslandEvent.RingerMode -> 350
            is IslandEvent.Media -> if (isPlaying) 340 else 220
            is IslandEvent.Sports ->
                when (status) {
                    IslandEvent.GameStatus.LIVE,
                    IslandEvent.GameStatus.HALFTIME -> 330
                    IslandEvent.GameStatus.PRE_GAME -> 260
                    IslandEvent.GameStatus.FINAL -> 200
                }
            is IslandEvent.Timer -> 320
            is IslandEvent.Stopwatch -> 310
            is IslandEvent.Bluetooth -> 240
            is IslandEvent.Hotspot -> 230
            is IslandEvent.PromotedOngoing -> 225
            is IslandEvent.NowPlaying -> 210
            is IslandEvent.Vpn -> 205
            is IslandEvent.Clipboard -> 150
            is IslandEvent.AppSwitch -> 140
            is IslandEvent.KeyguardIndication -> 130
        }

    private val IslandEvent.interruptingStickinessMs: Long?
        get() = when (this) {
            is IslandEvent.Alarm -> 5_000L
            else -> null
        }

    private val IslandEvent.Notification.isActiveCallNotification: Boolean
        get() {
            val extras = sbn.notification?.extras ?: return false
            return extras.containsKey(android.app.Notification.EXTRA_ANSWER_INTENT) ||
                extras.containsKey(android.app.Notification.EXTRA_DECLINE_INTENT) ||
                extras.containsKey(android.app.Notification.EXTRA_HANG_UP_INTENT)
        }
}
