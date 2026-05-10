package com.parachord.android.ui.screens.nowplaying

import androidx.compose.foundation.background
import com.parachord.android.ui.components.hapticClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ResolverIconRow
import com.parachord.android.ui.theme.PlayerSurface
import com.parachord.android.ui.theme.PlayerTextPrimary
import com.parachord.android.ui.theme.PlayerTextSecondary
import com.parachord.android.ui.theme.PurpleDark

/**
 * Queue sheet content displayed below the now-playing controls.
 * Shows upcoming tracks with drag-to-reorder and swipe-to-dismiss.
 */
@Composable
fun QueueSheet(
    upNext: List<TrackEntity>,
    playbackContext: PlaybackContext?,
    onPlayFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
    /** User-configured resolver priority order for sorting resolver icons. */
    resolverOrder: List<String> = emptyList(),
    /** Resolver names from the full resolver pipeline, keyed by "title|artist". */
    trackResolvers: Map<String, List<String>> = emptyMap(),
    /** Resolver confidence scores keyed by "title|artist", then by resolver name. */
    trackResolverConfidences: Map<String, Map<String, Float>> = emptyMap(),
    /** True when queue is "paused" (spinoff or listen-along) — dims tracks to show they'll resume later. */
    queueSuspended: Boolean = false,
    /** Navigate to the source context (album, playlist, artist page). */
    onNavigateToContext: ((PlaybackContext) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PlayerSurface),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (queueSuspended) "YOUR QUEUE" else "UP NEXT",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (queueSuspended) PlayerTextSecondary else PlayerTextPrimary,
                    )
                    if (upNext.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${upNext.size} track${if (upNext.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PlayerTextSecondary,
                        )
                    }
                }
                if (playbackContext != null) {
                    val contextLabel = when (playbackContext.type) {
                        "listen-along" -> "Listening along with ${playbackContext.name}"
                        // Spinoff covers two flavors: in-app right-click → Spinoff
                        // (name = "Spinoff from {title}") and Mode B/C protocol radio
                        // (id = "pool-based", name = station name e.g. "Radio: Slowdive").
                        // Desktop branches its "Spun off from X by Y" template to
                        // station-name-only when sourceTrack.artist is blank — Android
                        // never had that template, so rendering name directly is the
                        // correct convergence point for both flavors.
                        "spinoff" -> playbackContext.name
                        else -> "Playing from: ${playbackContext.name}"
                    }
                    val contextColor = when (playbackContext.type) {
                        "listen-along" -> Color(0xFF34D399) // Green, matching desktop
                        "spinoff" -> Color(0xFFC084FC) // Purple, matching spinoff button
                        else -> PurpleDark
                    }
                    // Navigable context types link to their source page
                    val isNavigable = onNavigateToContext != null &&
                        playbackContext.type in listOf("album", "playlist", "artist")
                    Text(
                        text = contextLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = contextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (isNavigable) {
                            Modifier.hapticClickable { onNavigateToContext?.invoke(playbackContext) }
                        } else Modifier,
                    )
                }
            }
            if (upNext.isNotEmpty()) {
                TextButton(onClick = onClearQueue) {
                    Text(
                        text = "Clear",
                        color = PlayerTextSecondary,
                    )
                }
            }
        }

        if (upNext.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Queue is empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlayerTextSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    items = upNext,
                    key = { index, track -> "${track.id}-$index" },
                ) { index, track ->
                    QueueTrackRow(
                        track = track,
                        index = index,
                        suspended = queueSuspended,
                        resolverOrder = resolverOrder,
                        trackResolvers = trackResolvers,
                        trackResolverConfidences = trackResolverConfidences,
                        onTap = { onPlayFromQueue(index) },
                        onRemove = { onRemoveFromQueue(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(
    track: TrackEntity,
    index: Int,
    suspended: Boolean,
    resolverOrder: List<String> = emptyList(),
    trackResolvers: Map<String, List<String>> = emptyMap(),
    trackResolverConfidences: Map<String, Map<String, Float>> = emptyMap(),
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    // When queue is suspended (spinoff/listen-along), dim tracks to show they'll resume later
    val dimAlpha = if (suspended) 0.5f else 1f

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            onRemove()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = 0.3f))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = Color.White,
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PlayerSurface)
                .hapticClickable(onClick = onTap)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Track number or ·· when suspended (desktop behavior)
            Text(
                text = if (suspended) "··" else String.format("%02d", index + 1),
                style = MaterialTheme.typography.bodySmall,
                color = PlayerTextSecondary.copy(alpha = if (suspended) 0.5f else 0.7f),
                modifier = Modifier.width(24.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Album art
            AlbumArtCard(
                artworkUrl = track.artworkUrl,
                modifier = Modifier.graphicsLayer { alpha = dimAlpha },
                size = 40.dp,
                cornerRadius = 4.dp,
                elevation = 0.dp,
                placeholderName = track.artist.ifBlank { track.title },
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Title and artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlayerTextPrimary.copy(alpha = dimAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = PlayerTextSecondary.copy(alpha = if (suspended) 0.35f else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Resolver badges
            val resolvers = trackResolvers["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"]
                ?: track.availableResolvers(resolverOrder)
            if (resolvers.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                ResolverIconRow(
                    resolvers = resolvers,
                    size = 20.dp,
                    confidences = trackResolverConfidences["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"],
                    modifier = Modifier.graphicsLayer { alpha = dimAlpha },
                )
            }
        }
    }
}
