/*
 * Copyright (C) 2025-2026 AxionOS Project
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

package com.android.systemui.shared.clocks.view

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalDragOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.android.axion.compose.host.AxComposeView
import com.android.systemui.shared.clocks.ClockSettingsRepository
import kotlinx.coroutines.launch

class AxClockHost(private val clock: AxClockView) {

    private lateinit var composeView: AxComposeView

    fun attach(content: @Composable () -> Unit) {
        clock.setWillNotDraw(false)
        clock.clipChildren = false
        clock.clipToPadding = false
        clock.layoutDirection = View.LAYOUT_DIRECTION_LTR
        ClockSettingsRepository.init(clock.context)

        composeView = AxComposeView(clock.context).apply {
            setContent { Host { content() } }
        }
        
        try {
            clock.addView(composeView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        } catch (e: Exception) {
            Log.d("AxClockHost", "AxClockHost init failed error: $e")
        }
    }

    val view: AxComposeView get() = composeView

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val state = clock.state

        LaunchedEffect(Unit) {
            ClockSettingsRepository.isDateBelow.collect { state.dateBelowState.value = it }
        }
        LaunchedEffect(Unit) {
            ClockSettingsRepository.alignment.collect { state.alignmentState.value = it }
        }
        LaunchedEffect(Unit) {
            ClockSettingsRepository.clockColorOverride.collect { state.clockColorOverrideState.value = it }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val align = state.alignmentState.value
            val trigger by state.fidgetTrigger
            val isDoze by state.dozeFlow.collectAsState()
            val animScale = remember { Animatable(1f) }
            var initialDoze by remember { mutableStateOf(true) }

            LaunchedEffect(trigger) {
                if (trigger == 0L || clock.useGlitchInteraction) return@LaunchedEffect
                animScale.snapTo(1f)
                animScale.animateTo(COMPOSE_FIDGET_SQUEEZE, tween(COMPOSE_FIDGET_PHASE_MS, easing = COMPOSE_FIDGET_EASING))
                animScale.animateTo(COMPOSE_FIDGET_EXPAND, tween(COMPOSE_FIDGET_PHASE_MS, easing = COMPOSE_FIDGET_EASING))
                animScale.animateTo(1f, tween(COMPOSE_FIDGET_SETTLE_MS, easing = COMPOSE_FIDGET_EASING))
            }

            LaunchedEffect(isDoze) {
                if (initialDoze) { initialDoze = false; return@LaunchedEffect }
                if (!isDoze) {
                    animScale.snapTo(DOZE_WAKE_START)
                    animScale.animateTo(1f, tween(DOZE_WAKE_MS, easing = DOZE_EASING))
                }
            }

            val sizeModifier = if (clock.isLargeClock) {
                Modifier.fillMaxWidth().wrapContentHeight()
            } else {
                Modifier.fillMaxSize()
            }

            val density = androidx.compose.ui.platform.LocalDensity.current
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val persistedOffset by ClockSettingsRepository.heightOffset.collectAsState()
            val animOffset = remember { Animatable(persistedOffset) }
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

            LaunchedEffect(persistedOffset) {
                if (!animOffset.isRunning) {
                    animOffset.snapTo(persistedOffset)
                }
            }

            Box(
                modifier = sizeModifier
                    .graphicsLayer {
                        val a = animScale.value
                        scaleX = a
                        scaleY = a
                        when (align) {
                            ClockSettingsRepository.ALIGNMENT_LEFT ->
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            ClockSettingsRepository.ALIGNMENT_RIGHT ->
                                transformOrigin = TransformOrigin(1f, 0.5f)
                        }
                    }
                    .pointerInput(clock.isPreviewMode) {
                        if (clock.isPreviewMode) return@pointerInput

                        val screenHeightDp = configuration.screenHeightDp.toFloat()
                        val minBound = -screenHeightDp * 0.2f
                        val maxBound = screenHeightDp * 0.5f
                        val resistance = 0.45f
                        var lastTapTime = 0L
                        var lastTapPosition: androidx.compose.ui.geometry.Offset? = null

                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val currentTime = System.currentTimeMillis()
                            val tapPosition = down.position
                            
                            val isDoubleTap = currentTime - lastTapTime < 300 &&
                                    lastTapPosition?.let { (it - tapPosition).getDistance() < 100 } ?: false

                            if (isDoubleTap) {
                                // Double tap detected
                                coroutineScope.launch {
                                    animOffset.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
                                    ClockSettingsRepository.saveHeightOffset(clock.context, 0f)
                                }
                                lastTapTime = 0
                                lastTapPosition = null
                            } else {
                                lastTapTime = currentTime
                                lastTapPosition = tapPosition
                                
                                val slopDrag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                                    val overDp = with(density) { over.toDp().value }
                                    animOffset.snapTo(animOffset.value + overDp)
                                    change.consume()
                                }

                                if (slopDrag != null) {
                                    // User started dragging, so clear tap history to prevent accidental reset
                                    lastTapTime = 0
                                    lastTapPosition = null

                                    var drag: PointerInputChange? = slopDrag
                                    while (drag != null) {
                                        val dragAmount = with(density) { (drag!!.position.y - drag!!.previousPosition.y).toDp().value }
                                        val current = animOffset.value
                                        val adjustedDelta = if (current < minBound || current > maxBound) {
                                            dragAmount * resistance
                                        } else {
                                            dragAmount
                                        }

                                        // We can call snapTo directly here if we want to avoid launch,
                                        // but Animatable.snapTo is a suspend function and we are in
                                        // AwaitPointerEventScope (which is a suspend scope).
                                        // However, snapTo might conflict with the pointer loop if not careful.
                                        // Using coroutineScope.launch is safer for UI updates from pointer events.
                                        // BUT the feedback explicitly said to remove it.
                                        // Let's try calling it directly.
                                        animOffset.snapTo(current + adjustedDelta)

                                        drag!!.consume()
                                        drag = awaitVerticalDragOrCancellation(down.id)
                                    }

                                    // Drag ended: settle with physics
                                    coroutineScope.launch {
                                        val finalValue = animOffset.value.coerceIn(minBound, maxBound)
                                        if (animOffset.value != finalValue) {
                                            animOffset.animateTo(
                                                finalValue,
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessLow
                                                )
                                            )
                                        }
                                        ClockSettingsRepository.saveHeightOffset(clock.context, finalValue)
                                    }
                                }
                            }
                        }
                    }
            ) {
                content()
            }
        }
    }
}
