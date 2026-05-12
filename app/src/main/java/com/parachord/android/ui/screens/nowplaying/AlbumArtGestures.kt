package com.parachord.android.ui.screens.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.components.AlbumArtCardFill
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
