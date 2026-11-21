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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import com.parachord.android.resolver.trackKey
import com.parachord.android.ui.theme.LocalSourceColumnWidth
import com.parachord.android.ui.theme.LocalTrackRowCache
import com.parachord.shared.ui.TrackRowRules

/**
 * The one track row every list draws (#760), desktop's `TrackRow`
 * (`components/track-lists.js`) in its columns:
 *
 * number · cover · title over artist · source icons · duration
 *
 * - **The duration is the last column**, right-aligned and the same width in
 *   every row, so durations line up down the list. Its length is the shared
 *   `TrackRowRules.pickDurationMs`: the track's own, else the length a
 *   resolved source knows ([sourceDurationMs], else the resolver cache's
 *   `trackSourceDurations` through [LocalTrackRowCache]),
 *   else nothing (never "0:00").
 * - **The source icons sit right up against it**, right-aligned in a column
 *   one icon per service the user can play from ([LocalSourceColumnWidth]),
 *   so rows with fewer sources still line up. While a row resolves the
 *   column shimmers; each icon plays that source ([onPlaySource]).
 * - **A row nothing can play is dimmed** ([unplayable], else the resolver
 *   cache: it resolved and has no badge; `TrackRowRules.UNPLAYABLE_ALPHA`).
 * - The now-playing row ([isPlaying]) has its title in the accent.
 *
 * Phones keep the cover at the left, which desktop's rows don't have, and put
 * the artist under the title. Supports long-press via [onLongClick].
 */
@Composable
fun TrackRow(
    title: String,
    artist: String,
    artworkUrl: String? = null,
    /** The track's album, when known — lets a dead cover be looked up again. */
    album: String? = null,
    resolver: String? = null,
    resolvers: List<String>? = null,
    resolverConfidences: Map<String, Float>? = null,
    /** The track's own length, in ms. */
    duration: Long? = null,
    /** The length a resolved source knows, in ms (`TrackResolverCache.trackSourceDurations`). */
    sourceDurationMs: Long? = null,
    trackNumber: Int? = null,
    isPlaying: Boolean = false,
    resolving: Boolean = false,
    /**
     * The resolve came back and nothing can play the track: the row is dimmed.
     * Null reads it from the resolver cache ([LocalTrackRowCache]).
     */
    unplayable: Boolean? = null,
    /** Whether the list shows the duration column (every row of a list must agree). */
    showDuration: Boolean = true,
    /** The line under the title, when it isn't just the artist ("Artist  •  Spotify"). */
    subtitle: String? = null,
    /**
     * One extra column before the icons (desktop's `cells`): History's "2h ago",
     * a play count. [cellAccent] draws it in the accent ("Now").
     */
    cell: String? = null,
    cellAccent: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    /** Plays the track from one source, when its icon is tapped. */
    onPlaySource: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Combine single resolver with list of resolvers, deduplicating
    val allResolvers = buildList {
        resolvers?.let { addAll(it) }
        if (resolver != null && !contains(resolver)) add(resolver)
    }
    val rowCache = LocalTrackRowCache.current
    val key = trackKey(title, artist)
    val cachedBadges = rowCache?.resolvers?.value?.get(key)
    val dimmed = !resolving && allResolvers.isEmpty() &&
        (unplayable ?: (cachedBadges != null && cachedBadges.isEmpty()))
    val sourceLength = sourceDurationMs ?: rowCache?.sourceDurations?.value?.get(key)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (dimmed) TrackRowRules.UNPLAYABLE_ALPHA else 1f)
            .hapticCombinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Optional track number (for album tracklists)
        if (trackNumber != null) {
            Text(
                text = "$trackNumber",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(TrackRowRules.NUMBER_COLUMN.dp),
            )
        }

        // Album art — shimmer skeleton while resolving with no art yet (e.g. just
        // after a metadata edit), otherwise the cover / gradient placeholder.
        if (artworkUrl == null && resolving) {
            Box(
                modifier = Modifier
                    .size(TrackRowRules.COVER.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush()),
            )
        } else {
            AlbumArtCard(
                artworkUrl = artworkUrl,
                artist = artist,
                album = album,
                size = TrackRowRules.COVER.dp,
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
                text = subtitle ?: artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (cell != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = cell,
                style = MaterialTheme.typography.labelSmall,
                color = if (cellAccent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))
        TrackRowSources(
            resolvers = allResolvers,
            confidences = resolverConfidences,
            resolving = resolving,
            onPlaySource = onPlaySource,
        )

        if (showDuration) {
            TrackRowDuration(TrackRowRules.formatDuration(TrackRowRules.pickDurationMs(duration, sourceLength)))
        }
    }
}

/**
 * A track row's source icon column: [LocalSourceColumnWidth] wide, the icons
 * at its right edge (right up against the duration), two shimmer squares while
 * the row resolves with nothing yet, nothing when nothing can play.
 */
@Composable
fun TrackRowSources(
    resolvers: List<String>,
    confidences: Map<String, Float>?,
    resolving: Boolean,
    onPlaySource: ((String) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .width(LocalSourceColumnWidth.current)
            .clipToBounds(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (resolvers.isNotEmpty()) {
            ResolverIconRow(
                resolvers = resolvers,
                size = TrackRowRules.SOURCE_ICON.dp,
                confidences = confidences,
                onIconClick = onPlaySource,
            )
        } else if (resolving) {
            repeat(2) { i ->
                if (i > 0) Spacer(modifier = Modifier.width(TrackRowRules.SOURCE_ICON_GAP.dp))
                Box(
                    modifier = Modifier
                        .size(TrackRowRules.SOURCE_ICON.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush()),
                )
            }
        }
    }
}

/** A track row's duration column: right-aligned, the same width in every row. */
@Composable
fun TrackRowDuration(label: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(TrackRowRules.DURATION_COLUMN.dp),
    )
}

/**
 * What a track row's source icon does (#760): [TrackRow.onPlaySource] for a
 * row whose tap runs [play]. The tap picks that source for the row's track
 * (`PlaybackController.pickSourceForNextPlay`), then runs the row's usual play,
 * so the queue and its context are the same as a tap on the row.
 */
@Composable
fun rememberPlayFromSource(): (title: String, artist: String, play: () -> Unit) -> (String) -> Unit {
    val controller = org.koin.compose.koinInject<com.parachord.android.playback.PlaybackController>()
    return androidx.compose.runtime.remember(controller) {
        { title, artist, play ->
            { resolver ->
                controller.pickSourceForNextPlay(title, artist, resolver)
                play()
            }
        }
    }
}
