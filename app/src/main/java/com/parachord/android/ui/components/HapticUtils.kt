package com.parachord.android.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * [clickable] wrapper that performs a light haptic tick on every tap.
 */
fun Modifier.hapticClickable(
    enabled: Boolean = true,
    /** What a tap does, for TalkBack ("double tap to <label>"); the element keeps its own text. */
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    clickable(enabled = enabled, onClickLabel = onClickLabel) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/**
 * [combinedClickable] wrapper that performs haptic feedback on tap and long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.hapticCombinedClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    combinedClickable(
        enabled = enabled,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        onLongClick = onLongClick?.let { handler ->
            {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                handler()
            }
        },
        onDoubleClick = onDoubleClick,
    )
}

/**
 * Provides a haptic click performer for use inside onClick lambdas
 * (e.g. IconButton, Button) where a Modifier extension isn't applicable.
 */
@Composable
fun rememberHapticClick(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
}

/**
 * Provides haptic callbacks for drag-to-reorder gestures.
 * [onDragStart] fires a long-press haptic, [onDragMove] fires a lighter tick
 * when the dragged item crosses an index boundary.
 */
@Composable
fun rememberDragHaptics(): DragHaptics {
    val view = LocalView.current
    return DragHaptics(
        onDragStart = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
        onDragMove = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
    )
}

data class DragHaptics(
    val onDragStart: () -> Unit,
    val onDragMove: () -> Unit,
)
