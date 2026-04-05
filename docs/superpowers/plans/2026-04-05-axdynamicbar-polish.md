# AxDynamicBar Polish Pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 4 polish issues in AxDynamicBar: download chip width blowout, screen recording countdown→timer snap, laggy expand/collapse animation, and misplaced dismiss strip.

**Architecture:** Four independent, targeted edits across three files. Tasks 1 and 2 are single-function changes. Tasks 3 and 4 refactor `ExpandedIslandContent.kt` and `AxDynamicBarExpandedPanel.kt` — do them in order since they touch the same files.

**Tech Stack:** Kotlin, Jetpack Compose, Android SystemUI

---

## Files Modified

| File | Task | What changes |
|---|---|---|
| `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/PillIslandContent.kt` | 1 | `PromotedOngoingText` — show "Downloading · X%" for progress events |
| `packages/SystemUI/src/com/android/systemui/axdynamicbar/shared/IslandContentTokens.kt` | 2 | `textKeyFor` — add `ScreenRecording` branch keyed on `isCountdown` |
| `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/ExpandedIslandContent.kt` | 3, 4 | Refactor `SeedCard`/`StaggeredCard` animations; add `collapseRequested` param; remove `DismissStrip` item |
| `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/AxDynamicBarExpandedPanel.kt` | 3, 4 | Extend outer exit tween; pass `collapseRequested`; fix `topPad`; add fixed dismiss strip in status bar area |

---

## Task 1: Fix download chip width

**Files:**
- Modify: `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/PillIslandContent.kt` (function `PromotedOngoingText`, around line 759)

- [ ] **Step 1: Replace `PromotedOngoingText`**

Replace the existing function body:

```kotlin
// BEFORE
private fun PromotedOngoingText(event: IslandEvent.PromotedOngoing, modifier: Modifier, overrideColor: Color? = null) {
    val label = event.shortText.ifEmpty { event.title.ifEmpty { event.appName } }
    MarqueeLabel(label, overrideColor ?: BlueAccent, modifier)
}
```

```kotlin
// AFTER
private fun PromotedOngoingText(event: IslandEvent.PromotedOngoing, modifier: Modifier, overrideColor: Color? = null) {
    val label = if (event.progress >= 0f) {
        "Downloading · ${(event.progress * 100).toInt()}%"
    } else {
        event.shortText.ifEmpty { event.title.ifEmpty { event.appName } }
    }
    MarqueeLabel(label, overrideColor ?: BlueAccent, modifier)
}
```

- [ ] **Step 2: Build to verify it compiles**

```bash
cd /home/bijoyv9/android && m SystemUI 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, no errors in `PillIslandContent.kt`.

- [ ] **Step 3: Commit**

```bash
git add packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/PillIslandContent.kt
git commit -m "AxDynamicBar: Show Downloading percentage in chip instead of file name"
```

---

## Task 2: Fix countdown → timer transition

**Files:**
- Modify: `packages/SystemUI/src/com/android/systemui/axdynamicbar/shared/IslandContentTokens.kt` (function `textKeyFor`, around line 539)

- [ ] **Step 1: Add `ScreenRecording` branch to `textKeyFor`**

```kotlin
// BEFORE
internal fun textKeyFor(event: IslandEvent): Any =
    when (event) {
        is IslandEvent.Media -> "${event.track}|${event.artist}"
        is IslandEvent.Timer,
        is IslandEvent.Stopwatch,
        is IslandEvent.AudioRecording,
        is IslandEvent.Call -> "tick_text"
        else -> event.id
    }
```

```kotlin
// AFTER
internal fun textKeyFor(event: IslandEvent): Any =
    when (event) {
        is IslandEvent.Media -> "${event.track}|${event.artist}"
        is IslandEvent.ScreenRecording -> "screen_recording:${event.isCountdown}"
        is IslandEvent.Timer,
        is IslandEvent.Stopwatch,
        is IslandEvent.AudioRecording,
        is IslandEvent.Call -> "tick_text"
        else -> event.id
    }
