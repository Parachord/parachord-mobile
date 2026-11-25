package com.parachord.android.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.playback.effectiveArtwork
import com.parachord.android.playback.showsLoading
import com.parachord.android.ui.MainViewModel
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.screens.nowplaying.NowPlayingViewModel
import com.parachord.android.ui.theme.FillAccent
import com.parachord.android.ui.theme.PlayerSurface
import com.parachord.android.ui.theme.PlayerTextPrimary
import com.parachord.android.ui.theme.PlayerTextSecondary
import com.parachord.android.ui.theme.PurpleDark
import com.parachord.shared.car.CarNowPlayingButton
import com.parachord.shared.playback.shuffleAllowed
import com.parachord.shared.car.carNowPlayingButtons
import com.parachord.shared.ui.LayoutRules
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.TrackRowRules
import com.parachord.shared.ui.icons.AppIcon
import org.koin.androidx.compose.koinViewModel

/** The car's Now Playing, matching Android Auto's layout: large art, title and
 * artist, a seek bar with times, prev / play-pause / next, and the shared car
 * custom buttons (heart, Spinoff) [carNowPlayingButtons] picks — the same set
 * Auto's now playing shows. The queue button sits in the top corner. Always
 * dark (the player surface), like the phone's Now Playing.
 */
private val ActiveControlColor = Color(0xFFC084FC) // The phone Now Playing' spinoff/active color

/** White on fill-accent surfaces (design system: never on the theme accent). */
private val OnFill = com.parachord.android.ui.theme.tokenColor(DesignTokens.ON_ACCENT)
@Composable
fun CarNowPlayingScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val nowPlaying: NowPlayingViewModel = koinViewModel()
    val state by nowPlaying.playbackState.collectAsStateWithLifecycle()
    val trackArtwork by mainViewModel.trackArtwork.collectAsStateWithLifecycle()
    val loved by mainViewModel.isCurrentTrackFavorited.collectAsStateWithLifecycle()

    val track = state.currentTrack

    Column(
        Modifier
            .fillMaxSize()
            .background(PlayerSurface)
            .systemBarsPadding(),
    ) {
        // Top row: back on the left, the queue in the corner.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(64.dp)) {
                ParachordIcon(AppIcon.ChevronLeft, contentDescription = "Back", size = 32.dp, tint = PlayerTextPrimary)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenQueue, modifier = Modifier.size(64.dp)) {
                ParachordIcon(AppIcon.ListMusic, contentDescription = "Queue", size = 30.dp, tint = PlayerTextPrimary)
            }
        }

        if (track == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing playing",
                    color = PlayerTextSecondary,
                    fontSize = 16.sp,
                )
            }
        } else {
            val streamMeta = state.streamingMetadata
            val title = streamMeta?.title ?: track.title
            val artist = streamMeta?.artist ?: track.artist

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // The art takes its share of the height, capped at the column's width.
                val artSize = minOf(maxWidth * 0.42f, maxHeight * 0.52f)
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight()
                        .widthIn(max = LayoutRules.PLAYER_CONTROLS_MAX.dp)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AlbumArtCard(
                        artworkUrl = state.effectiveArtwork(trackArtwork),
                        size = artSize,
                        cornerRadius = 12.dp,
                        elevation = 0.dp,
                        placeholderName = artist,
                        artist = artist,
                        album = streamMeta?.album ?: track.album,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = title,
                        color = PlayerTextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = artist,
                        color = PlayerTextSecondary,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.duration > 0) {
                        val position = state.position.coerceIn(0L, state.duration)
                        Slider(
                            value = position.toFloat(),
                            onValueChange = { nowPlaying.seekTo(it.toLong()) },
                            valueRange = 0f..state.duration.toFloat(),
                            colors = SliderDefaults.colors(
                                activeTrackColor = PurpleDark,
                                inactiveTrackColor = PlayerTextSecondary.copy(alpha = 0.3f),
                                thumbColor = PurpleDark,
                            ),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = TrackRowRules.formatDuration(position),
                                color = PlayerTextSecondary,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = TrackRowRules.formatDuration(state.duration),
                                color = PlayerTextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    // Transport: prev / play-pause / next, Auto-sized.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        IconButton(
                            onClick = { nowPlaying.skipPrevious() },
                            modifier = Modifier.size(76.dp),
                            colors = IconButtonDefaults.iconButtonColors(contentColor = PlayerTextPrimary),
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        Spacer(Modifier.width(24.dp))
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clip(CircleShape)
                                .background(FillAccent)
                                .hapticClickable(onClick = { nowPlaying.togglePlayPause() }),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.showsLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = OnFill,
                                    strokeWidth = 4.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                                    tint = OnFill,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(24.dp))
                        IconButton(
                            onClick = { nowPlaying.skipNext() },
                            modifier = Modifier.size(76.dp),
                            colors = IconButtonDefaults.iconButtonColors(contentColor = PlayerTextPrimary),
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Shuffle, as the phone's Now Playing: disabled during a
                        // spinoff or Listen Along (the shared shuffleAllowed rule).
                        IconButton(
                            onClick = { nowPlaying.toggleShuffle() },
                            enabled = shuffleAllowed(state.spinoffMode, state.playbackContext?.type),
                            modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (state.shuffleEnabled) ActiveControlColor else PlayerTextSecondary,
                                disabledContentColor = PlayerTextSecondary.copy(alpha = 0.3f),
                            ),
                        ) {
                            ParachordIcon(AppIcon.Shuffle, contentDescription = "Shuffle", size = 30.dp)
                        }
                        // The same custom buttons Auto's now playing shows.
                        carNowPlayingButtons(
                            loved = loved,
                            spinoffMode = state.spinoffMode,
                            spinoffAvailable = state.spinoffAvailable,
                            spinoffLoading = state.spinoffLoading,
                        ).forEach { button ->
                            when (button) {
                                CarNowPlayingButton.LOVE, CarNowPlayingButton.UNLOVE -> IconButton(
                                    onClick = { mainViewModel.toggleCurrentTrackFavorite() },
                                    modifier = Modifier.size(64.dp),
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = if (loved) androidx.compose.material3.MaterialTheme.colorScheme.error else PlayerTextSecondary,
                                    ),
                                ) {
                                    ParachordIcon(
                                        AppIcon.Heart,
                                        contentDescription = if (loved) "Remove from Collection" else "Add to Collection",
                                        size = 30.dp,
                                    )
                                }
                                CarNowPlayingButton.SPINOFF, CarNowPlayingButton.END_SPINOFF -> {
                                    val active = state.spinoffMode || state.spinoffLoading
                                    IconButton(
                                        // A tap while the pool builds is ignored (the shared #551 rule):
                                        // the button already reads as running, and toggling would kill the fetch.
                                        onClick = { if (!state.spinoffLoading) nowPlaying.toggleSpinoff() },
                                        modifier = Modifier.size(64.dp),
                                        colors = IconButtonDefaults.iconButtonColors(
                                            contentColor = if (active) ActiveControlColor else PlayerTextSecondary,
                                        ),
                                    ) {
                                        ParachordIcon(
                                            AppIcon.Spinoff,
                                            contentDescription = if (active) "End Spinoff" else "Spinoff",
                                            size = 30.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
