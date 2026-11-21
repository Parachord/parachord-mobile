package com.parachord.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.parachord.android.ui.theme.serviceColorOrNull
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.R
import com.parachord.android.ui.theme.LocalResolverOrder

/**
 * Small colored square with white service logo icon.
 * Matches the desktop's 20x20 colored squares with 4px border radius.
 *
 * @param showBackground If true (default), shows the colored square background.
 *   If false, renders just the white icon (useful for overlaying on a colored tile).
 * @param confidence Match confidence (0.0–1.0). Matches with confidence ≤ 0.8 are
 *   dimmed to 60% opacity, matching the desktop's behavior.
 */
@Composable
fun ResolverIconSquare(
    resolver: String,
    size: Dp = 20.dp,
    showBackground: Boolean = true,
    confidence: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val bgColor = serviceColorOrNull(resolver)
    val iconAlpha = if (com.parachord.shared.resolver.ResolverBadges.isDimmed(confidence.toDouble())) 0.6f else 1f

    // Subsonic's logo is a bitmap, like SoundCloud's: the yellow submarine
    // from subsonic.org, full colour on the resolver-coloured (teal) badge —
    // a port of desktop's SERVICE_LOGO_IMAGES entry (ui-atoms.js), which has
    // no monochrome vector of it either.
    if (resolver.lowercase() == "subsonic") {
        if (showBackground && bgColor != null) {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(iconAlpha)
                    .background(bgColor),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.subsonic_icon),
                    contentDescription = "Subsonic",
                    modifier = Modifier.size(size * 0.65f),
                )
            }
        } else {
            Image(
                painter = painterResource(R.drawable.subsonic_logo),
                contentDescription = "Subsonic",
                modifier = modifier.size(size).alpha(iconAlpha),
            )
        }
        return
    }

    // SoundCloud uses a PNG drawable instead of an SVG path
    if (resolver.lowercase() == "soundcloud") {
        if (showBackground && bgColor != null) {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(iconAlpha)
                    .background(bgColor),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.soundcloud_icon_white),
                    contentDescription = "SoundCloud",
                    modifier = Modifier.size(size * 0.65f),
                )
            }
        } else {
            Image(
                painter = painterResource(R.drawable.soundcloud_icon_white),
                contentDescription = "SoundCloud",
                modifier = modifier.size(size).alpha(iconAlpha),
            )
        }
        return
    }

    // Achordion's logo carries its own dark circle and colours (shared
    // ServiceLogos), so it's drawn whole, with no square behind it.
    if (resolver.lowercase() == "achordion") {
        val logo = remember { serviceLogoVector("achordion") } ?: return
        Image(
            painter = rememberVectorPainter(logo),
            contentDescription = "Achordion",
            modifier = modifier.size(size).alpha(iconAlpha),
        )
        return
    }

    // Desktop's logo for the service (shared ServiceLogos, generated from
    // brand-logos.js), white on its colour square.
    val iconVector = remember(resolver) { serviceLogoVector(resolver.lowercase()) } ?: return

    if (showBackground) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
                .alpha(iconAlpha)
                .background(bgColor ?: return),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = rememberVectorPainter(iconVector),
                contentDescription = resolver,
                modifier = Modifier.size(size * 0.65f),
            )
        }
    } else {
        // No background — just the white logo
        Image(
            painter = rememberVectorPainter(iconVector),
            contentDescription = resolver,
            modifier = modifier.size(size).alpha(iconAlpha),
        )
    }
}

/**
 * Row of resolver icon squares for a track.
 * Shows all available resolvers as small colored squares.
 *
 * @param confidences Optional map of resolver → confidence (0.0–1.0).
 *   Resolvers with confidence ≤ 0.8 are dimmed to match the desktop.
 */
