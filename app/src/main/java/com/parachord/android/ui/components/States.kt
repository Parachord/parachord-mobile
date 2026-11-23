package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.theme.FillAccent
import com.parachord.android.ui.theme.TextDanger
import com.parachord.android.ui.theme.current
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.StateSpec
import com.parachord.shared.ui.UiMessages
import com.parachord.shared.ui.icons.AppIcon

/** An action under a state: the first is filled, the rest outlined. */
data class StateAction(val label: String, val onClick: () -> Unit)

/**
 * Nothing to show yet (desktop `states.js` `EmptyState`, shared [StateSpec]):
 * an icon in a soft circle (Music unless the page names one; null hides it),
 * [title], an optional [body] saying why or what to do, and [actions].
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: AppIcon? = AppIcon.Music,
    actions: List<StateAction> = emptyList(),
    compact: Boolean = false,
) {
    StateBlock(
        icon = icon,
        iconTint = DesignTokens.textTertiary.current(),
        title = title,
        body = body,
        actions = actions,
        compact = compact,
        modifier = modifier,
    )
}

/**
 * A load that failed (desktop's `ErrorState`): the warning icon in the danger
 * colour, [title] (the repository's own words where it has them, else "Something
 * went wrong"), an optional [body] with the reason, then [actions] and "Try
 * again" ([onRetry]), filled unless other actions come first. Announced as it
 * appears.
 */
@Composable
fun ErrorState(
    modifier: Modifier = Modifier,
    title: String = StateSpec.ERROR_TITLE,
    body: String? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = UiMessages.TRY_AGAIN,
    actions: List<StateAction> = emptyList(),
    compact: Boolean = false,
) {
    StateBlock(
        icon = AppIcon.AlertTriangle,
        iconTint = TextDanger,
        title = title,
        body = body,
        actions = actions + listOfNotNull(onRetry?.let { StateAction(retryLabel, it) }),
        compact = compact,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun StateBlock(
    icon: AppIcon?,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    body: String?,
    actions: List<StateAction>,
    compact: Boolean,
    modifier: Modifier,
) {
    val padV = if (compact) StateSpec.COMPACT_PADDING_V else StateSpec.PAGE_PADDING_V
    val padH = if (compact) StateSpec.COMPACT_PADDING_H else StateSpec.PAGE_PADDING_H
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = padV.dp, horizontal = padH.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StateSpec.GAP.dp),
    ) {
        if (icon != null) {
            val circle = if (compact) StateSpec.CIRCLE_COMPACT else StateSpec.CIRCLE
            Box(
                modifier = Modifier.size(circle.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ParachordIcon(
                    icon = icon,
                    contentDescription = null,
                    size = (if (compact) StateSpec.ICON_COMPACT else StateSpec.ICON).dp,
                    tint = iconTint,
                )
            }
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontSize = (if (compact) StateSpec.TITLE_SIZE_COMPACT else StateSpec.TITLE_SIZE).sp,
                fontWeight = FontWeight(StateSpec.TITLE_WEIGHT),
            ),
        )
        if (body != null) {
            Text(
                text = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = StateSpec.BODY_SIZE.sp,
                    lineHeight = (StateSpec.BODY_SIZE * StateSpec.BODY_LINE_HEIGHT).sp,
                ),
                modifier = Modifier.widthIn(max = StateSpec.BODY_MAX_WIDTH.dp),
            )
        }
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = StateSpec.ACTIONS_TOP.dp),
                horizontalArrangement = Arrangement.spacedBy(StateSpec.ACTIONS_GAP.dp),
            ) {
                actions.forEachIndexed { i, a ->
                    if (i == 0) {
                        Button(
                            onClick = a.onClick,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FillAccent, contentColor = androidx.compose.ui.graphics.Color.White),
                        ) { Text(a.label) }
                    } else {
                        OutlinedButton(onClick = a.onClick, shape = RoundedCornerShape(8.dp)) { Text(a.label) }
                    }
                }
            }
        }
    }
}
