package com.parachord.android.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.resolver.trackKey
import com.parachord.android.playback.queueSuspended
import com.parachord.android.ui.MainViewModel
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.TrackRowDuration
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.screens.nowplaying.NowPlayingViewModel
import com.parachord.android.ui.theme.PurpleDark
import com.parachord.android.ui.theme.queueContextColor
import com.parachord.shared.playback.PlaybackMessages
import com.parachord.shared.ui.TrackRowRules
import com.parachord.shared.ui.icons.AppIcon
import org.koin.androidx.compose.koinViewModel

/**
 * The car's queue: the current track, then Up Next. A row tap jumps to that
 * song (`playFromQueue`) and that is all the queue does — no reordering, no
 * removal, no clear. Suspended queues (a spinoff or listen-along holding the
 * user's queue) dim and number with "··", as the phone's `QueueSheet` does.
 */
@Composable
fun CarQueueScreen(mainViewModel: MainViewModel, onBack: () -> Unit) {
    val nowPlaying: NowPlayingViewModel = koinViewModel()
    val state by nowPlaying.playbackState.collectAsStateWithLifecycle()
    val trackArtwork by mainViewModel.trackArtwork.collectAsStateWithLifecycle()
    val upNext = state.upNext
    val suspended = state.queueSuspended

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        // Header: back, title, count.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(64.dp)) {
                ParachordIcon(AppIcon.ChevronLeft, contentDescription = "Back", size = 32.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (suspended) "YOUR QUEUE" else "QUEUE",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (suspended) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (upNext.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${upNext.size} track${if (upNext.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // "Playing from: …", the shared label.
        state.playbackContext?.let { ctx ->
            Text(
                text = PlaybackMessages.contextLabel(ctx.type, ctx.name),
                style = MaterialTheme.typography.bodyMedium,
                color = queueContextColor(ctx.type),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
            )
        }

        val currentTrack = state.currentTrack
        if (currentTrack == null && upNext.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Queue is empty",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                currentTrack?.let { track ->
                    item(key = "car:queue:current:${track.id}") {
                        CarQueueRow(
                            track = track,
                            label = "NOW PLAYING",
                            trackArtwork = trackArtwork,
                        )
                    }
                }
                itemsIndexed(upNext, key = { index, track -> "car:queue:${track.id}-$index" }) { index, track ->
                    CarQueueRow(
                        track = track,
                        number = if (suspended) null else index + 1,
                        suspended = suspended,
                        trackArtwork = trackArtwork,
                        onTap = { nowPlaying.playFromQueue(index) },
                    )
                }
            }
        }
    }
}

/** One queue row: its number (or "··" when suspended, or nothing for the current track), art, names, duration. */
@Composable
private fun CarQueueRow(
    track: TrackEntity,
    trackArtwork: Map<String, String>,
    number: Int? = null,
    suspended: Boolean = false,
    label: String? = null,
    onTap: (() -> Unit)? = null,
) {
    val dimAlpha = if (suspended) 0.5f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.hapticClickable(onClick = onTap) else Modifier)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = PurpleDark,
                modifier = Modifier.width(88.dp),
            )
        } else {
            Text(
                text = if (suspended) "··" else String.format("%02d", number ?: 1),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.width(40.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        AlbumArtCard(
            artworkUrl = trackArtwork[trackKey(track.title, track.artist)] ?: track.artworkUrl,
            size = 48.dp,
            cornerRadius = 4.dp,
            elevation = 0.dp,
            placeholderName = track.artist.ifBlank { track.title },
        )
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = dimAlpha },
        ) {
            Text(
                text = track.title,
                fontSize = 16.sp,
                color = if (label != null) PurpleDark else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val duration = TrackRowRules.pickDurationMs(track.duration, null)
        if (duration != null && label == null) {
            Spacer(Modifier.width(12.dp))
            TrackRowDuration(
                label = TrackRowRules.formatDuration(duration),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dimAlpha),
            )
        }
    }
}
