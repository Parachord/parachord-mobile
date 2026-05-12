# Now Playing Gestures Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add double-tap-to-love (Instagram-classic, always-add) and horizontal swipe-to-skip gestures to the album-art region of the Now Playing screen.

**Architecture:** A new `AlbumArtWithGestures` composable in `ui/screens/nowplaying/AlbumArtGestures.kt` wraps the existing `AlbumArtCardFill`, layers a heart-pop overlay, and adds a single `Modifier.pointerInput(track.id)` block with two sibling detectors: `detectTapGestures` and `detectHorizontalDragGestures`. A pure `decideSwipeCommit` function isolates the threshold/velocity decision for unit testing.

**Tech Stack:** Jetpack Compose `pointerInput` + gesture detectors, `Animatable<Float>` for scale/alpha/offset, `kotlinx.coroutines` for concurrent animation jobs, `VelocityTracker` for release-velocity computation. Tests via JUnit (pure Kotlin) for the decision function; Compose pointer-input behavior verified on-device.

**Design doc:** [`docs/plans/2026-05-11-now-playing-gestures-design.md`](2026-05-11-now-playing-gestures-design.md). Read first for decision rationale.

---

## Task 0: Branch baseline

**Step 1:** Confirm branch.

Run: `git branch --show-current && git status -s`
Expected: `feature/now-playing-gestures` and a clean tree.

**Step 2:** Confirm baseline tests pass.

Run: `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

No commit — read-only orientation.

---

## Task 1: `decideSwipeCommit` pure logic + 8 unit tests

TDD. Pure Kotlin, no Compose, no Android. The function returns the swipe outcome given offset, velocity, and threshold.

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGesturesLogic.kt`
- Create: `app/src/test/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGesturesLogicTest.kt`

**Step 1: Write the failing tests.**

```kotlin
// app/src/test/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGesturesLogicTest.kt
package com.parachord.android.ui.screens.nowplaying

import org.junit.Test
import kotlin.test.assertEquals

class AlbumArtGesturesLogicTest {

    private val threshold = 300f          // ~30% of a 1000px screen
    private val velocityThreshold = 600f

    @Test
    fun belowThresholdAndSlowVelocity_snapsBack() {
        val result = decideSwipeCommit(offsetX = -50f, velocity = -100f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.SnapBack, result)
    }

    @Test
    fun aboveThresholdLeftDrag_slowVelocity_commitsNext() {
        // Finger moved left → offsetX is negative → next
        val result = decideSwipeCommit(offsetX = -350f, velocity = -100f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Next, result)
    }

    @Test
    fun aboveThresholdRightDrag_slowVelocity_commitsPrevious() {
        // Finger moved right → offsetX is positive → previous
        val result = decideSwipeCommit(offsetX = 350f, velocity = 100f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Previous, result)
    }

    @Test
    fun belowThresholdButFastNegativeVelocity_commitsNext() {
        val result = decideSwipeCommit(offsetX = -50f, velocity = -800f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Next, result)
    }

    @Test
    fun belowThresholdButFastPositiveVelocity_commitsPrevious() {
        val result = decideSwipeCommit(offsetX = 50f, velocity = 800f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Previous, result)
    }

    @Test
    fun velocityDisagreesWithOffset_velocityWins() {
        // Dragged left past threshold, but released with a fast rightward flick
        // → user is "throwing it back". Velocity wins → Previous.
        val result = decideSwipeCommit(offsetX = -350f, velocity = 900f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Previous, result)
    }

    @Test
    fun zeroDrag_zeroVelocity_snapsBack() {
        val result = decideSwipeCommit(offsetX = 0f, velocity = 0f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.SnapBack, result)
    }

    @Test
    fun exactlyAtThreshold_commits() {
        val result = decideSwipeCommit(offsetX = -300f, velocity = 0f,
            commitThreshold = threshold, velocityThreshold = velocityThreshold)
        assertEquals(SwipeOutcome.Next, result)
    }
}
```