@Composable
fun ResolverIconRow(
    resolvers: List<String>,
    size: Dp = 20.dp,
    confidences: Map<String, Float>? = null,
    modifier: Modifier = Modifier,
    /** Plays that source (desktop's `SourceIconButton`); null draws plain icons. */
    onIconClick: ((String) -> Unit)? = null,
) {
    if (resolvers.isEmpty()) return
    val resolverOrder = LocalResolverOrder.current
    // Sort by priority first, then confidence descending (matching desktop).
    // This ensures the highest-scoring resolver (the one that will actually play)
    // appears first when priority is the same.
    val sorted = if (resolverOrder.isNotEmpty()) {
        resolvers.sortedWith(compareBy<String> { r ->
            resolverOrder.indexOf(r).let { if (it == -1) resolverOrder.size else it }
        }.thenByDescending { r -> confidences?.get(r) ?: 1f })
    } else {
        resolvers.sortedByDescending { r -> confidences?.get(r) ?: 1f }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sorted.forEachIndexed { index, resolver ->
            if (index > 0) Spacer(modifier = Modifier.width(com.parachord.shared.ui.TrackRowRules.SOURCE_ICON_GAP.dp))
            ResolverIconSquare(
                resolver = resolver,
                size = size,
                confidence = confidences?.get(resolver) ?: 1f,
                modifier = if (onIconClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClickLabel = "Play from ${resolverDisplayName(resolver)}") { onIconClick(resolver) }
                } else Modifier,
            )
        }
    }
}

/** Human-readable display names for resolvers. */
fun resolverDisplayName(resolver: String): String =
    com.parachord.shared.playback.resolverDisplayName(resolver)

/**
 * Resolver source dropdown for the Now Playing screen.
 * Shows the currently active resolver icon + chevron. Tapping opens a dropdown
 * listing all available sources, matching the desktop's "Play from" menu.
 *
 * Only shows the chevron affordance when multiple sources are available.
 * Offers only sources that can play, one per resolver, in the user's order
 * (`SourceReselection.switchableSources`, shared with iOS).
 *
 * @param currentResolver The resolver currently playing this track
 * @param availableSources All resolved sources for this track (from TrackResolverCache)
 * @param onSwitchSource Called with the resolver ID when the user picks a different source
 */
@Composable
fun ResolverSourceDropdown(
    currentResolver: String,
    availableSources: List<com.parachord.android.resolver.ResolvedSource>,
    onSwitchSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolverOrder = LocalResolverOrder.current
    val notPlaying = com.parachord.android.ui.theme.LocalNotPlaying.current
    // Sources that can play, one per resolver, in the user's order (shared,
    // #587), leaving out the plug-ins that don't play (#707).
    val offered = remember(availableSources, resolverOrder, notPlaying) {
        com.parachord.shared.resolver.SourceReselection.switchableSources(availableSources, resolverOrder, notPlaying)
    }
    val hasMultipleSources = offered.size > 1

    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )

    Box(modifier = modifier) {
        // Trigger button: resolver icon + optional chevron
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (hasMultipleSources) {
                        Modifier
                            .clickable { expanded = !expanded }
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    } else {
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    }
                ),
        ) {
            ResolverIconSquare(resolver = currentResolver, size = 24.dp)
            if (hasMultipleSources) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Switch source",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                )
            }
        }

        // Dropdown menu — dark glassmorphic style matching desktop
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(Color(0xFA1E1E23))
                .widthIn(min = 160.dp),
        ) {
            // "Play from" header
            Text(
                text = "PLAY FROM",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )

            offered.forEach { source ->
                val isActive = source.resolver == currentResolver
                val iconColor = serviceColorOrNull(source.resolver)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable {
                            if (!isActive) {
                                onSwitchSource(source.resolver)
                            }
                            expanded = false
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .widthIn(min = 140.dp),
                ) {
                    ResolverIconSquare(
                        resolver = source.resolver,
                        size = 16.dp,
                        confidence = source.confidence?.toFloat() ?: 1f,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = resolverDisplayName(source.resolver),
                        color = iconColor ?: Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Active",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}
