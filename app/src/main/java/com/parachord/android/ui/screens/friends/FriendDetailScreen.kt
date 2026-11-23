package com.parachord.android.ui.screens.friends

import com.parachord.android.ui.components.adaptiveGridCells
import com.parachord.android.ui.components.ResolveWhileVisible
import com.parachord.android.ui.components.rememberPlayFromSource
import kotlinx.coroutines.Job
import com.parachord.shared.format.playCountLabel
import com.parachord.shared.format.RelativeTime
import com.parachord.shared.ui.UiMessages
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import com.parachord.android.ui.theme.FillAccent
import com.parachord.shared.playback.resolverDisplayName
import com.parachord.shared.recommendations.RecommendationCopy
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
import com.parachord.android.ui.components.ParachordAlbumCard
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.ResolverIconRow
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.FilterChips
import com.parachord.android.ui.components.FilterChipItem

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
    val isFollowed by viewModel.isFollowed.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.messages.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    }

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
                if (currentFriend != null && isFollowed) {
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
                com.parachord.android.ui.components.FriendAvatar(
                    currentFriend.displayName, currentFriend.avatarUrl,
                    size = com.parachord.shared.ui.FriendAvatarSpec.Size.LARGE,
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
                Text(
                    text = "Listening activity from ${resolverDisplayName(currentFriend.service)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (!isFollowed) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.follow() },
                        colors = ButtonDefaults.buttonColors(containerColor = FillAccent, contentColor = Color.White),
                    ) {
                        Text(RecommendationCopy.FOLLOW)
                    }
                }
            }
        }

        // Tabs
        SwipeableTabLayout(tabs = friendDetailTabs, initialPage = viewModel.initialPage) { page ->
            when (page) {
                0 -> FriendRecentTab(
                    recentTracks = recentTracks,
                    hiddenMessage = friend?.takeIf { it.hidesListening }?.let {
                        UiMessages.friendHidesListening(it.displayName)
                    },
                    onPlayTrack = viewModel::playRecentTrack,
                    onTrackVisible = viewModel::resolveVisible,
                    onRetry = { viewModel.retryTab(0) },
                    service = currentFriend?.service,
                    trackResolvers = trackResolvers,
                    trackResolverConfidences = trackResolverConfidences,
                    trackArtwork = trackArtwork,
                )
                1 -> FriendTopSongsTab(
                    tracks = topTracks,
                    service = currentFriend?.service,
                    onRetry = { viewModel.retryTab(1) },
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onPlayTrack = viewModel::playTopTrack,
                    onTrackVisible = viewModel::resolveVisible,
                    trackResolvers = trackResolvers,
                    trackResolverConfidences = trackResolverConfidences,
                    trackArtwork = trackArtwork,
                )
                2 -> FriendTopAlbumsTab(
                    albums = topAlbums,
                    service = currentFriend?.service,
                    onRetry = { viewModel.retryTab(2) },
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onAlbumClick = onNavigateToAlbum,
                    onNavigateToArtist = onNavigateToArtist,
                    onPlayAlbum = viewModel::playAlbumByName,
                    onQueueAlbum = viewModel::queueAlbumByName,
                    onAddAlbumToCollection = viewModel::addAlbumToCollection,
                )
                3 -> FriendTopArtistsTab(
                    artists = topArtists,
                    service = currentFriend?.service,
                    onRetry = { viewModel.retryTab(3) },
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
    FilterChips(
        chips = periodOptions.map { FilterChipItem(it.key, it.label) },
        selectedId = selectedPeriod,
        onSelect = onPeriodChanged,
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

// ---------- Recent Tab ----------

@Composable
private fun FriendRecentTab(
    recentTracks: Resource<List<RecentTrack>>,
    hiddenMessage: String? = null,
    onPlayTrack: (Int) -> Unit,
    onTrackVisible: (title: String, artist: String) -> Job? = { _, _ -> null },
    onRetry: () -> Unit = {},
    service: String? = null,
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
        is Resource.Error -> ErrorState(service, onRetry)
        is Resource.Success -> {
            if (recentTracks.data.isEmpty()) {
                EmptyState(hiddenMessage ?: UiMessages.FRIEND_RECENT_EMPTY)
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(recentTracks.data.size) { index ->
                        val track = recentTracks.data[index]
                        val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                        ResolveWhileVisible(key) { onTrackVisible(track.title, track.artist) }
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
    // The shared row (#760), with History's "2h ago" as its extra column.
    TrackRow(
        title = track.title,
        artist = track.artist,
        artworkUrl = artworkUrl,
        album = track.album,
        resolvers = resolvers,
        resolverConfidences = resolverConfidences,
        isPlaying = track.nowPlaying,
        subtitle = null,
        cell = if (track.nowPlaying) "Now" else RelativeTime.agoSeconds(track.timestamp),
        cellAccent = track.nowPlaying,
        onClick = onClick,
        onPlaySource = rememberPlayFromSource()(track.title, track.artist, onClick),
    )
}

// ---------- Top Songs Tab ----------

@Composable
private fun FriendTopSongsTab(
    tracks: Resource<List<HistoryTrack>>,
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onTrackVisible: (title: String, artist: String) -> Job? = { _, _ -> null },
    service: String? = null,
    onRetry: () -> Unit = {},
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
            is Resource.Error -> ErrorState(service, onRetry)
            is Resource.Success -> {
                if (tracks.data.isEmpty()) {
                    EmptyState(UiMessages.FRIEND_TRACKS_EMPTY)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(tracks.data.size) { index ->
                            val track = tracks.data[index]
                            val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                            ResolveWhileVisible(key) { onTrackVisible(track.title, track.artist) }
                            TrackRow(
                                title = track.title,
                                artist = "${track.artist}  •  ${playCountLabel(track.playCount)}",
                                artworkUrl = track.artworkUrl ?: trackArtwork[key],
                                trackNumber = track.rank,
                                resolvers = trackResolvers[key],
                                resolverConfidences = trackResolverConfidences[key],
                                onClick = { onPlayTrack(index) },
                                onPlaySource = rememberPlayFromSource()(track.title, "${track.artist}  •  ${playCountLabel(track.playCount)}") { onPlayTrack(index) },
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
    onPlayAlbum: (albumTitle: String, albumArtist: String) -> Unit = { _, _ -> },
    onQueueAlbum: (albumTitle: String, albumArtist: String) -> Unit = { _, _ -> },
    onAddAlbumToCollection: (title: String, artist: String, artworkUrl: String?) -> Unit = { _, _, _ -> },
    service: String? = null,
    onRetry: () -> Unit = {},
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
            is Resource.Error -> ErrorState(service, onRetry)
            is Resource.Success -> {
                if (albums.data.isEmpty()) {
                    EmptyState(UiMessages.FRIEND_ALBUMS_EMPTY)
                } else {
                    LazyVerticalGrid(
                        columns = adaptiveGridCells(),
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
                                    onPlayAlbum = {
                                        showMenu = false
                                        onPlayAlbum(album.name, album.artist)
                                    },
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
    ParachordAlbumCard(
        title = album.name,
        artist = album.artist,
        artworkUrl = album.artworkUrl,
        width = null,
        onOpen = onClick,
        onLongPress = onLongClick,
        subtitle = playCountLabel(album.playCount),
        rank = album.rank,
    )
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
    service: String? = null,
    onRetry: () -> Unit = {},
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
            is Resource.Error -> ErrorState(service, onRetry)
            is Resource.Success -> {
                if (artists.data.isEmpty()) {
                    EmptyState(UiMessages.FRIEND_ARTISTS_EMPTY)
                } else {
                    LazyVerticalGrid(
                        columns = adaptiveGridCells(),
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

    com.parachord.android.ui.components.ParachordArtistCard(
        name = artist.name,
        imageUrl = artist.imageUrl,
        width = null,
        onOpen = onClick,
        onLongPress = { showMenu = true },
        subtitle = playCountLabel(artist.playCount),
        rank = artist.rank,
    )

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
private fun ErrorState(service: String?, onRetry: (() -> Unit)? = null) {
    val message = com.parachord.shared.ui.UiMessages.couldntReach(service)
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
                    Text(RecommendationCopy.TRY_AGAIN)
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

