package com.parachord.android.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.theme.PlayerSurface
import com.parachord.android.ui.theme.PlayerTextPrimary
import com.parachord.android.ui.theme.PlayerTextSecondary
import com.parachord.android.ui.theme.PurpleDark

/**
 * Compact mini-player bar shown above the navigation bar when a track is playing.
 *
 * Always uses dark surface colors regardless of the system theme,
 * matching the desktop app's player bar behavior.
 */
@Composable
fun MiniPlayer(
    trackTitle: String,
    artistName: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isFavorited: Boolean,
    isOnTour: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PlayerSurface),
    ) {
        // Thin progress bar at top
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = PurpleDark,
            trackColor = Color.White.copy(alpha = 0.1f),
        )

        // Main content row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hapticClickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Album art thumbnail
            AlbumArtCard(
                artworkUrl = artworkUrl,
                size = 40.dp,
                cornerRadius = 4.dp,
                elevation = 0.dp,
                placeholderName = artistName.ifBlank { trackTitle },
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Title and artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trackTitle,
                    color = PlayerTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = artistName,
                        color = PlayerTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isOnTour) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10C9B4)),
                        )
                    }
                }
            }

            // Heart/Favorite button
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (isFavorited) Color(0xFFEF4444) else PlayerTextSecondary,
                ),
            ) {
                Icon(
                    imageVector = if (isFavorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorited) "Remove from Collection" else "Add to Collection",
                    modifier = Modifier.size(18.dp),
                )
            }

            // Play/Pause button — shows spinner when buffering
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = PlayerTextPrimary,
                ),
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = PlayerTextPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Skip next button
            IconButton(
                onClick = onSkipNext,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = PlayerTextSecondary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Loading-state stub of [MiniPlayer], rendered when
 * `isPlaybarLoading && currentTrack == null` — i.e. a deeplink-initiated
 * radio (`parachord://play/radio?url=…` Mode C) is mid-fetch and we
 * haven't picked a first track yet. Same height + surface as the real
 * mini-player so the bottom-bar layout doesn't jump when the first track
 * lands. Spinner sits where album art would be; the placeholder text
 * gives the user something to read during the multi-second fetch.
 */
@Composable
fun MiniPlayerLoading(
    label: String = "Loading…",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PlayerSurface),
    ) {
        // Same 2dp slot as the real progress bar so heights match exactly.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.1f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Spinner in the album-art slot (40dp).
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = PurpleDark,
                    strokeWidth = 2.dp,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                color = PlayerTextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
