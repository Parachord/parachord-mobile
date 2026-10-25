package com.parachord.android.ui.screens.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ContextMenuItem
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalBgDarker
import com.parachord.android.ui.components.ModalDivider
import com.parachord.android.ui.components.ModalTextActive
import com.parachord.android.ui.components.ModalTextPrimary
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.shimmerBrush
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AlbumViewModel = koinViewModel(),
) {
    val albumDetail by viewModel.albumDetail.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isResolving by viewModel.isResolving.collectAsStateWithLifecycle()
    val trackResolvers by viewModel.trackResolvers.collectAsStateWithLifecycle()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val nowPlayingTitle by viewModel.nowPlayingTitle.collectAsStateWithLifecycle()
    val isAlbumInCollection by viewModel.isAlbumInCollection.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()
    val shareAlbumLite = com.parachord.android.share.rememberShareAlbumLite()
    var showAlbumMenu by remember { mutableStateOf(false) }

    // Context menu host
    TrackContextMenuHost(
        state = contextMenuState,
        playlists = playlists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { playlist, track -> viewModel.addToPlaylist(playlist, track) },
        onNavigateToArtist = onNavigateToArtist,
        onToggleCollection = { track, isInCollection ->
            if (!isInCollection) viewModel.addToCollection(track)
        },
    )

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (isLoading && albumDetail == null) {
                    val brush = shimmerBrush()
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(brush),
                    )
                } else {
                    Text(
                        text = (albumDetail?.title ?: "Album").uppercase(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Light,
                            letterSpacing = 0.2.em,
                        ),
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (isResolving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (isLoading) {
            // Shimmer loading skeleton — header + track rows
            val brush = shimmerBrush()
            Column(modifier = Modifier.fillMaxSize()) {
                // Album header skeleton
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // Album art placeholder
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(brush),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        // Title
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Artist
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Release type + year
                        Box(
                            modifier = Modifier
                                .width(80.dp)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        // Play button placeholder
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(brush),
                        )
                    }
                }
                HorizontalDivider()
                // Track row skeletons
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    repeat(6) { ShimmerTrackRow() }
                }
            }
        } else {
            val detail = albumDetail
            if (detail == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Album not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Album header
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            AlbumArtCard(
                                artworkUrl = detail.artworkUrl,
                                artist = detail.artist,
                                album = detail.title,
                                size = 140.dp,
                                cornerRadius = 8.dp,
                                elevation = 4.dp,
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = detail.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = detail.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onNavigateToArtist(detail.artist) },
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 2.dp),
                                ) {
                                    detail.releaseType?.let { type ->
                                        val badgeColor = releaseTypeBadgeColor(type)
                                        Text(
                                            text = type.uppercase(),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.5.sp,
                                            color = badgeColor,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(badgeColor.copy(alpha = 0.18f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    detail.year?.let { year ->
                                        Text(
                                            text = "$year",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                                // The providers couldn't supply this list; a plugin's
                                // catalog did (#424, desktop parity).
                                detail.catalogSource?.let { source ->
                                    Text(
                                        text = "Track list from $source",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = { viewModel.playAll() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = Color.White,
                                        ),
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play All")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(onClick = { showAlbumMenu = true }) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = "More options",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { HorizontalDivider() }

                    // Tracklist
                    itemsIndexed(
                        detail.tracks,
                        key = { idx, t -> "track-$idx-${t.title}" },
                    ) { index, track ->
                        TrackRow(
                            title = track.title,
                            artist = track.artist,
                            artworkUrl = track.artworkUrl ?: detail.artworkUrl,
                            duration = track.duration,
                            trackNumber = index + 1,
                            isPlaying = nowPlayingTitle == track.title,
                            resolvers = trackResolvers["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"]?.ifEmpty { null },
                            resolverConfidences = trackResolverConfidences["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"],
                            onClick = { viewModel.playTrack(index) },
                            onLongClick = {
                                val entity = viewModel.resolvedTrackEntity(index)
                                if (entity != null) {
                                    contextMenuState.show(
                                        TrackContextInfo(
                                            title = track.title,
                                            artist = track.artist,
                                            album = detail.title,
                                            artworkUrl = track.artworkUrl ?: detail.artworkUrl,
                                            duration = track.duration,
                                        ),
                                        entity,
                                    )
                                }
                            },
                        )
                    }

                    // Provider attribution
                    (detail.catalogSource ?: detail.provider).takeIf { it.isNotBlank() }?.let { providers ->
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Source: ${providers.split("+").distinct().joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        if (showAlbumMenu) {
            val detail = albumDetail
            if (detail != null) {
                AlbumOptionsSheet(
                    albumTitle = detail.title,
                    artistName = detail.artist,
                    artworkUrl = detail.artworkUrl,
                    isInCollection = isAlbumInCollection,
                    onDismiss = { showAlbumMenu = false },
                    onQueueAlbum = {
                        showAlbumMenu = false
                        viewModel.queueAll()
                    },
                    onGoToArtist = {
                        showAlbumMenu = false
                        onNavigateToArtist(detail.artist)
                    },
                    onToggleCollection = {
                        showAlbumMenu = false
                        if (isAlbumInCollection) viewModel.removeAlbumFromCollection()
                        else viewModel.addAlbumToCollection()
                    },
                    onShare = { shareAlbumLite(detail.title, detail.artist, detail.artworkUrl) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumOptionsSheet(
    albumTitle: String,
    artistName: String,
    artworkUrl: String?,
    isInCollection: Boolean,
    onDismiss: () -> Unit,
    onQueueAlbum: () -> Unit,
    onGoToArtist: () -> Unit,
    onToggleCollection: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ModalBg,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(ModalBg, ModalBgDarker)))
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtCard(
                    artworkUrl = artworkUrl,
                    size = 48.dp,
                    cornerRadius = 4.dp,
                    elevation = 1.dp,
                    placeholderName = artistName,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = albumTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ModalTextActive,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = artistName,
                        fontSize = 13.sp,
                        color = ModalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 8.dp))

            ContextMenuItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = "Queue Album",
                onClick = onQueueAlbum,
            )
            ContextMenuItem(
                icon = Icons.Filled.Person,
                label = "Go to Artist",
                onClick = onGoToArtist,
            )

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))

            ContextMenuItem(
                icon = if (isInCollection) Icons.Filled.HeartBroken else Icons.Filled.Favorite,
                label = if (isInCollection) "Remove from Collection" else "Add to Collection",
                onClick = onToggleCollection,
            )

            if (onShare != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.Share,
                    label = "Share",
                    onClick = { onShare(); onDismiss() },
                )
            }
        }
    }
}

private fun releaseTypeBadgeColor(type: String): Color = when (type.lowercase()) {
    "album" -> Color(0xFF6366F1)        // indigo
    "ep" -> Color(0xFFA855F7)           // purple
    "single" -> Color(0xFFEC4899)       // pink
    "live" -> Color(0xFFF59E0B)         // amber
    "compilation" -> Color(0xFF14B8A6)  // teal
    else -> Color(0xFF9CA3AF)           // gray
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:%02d".format(seconds)
}