**Step 2: Run, confirm failure.**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlbumArtGesturesLogicTest*"`
Expected: 8 failures with "decideSwipeCommit / SwipeOutcome not defined" (or build-fail).

**Step 3: Implement.**

```kotlin
// app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGesturesLogic.kt
package com.parachord.android.ui.screens.nowplaying

import kotlin.math.abs
import kotlin.math.sign

/**
 * Swipe-release outcome for the album-art horizontal-drag gesture.
 *
 * The decision is pure: given the current drag offset and release
 * velocity, return whether to commit to the next track, the previous
 * track, or spring back to centered.
 */
sealed class SwipeOutcome {
    /** Commit to the next track (finger moved left, offsetX < 0). */
    object Next : SwipeOutcome()
    /** Commit to the previous track (finger moved right, offsetX > 0). */
    object Previous : SwipeOutcome()
    /** Drag didn't cross the threshold — animate back to centered. */
    object SnapBack : SwipeOutcome()
}

/**
 * Decide whether a horizontal drag should commit to next / previous /
 * snap-back.
 *
 * Rules:
 *  - If neither the drag distance NOR the release velocity crosses its
 *    threshold, snap back.
 *  - Otherwise, the SIGN of the dominant signal (whichever crossed)
 *    decides direction:
 *      - Velocity dominates when it crossed its threshold.
 *      - Otherwise the offset's sign decides.
 *    Convention: leftward motion (negative offset / negative velocity)
 *    → Next; rightward → Previous.
 *  - Velocity overrides offset when they disagree — handles "fast
 *    flick to throw back" past-threshold drags.
 *
 * @param offsetX horizontal drag distance in pixels. Negative = finger
 *   moved left.
 * @param velocity release velocity in px/s. Negative = leftward.
 * @param commitThreshold minimum |offsetX| (px) for an offset-based
 *   commit. Typically 30% of screen width.
 * @param velocityThreshold minimum |velocity| (px/s) for a
 *   velocity-based commit. Defaults to 600 px/s.
 */
fun decideSwipeCommit(
    offsetX: Float,
    velocity: Float,
    commitThreshold: Float,
    velocityThreshold: Float = 600f,
): SwipeOutcome {
    val absOffset = abs(offsetX)
    val absVelocity = abs(velocity)
    val offsetCrossed = absOffset >= commitThreshold
    val velocityCrossed = absVelocity >= velocityThreshold
    if (!offsetCrossed && !velocityCrossed) return SwipeOutcome.SnapBack
    // Velocity dominates when it crossed; otherwise offset's sign decides.
    val sign = if (velocityCrossed) (-velocity).sign else (-offsetX).sign
    return if (sign > 0) SwipeOutcome.Next else SwipeOutcome.Previous
}
```

**Step 4: Run, confirm green.**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlbumArtGesturesLogicTest*"`
Expected: 8/8 PASS.

**Step 5: Commit.**

```bash
git add app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGesturesLogic.kt \
        app/src/test/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGesturesLogicTest.kt
git commit -m "$(cat <<'EOF'
ui: decideSwipeCommit + 8 unit tests (#now-playing-gestures)

Pure threshold + velocity logic for the album-art horizontal-drag
gesture. Velocity overrides offset on disagreement (handles
fast-flick-to-throw-back). Convention: leftward motion → Next,
rightward → Previous. Extracted to its own file so Compose
pointer-input doesn't have to be tested with brittle integration
tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `AlbumArtWithGestures` skeleton (no gestures wired yet)

The composable wraps `AlbumArtCardFill` and exposes the public API. Gesture wiring is added in Tasks 3 + 4 incrementally so each step is reviewable.

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGestures.kt`

**Step 1: Write the file.**

