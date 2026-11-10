package com.parachord.shared.playback

import kotlin.math.abs
import kotlin.math.sign

/**
 * The Now Playing album-art gestures, shared by Android
 * (`AlbumArtWithGestures`) and iOS (`PCNowPlaying`): double-tap loves the
 * track, a horizontal swipe skips. See
 * `docs/plans/2026-05-11-now-playing-gestures-design.md`.
 *
 * This file holds the release decision; each platform draws the drag and the
 * heart pop.
 */
enum class SwipeOutcome {
    /** Commit to the next track (finger moved left, offsetX < 0). */
    Next,
    /** Commit to the previous track (finger moved right, offsetX > 0). */
    Previous,
    /** Drag didn't cross a threshold: spring back to centered. */
    SnapBack,
}

/** A drag that crosses this fraction of the screen width commits. */
const val SWIPE_COMMIT_FRACTION = 0.30f

/** A release faster than this commits however short the drag (Android pixels per second). */
const val SWIPE_VELOCITY_THRESHOLD_PX_PER_S = 600f

/**
 * The same speed in points (iOS) or dp: 600 px/s on a ~2.6x phone screen,
 * the density the Android value was tuned on.
 */
const val SWIPE_VELOCITY_THRESHOLD_PT_PER_S = 230f

/** Drags starting this close to the art's left or right edge are ignored (the system back swipe). */
const val SWIPE_EDGE_GUARD_PT = 16f

/**
 * Decide whether a horizontal drag should commit to next / previous /
 * snap-back.
 *
 *  - If neither the drag distance NOR the release velocity crosses its
 *    threshold, snap back.
 *  - Otherwise velocity decides the direction when it crossed (a fast flick
 *    back wins over a long drag), else the offset's sign does. Leftward
 *    (negative) → Next; rightward → Previous.
 */
fun decideSwipeCommit(
    offsetX: Float,
    velocity: Float,
    commitThreshold: Float,
    velocityThreshold: Float = SWIPE_VELOCITY_THRESHOLD_PX_PER_S,
): SwipeOutcome {
    val offsetCrossed = abs(offsetX) >= commitThreshold
    val velocityCrossed = abs(velocity) >= velocityThreshold
    if (!offsetCrossed && !velocityCrossed) return SwipeOutcome.SnapBack
    val sign = if (velocityCrossed) (-velocity).sign else (-offsetX).sign
    return if (sign > 0) SwipeOutcome.Next else SwipeOutcome.Previous
}