```

- [ ] **Step 2: Build to verify it compiles**

```bash
cd /home/bijoyv9/android && m SystemUI 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, no errors in `IslandContentTokens.kt`.

- [ ] **Step 3: Commit**

```bash
git add packages/SystemUI/src/com/android/systemui/axdynamicbar/shared/IslandContentTokens.kt
git commit -m "AxDynamicBar: Animate countdown-to-timer transition on screen recording chip"
```

---

## Task 3: Redesign expand/collapse animation

**Files:**
- Modify: `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/ExpandedIslandContent.kt`
- Modify: `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/AxDynamicBarExpandedPanel.kt`

### Step 1: Update imports in `ExpandedIslandContent.kt`

- [ ] **Remove unused imports and add new ones**

Remove these imports (no longer needed after SeedCard refactor):
```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
```

Add these imports:
```kotlin
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideOutVertically
```

(`slideInVertically` is already imported. `scaleIn`/`scaleOut` are still used in `ExpandedEventCard` — keep them.)

### Step 2: Add `collapseRequested` parameter to `ExpandedIslandContent`

- [ ] **Update the function signature and thread `collapseRequested` to cards**

```kotlin
// BEFORE signature
fun ExpandedIslandContent(
    events: List<IslandEvent>,
    interactor: IslandActions,
    onCollapse: () -> Unit,
    expandedFilter: String? = null,
    pinnedEventId: String? = null,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
)
```

```kotlin
// AFTER signature
fun ExpandedIslandContent(
    events: List<IslandEvent>,
    interactor: IslandActions,
    onCollapse: () -> Unit,
    collapseRequested: Boolean = false,
    expandedFilter: String? = null,
    pinnedEventId: String? = null,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
)
```

Inside the `itemsIndexed` block (around line 173), update both card calls to pass `collapseRequested` and per-card delays:

```kotlin
// BEFORE
itemsIndexed(filteredEvents, key = { _, event -> event.id }) { index, event ->
    if (index == total - 1) {
        SeedCard(
            event = event,
            interactor = interactor,
            hapticsViewModelFactory = hapticsViewModelFactory,
            onDismiss = { interactor.dismissEvent(event) },
            modifier = Modifier.animateItem(),
        )
    } else {
        val distanceFromSeed = total - 1 - index
        StaggeredCard(
            event = event,
            interactor = interactor,
            hapticsViewModelFactory = hapticsViewModelFactory,
            onDismiss = { interactor.dismissEvent(event) },
            delayMs = 140L + distanceFromSeed * 80L,
            modifier = Modifier.animateItem(),
        )
    }
}
```

```kotlin
// AFTER
itemsIndexed(filteredEvents, key = { _, event -> event.id }) { index, event ->
    if (index == total - 1) {
        SeedCard(
            event = event,
            interactor = interactor,
            hapticsViewModelFactory = hapticsViewModelFactory,
            onDismiss = { interactor.dismissEvent(event) },
            exitDelayMs = index.toLong() * 80L,
            collapseRequested = collapseRequested,
            modifier = Modifier.animateItem(),
        )
    } else {
        val distanceFromSeed = total - 1 - index
        StaggeredCard(
            event = event,
            interactor = interactor,
            hapticsViewModelFactory = hapticsViewModelFactory,
            onDismiss = { interactor.dismissEvent(event) },
            delayMs = 140L + distanceFromSeed * 80L,
            exitDelayMs = index.toLong() * 80L,
            collapseRequested = collapseRequested,
            modifier = Modifier.animateItem(),
        )
    }
}
```

### Step 3: Replace `SeedCard`

- [ ] **Replace the entire `SeedCard` composable**

