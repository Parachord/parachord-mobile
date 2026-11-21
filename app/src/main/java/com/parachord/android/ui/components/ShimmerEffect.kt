package com.parachord.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Shimmer loading effect matching the desktop app's skeleton loaders.
 * A gradient slides across the placeholder (1500ms duration).
 */
@Composable
fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant,
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f),
    )
}

/**
 * Shimmer placeholder for a track row (album art + two text lines).
 */
@Composable
fun ShimmerTrackRow(modifier: Modifier = Modifier, showDuration: Boolean = true) {
    // TrackRow's placeholder, in its columns (cover, title over artist, the
    // source icons, the duration), so nothing moves when the rows land (#760).
    val brush = shimmerBrush()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(com.parachord.shared.ui.TrackRowRules.COVER.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        TrackRowSources(resolvers = emptyList(), confidences = null, resolving = true)
        if (showDuration) {
            Box(
                modifier = Modifier.width(com.parachord.shared.ui.TrackRowRules.DURATION_COLUMN.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush),
                )
            }
        }
    }
}

/**
 * Shimmer placeholder for an album card (square image + text below).
 * Fixed 120dp width — used in horizontal carousels.
 */
@Composable
fun ShimmerAlbumCard(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()

    Column(modifier = modifier.width(120.dp)) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(brush),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush),
        )
    }
}

/**
 * Shimmer placeholder for a discography album card (fill-width variant).
 * Matches the 2-column grid layout in the artist discography tab:
 * square artwork + title line + type badge/year row, inside a rounded card.
 */
@Composable
fun ShimmerDiscographyCard(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        // Square artwork placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(brush),
        )
        Spacer(modifier = Modifier.height(10.dp))
        // Title
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(13.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush),
        )
        Spacer(modifier = Modifier.height(6.dp))
        // Type badge + year
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
        }
    }
}