```kotlin
// app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGestures.kt
package com.parachord.android.ui.screens.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.components.AlbumArtCardFill

/**
 * Album art with double-tap-to-love and horizontal swipe-to-skip
 * gestures.
 *
 *  - **Single tap** (via [onSingleTap]) preserves the existing tap-
 *    to-open-album behavior.
 *  - **Double tap** invokes [onDoubleTapLove] and pops a heart icon at
 *    the touch position. Animation only fires when [isLoved] is false
 *    at gesture time (Instagram-strict: no toggle, no animation on
 *    repeat).
 *  - **Horizontal drag** moves the artwork with the finger. On release,
 *    commits to next ([onSwipeNext]) or previous ([onSwipePrevious])
 *    if the drag crossed the commit threshold or released with high
 *    velocity, otherwise springs back.
 *
 * See `docs/plans/2026-05-11-now-playing-gestures-design.md` for the
 * full design.
 */
@Composable
fun AlbumArtWithGestures(
    artworkUrl: String?,
    isLoved: Boolean,
    onSingleTap: (() -> Unit)?,
    onDoubleTapLove: () -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    placeholderName: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    elevation: Dp = 8.dp,
) {
    // Task 2: pass-through skeleton. Gestures wired in Tasks 3 + 4.
    Box(modifier = modifier) {
        AlbumArtCardFill(
            artworkUrl = artworkUrl,
            modifier = Modifier,
            cornerRadius = cornerRadius,
            elevation = elevation,
            placeholderName = placeholderName,
        )
    }
}
```

**Step 2: Compile.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit.**

```bash
git add app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGestures.kt
git commit -m "$(cat <<'EOF'
ui: AlbumArtWithGestures pass-through skeleton (#now-playing-gestures)

Public API + parameters. Wraps AlbumArtCardFill without gestures yet
so reviewers can see the call-site contract before the gesture
mechanics land in Tasks 3 + 4.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Double-tap to love + heart-pop animation

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGestures.kt`

**Step 1: Add the gesture + animation state.**

Replace the body of `AlbumArtWithGestures` with:

```kotlin
@Composable
fun AlbumArtWithGestures(
    artworkUrl: String?,
    isLoved: Boolean,
    onSingleTap: (() -> Unit)?,
    onDoubleTapLove: () -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    placeholderName: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    elevation: Dp = 8.dp,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Heart-pop animation state. poppedAt is the touch position in
    // composable-local pixels; null when no animation is active.
    var poppedAt by remember { mutableStateOf<Offset?>(null) }
    val heartScale = remember { Animatable(0.5f) }
    val heartAlpha = remember { Animatable(0f) }

    // Read the loved state at gesture time — not via LaunchedEffect on
    // `isLoved` — so the animation is anchored to the explicit gesture,
    // not to repository state changes triggered by other paths
    // (e.g. context-menu love).
    val isLovedNow by rememberUpdatedState(isLoved)

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { onSingleTap?.invoke() },
                onDoubleTap = { offset ->
                    if (!isLovedNow) {
                        onDoubleTapLove()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        poppedAt = offset
                    }
                    // Already loved → no-op, no animation, no haptic.
                },
            )
        },
    ) {
        AlbumArtCardFill(
            artworkUrl = artworkUrl,
            modifier = Modifier,
            cornerRadius = cornerRadius,
            elevation = elevation,
            placeholderName = placeholderName,
        )

        // Heart-pop overlay. Renders only while the animation is active.
        poppedAt?.let { pos ->
            val heartSizeDp = 96.dp
            val heartHalfPx = with(density) { (heartSizeDp / 2).toPx().toInt() }
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(heartSizeDp)
                    .offset { IntOffset(pos.x.toInt() - heartHalfPx, pos.y.toInt() - heartHalfPx) }
                    .graphicsLayer {
                        scaleX = heartScale.value
                        scaleY = heartScale.value
                        alpha = heartAlpha.value
                        shadowElevation = 8f
                    },
            )
        }
    }

    // Drive the animation as a side-effect of poppedAt changing.
    LaunchedEffect(poppedAt) {
        val pos = poppedAt ?: return@LaunchedEffect
        heartScale.snapTo(0.5f)
        heartAlpha.snapTo(0f)
        coroutineScope {
            launch {
                heartScale.animateTo(1.5f, tween(150))
                heartScale.animateTo(1.0f, tween(100))
            }
            launch {
                heartAlpha.animateTo(1f, tween(100))
                delay(200)
                heartAlpha.animateTo(0f, tween(300))
            }
        }
        // Only clear if we're still showing the same pop — guards against
        // a rapid second double-tap that reset poppedAt mid-animation.
        if (poppedAt == pos) poppedAt = null
    }
}
```

