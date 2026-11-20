package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.ui.ArtPlayButtonSpec
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.icons.AppIcon

private val Disc = tokenColor(DesignTokens.ON_ACCENT)
private val Ink = tokenColor(DesignTokens.ON_ART_INK)

/** The play button's touch target sits this far in from the cover's corner, so its 40 dp disc is 8 dp in. */
val ArtPlayInset = 4.dp

/**
 * The play button over art, desktop's (`card-art.js`; spec in the shared
 * [ArtPlayButtonSpec]): a white disc of [size] with a large shadow and the
 * triangle in on-art ink, in a touch target of at least 48. Every play button
 * drawn on a cover or tile uses it; a standalone Play control (Now Playing, a
 * page's Play button) stays the fill-accent one.
 */
@Composable
fun ArtPlayButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ArtPlayButtonSpec.SIZE.dp,
) {
    val target = maxOf(size, ArtPlayButtonSpec.MIN_TOUCH.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(target)
            .semantics {
                role = Role.Button
                contentDescription = ArtPlayButtonSpec.label(title)
            }
            .hapticClickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .shadow(ArtPlayButtonSpec.ELEVATION.dp, CircleShape)
                .clip(CircleShape)
                .background(Disc),
        ) {
            ParachordIcon(
                AppIcon.Play,
                contentDescription = null,
                size = size * ArtPlayButtonSpec.ICON_FRACTION,
                tint = Ink,
                modifier = Modifier.offset(x = size * ArtPlayButtonSpec.ICON_NUDGE),
            )
        }
    }
}
