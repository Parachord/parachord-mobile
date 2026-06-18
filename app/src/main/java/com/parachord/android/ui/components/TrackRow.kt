package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Custom track list item replacing generic Material ListItem.
 *
 * Shows album art (48dp), title/artist, optional track number,
 * resolver icon squares (colored squares with white logos matching desktop),
 * and optional duration.
 *
 * Supports long-press via [onLongClick] for context menu actions.
 */
@Composable
fun TrackRow(
    title: String,
    artist: String,
    artworkUrl: String? = null,
    resolver: String? = null,
    resolvers: List<String>? = null,
    resolverConfidences: Map<String, Float>? = null,
    duration: Long? = null,
    trackNumber: Int? = null,
    isPlaying: Boolean = false,
    resolving: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Combine single resolver with list of resolvers, deduplicating
    val allResolvers = buildList {
        resolvers?.let { addAll(it) }
        if (resolver != null && !contains(resolver)) add(resolver)
    }

    val artSize = 44.dp
    val vertPad = 6.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .hapticCombinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = vertPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Optional track number (for album tracklists)
        if (trackNumber != null) {
            Text(
                text = "$trackNumber",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        }

        // Album art — shimmer skeleton while resolving with no art yet (e.g. just
        // after a metadata edit), otherwise the cover / gradient placeholder.
        if (artworkUrl == null && resolving) {
            Box(
                modifier = Modifier
                    .size(artSize)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush()),
            )
        } else {
            AlbumArtCard(
                artworkUrl = artworkUrl,
                size = artSize,
                cornerRadius = 4.dp,
                elevation = 1.dp,
                placeholderName = artist.ifBlank { title },
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Duration
        if (duration != null && duration > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatTrackDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Resolver icon squares (colored squares with white logos)
        if (allResolvers.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            ResolverIconRow(
                resolvers = allResolvers,
                size = 20.dp,
                confidences = resolverConfidences,
            )
        }
    }
}

private fun formatTrackDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