Add imports:

```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
```

**Step 2: Compile.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit.**

```bash
git add app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGestures.kt
git commit -m "$(cat <<'EOF'
ui: double-tap-to-love + heart-pop overlay (#now-playing-gestures)

Instagram-classic: on double-tap, if !isLoved → fire callback +
haptic + animate a 96dp white heart at the touch point (scale 0.5→1.5→
1.0, alpha 0→1→0, total ~600ms). Already-loved tracks are no-op (no
animation, no haptic, no toast). Loved-state read via
rememberUpdatedState so the gesture handler always sees the current
value.

Single-tap preserved via the existing onSingleTap callback.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Horizontal swipe to skip

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGestures.kt`

**Step 1: Add drag state + handler block.**

Add this state above the `Box(...)` (alongside `poppedAt`, etc.):

```kotlin
val configuration = LocalConfiguration.current
val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
val commitThresholdPx = screenWidthPx * SWIPE_COMMIT_FRACTION
val edgeGuardPx = with(density) { EDGE_GUARD_DP.toPx() }

val offsetX = remember { Animatable(0f) }
val coroutineScope = rememberCoroutineScope()
val velocityTracker = remember { VelocityTracker() }
var dragStartX by remember { mutableStateOf(0f) }
var dragInProgress by remember { mutableStateOf(false) }
```

Add file-level constants at the bottom of the file (after the function):

```kotlin
private const val SWIPE_COMMIT_FRACTION = 0.30f
private const val SWIPE_VELOCITY_THRESHOLD_PX_PER_S = 600f
private val EDGE_GUARD_DP = 16.dp
private const val SWIPE_OUT_ANIMATION_MS = 200
```

Add a second gesture detector block on the same `Box`. Compose's `pointerInput` accepts multiple gesture detectors via separate `pointerInput(...)` modifiers chained. Easier: add a `.pointerInput(Unit) { detectHorizontalDragGestures(...) }` chained after the existing tap detector:

```kotlin
Box(
    modifier = modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { onSingleTap?.invoke() },
                onDoubleTap = { offset ->
                    if (!isLovedNow) {
                        onDoubleTapLove()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        poppedAt = offset
                    }
                },
            )
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { start ->
                    dragStartX = start.x
                    if (start.x < edgeGuardPx || start.x > size.width - edgeGuardPx) {
                        // Reserve edge for system back-gesture. Mark drag
                        // as inactive so onDrag updates are ignored.
                        dragInProgress = false
                        return@detectHorizontalDragGestures
                    }
                    dragInProgress = true
                    velocityTracker.resetTracking()
                    coroutineScope.launch { offsetX.stop() }
                },
                onHorizontalDrag = { change, dx ->
                    if (!dragInProgress) return@detectHorizontalDragGestures
                    coroutineScope.launch {
                        offsetX.snapTo(offsetX.value + dx)
                    }
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                },
                onDragEnd = {
                    if (!dragInProgress) return@detectHorizontalDragGestures
                    dragInProgress = false
                    val velocity = velocityTracker.calculateVelocity().x
                    val outcome = decideSwipeCommit(
                        offsetX = offsetX.value,
                        velocity = velocity,
                        commitThreshold = commitThresholdPx,
                        velocityThreshold = SWIPE_VELOCITY_THRESHOLD_PX_PER_S,
                    )
                    coroutineScope.launch {
                        when (outcome) {
                            SwipeOutcome.Next -> {
                                offsetX.animateTo(-screenWidthPx, tween(SWIPE_OUT_ANIMATION_MS))
                                onSwipeNext()
                                // Reset for next track's compose pass.
                                offsetX.snapTo(0f)
                            }
                            SwipeOutcome.Previous -> {
                                offsetX.animateTo(screenWidthPx, tween(SWIPE_OUT_ANIMATION_MS))
                                onSwipePrevious()
                                offsetX.snapTo(0f)
                            }
                            SwipeOutcome.SnapBack -> {
                                offsetX.animateTo(0f, spring())
                            }
                        }
                    }
                },
                onDragCancel = {
                    if (!dragInProgress) return@detectHorizontalDragGestures
                    dragInProgress = false
                    coroutineScope.launch {
                        offsetX.animateTo(0f, spring())
                    }
                },
            )
        },
) {
    AlbumArtCardFill(
        artworkUrl = artworkUrl,
        modifier = Modifier
            .graphicsLayer {
                translationX = offsetX.value
                alpha = 1f - (kotlin.math.abs(offsetX.value) / screenWidthPx) * 0.3f
            },
        cornerRadius = cornerRadius,
        elevation = elevation,
        placeholderName = placeholderName,
    )
    // heart-pop overlay block (unchanged) — note: heart overlay
    // intentionally NOT offset by translationX, so the heart stays
    // anchored to the user's tap position even if the artwork drifts.
    poppedAt?.let { pos -> /* … same as Task 3 … */ }
}
```

