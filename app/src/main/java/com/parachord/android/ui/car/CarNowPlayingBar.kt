package com.parachord.android.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.playback.effectiveArtwork
import com.parachord.android.playback.showsLoading
import com.parachord.android.ui.MainViewModel
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.theme.PlayerSurface
import com.parachord.android.ui.theme.PlayerTextPrimary
import com.parachord.android.ui.theme.PlayerTextSecondary
import com.parachord.android.ui.theme.PurpleDark
import com.parachord.shared.ui.icons.AppIcon
import com.parachord.android.ui.components.ParachordIcon

/**
 * The car home's now-playing bar, the phone [MiniPlayer][com.parachord.android.ui.components.MiniPlayer]'s
 * shape at car size: art, title/artist, heart, play/pause, next — tapping it
 * opens Now Playing. Always dark (the player surface), like the phone bar.
 * Nothing renders while no track is playing (except the loading placeholder,
 * while a radio or spinoff fetches its first track).
 *
 * A deliberate redraw, not a reuse: the phone `MiniPlayer`'s sizes are fixed
 * phone-scale, and threading a scale factor through every one of its dp/sp
 * would complicate the phone component for a car-only layout (the same call
 * iOS's CarPlay makes). The content is the same state, the same actions.
 */
@Composable
fun CarNowPlayingBar(
    mainViewModel: MainViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackState by mainViewModel.playbackState.collectAsStateWithLifecycle()
    val trackArtwork by mainViewModel.trackArtwork.collectAsStateWithLifecycle()
    val isFavorited by mainViewModel.isCurrentTrackFavorited.collectAsStateWithLifecycle()
    val isOnTour by mainViewModel.isOnTour.collectAsStateWithLifecycle()

    val currentTrack = playbackState.currentTrack
    if (currentTrack == null) {
        if (playbackState.isPlaybarLoading || playbackState.spinoffLoading) {
            CarNowPlayingBarLoading(modifier)
        }
        return
    }

    val streamMeta = playbackState.streamingMetadata
    val progress = if (playbackState.duration > 0) {
        playbackState.position.toFloat() / playbackState.duration.toFloat()
    } else {
        0f
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PlayerSurface),
    ) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = PurpleDark,
            trackColor = Color.White.copy(alpha = 0.1f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hapticClickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArtCard(
                artworkUrl = playbackState.effectiveArtwork(trackArtwork),
                size = 56.dp,
                cornerRadius = 6.dp,
                elevation = 0.dp,
                placeholderName = streamMeta?.artist ?: currentTrack.artist,
                artist = streamMeta?.artist ?: currentTrack.artist,
                album = streamMeta?.album ?: currentTrack.album,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = streamMeta?.title ?: currentTrack.title,
                    color = PlayerTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = streamMeta?.artist ?: currentTrack.artist,
                        color = PlayerTextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isOnTour) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10C9B4)),
                        )
                    }
                }
            }
            IconButton(
                onClick = { mainViewModel.toggleCurrentTrackFavorite() },
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (isFavorited) MaterialTheme.colorScheme.error else PlayerTextSecondary,
                ),
            ) {
                ParachordIcon(
                    AppIcon.Heart,
                    contentDescription = if (isFavorited) "Remove from Collection" else "Add to Collection",
                    size = 26.dp,
                )
            }
            IconButton(
                onClick = { mainViewModel.togglePlayPause() },
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = PlayerTextPrimary),
            ) {
                if (playbackState.showsLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = PlayerTextPrimary,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            IconButton(
                onClick = { mainViewModel.skipNext() },
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = PlayerTextSecondary),
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** The bar's placeholder while a radio or spinoff fetches its first track (the phone bar's `MiniPlayerLoading`). */
@Composable
private fun CarNowPlayingBarLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PlayerSurface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.1f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = PurpleDark,
                    strokeWidth = 3.dp,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Loading…",
                color = PlayerTextSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