```kotlin
// REMOVE entirely:
@Composable
private fun SeedCard(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scaleX = remember { Animatable(0.38f) }
    val scaleY = remember { Animatable(0.32f) }

    LaunchedEffect(Unit) {
        delay(80L)
        launch { scaleX.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)) }
        scaleY.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium))
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                this.scaleX = scaleX.value
                this.scaleY = scaleY.value
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
    ) {
        MagneticSwipeToDismiss(
            onDismiss = onDismiss,
        ) {
            ExpandedEventCard(event, interactor, hapticsViewModelFactory)
        }
    }
}
```

```kotlin
// ADD:
@Composable
private fun SeedCard(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    onDismiss: () -> Unit,
    exitDelayMs: Long,
    collapseRequested: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(80L)
        visible = true
    }

    LaunchedEffect(collapseRequested) {
        if (collapseRequested) {
            delay(exitDelayMs)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 4 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 4 },
        modifier = modifier,
    ) {
        MagneticSwipeToDismiss(onDismiss = onDismiss) {
            ExpandedEventCard(event, interactor, hapticsViewModelFactory)
        }
    }
}
```

### Step 4: Replace `StaggeredCard`

- [ ] **Replace the entire `StaggeredCard` composable**

```kotlin
// REMOVE entirely:
@Composable
private fun StaggeredCard(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    onDismiss: () -> Unit,
    delayMs: Long,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(delayMs)
        visible = true
    }

    // No exit spec: visible is never set back to false — dismissal is handled
    // by MagneticSwipeToDismiss (swipe gesture) + animateItem() (slot removal).
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 },
        modifier = modifier,
    ) {
        MagneticSwipeToDismiss(
            onDismiss = onDismiss,
        ) {
            ExpandedEventCard(event, interactor, hapticsViewModelFactory)
        }
    }
}
```

```kotlin
// ADD:
@Composable
private fun StaggeredCard(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    onDismiss: () -> Unit,
    delayMs: Long,
    exitDelayMs: Long,
    collapseRequested: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(delayMs)
        visible = true
    }

    LaunchedEffect(collapseRequested) {
        if (collapseRequested) {
            delay(exitDelayMs)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 4 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 4 },
        modifier = modifier,
    ) {
        MagneticSwipeToDismiss(onDismiss = onDismiss) {
            ExpandedEventCard(event, interactor, hapticsViewModelFactory)
        }
    }
}
```

### Step 5: Update `AxDynamicBarExpandedPanel.kt` — outer exit timing, topPad, and collapseRequested

- [ ] **In `OverlayContent`, extend outer exit tween and add `expandedTopPad`**

`topPad` is also used by the notification alert card (which should continue to pop from y=0 on non-large screens). Add a separate variable for the expanded card area only:

```kotlin
// ADD after the existing topPad declaration — do NOT change topPad itself
val expandedTopPad = with(density) { statusBarHeightPx.toDp() } + if (isLargeScreen) 4.dp else 0.dp
```

Then in the expanded `Box`, change `.padding(top = topPad)` → `.padding(top = expandedTopPad)` (only the expanded Box; the notification alert Box keeps `.padding(top = topPad)`).

```kotlin
// BEFORE
AnimatedVisibility(
    visibleState = expandedVisible,
    enter = fadeIn(tween(180)),
    exit = fadeOut(tween(200)),
) {
```

```kotlin
// AFTER
AnimatedVisibility(
    visibleState = expandedVisible,
    enter = fadeIn(tween(180)),
    exit = fadeOut(tween(500)),
) {
```

- [ ] **Pass `collapseRequested` to `ExpandedIslandContent`**

```kotlin
// BEFORE
lastChipState.value?.let { state ->
    ExpandedIslandContent(
        events = state.allEvents,
        interactor = viewModel.interactor,
        onCollapse = { viewModel.collapsePanel() },
        pinnedEventId = state.event.id,
        hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
    )
}
```

```kotlin
// AFTER
lastChipState.value?.let { state ->
    ExpandedIslandContent(
        events = state.allEvents,
        interactor = viewModel.interactor,
        onCollapse = { viewModel.collapsePanel() },
        collapseRequested = !isExpanded,
        pinnedEventId = state.event.id,
        hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
    )
}
```

