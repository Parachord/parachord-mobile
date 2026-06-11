package com.parachord.android.ui.screens.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.playback.effectiveTrack
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.rememberHapticClick
import com.parachord.android.resolver.trackKey
import com.parachord.android.ui.components.ResolverSourceDropdown
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.rememberTrackContextMenuState
import com.parachord.android.ui.icons.ParachordIcons
import com.parachord.android.ui.theme.PlayerSurface
import com.parachord.android.ui.theme.PlayerTextPrimary
import com.parachord.android.ui.theme.PlayerTextSecondary
import com.parachord.android.ui.theme.PurpleDark
import kotlinx.coroutines.launch
import java.util.Locale

private val InactiveControlColor = Color(0xFF4B5563)
private val ActiveControlColor = Color(0xFFC084FC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (artistName: String) -> Unit = {},
    onNavigateToArtistOnTour: (artistName: String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToPlaylist: (playlistId: String) -> Unit = {},
    listenAlongFriend: FriendEntity? = null,
    onStopListenAlong: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = koinViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val resolverOrder by viewModel.resolverOrder.collectAsStateWithLifecycle()
    val trackResolvers by viewModel.trackResolvers.collectAsStateWithLifecycle()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsStateWithLifecycle()
    val trackArtwork by viewModel.trackArtwork.collectAsStateWithLifecycle()
    val trackSources by viewModel.trackSources.collectAsStateWithLifecycle()
    val isOnTour by viewModel.isOnTour.collectAsStateWithLifecycle()
    val track = playbackState.currentTrack
    val upNext = playbackState.upNext
    val scope = rememberCoroutineScope()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()
    val haptic = rememberHapticClick()

    // Context menu host
    TrackContextMenuHost(
        state = contextMenuState,
        playlists = playlists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { playlist, t -> viewModel.addToPlaylist(playlist, t) },
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onToggleCollection = { t, isInCollection ->
            if (!isInCollection) viewModel.addToCollection(t)
        },
    )

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = sheetState,
    )

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 28.dp, // Small peek so swipe-up gesture is detected
        sheetContainerColor = PlayerSurface,
        sheetContentColor = PlayerTextPrimary,
        sheetDragHandle = {
            // Subtle drag handle indicator for swipe-up affordance
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(PlayerTextSecondary.copy(alpha = 0.4f)),
                )
            }
        },
        containerColor = PlayerSurface,
        modifier = modifier,
        sheetContent = {
            QueueSheet(
                upNext = upNext,
                playbackContext = playbackState.playbackContext,
                onPlayFromQueue = { viewModel.playFromQueue(it) },
                onMoveInQueue = { from, to -> viewModel.moveInQueue(from, to) },
                onRemoveFromQueue = { viewModel.removeFromQueue(it) },
                onClearQueue = { viewModel.clearQueue() },
                resolverOrder = resolverOrder,
                trackResolvers = trackResolvers,
                trackResolverConfidences = trackResolverConfidences,
                trackArtwork = trackArtwork,
                queueSuspended = playbackState.spinoffMode ||
                    playbackState.playbackContext?.type == "listen-along",
                onNavigateToContext = { ctx ->
                    when (ctx.type) {
                        "album" -> {
                            val artist = track?.artist ?: return@QueueSheet
                            onNavigateToAlbum(ctx.name, artist)
                        }
                        "artist" -> onNavigateToArtist(ctx.name)
                        "playlist" -> {
                            val id = ctx.id ?: return@QueueSheet
                            onNavigateToPlaylist(id)
                        }
                    }
                },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PlayerSurface)
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            // Negative = swipe up → expand queue
                            if (totalDrag < -80f) {
                                scope.launch { sheetState.expand() }
                            }
                            // Positive = swipe down → collapse to mini-player
                            else if (totalDrag > 80f) {
                                onBack()
                            }
                        },
                    )
                },
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Always-dark top app bar — tap anywhere to collapse
                // When listening along, shows friend info + stop button instead of "NOW PLAYING"
                TopAppBar(
                    title = {
                        if (listenAlongFriend != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Headphones,
                                    contentDescription = null,
                                    tint = PurpleDark,
                                    modifier = Modifier.size(18.dp),
                                )
                                Column(
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(
                                        text = "Listening along with",
                                        color = PurpleDark.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                    )
                                    Text(
                                        text = listenAlongFriend.displayName,
                                        color = Color(0xFFEDE9FE), // purple-100
                                        fontSize = 14.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "NOW PLAYING",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Light,
                                    letterSpacing = 0.2.em,
                                ),
                                color = PlayerTextSecondary,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { haptic(); onBack() }) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close",
                                tint = PlayerTextPrimary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    },
                    actions = {
                        if (listenAlongFriend != null) {
                            IconButton(onClick = { haptic(); onStopListenAlong() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Stop listening along",
                                    tint = PurpleDark.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    windowInsets = WindowInsets(0),
                    modifier = Modifier.hapticClickable { onBack() },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Use streaming source artwork when available (higher quality, confirms actual track)
                val streamingMeta = playbackState.streamingMetadata
                val displayArtworkUrl = streamingMeta?.artworkUrl ?: track?.artworkUrl

                // Large album artwork — tap to open album page.
                // Dynamically sized: takes remaining height after fixed elements
                // (metadata, seek bar, controls, bottom row) are accounted for.
                // This prevents the play/pause button from being pushed off screen
                // when the title wraps to 2 lines.
                val currentTrackIsLoved by viewModel.currentTrackIsLoved.collectAsStateWithLifecycle()
                val singleTapHaptic = LocalHapticFeedback.current

                AlbumArtWithGestures(
                    artworkUrl = displayArtworkUrl,
                    isLoved = currentTrackIsLoved,
                    onSingleTap = track?.album?.takeIf { track.artist.isNotBlank() }?.let { album ->
                        {
                            singleTapHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigateToAlbum(album, track.artist)
                        }
                    },
                    onDoubleTapLove = {
                        track?.let { viewModel.addToCollection(it) }
                    },
                    onSwipeNext = { viewModel.skipNext() },
                    onSwipePrevious = { viewModel.skipPrevious() },
                    placeholderName = track?.artist ?: track?.title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    cornerRadius = 12.dp,
                    elevation = 8.dp,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Display actual streaming metadata when available, fall back to queued track
                val displayTitle = streamingMeta?.title ?: track?.title ?: "No track playing"
                val displayArtist = streamingMeta?.artist ?: track?.artist ?: ""
                val displayAlbum = streamingMeta?.album ?: track?.album

                Column(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = PlayerTextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Artist name row with On Tour dot
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = displayArtist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = PlayerTextSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (displayArtist.isNotBlank()) {
                                Modifier.hapticClickable { onNavigateToArtist(displayArtist) }
                            } else Modifier,
                        )
                        if (isOnTour && displayArtist.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            // Small teal dot — tapping opens the On Tour tab
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10C9B4))
                                    .hapticClickable { onNavigateToArtistOnTour(displayArtist) },
                            )
                        }
                    }

                    // Album name — always reserve space to prevent artwork resize
                    // when album metadata arrives late (e.g. from streaming source).
                    Spacer(modifier = Modifier.height(2.dp))
                    val hasAlbum = !displayAlbum.isNullOrBlank() && displayArtist.isNotBlank()
                    Text(
                        text = if (hasAlbum) displayAlbum!! else " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasAlbum) PlayerTextSecondary.copy(alpha = 0.7f) else Color.Transparent,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (hasAlbum) {
                            Modifier.hapticClickable {
                                onNavigateToAlbum(displayAlbum!!, displayArtist)
                            }
                        } else Modifier,
                    )

                    // Resolver source dropdown — shows current source with option to switch
                    if (track?.resolver != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        val key = trackKey(track.title, track.artist)
                        val sources = trackSources[key] ?: emptyList()
                        ResolverSourceDropdown(
                            currentResolver = track.resolver!!,
                            availableSources = sources,
                            onSwitchSource = { resolver -> viewModel.switchSource(resolver) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Seek bar with custom colors
                val duration = playbackState.duration.coerceAtLeast(1L)
                val position = playbackState.position

                Column(modifier = Modifier.padding(horizontal = 32.dp)) {
                    Slider(
                        value = position.toFloat(),
                        onValueChange = { viewModel.seekTo(it.toLong()) },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = PurpleDark,
                            inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDuration(position),
                            style = MaterialTheme.typography.bodySmall,
                            color = PlayerTextSecondary,
                        )
                        Text(
                            text = formatDuration(duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = PlayerTextSecondary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Playback controls with shuffle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shuffle (disabled during spinoff)
                    IconButton(
                        onClick = { haptic(); viewModel.toggleShuffle() },
                        enabled = !playbackState.spinoffMode,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (playbackState.shuffleEnabled) ActiveControlColor else InactiveControlColor,
                            disabledContentColor = InactiveControlColor.copy(alpha = 0.3f),
                        ),
                    ) {
                        Icon(
                            imageVector = ParachordIcons.Shuffle,
                            contentDescription = "Shuffle",
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Skip Previous
                    IconButton(
                        onClick = { haptic(); viewModel.skipPrevious() },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = PlayerTextPrimary,
                        ),
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Play/Pause — large purple circle; shows spinner when buffering
                    IconButton(
                        onClick = { haptic(); viewModel.togglePlayPause() },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color.White,
                        ),
                    ) {
                        if (playbackState.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }

                    // Skip Next
                    IconButton(
                        onClick = { haptic(); viewModel.skipNext() },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = PlayerTextPrimary,
                        ),
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Context menu (•••) button — opens track actions
                    // Uses effectiveTrack so actions (add to playlist, collection, etc.)
                    // reflect the actual streaming track metadata
                    IconButton(
                        onClick = {
                            haptic()
                            val effective = playbackState.effectiveTrack
                            if (effective != null) {
                                contextMenuState.show(
                                    TrackContextInfo(
                                        title = effective.title,
                                        artist = effective.artist,
                                        album = effective.album,
                                        artworkUrl = effective.artworkUrl,
                                        duration = effective.duration,
                                    ),
                                    effective,
                                )
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = InactiveControlColor,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "More actions",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom row: Queue (left) and Spinoff (right)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Queue button with badge
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            onClick = {
                                haptic()
                                scope.launch {
                                    if (sheetState.currentValue == SheetValue.Expanded) {
                                        sheetState.partialExpand()
                                    } else {
                                        sheetState.expand()
                                    }
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (upNext.isNotEmpty()) ActiveControlColor else PlayerTextSecondary,
                            ),
                        ) {
                            Icon(
                                imageVector = ParachordIcons.Queue,
                                contentDescription = "Queue",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        if (upNext.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-2).dp, y = 4.dp)
                                    .size(16.dp)
                                    .background(PurpleDark, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (upNext.size > 99) "99+" else "${upNext.size}",
                                    color = Color.White,
                                    fontSize = if (upNext.size > 99) 7.sp else 9.sp,
                                    lineHeight = if (upNext.size > 99) 7.sp else 9.sp,
                                )
                            }
                        }
                    }

                    // Spinoff button — states: loading (spinner), active (purple), available (gray), unavailable (dim)
                    IconButton(
                        onClick = { haptic(); viewModel.toggleSpinoff() },
                        enabled = !playbackState.spinoffLoading && playbackState.spinoffAvailable != false,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = when {
                                playbackState.spinoffMode -> ActiveControlColor
                                playbackState.spinoffAvailable == false -> PlayerTextSecondary.copy(alpha = 0.3f)
                                else -> PlayerTextSecondary
                            },
                            disabledContentColor = PlayerTextSecondary.copy(alpha = 0.3f),
                        ),
                    ) {
                        if (playbackState.spinoffLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = ActiveControlColor,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = ParachordIcons.Spinoff,
                                contentDescription = if (playbackState.spinoffMode) "Exit Spinoff" else "Spinoff",
                                modifier = Modifier
                                    .size(22.dp)
                                    .offset(y = (-1).dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
