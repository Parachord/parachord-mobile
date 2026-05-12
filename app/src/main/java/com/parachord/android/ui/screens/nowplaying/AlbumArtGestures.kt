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