Add imports:

```kotlin
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
```

**Step 2: Compile.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Run tests, confirm logic tests still pass.**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlbumArtGesturesLogicTest*" :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: 8/8 pure logic tests + full suite green.

**Step 4: Commit.**

```bash
git add app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGestures.kt
git commit -m "$(cat <<'EOF'
ui: horizontal swipe to skip + commit/spring-back (#now-playing-gestures)

Album art drifts 1:1 with the finger plus a slight fade so the user
sees it "leaving" (alpha 1.0 → 0.7 across full width). On release,
decideSwipeCommit (Task 1) picks next / previous / snap-back from the
offset + velocity. Commits animate out to ±screenWidth (200ms), fire
the callback, then snap back to 0 for the new track's compose pass.

16dp edge-guard at left/right preserves Android's system back-gesture.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Wire `AlbumArtWithGestures` into `NowPlayingScreen`

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/NowPlayingScreen.kt` (the `AlbumArtCardFill` callsite around line 293-308)
- Modify: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/NowPlayingViewModel.kt` — add a `StateFlow<Boolean>` that exposes whether the current track is in the user's collection
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt` if `skipPrevious()` doesn't exist as a public method (verify before assuming)

**Step 1: Add `currentTrackIsLoved` to `NowPlayingViewModel`.**

Find `NowPlayingViewModel`. Add a derived flow that collects whether the currently-playing track is in the collection. Read the existing VM for the right pattern — likely something like:

```kotlin
val currentTrackIsLoved: StateFlow<Boolean> = playbackState
    .map { it.currentTrack }
    .distinctUntilChanged()
    .flatMapLatest { track ->
        if (track == null) flowOf(false)
        else libraryRepository.isTrackInCollection(track.title, track.artist)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
```

Verify `LibraryRepository.isTrackInCollection(title, artist): Flow<Boolean>` exists at `shared/src/commonMain/kotlin/com/parachord/shared/repository/LibraryRepository.kt:142`. If the title+artist key matches what the existing `addToCollection` upserts use, this is the right join column.

Imports needed: `kotlinx.coroutines.flow.{map, distinctUntilChanged, flatMapLatest, flowOf, stateIn, SharingStarted}`.

**Step 2: Verify `playbackController.skipPrevious()` is callable.**

Run: `grep -nE "fun skipPrevious\(\)|skipPrevious\(\)" app/src/main/java/com/parachord/android/playback/PlaybackController.kt | head`

Expected: at least one `fun skipPrevious()` declaration. If the existing back button on `NowPlayingScreen` calls some other method (e.g. `skipBack()`), use that one.

**Step 3: Update the `NowPlayingScreen` callsite.**

Find the `AlbumArtCardFill(...)` call at `NowPlayingScreen.kt:293-308`. Replace with:

