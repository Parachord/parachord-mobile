package com.parachord.android.ui.screens.playlists

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.parachord.android.ui.components.ResolveWhileVisible
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState
import com.parachord.android.ui.theme.FillAccent
import com.parachord.shared.model.Resource
import com.parachord.shared.recommendations.RecommendationCopy
import org.koin.androidx.compose.koinViewModel

private const val SKELETON_ROWS = 8

/**
 * A service playlist opened from a recommendation (desktop's `openServicePlaylist`): it opens at
 * once, with skeleton rows until the tracks land, and can be played or saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicePlaylistScreen(
    onBack: () -> Unit,
    onOpenSaved: (playlistId: String) -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: ServicePlaylistViewModel = koinViewModel(),
) {
    val tracksState by viewModel.tracks.collectAsStateWithLifecycle()
    val coverUrl by viewModel.coverUrl.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val nowPlayingTitle by viewModel.nowPlayingTitle.collectAsStateWithLifecycle()
    val trackResolvers by viewModel.trackResolvers.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ServicePlaylistEvent.Saved -> {
                    Toast.makeText(context, RecommendationCopy.saved(event.name), Toast.LENGTH_SHORT).show()
                    onOpenSaved(event.playlistId)
                }
                is ServicePlaylistEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val loaded = (tracksState as? Resource.Success)?.data.orEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ServicePlaylistCover(cover = coverUrl, trackCovers = viewModel.trackCovers(loaded), size = 160.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = viewModel.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = viewModel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (tracksState is Resource.Success) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { viewModel.playFrom(0) },
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = FillAccent, contentColor = Color.White),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(RecommendationCopy.PLAY)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.save() },
                                enabled = !saving,
                                shape = RoundedCornerShape(24.dp),
                            ) {
                                Text(RecommendationCopy.SAVE)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            when (val state = tracksState) {
                Resource.Loading -> items(SKELETON_ROWS) { ShimmerTrackRow() }
                is Resource.Error -> item(key = "error") {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        OutlinedButton(onClick = viewModel::retry, shape = RoundedCornerShape(24.dp)) {
                            Text(RecommendationCopy.TRY_AGAIN)
                        }
                    }
                }
                is Resource.Success -> itemsIndexed(state.data, key = { index, track -> "${index}:${track.id}" }) { index, track ->
                    val resolverKey = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                    ResolveWhileVisible(track.id) { viewModel.resolveVisible(track) }
                    TrackRow(
                        title = track.title,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        resolvers = trackResolvers[resolverKey],
                        duration = track.duration,
                        trackNumber = index + 1,
                        isPlaying = nowPlayingTitle == track.title,
                        onClick = { viewModel.playFrom(index) },
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
        onToggleCollection = { track, isInCollection -> viewModel.toggleCollection(track, isInCollection) },
    )
}

/** The cover the card showed; else a 2x2 mosaic of the first track covers (one fills it). */
@Composable
private fun ServicePlaylistCover(cover: String?, trackCovers: List<String>, size: Dp) {
    val half = size / 2
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp))) {
        when {
            cover != null -> AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            trackCovers.size >= 4 -> Column {
                Row {
                    AsyncImage(trackCovers[0], null, contentScale = ContentScale.Crop, modifier = Modifier.size(half))
                    AsyncImage(trackCovers[1], null, contentScale = ContentScale.Crop, modifier = Modifier.size(half))
                }
                Row {
                    AsyncImage(trackCovers[2], null, contentScale = ContentScale.Crop, modifier = Modifier.size(half))
                    AsyncImage(trackCovers[3], null, contentScale = ContentScale.Crop, modifier = Modifier.size(half))
                }
            }
            trackCovers.isNotEmpty() -> AsyncImage(trackCovers[0], null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Box(Modifier.fillMaxSize().background(Color(0xFF2D2D2D)), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color(0xFF6B7280), modifier = Modifier.size(48.dp))
            }
        }
    }
}
