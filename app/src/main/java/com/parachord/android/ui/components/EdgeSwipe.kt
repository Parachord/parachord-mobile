package com.parachord.android.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import kotlin.math.abs

/** How wide each edge's swipe zone is. */
val EDGE_SWIPE_ZONE: Dp = 24.dp

/**
 * How tall the band on each edge is that the app claims from Android's
 * gesture navigation. Android honours at most 200dp per edge, so Back
 * still works from each edge above and below the band.
 */
val EDGE_SWIPE_BAND: Dp = 200.dp

/** How far a swipe must travel before it opens the drawer or the chat. */
val EDGE_SWIPE_DISTANCE: Dp = 60.dp

/** Which edge a touch at [x] (in a container [width] wide) starts on, if any. */
enum class SwipeEdge { LEFT, RIGHT }

fun swipeEdgeAt(x: Float, y: Float, width: Float, height: Float, zonePx: Float, bandPx: Float): SwipeEdge? {
    val bandTop = (height - bandPx) / 2f
    if (y < bandTop || y > bandTop + bandPx) return null
    return when {
        x <= zonePx -> SwipeEdge.LEFT
        x >= width - zonePx -> SwipeEdge.RIGHT
        else -> null
    }
}

/** The two bands, in the container's coordinates, that gesture navigation leaves to the app. */
fun edgeSwipeBands(width: Float, height: Float, zonePx: Float, bandPx: Float): List<Rect> {
    val top = (height - bandPx) / 2f
    return listOf(
        Rect(0f, top, zonePx, top + bandPx),
        Rect(width - zonePx, top, width, top + bandPx),
    )
}

/**
 * Opens the drawer from a swipe right that starts in the left edge band, and
 * the chat from a swipe left that starts in the right edge band. Nothing
 * else opens either: a carousel pulled past its end doesn't.
 *
 * The bands are vertically centred and [EDGE_SWIPE_BAND] tall, and are
 * excluded from gesture navigation, so Back still works from each edge
 * above and below them. Touches are watched before the content sees them
 * and never consumed until the swipe is clearly horizontal and has started
 * in a band, so taps, long-presses and vertical scrolls near the edge reach
 * the content as before.
 */
fun Modifier.edgeSwipes(onLeft: () -> Unit, onRight: () -> Unit): Modifier = composed {
    val density = LocalDensity.current
    val zonePx = with(density) { EDGE_SWIPE_ZONE.toPx() }
    val bandPx = with(density) { EDGE_SWIPE_BAND.toPx() }
    val distancePx = with(density) { EDGE_SWIPE_DISTANCE.toPx() }
    this
        .systemGestureExclusion { coords ->
            // One rect per call; the band on the left edge.
            val s = coords.size
            edgeSwipeBands(s.width.toFloat(), s.height.toFloat(), zonePx, bandPx)[0]
        }
        .systemGestureExclusion { coords ->
            val s = coords.size
            edgeSwipeBands(s.width.toFloat(), s.height.toFloat(), zonePx, bandPx)[1]
        }
        .pointerInput(onLeft, onRight) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val edge = swipeEdgeAt(
                    down.position.x, down.position.y,
                    size.width.toFloat(), size.height.toFloat(), zonePx, bandPx,
                ) ?: return@awaitEachGesture
                var dx = 0f
                var dy = 0f
                var claimed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.positionChange()
                    dx += delta.x
                    dy += delta.y
                    if (!claimed) {
                        val inward = if (edge == SwipeEdge.LEFT) dx else -dx
                        // A vertical move first: leave it to the content's scroll.
                        if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break
                        if (inward > viewConfiguration.touchSlop && abs(dx) > abs(dy)) claimed = true
                    }
                    if (claimed) {
                        change.consume()
                        val inward = if (edge == SwipeEdge.LEFT) dx else -dx
                        if (inward >= distancePx) {
                            if (edge == SwipeEdge.LEFT) onLeft() else onRight()
                            // Swallow the rest of this gesture.
                            while (true) {
                                val rest = awaitPointerEvent(PointerEventPass.Initial)
                                rest.changes.forEach { it.consume() }
                                if (rest.changes.none { it.pressed }) break
                            }
                            break
                        }
                    }
                }
            }
        }
}
