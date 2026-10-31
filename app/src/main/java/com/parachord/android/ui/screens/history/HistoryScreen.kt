package com.parachord.android.ui.screens.history

import androidx.compose.foundation.background
import com.parachord.android.resolver.trackKey
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
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
import com.parachord.android.ui.screens.library.CollectionFilterBar

private val historyTabs = listOf("Top Songs", "Top Albums", "Top Artists", "Recently Played")

private data class PeriodOption(val key: String, val label: String)

private val periodOptions = listOf(
    PeriodOption("7day", "7 Days"),
    PeriodOption("1month", "Month"),
    PeriodOption("3month", "3 Months"),
    PeriodOption("6month", "6 Months"),
    PeriodOption("12month", "Year"),
    PeriodOption("overall", "All Time"),
)

private data class SortOption(val key: String, val label: String)

private val sortOptions = listOf(
    SortOption("recent", "Recent"),
    SortOption("artist", "By Artist"),
    SortOption("title", "By Title"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val topTracks by viewModel.topTracks.collectAsState()
    val topAlbums by viewModel.topAlbums.collectAsState()
    val topArtists by viewModel.topArtists.collectAsState()
    val filteredRecentTracks by viewModel.filteredRecentTracks.collectAsState()
    val recentSort by viewModel.recentSort.collectAsState()
    val recentSearch by viewModel.recentSearch.collectAsState()
    val trackResolvers by viewModel.trackResolvers.collectAsState()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsState()
    val trackArtwork by viewModel.trackArtwork.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "HISTORY",
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
            windowInsets = WindowInsets(0),
        )
        SwipeableTabLayout(tabs = historyTabs, initialPage = viewModel.initialPage) { page ->
            when (page) {
                0 -> TopSongsTab(
                    tracks = topTracks,
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onPlayTrack = viewModel::playTopTrack,
                    trackResolvers = trackResolvers,
                    trackResolverConfidences = trackResolverConfidences,
                    trackArtwork = trackArtwork,
                )
                1 -> TopAlbumsTab(
                    albums = topAlbums,
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onAlbumClick = onNavigateToAlbum,
                    onNavigateToArtist = onNavigateToArtist,
                    onQueueAlbum = { title, artist -> viewModel.queueAlbumByName(title, artist) },
                    onAddAlbumToCollection = { title, artist, artworkUrl -> viewModel.addAlbumToCollection(title, artist, artworkUrl) },
                )
                2 -> TopArtistsTab(
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
                3 -> RecentlyPlayedTab(
                    recentTracks = filteredRecentTracks,
                    sort = recentSort,
                    search = recentSearch,
                    onSortChanged = viewModel::setRecentSort,
                    onSearchChanged = viewModel::setRecentSearch,
                    onPlayTrack = viewModel::playRecentTrack,
                    trackResolvers = trackResolvers,
                    trackResolverConfidences = trackResolverConfidences,
                    trackArtwork = trackArtwork,
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

// ---------- Top Songs Tab ----------

@Composable
private fun TopSongsTab(
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
            is Resource.Error -> ErrorState(message = tracks.message, onRetry = { onPeriodChanged(selectedPeriod) })
            is Resource.Success -> {
                if (tracks.data.isEmpty()) {
                    EmptyState("No listening data yet")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(tracks.data.size) { index ->
                            val track = tracks.data[index]
                            TrackRow(
                                title = track.title,
                                artist = "${track.artist}  •  ${formatPlayCount(track.playCount)}",
                                artworkUrl = track.artworkUrl ?: trackArtwork[trackKey(track.title, track.artist)],
                                trackNumber = track.rank,
                                resolvers = trackResolvers["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"]?.ifEmpty { null },
                                resolverConfidences = trackResolverConfidences["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"],
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
private fun TopAlbumsTab(
    albums: Resource<List<HistoryAlbum>>,
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    onAlbumClick: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    onQueueAlbum: (title: String, artist: String) -> Unit = { _, _ -> },
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
            is Resource.Error -> ErrorState(message = albums.message, onRetry = { onPeriodChanged(selectedPeriod) })
            is Resource.Success -> {
                if (albums.data.isEmpty()) {
                    EmptyState("No album data yet")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(albums.data.size, key = { "${albums.data[it].rank}-${albums.data[it].name}-${albums.data[it].artist}" }) { index ->
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
                                    onQueueAlbum = {
                                        showMenu = false
                                        onQueueAlbum(album.name, album.artist)
                                    },
                                    onGoToAlbum = {
                                        showMenu = false
                                        onAlbumClick(album.name, album.artist)
                                    },
                                    onGoToArtist = {
                                        showMenu = false
                                        onNavigateToArtist(album.artist)
                                    },
                                    onToggleCollection = {
                                        showMenu = false
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
private fun TopArtistsTab(
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
                // Simple loading placeholder
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
            is Resource.Error -> ErrorState(message = artists.message, onRetry = { onPeriodChanged(selectedPeriod) })
            is Resource.Success -> {
                if (artists.data.isEmpty()) {
                    EmptyState("No artist data yet")
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
            cornerRadius = 48.dp, // circular
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

// ---------- Recently Played Tab ----------

@Composable
private fun RecentlyPlayedTab(
    recentTracks: Resource<List<RecentTrack>>,
    sort: String,
    search: String,
    onSortChanged: (String) -> Unit,
    onSearchChanged: (String) -> Unit,
    onPlayTrack: (Int) -> Unit,
    trackResolvers: Map<String, List<String>> = emptyMap(),
    trackResolverConfidences: Map<String, Map<String, Float>> = emptyMap(),
    trackArtwork: Map<String, String> = emptyMap(),
) {
    Column(modifier = Modifier.fillMaxSize()) {
        when (recentTracks) {
            is Resource.Loading -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(10) { ShimmerTrackRow(modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            }
            is Resource.Error -> ErrorState(message = recentTracks.message, onRetry = {})
            is Resource.Success -> {
                if (recentTracks.data.isEmpty() && search.isBlank()) {
                    EmptyState("No recent listening history")
                } else {
                    // Sort + filter bar (matching Collection tab pattern)
                    CollectionFilterBar(
                        sortLabel = sortOptions.firstOrNull { it.key == sort }?.label ?: "Recent",
                        sortOptions = sortOptions.map { option ->
                            option.label to { onSortChanged(option.key) }
                        },
                        selectedSortLabel = sortOptions.firstOrNull { it.key == sort }?.label ?: "Recent",
                        searchQuery = search,
                        onSearchQueryChange = onSearchChanged,
                        onClearSearch = { onSearchChanged("") },
                    )

                    if (recentTracks.data.isEmpty()) {
                        EmptyState("No matching tracks")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(recentTracks.data.size) { index ->
                                RecentTrackRow(
                                    track = recentTracks.data[index],
                                    resolvers = trackResolvers["${recentTracks.data[index].title.lowercase().trim()}|${recentTracks.data[index].artist.lowercase().trim()}"]?.ifEmpty { null },
                                    resolverConfidences = trackResolverConfidences["${recentTracks.data[index].title.lowercase().trim()}|${recentTracks.data[index].artist.lowercase().trim()}"],
                                    artworkUrl = recentTracks.data[index].let { it.artworkUrl ?: trackArtwork[trackKey(it.title, it.artist)] },
                                    onClick = { onPlayTrack(index) },
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
            Row {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (track.source.isNotBlank()) {
                    Text(
                        text = "  •  ${track.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
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

// ---------- Shared States ----------

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
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
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) {
                Text("Try Again")
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
        )
    }
}

// ---------- Helpers ----------

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