```kotlin
val currentTrackIsLoved by viewModel.currentTrackIsLoved.collectAsStateWithLifecycle()

AlbumArtWithGestures(
    artworkUrl = displayArtworkUrl,
    isLoved = currentTrackIsLoved,
    onSingleTap = track?.album?.takeIf { track.artist.isNotBlank() }?.let { album ->
        { onNavigateToAlbum(album, track.artist) }
    },
    onDoubleTapLove = {
        track?.let { viewModel.addToCollection(it) }
    },
    onSwipeNext = { playbackController.skipNext() },
    onSwipePrevious = { playbackController.skipPrevious() },
    placeholderName = track?.artist ?: track?.title,
    modifier = Modifier
        .weight(1f)
        .padding(horizontal = 8.dp),
    cornerRadius = 12.dp,
    elevation = 8.dp,
)
```

Verify `playbackController` is in scope at the callsite. If `NowPlayingScreen` doesn't already inject the controller (it might expose hooks via `viewModel` instead), add a `playbackController: PlaybackController = koinInject()` parameter, OR add `skipNext()` / `skipPrevious()` methods to `NowPlayingViewModel` and call those from the lambdas.

**Step 4: Compile + test.**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, full suite green.

**Step 5: Commit.**

```bash
git add app/src/main/java/com/parachord/android/ui/screens/nowplaying/NowPlayingScreen.kt \
        app/src/main/java/com/parachord/android/ui/screens/nowplaying/NowPlayingViewModel.kt
git commit -m "$(cat <<'EOF'
ui: wire AlbumArtWithGestures into NowPlayingScreen (#now-playing-gestures)

Replaces the AlbumArtCardFill callsite. Single-tap preserved
(navigate to album). Double-tap → viewModel.addToCollection (Instagram-
strict; heart pops only on a NEW love thanks to currentTrackIsLoved
state). Horizontal swipe → playbackController.skipNext/skipPrevious.

New NowPlayingViewModel.currentTrackIsLoved StateFlow joins the
currently-playing track against LibraryRepository.isTrackInCollection
(by title + artist) and emits false when nothing's playing.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Manual on-device verification

**Step 1: Build + install.**

```bash
adb shell am force-stop com.parachord.android.debug
./gradlew installDebug
adb shell monkey -p com.parachord.android.debug -c android.intent.category.LAUNCHER 1
sleep 5
```

**Step 2: Run through the gesture matrix.**

Play a track, navigate to Now Playing, then for each:

| # | Gesture | Expected |
|---|---|---|
| 1 | Double-tap on album art, track NOT loved | Heart pops at tap point (~600ms scale 0.5→1.5→1.0 + alpha fade-in-out), haptic tick, track added to collection. Verify in Library → Tracks. |
| 2 | Double-tap on album art, track already loved | No animation, no haptic, no toast. Silence. |
| 3 | Single tap on album art (when album page is known) | Navigates to album page (unchanged from before). |
| 4 | Slow drag <30% leftward, release | Artwork drifts with finger, fades slightly, springs back. No skip. |
| 5 | Slow drag ≥30% leftward, release | Artwork slides off-screen left, next track loads, new artwork composes at center. |
| 6 | Slow drag ≥30% rightward, release | Same but rightward → previous track. |
| 7 | Fast leftward flick from short drag | Velocity overrides, commits to next. |
| 8 | Fast rightward flick from leftward drag-past-threshold | Velocity wins → previous (the "throw back" case). |
| 9 | Drag starting from the leftmost ~16dp edge | Drag is ignored — system back-gesture takes over. |
| 10 | Rapid alternating gestures (tap, drag, double-tap, drag) | No stuck states, no swallowed inputs. |
| 11 | End-of-queue swipe (queue has 1 track) | Artwork slides off + rebounds (skip is a no-op). |

**Step 3: If anything's off, file a fix commit on this branch BEFORE moving to Task 7.** Most likely issues:
- Heart pop position offset wrong (tap somewhere, heart appears elsewhere) → check the offset math in Step 1 of Task 3.
- Drag arbitration with the parent scroll (Now Playing has a vertical scroll container?) → check whether the parent steals vertical drag and whether horizontal drags are intercepted before reaching the album art.
- Skip fires but artwork doesn't reset → check the `offsetX.snapTo(0f)` after `onSwipeNext` / `onSwipePrevious`.

No commit; verification only.

---

## Task 7: Open PR

**Step 1: Push branch.**

```bash
git push -u origin feature/now-playing-gestures
```

**Step 2: Open PR.**

```bash
gh pr create --title "ui: Now Playing — double-tap to love + swipe to skip" --body "$(cat <<'EOF'
Adds two touch gestures to the album-art region of the Now Playing screen.

