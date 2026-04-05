# AxDynamicBar Polish Pass — Design Spec

**Date:** 2026-04-05
**Branch:** staging

## Overview

Four targeted fixes to AxDynamicBar: chip width blowout on downloads, janky countdown-to-timer transition on screen recording, laggy expand/collapse animation, and misplaced dismiss strip.

---

## Issue 1 — Download chip width

### Problem
`PromotedOngoingText` renders `event.shortText` directly in the chip. Android's download manager sets `shortText` to the full file name (e.g. `com.example.app-3.2.1.apk`), which can be very long. The chip expands to fit, pushing notification icons out of the status bar.

### Fix
In `PillIslandContent.kt`, `PromotedOngoingText`: when `event.progress >= 0f`, show `"Downloading · ${(event.progress * 100).toInt()}%"` instead of `shortText`. When there is no progress value, keep the existing fallback chain (`shortText → title → appName`).

The full file name remains visible in the expanded card via `PromotedOngoingExpanded` — no change there.

**File:** `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/PillIslandContent.kt`
**Function:** `PromotedOngoingText`

---

## Issue 2 — Screen recording countdown → timer transition

### Problem
`textKeyFor(ScreenRecording)` falls through to the `else` branch and returns `event.id`, which is the same before and after the countdown ends. The `AnimatedContent` wrapping the chip text sees an unchanged key when `isCountdown` flips false → no crossfade fires → text snaps directly from "1" to "0:00".

### Fix
Add a `ScreenRecording` branch to `textKeyFor` in `IslandContentTokens.kt`:

```kotlin
is IslandEvent.ScreenRecording -> "screen_recording:${event.isCountdown}"
```

When `isCountdown` changes, the key changes, and the existing `AnimatedContent` fade crossfade fires automatically in both `AxDynamicBarChip.kt` and `AxDynamicBarNowBar.kt`. No other changes needed.

**File:** `packages/SystemUI/src/com/android/systemui/axdynamicbar/shared/IslandContentTokens.kt`
**Function:** `textKeyFor`

---

## Issue 3 — Expand/collapse animation

### Problem
Current `SeedCard` uses a `scaleX/scaleY Animatable` spring starting at `(0.38f, 0.32f)` with `transformOrigin = Top`. This reads as "chip morphs into the top card." `StaggeredCard` has no exit animation — collapse is abrupt.

### Desired behaviour
- **Enter:** Bottom card (pinned/primary, last in list) slides up first. Cards above follow with `80ms × distanceFromSeed` stagger. All cards use `fadeIn + slideInVertically` from ~25% of their own height below final position.
- **Exit (collapse):** Mirror of entry. Top cards (furthest from primary) exit first with `slideOutVertically + fadeOut`, staggered with the same `80ms × distanceFromSeed` delay. Primary card exits last.

### Changes

**`SeedCard`** — remove `Animatable` scale spring entirely. Replace with:
```kotlin
AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(200)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 4 },
    exit  = fadeOut(tween(160)) + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 4 },
)
```
`visible` starts `false`, set to `true` after an `80ms` delay via `LaunchedEffect`.

**`StaggeredCard`** — add exit animation and a collapse-driven exit. Add `exitDelayMs` parameter (= `distanceFromSeed * 80L` counted from top). When `ExpandedIslandContent` receives a collapse signal, flip `visible = false` on each card with its exit delay, then invoke `onCollapse` after the last card's exit completes.

**Collapse signal propagation:** Add a `collapseRequested: Boolean` parameter to `ExpandedIslandContent`. This is derived from `!isExpanded` in `OverlayContent` and passed down. When it flips `true`, each card's `visible` state is set to `false` (with its exit delay) via a `LaunchedEffect(collapseRequested)`. No `onCollapse()` coordination needed — the outer `AnimatedVisibility` in `OverlayContent` already fades out the overlay when `isExpanded` becomes false. Its exit duration is extended to `tween(350)` to give cards time to animate out before the overlay disappears.

**File:** `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/ExpandedIslandContent.kt`

---

## Issue 4 — Dismiss strip placement

### Problem
`DismissStrip` is `item(key="dismiss_strip")` — first item in the `LazyColumn`. It occupies visible space above the cards and looks awkward.

### Fix
1. **Remove** `item(key="dismiss_strip")` from `ExpandedIslandContent.kt`'s `LazyColumn`.
2. **Add** a fixed dismiss strip overlay in `OverlayContent` (`AxDynamicBarExpandedPanel.kt`). Place it inside the existing `AnimatedVisibility(visibleState = expandedVisible)` block, as a second child of the top-level `Box`, aligned to `Alignment.TopCenter`. It sits at `y = 0` covering the status bar area where the chip was (chip is hidden when expanded). Tapping calls `viewModel.collapsePanel()`.

The strip becomes a fixed-position element that doesn't scroll with cards and doesn't consume space in the card list.

**Files:**
- `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/compose/ExpandedIslandContent.kt`
- `packages/SystemUI/src/com/android/systemui/axdynamicbar/ui/AxDynamicBarExpandedPanel.kt`
