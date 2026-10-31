package com.parachord.android.ui.screens.friends

import androidx.compose.foundation.background
import com.parachord.android.ui.components.AlbumContextMenu
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.hapticCombinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.koin.androidx.compose.koinViewModel
import com.parachord.shared.model.HistoryAlbum
import com.parachord.shared.model.HistoryArtist
import com.parachord.shared.model.HistoryTrack
import com.parachord.shared.model.RecentTrack
import com.parachord.shared.model.Resource
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.ResolverIconRow
import com.parachord.android.ui.components.TrackRow

private val OnAirGreen = Color(0xFF22C55E)

private val friendDetailTabs = listOf("Recent", "Top Songs", "Top Albums", "Top Artists")

private data class PeriodOption(val key: String, val label: String)

private val periodOptions = listOf(
    PeriodOption("7day", "7 Days"),
    PeriodOption("1month", "Month"),
    PeriodOption("3month", "3 Months"),
    PeriodOption("6month", "6 Months"),
    PeriodOption("12month", "Year"),
    PeriodOption("overall", "All Time"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendDetailScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FriendDetailViewModel = koinViewModel(),
) {
    val friend by viewModel.friend.collectAsState()
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val topTracks by viewModel.topTracks.collectAsState()
    val topAlbums by viewModel.topAlbums.collectAsState()
    val topArtists by viewModel.topArtists.collectAsState()
    val trackResolvers by viewModel.trackResolvers.collectAsState()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsState()
    val trackArtwork by viewModel.trackArtwork.collectAsState()

    val currentFriend = friend

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = currentFriend?.displayName?.uppercase() ?: "FRIEND",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (currentFriend != null) {
                    IconButton(onClick = { viewModel.togglePin() }) {
                        Icon(
                            imageVector = if (currentFriend.pinnedToSidebar) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (currentFriend.pinnedToSidebar) "Unpin from sidebar" else "Pin to sidebar",
                            tint = if (currentFriend.pinnedToSidebar) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            windowInsets = WindowInsets(0),
        )

        // Friend header
        if (currentFriend != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AlbumArtCard(
                    artworkUrl = currentFriend.avatarUrl,
                    size = 80.dp,
                    cornerRadius = 40.dp,
                    placeholderName = currentFriend.displayName,
                )
                if (currentFriend.isOnAir) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "● ON AIR",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnAirGreen,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "@${currentFriend.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val serviceLabel = when (currentFriend.service) {
                    "lastfm" -> "Last.fm"
                    "listenbrainz" -> "ListenBrainz"
                    else -> currentFriend.service
                }
                Text(
                    text = "Listening activity from $serviceLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // Tabs
        SwipeableTabLayout(tabs = friendDetailTabs, initialPage = viewModel.initialPage) { page ->
            when (page) {
                0 -> FriendRecentTab(
                    recentTracks = recentTracks,
                    hiddenMessage = friend?.takeIf { it.hidesListening }?.let {
                        "${it.displayName} hides their recent listening on Last.fm. " +
                            "Their top tracks, albums and artists are in the other tabs."
                    },
                    onPlayTrack = viewModel::playRecentTrack,
                    onRetry = viewModel::refresh,
                    trackResolvers = trackResolvers,
                    trackResolverConfidences = trackResolverConfidences,
                    trackArtwork = trackArtwork,
                )
                1 -> FriendTopSongsTab(
                    tracks = topTracks,
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onPlayTrack = viewModel::playTopTrack,
                    trackResolvers = trackResolvers,
                    trackResolverConfidences = trackResolverConfidences,
                    trackArtwork = trackArtwork,
                )
                2 -> FriendTopAlbumsTab(
                    albums = topAlbums,
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onAlbumClick = onNavigateToAlbum,
                    onNavigateToArtist = onNavigateToArtist,
                    onQueueAlbum = viewModel::queueAlbumByName,
                    onAddAlbumToCollection = viewModel::addAlbumToCollection,
                )
                3 -> FriendTopArtistsTab(
                    artists = topArtists,
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onArtistClick = onNavigateToArtist,
                    onPlayTopSongs = { viewModel.playArtistTopSongs(it) },
                    onQueueTopSongs = { viewModel.queueArtistTopSongs(it) },
                    onToggleArtistCollection = { name, imageUrl, inCol ->
                        viewModel.toggleArtistCollection(name, imageUrl, inCol)
                    },
                )
            }
        }
    }
}

// ---------- Period Filter ----------

@Composable
private fun PeriodFilter(
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(periodOptions, key = { it.key }) { option ->
            FilterChip(
                selected = selectedPeriod == option.key,
                onClick = { onPeriodChanged(option.key) },
                label = { Text(option.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

// ---------- Recent Tab ----------

@Composable
private fun FriendRecentTab(
    recentTracks: Resource<List<RecentTrack>>,
    hiddenMessage: String? = null,
    onPlayTrack: (Int) -> Unit,
    onRetry: () -> Unit = {},
    trackResolvers: Map<String, List<String>> = emptyMap(),
    trackResolverConfidences: Map<String, Map<String, Float>> = emptyMap(),
    trackArtwork: Map<String, String> = emptyMap(),
) {
    when (recentTracks) {
        is Resource.Loading -> {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(10) { ShimmerTrackRow(modifier = Modifier.padding(horizontal = 16.dp)) }
            }
        }
        is Resource.Error -> ErrorState(recentTracks.message, onRetry)
        is Resource.Success -> {
            if (recentTracks.data.isEmpty()) {
                EmptyState(hiddenMessage ?: "No recent listening activity")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(recentTracks.data.size) { index ->
                        val track = recentTracks.data[index]
                        val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                        RecentTrackRow(
                            track = track,
                            resolvers = trackResolvers[key],
                            resolverConfidences = trackResolverConfidences[key],
                            artworkUrl = track.artworkUrl ?: trackArtwork[key],
                            onClick = { onPlayTrack(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTrackRow(
    track: RecentTrack,
    resolvers: List<String>? = null,
    resolverConfidences: Map<String, Float>? = null,
    artworkUrl: String? = track.artworkUrl,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtCard(
            artworkUrl = artworkUrl,
            size = 44.dp,
            cornerRadius = 4.dp,
            elevation = 1.dp,
            placeholderName = track.title,
            artist = track.artist,
            album = track.album,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (track.nowPlaying) "▶ ${track.title}" else track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (track.nowPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Time ago
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (track.nowPlaying) "Now" else formatTimeAgo(track.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = if (track.nowPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Resolver badges (to the right of the time)
        if (!resolvers.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            ResolverIconRow(resolvers = resolvers, size = 20.dp, confidences = resolverConfidences)
        }
    }
}

// ---------- Top Songs Tab ----------

@Composable
private fun FriendTopSongsTab(
    tracks: Resource<List<HistoryTrack>>,
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    onPlayTrack: (Int) -> Unit,
    trackResolvers: Map<String, List<String>> = emptyMap(),
    trackResolverConfidences: Map<String, Map<String, Float>> = emptyMap(),
    trackArtwork: Map<String, String> = emptyMap(),
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PeriodFilter(selectedPeriod = selectedPeriod, onPeriodChanged = onPeriodChanged)
        when (tracks) {
            is Resource.Loading -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(10) { ShimmerTrackRow(modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            }
            is Resource.Error -> ErrorState(tracks.message)
            is Resource.Success -> {
                if (tracks.data.isEmpty()) {
                    EmptyState("No listening data for this period")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(tracks.data.size) { index ->
                            val track = tracks.data[index]
                            val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                            TrackRow(
                                title = track.title,
                                artist = "${track.artist}  •  ${formatPlayCount(track.playCount)}",
                                artworkUrl = track.artworkUrl ?: trackArtwork[key],
                                trackNumber = track.rank,
                                resolvers = trackResolvers[key],
                                resolverConfidences = trackResolverConfidences[key],
                                onClick = { onPlayTrack(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Top Albums Tab ----------

@Composable
private fun FriendTopAlbumsTab(
    albums: Resource<List<HistoryAlbum>>,
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    onAlbumClick: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    onQueueAlbum: (albumTitle: String, albumArtist: String) -> Unit = { _, _ -> },
    onAddAlbumToCollection: (title: String, artist: String, artworkUrl: String?) -> Unit = { _, _, _ -> },
) {
    val shareAlbumLite = com.parachord.android.share.rememberShareAlbumLite()
    Column(modifier = Modifier.fillMaxSize()) {
        PeriodFilter(selectedPeriod = selectedPeriod, onPeriodChanged = onPeriodChanged)
        when (albums) {
            is Resource.Loading -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(10) { ShimmerTrackRow(modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            }
            is Resource.Error -> ErrorState(albums.message)
            is Resource.Success -> {
                if (albums.data.isEmpty()) {
                    EmptyState("No album data for this period")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(albums.data.size, key = { "${albums.data[it].rank}-${albums.data[it].name}" }) { index ->
                            val album = albums.data[index]
                            var showMenu by remember { mutableStateOf(false) }
                            AlbumGridItem(
                                album = album,
                                onClick = { onAlbumClick(album.name, album.artist) },
                                onLongClick = { showMenu = true },
                            )
                            if (showMenu) {
                                AlbumContextMenu(
                                    albumTitle = album.name,
                                    artistName = album.artist,
                                    artworkUrl = album.artworkUrl,
                                    isInCollection = false,
                                    onDismiss = { showMenu = false },
                                    onQueueAlbum = { onQueueAlbum(album.name, album.artist) },
                                    onGoToAlbum = { onAlbumClick(album.name, album.artist) },
                                    onGoToArtist = { onNavigateToArtist(album.artist) },
                                    onToggleCollection = {
                                        onAddAlbumToCollection(album.name, album.artist, album.artworkUrl)
                                    },
                                    onShare = { shareAlbumLite(album.name, album.artist, album.artworkUrl) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGridItem(album: HistoryAlbum, onClick: () -> Unit = {}, onLongClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .hapticCombinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            AlbumArtCard(
                artworkUrl = album.artworkUrl,
                artist = album.artist,
                album = album.name,
                size = 180.dp,
                cornerRadius = 8.dp,
                placeholderName = album.name,
            )
            // Rank badge
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .align(Alignment.TopStart),
            ) {
                Text(
                    text = "#${album.rank}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            // Play count badge
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .align(Alignment.TopEnd),
            ) {
                Text(
                    text = formatPlayCount(album.playCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

// ---------- Top Artists Tab ----------

@Composable
private fun FriendTopArtistsTab(
    artists: Resource<List<HistoryArtist>>,
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    onArtistClick: (String) -> Unit = {},
    onPlayTopSongs: (String) -> Unit = {},
    onQueueTopSongs: (String) -> Unit = {},
    onToggleArtistCollection: (name: String, imageUrl: String?, isInCollection: Boolean) -> Unit = { _, _, _ -> },
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PeriodFilter(selectedPeriod = selectedPeriod, onPeriodChanged = onPeriodChanged)
        when (artists) {
            is Resource.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is Resource.Error -> ErrorState(artists.message)
            is Resource.Success -> {
                if (artists.data.isEmpty()) {
                    EmptyState("No artist data for this period")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(artists.data, key = { "${it.rank}-${it.name}" }) { artist ->
                            ArtistGridItem(
                                artist = artist,
                                onClick = { onArtistClick(artist.name) },
                                onPlayTopSongs = { onPlayTopSongs(artist.name) },
                                onQueueTopSongs = { onQueueTopSongs(artist.name) },
                                onGoToArtist = { onArtistClick(artist.name) },
                                onToggleCollection = { onToggleArtistCollection(artist.name, artist.imageUrl, false) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistGridItem(
    artist: HistoryArtist,
    onClick: () -> Unit = {},
    onPlayTopSongs: () -> Unit = {},
    onQueueTopSongs: () -> Unit = {},
    onGoToArtist: () -> Unit = {},
    onToggleCollection: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val shareArtist = com.parachord.android.share.rememberShareArtist()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .hapticCombinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true },
            ),
    ) {
        AlbumArtCard(
            artworkUrl = artist.imageUrl,
            size = 96.dp,
            cornerRadius = 48.dp,
            placeholderName = artist.name,
            modifier = Modifier.clip(CircleShape),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = formatPlayCount(artist.playCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    if (showMenu) {
        com.parachord.android.ui.components.ArtistContextMenu(
            artistName = artist.name,
            imageUrl = artist.imageUrl,
            isInCollection = false,
            onDismiss = { showMenu = false },
            onPlayTopSongs = onPlayTopSongs,
            onQueueTopSongs = onQueueTopSongs,
            onGoToArtist = onGoToArtist,
            onToggleCollection = onToggleCollection,
            onShare = { shareArtist(artist.name, artist.imageUrl) },
        )
    }
}

// ---------- Shared ----------

@Composable
private fun ErrorState(message: String, onRetry: (() -> Unit)? = null) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

private fun formatPlayCount(count: Int): String = when {
    count >= 1_000_000 -> "${"%.1f".format(count / 1_000_000.0)}M plays"
    count >= 1_000 -> "${"%.1f".format(count / 1_000.0)}K plays"
    count == 1 -> "1 play"
    else -> "$count plays"
}

private fun formatTimeAgo(timestampSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestampSeconds
    return when {
        diff < 60 -> "Just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 172800 -> "Yesterday"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> "${diff / 604800}w ago"
    }
}