## Summary

- **Double-tap to love** (Instagram-strict). On a new love: fires the existing \`addToCollection\` path, haptic tick, and a 96dp white heart pops at the tap point (scale 0.5 → 1.5 → 1.0, alpha 0 → 1 → 0, total ~600ms). Already-loved tracks are no-op. Removal still goes through the existing context menu.
- **Horizontal swipe to skip**. Album art follows the finger with a slight fade. On release, commits to next / previous if drag ≥30% of screen width OR fast release velocity (600 px/s). Otherwise springs back. Velocity overrides offset when they disagree (handles fast-flick-to-throw-back). 16dp edge-guard preserves Android's system back-gesture.
- **Single-tap → album page** preserved.

## Implementation

- New \`AlbumArtWithGestures\` composable (\`ui/screens/nowplaying/AlbumArtGestures.kt\`) wraps the existing \`AlbumArtCardFill\`. Exposes \`onSingleTap\` / \`onDoubleTapLove\` / \`onSwipeNext\` / \`onSwipePrevious\` callbacks + an \`isLoved\` flag for the animation gate.
- Pure \`decideSwipeCommit(offsetX, velocity, threshold)\` function isolated for testing — 8 unit tests cover threshold / velocity / direction / disagreement cases.
- Loved-state derived from \`LibraryRepository.isTrackInCollection(title, artist)\` via a new \`NowPlayingViewModel.currentTrackIsLoved\` StateFlow.

## Test plan

- [x] 8/8 \`AlbumArtGesturesLogicTest\` unit tests pass.
- [x] Full suite green: \`./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest\`.
- [x] Manual on-device gesture matrix (see Task 6 of the plan).

## Design + plan

- [docs/plans/2026-05-11-now-playing-gestures-design.md](docs/plans/2026-05-11-now-playing-gestures-design.md)
- [docs/plans/2026-05-11-now-playing-gestures.md](docs/plans/2026-05-11-now-playing-gestures.md)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Risk notes

- **Vertical scroll arbitration.** If `NowPlayingScreen` puts the album art inside a parent vertical scroll, Compose's gesture arbitration should let the horizontal drag win over vertical scroll because `detectHorizontalDragGestures` calls `awaitTouchSlopOrCancellation` with a horizontal axis preference. If users find vertical scrolling jittery at the album-art region, gate the parent scroll on `dragInProgress == false` via a wrapper modifier.
- **Compose recomposition during animation.** `offsetX.animateTo(...)` runs in the `coroutineScope` from `rememberCoroutineScope()`. If the composable is removed mid-animation (e.g. user navigates away), the scope cancels cleanly. No leak.
- **`pointerInput(Unit)` keying.** The plan uses `Unit` as the key, meaning the gesture detector survives recomposition. If the track changes mid-gesture (auto-advance fires), the gesture state is preserved — this is acceptable since the gesture target is "the album art region", not the specific track. The next track's artwork inherits an `offsetX = 0` from `snapTo(0f)` in `onSwipeNext` / `onSwipePrevious`.
- **Heart-pop animation re-fires on rapid double-taps.** `LaunchedEffect(poppedAt)` re-runs when `poppedAt` changes; the `if (poppedAt == pos)` guard prevents stale completions from nuking a fresh animation.

## Out of scope

- Vertical swipe (undefined behavior).
- Pinch-to-zoom on album art.
- Animated heart-pop when the context menu adds a love.
- Auto-scroll the queue UI on swipe (already updates via state observers).
- Accessibility / TalkBack swipe equivalents (long-press context menu remains the a11y path).
