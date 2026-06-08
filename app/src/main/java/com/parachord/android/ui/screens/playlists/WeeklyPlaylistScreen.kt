package com.parachord.android.ui.screens.playlists

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyPlaylistScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: WeeklyPlaylistViewModel = koinViewModel(),
) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val weekLabel by viewModel.weekLabel.collectAsStateWithLifecycle()
    val description by viewModel.description.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val coverUrls by viewModel.coverUrls.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val nowPlayingTitle by viewModel.nowPlayingTitle.collectAsStateWithLifecycle()
    val trackResolvers by viewModel.trackResolvers.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = (if (weekLabel.isNotEmpty()) "$weekLabel — " else "") + title.uppercase(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                        fontSize = 16.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Header with mosaic + play/save buttons
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Mosaic cover art
                        WeeklyMosaic(coverUrls = coverUrls, size = 160.dp)

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // Meta line
                        Text(
                            text = buildString {
                                append("ListenBrainz")
                                append(" · ")
                                append("${tracks.size} tracks")
                                if (weekLabel.isNotEmpty()) {
                                    append(" · ")
                                    append(weekLabel)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (description.isNotEmpty()) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (tracks.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { viewModel.playAll() },
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Play All")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // Save to Library button
                                OutlinedButton(
                                    onClick = { viewModel.saveToLibrary() },
                                    enabled = !saved,
                                    shape = RoundedCornerShape(24.dp),
                                ) {
                                    Icon(
                                        if (saved) Icons.Filled.Check else Icons.Filled.SaveAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (saved) "Saved" else "Save")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Track list
                itemsIndexed(tracks, key = { index, track -> track.id }) { index, track ->
                    val resolverKey = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                    // Visibility-scoped resolution (#177): resolve a row only once
                    // it scrolls into view (LazyColumn composes viewport + overscan),
                    // not the whole list on open. The cache dedups, so re-scroll is free.
                    LaunchedEffect(track.id) { viewModel.resolveVisibleTrack(track) }
                    TrackRow(
                        title = track.title,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        resolvers = trackResolvers[resolverKey],
                        duration = track.duration,
                        trackNumber = index + 1,
                        isPlaying = nowPlayingTitle == track.title,
                        onClick = { viewModel.playTrack(index) },
                        onLongClick = {
                            contextMenuState.show(
                                TrackContextInfo(
                                    title = track.title,
                                    artist = track.artist,
                                    album = track.album,
                                    artworkUrl = track.artworkUrl,
                                    duration = track.duration,
                                ),
                                track,
                            )
                        },
                    )
                }
            }
        }
    }

    TrackContextMenuHost(
        state = contextMenuState,
        playlists = allPlaylists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { playlist, track -> viewModel.addToPlaylist(playlist, track) },
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onToggleCollection = { track, isInCollection ->
            viewModel.toggleCollection(track, isInCollection)
        },
    )
}

@Composable
private fun WeeklyMosaic(
    coverUrls: List<String>,
    size: androidx.compose.ui.unit.Dp,
) {
    val halfSize = size / 2

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        if (coverUrls.size >= 4) {
            Column {
                Row {
                    AsyncImage(model = coverUrls[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                    AsyncImage(model = coverUrls[1], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                }
                Row {
                    AsyncImage(model = coverUrls[2], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                    AsyncImage(model = coverUrls[3], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                }
            }
        } else if (coverUrls.isNotEmpty()) {
            AsyncImage(model = coverUrls[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF2D2D2D)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = Color(0xFF6B7280),
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}
