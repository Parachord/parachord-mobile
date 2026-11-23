package com.parachord.android.ui.components

import com.parachord.shared.ui.icons.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.android.ui.theme.current
import com.parachord.shared.ui.BadgeSpec
import com.parachord.shared.ui.BadgeTone
import com.parachord.shared.ui.DesignTokens

/**
 * The status pill (desktop `badge.js`, shared [BadgeSpec]): a [tone]'s
 * colours, or, with [color], the service's colour as a tint and a dot with
 * primary text. [description] is read out in place of the label (desktop's
 * tooltip); [icon] goes before the label (not with a service colour,
 * which has its dot); [onClick] makes it a link.
 */
@Composable
fun ParachordBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.NEUTRAL,
    color: Color? = null,
    description: String? = null,
    icon: AppIcon? = null,
    onClick: (() -> Unit)? = null,
) {
    val textPrimary = DesignTokens.textPrimary.current()
    val (fill, ink) = when {
        color != null -> color.copy(alpha = BadgeSpec.SERVICE_TINT_ALPHA) to textPrimary
        tone == BadgeTone.INFO -> DesignTokens.textInfo.current().let { it.copy(alpha = BadgeSpec.INFO_TINT_ALPHA) to it }
        tone == BadgeTone.ACCENT -> ParachordTheme.colors.accentSurface to DesignTokens.accent.current()
        tone == BadgeTone.SURFACE -> ParachordTheme.colors.cardBackground to textPrimary
        else -> DesignTokens.bgSecondary.current() to DesignTokens.textSecondary.current()
    }
    var m = modifier
    if (tone == BadgeTone.SURFACE && color == null) m = m.shadow(2.dp, CircleShape)
    m = m.clip(CircleShape).background(fill)
    if (onClick != null) m = m.clickable(onClick = onClick)
    if (description != null) m = m.semantics(mergeDescendants = true) { contentDescription = description }
    Row(
        modifier = m.padding(horizontal = BadgeSpec.PADDING_H.dp, vertical = BadgeSpec.PADDING_V.dp),
        horizontalArrangement = Arrangement.spacedBy(BadgeSpec.GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) {
            Box(Modifier.size(BadgeSpec.DOT_SIZE.dp).clip(CircleShape).background(color))
        } else if (icon != null) {
            ParachordIcon(icon, contentDescription = null, size = BadgeSpec.ICON_SIZE.dp, tint = ink)
        }
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            color = ink,
            style = TextStyle(
                fontSize = BadgeSpec.TEXT_SIZE.sp,
                fontWeight = FontWeight(BadgeSpec.TEXT_WEIGHT),
                lineHeight = BadgeSpec.LINE_HEIGHT.sp,
            ),
        )
    }
}