- [ ] **Build to verify it compiles**

```bash
cd /home/bijoyv9/android && m SystemUI 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Commit**

```bash
git add packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/ExpandedIslandContent.kt
git add packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/AxDynamicBarExpandedPanel.kt
git commit -m "AxDynamicBar: Redesign expand/collapse card animation (slide up from bottom, staggered)"
```

---

## Task 4: Move dismiss strip to status bar area

**Files:**
- Modify: `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/ExpandedIslandContent.kt`
- Modify: `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/AxDynamicBarExpandedPanel.kt`

### Step 1: Remove `DismissStrip` from `ExpandedIslandContent`

- [ ] **Remove the `item(key="dismiss_strip")` from the `LazyColumn`**

```kotlin
// REMOVE these two lines from inside the LazyColumn:
        item(key = "dismiss_strip") {
            DismissStrip(onDismiss = onCollapse)
        }
```

- [ ] **Remove the `DismissStrip` private composable** (entire function, lines ~355–375)

```kotlin
// REMOVE entirely:
@Composable
private fun DismissStrip(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.15f))
        )
    }
}
```

After removing `DismissStrip`, `clickable`, `MutableInteractionSource`, `RoundedCornerShape`, `height`, `width` may no longer be needed at the top of the file. Check if they are used elsewhere in the file before removing imports — `clickable` is used in notification group cards, `MutableInteractionSource` likewise, `height`/`width` are used in card layouts. Keep all of them.

### Step 2: Add fixed dismiss strip in `AxDynamicBarExpandedPanel.kt`

- [ ] **Restructure the `AnimatedVisibility(visibleState = expandedVisible)` block**

The current block has a single `Box` child. Wrap its contents in an outer `Box(fillMaxSize)` and add the dismiss strip as a sibling.

Also add these imports at the top of the file if not already present:
```kotlin
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
```

(`dp` and `Box` and `fillMaxWidth` are already imported.)

```kotlin
// BEFORE — the AnimatedVisibility(visibleState = expandedVisible) block:
    AnimatedVisibility(
        visibleState = expandedVisible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(500)),
    ) {
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    /* ... tap-to-dismiss logic ... */
                }
                .padding(top = topPad),
            contentAlignment = chipAlignment,
        ) {
            lastChipState.value?.let { state ->
                ExpandedIslandContent(
                    events = state.allEvents,
                    interactor = viewModel.interactor,
                    onCollapse = { viewModel.collapsePanel() },
                    collapseRequested = !isExpanded,
                    pinnedEventId = state.event.id,
                    hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                )
            }
        }
    }
```

```kotlin
// AFTER — wrap in outer Box, add strip:
    AnimatedVisibility(
        visibleState = expandedVisible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(500)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Full-screen tap-to-dismiss + cards
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        /* ... tap-to-dismiss logic — unchanged ... */
                    }
                    .padding(top = expandedTopPad),
                contentAlignment = chipAlignment,
            ) {
                lastChipState.value?.let { state ->
                    ExpandedIslandContent(
                        events = state.allEvents,
                        interactor = viewModel.interactor,
                        onCollapse = { viewModel.collapsePanel() },
                        collapseRequested = !isExpanded,
                        pinnedEventId = state.event.id,
                        hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                    )
                }
            }

            // Fixed dismiss strip — sits in the status bar area where the chip was
            val stripHeight = if (statusBarHeightPx > 0) {
                with(density) { statusBarHeightPx.toDp() }
            } else 28.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(stripHeight)
                    .align(Alignment.TopCenter)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { viewModel.collapsePanel() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                )
            }
        }
    }
```

- [ ] **Build to verify it compiles**

```bash
cd /home/bijoyv9/android && m SystemUI 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, no errors.

- [ ] **Commit**

```bash
git add packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/ExpandedIslandContent.kt
git add packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/AxDynamicBarExpandedPanel.kt
git commit -m "AxDynamicBar: Move dismiss strip to status bar area"
```
