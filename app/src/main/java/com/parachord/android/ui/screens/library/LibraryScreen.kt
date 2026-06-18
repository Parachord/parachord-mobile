package com.parachord.android.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.hapticCombinedClickable
import com.parachord.android.ui.theme.ParachordTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ContextMenuItem
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalBgDarker
import com.parachord.android.ui.components.ModalDivider
import com.parachord.android.ui.components.ModalTextPrimary
import com.parachord.android.ui.components.ModalTextActive
import com.parachord.android.ui.components.ModalScrim
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState
import com.parachord.android.ui.screens.friends.FriendsViewModel
import com.parachord.android.ui.screens.sync.SyncSetupSheet

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToFriend: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    initialTab: Int = 0,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = koinViewModel(),
    friendsViewModel: FriendsViewModel = koinViewModel(),
) {
    val shareAlbumLite = com.parachord.android.share.rememberShareAlbumLite()
    val shareArtist = com.parachord.android.share.rememberShareArtist()
    val sortedArtists by viewModel.sortedArtists.collectAsStateWithLifecycle()
    val sortedAlbums by viewModel.sortedAlbums.collectAsStateWithLifecycle()
    val sortedTracks by viewModel.sortedTracks.collectAsStateWithLifecycle()
    val rawArtists by viewModel.artists.collectAsStateWithLifecycle()
    val rawTracks by viewModel.tracks.collectAsStateWithLifecycle()
    val rawAlbums by viewModel.albums.collectAsStateWithLifecycle()
    val friends by friendsViewModel.friends.collectAsState()

    val artistSort by viewModel.artistSort.collectAsStateWithLifecycle()
    val albumSort by viewModel.albumSort.collectAsStateWithLifecycle()
    val trackSort by viewModel.trackSort.collectAsStateWithLifecycle()
    val friendSort by viewModel.friendSort.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var showSyncSheet by remember { mutableStateOf(false) }
    /** Which provider's wizard to open. Set when the user picks from the
     *  provider menu below. Default Spotify for back-compat with the
     *  previous single-provider entry. */
    var syncSheetProviderId by remember { mutableStateOf("spotify") }
    /** When true, render a small bottom sheet that lets the user pick
     *  which provider's sync wizard to open. The library top-bar sync
     *  icon now opens this picker instead of going straight into Spotify
     *  — otherwise users with both Spotify + AM connected can't reach
     *  the AM wizard from the Collection screen. */
    var showSyncProviderPicker by remember { mutableStateOf(false) }
    val contextMenuState = rememberTrackContextMenuState()
    val allPlaylists by viewModel.playlists.collectAsStateWithLifecycle()
    val resolverOrder by viewModel.resolverOrder.collectAsStateWithLifecycle()
    val trackResolvers by viewModel.trackResolvers.collectAsStateWithLifecycle()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsStateWithLifecycle()
    val resolvingKeys by viewModel.resolvingKeys.collectAsStateWithLifecycle()
    val listenBrainzConnected by viewModel.listenBrainzConnected.collectAsStateWithLifecycle()

    if (showSyncSheet) {
        SyncSetupSheet(
            onDismiss = { showSyncSheet = false },
            providerId = syncSheetProviderId,
        )
    }

    if (showSyncProviderPicker) {
        SyncProviderPickerSheet(
            onDismiss = { showSyncProviderPicker = false },
            showListenBrainz = listenBrainzConnected,
            onPick = { providerId ->
                syncSheetProviderId = providerId
                showSyncProviderPicker = false
                showSyncSheet = true
            },
        )
    }

    // Track context menu host
    TrackContextMenuHost(
        state = contextMenuState,
        playlists = allPlaylists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { playlist, track -> viewModel.addToPlaylist(playlist, track) },
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onToggleCollection = { track, isInCollection ->
            if (isInCollection) viewModel.removeTrackFromCollection(track)
        },
    )

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "COLLECTION",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu")
                }
            },
            actions = {
                IconButton(onClick = { showSyncProviderPicker = true }) {
                    Icon(Icons.Default.Sync, contentDescription = "Sync")
                }
            },
            windowInsets = WindowInsets(0),
        )
        SwipeableTabLayout(
            tabs = listOf("Artists", "Albums", "Songs", "Friends"),
            counts = listOf(rawArtists.size, rawAlbums.size, rawTracks.size, friends.size),
            initialPage = initialTab,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> {
                    val artistGridState = rememberLazyGridState()
                    val artistScope = rememberCoroutineScope()

                    // Scroll to top when sort or search changes
                    LaunchedEffect(artistSort, searchQuery) {
                        artistGridState.scrollToItem(0)
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        CollectionFilterBar(
                            sortLabel = artistSort.label,
                            sortOptions = ArtistSort.entries.map { sort ->
                                sort.label to {
                                    viewModel.setArtistSort(sort)
                                    artistScope.launch { artistGridState.scrollToItem(0) }
                                }
                            },
                            selectedSortLabel = artistSort.label,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.setSearchQuery(it) },
                            onClearSearch = { viewModel.setSearchQuery("") },
                        )
                        if (sortedArtists.isEmpty()) {
                            EmptyState("No artists yet", Icons.Default.Person, onSyncClick = { showSyncProviderPicker = true })
                        } else {
                            LazyVerticalGrid(
                                state = artistGridState,
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(sortedArtists, key = { it.id }) { artist ->
                                    // Trigger lazy image enrichment for artists with no image
                                    if (artist.imageUrl == null) {
                                        LaunchedEffect(artist.id) {
                                            viewModel.enrichArtistImageIfNeeded(artist.name)
                                        }
                                    }

                                    var showMenu by remember { mutableStateOf(false) }

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .hapticCombinedClickable(
                                                onClick = { onNavigateToArtist(artist.name) },
                                                onLongClick = { showMenu = true },
                                            ),
                                    ) {
                                        AlbumArtCard(
                                            artworkUrl = artist.imageUrl,
                                            size = 96.dp,
                                            cornerRadius = 48.dp,
                                            placeholderName = artist.name,
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
                                    }

                                    if (showMenu) {
                                        com.parachord.android.ui.components.ArtistContextMenu(
                                            artistName = artist.name,
                                            imageUrl = artist.imageUrl,
                                            isInCollection = true,
                                            onDismiss = { showMenu = false },
                                            onPlayTopSongs = {
                                                viewModel.playArtistTopSongs(artist.name)
                                            },
                                            onQueueTopSongs = {
                                                viewModel.queueArtistTopSongs(artist.name)
                                            },
                                            onGoToArtist = {
                                                onNavigateToArtist(artist.name)
                                            },
                                            onToggleCollection = {
                                                viewModel.removeArtistFromCollection(artist)
                                            },
                                            onShare = { shareArtist(artist.name, artist.imageUrl) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    val albumGridState = rememberLazyGridState()
                    val albumScope = rememberCoroutineScope()

                    LaunchedEffect(albumSort, searchQuery) {
                        albumGridState.scrollToItem(0)
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        CollectionFilterBar(
                            sortLabel = albumSort.label,
                            sortOptions = AlbumSort.entries.map { sort ->
                                sort.label to {
                                    viewModel.setAlbumSort(sort)
                                    albumScope.launch { albumGridState.scrollToItem(0) }
                                }
                            },
                            selectedSortLabel = albumSort.label,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.setSearchQuery(it) },
                            onClearSearch = { viewModel.setSearchQuery("") },
                        )
                        if (sortedAlbums.isEmpty()) {
                            EmptyState("No albums yet", Icons.Default.MusicNote, onSyncClick = { showSyncProviderPicker = true })
                        } else {
                            LazyVerticalGrid(
                                state = albumGridState,
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(sortedAlbums, key = { it.id }) { album ->
                                    // Trigger lazy artwork enrichment for albums with no artwork
                                    if (album.artworkUrl == null) {
                                        LaunchedEffect(album.id) {
                                            viewModel.enrichAlbumArtIfNeeded(album.title, album.artist)
                                        }
                                    }

                                    var showMenu by remember { mutableStateOf(false) }

                                    Column(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .hapticCombinedClickable(
                                                onClick = { onNavigateToAlbum(album.title, album.artist) },
                                                onLongClick = { showMenu = true },
                                            ),
                                    ) {
                                        AlbumArtCard(
                                            artworkUrl = album.artworkUrl,
                                            size = 180.dp,
                                            cornerRadius = 8.dp,
                                            placeholderName = album.title,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = album.title,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.Medium,
                                            ),
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

                                    if (showMenu) {
                                        com.parachord.android.ui.components.AlbumContextMenu(
                                            albumTitle = album.title,
                                            artistName = album.artist,
                                            artworkUrl = album.artworkUrl,
                                            isInCollection = true,
                                            onDismiss = { showMenu = false },
                                            onPlayAlbum = {
                                                showMenu = false
                                                viewModel.playAlbum(album.id, album.title)
                                            },
                                            onQueueAlbum = {
                                                showMenu = false
                                                viewModel.queueAlbum(album.id)
                                            },
                                            onGoToAlbum = {
                                                showMenu = false
                                                onNavigateToAlbum(album.title, album.artist)
                                            },
                                            onGoToArtist = {
                                                showMenu = false
                                                onNavigateToArtist(album.artist)
                                            },
                                            onToggleCollection = {
                                                showMenu = false
                                                viewModel.removeAlbumFromCollection(album)
                                            },
                                            onShare = { shareAlbumLite(album.title, album.artist, album.artworkUrl) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    val trackListState = rememberLazyListState()
                    val trackScope = rememberCoroutineScope()

                    LaunchedEffect(trackSort, searchQuery) {
                        trackListState.scrollToItem(0)
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        CollectionFilterBar(
                            sortLabel = trackSort.label,
                            sortOptions = TrackSort.entries.map { sort ->
                                sort.label to {
                                    viewModel.setTrackSort(sort)
                                    trackScope.launch { trackListState.scrollToItem(0) }
                                }
                            },
                            selectedSortLabel = trackSort.label,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.setSearchQuery(it) },
                            onClearSearch = { viewModel.setSearchQuery("") },
                        )
                        if (sortedTracks.isEmpty()) {
                            EmptyState("No songs yet", Icons.Default.MusicNote, onSyncClick = { showSyncProviderPicker = true })
                        } else {
                            LazyColumn(state = trackListState, modifier = Modifier.fillMaxSize()) {
                                items(sortedTracks, key = { it.id }) { track ->
                                    TrackRow(
                                        title = track.title,
                                        artist = track.artist,
                                        artworkUrl = track.artworkUrl,
                                        resolvers = trackResolvers["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"]
                                            ?: track.availableResolvers(resolverOrder),
                                        resolverConfidences = trackResolverConfidences["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"],
                                        resolving = resolvingKeys.contains("${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"),
                                        duration = track.duration,
                                        onClick = { viewModel.playTrack(track) },
                                        onLongClick = {
                                            contextMenuState.show(
                                                TrackContextInfo(
                                                    title = track.title,
                                                    artist = track.artist,
                                                    album = track.album,
                                                    artworkUrl = track.artworkUrl,
                                                    duration = track.duration,
                                                    isInCollection = true,
                                                ),
                                                track,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                3 -> {
                    FriendsTab(
                        friends = friends,
                        friendSort = friendSort,
                        searchQuery = searchQuery,
                        onSortChange = { viewModel.setFriendSort(it) },
                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
                        onClearSearch = { viewModel.setSearchQuery("") },
                        onNavigateToFriend = onNavigateToFriend,
                        onRemoveFriend = { friendsViewModel.removeFriend(it) },
                        onPinFriend = { friendsViewModel.pinFriend(it) },
                        onListenAlong = { /* TODO: wire up listen-along */ },
                    )
                }
            }
        }
    }
}

private val OnAirGreen = Color(0xFF22C55E)
private val LastFmRed = Color(0xFFD51007)
private val ListenBrainzOrange = Color(0xFFE8702A)
private val MiniPlaybarBgLight = Color(0xF21F2937)
private val MiniPlaybarBgDark = Color(0xF2262626)

// Uses ModalBg, ModalBgDarker, ModalDivider from TrackContextMenu.kt

/** Hexagonal clip path matching the desktop's friend avatar treatment. */
private val HexagonShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, 0f)
            lineTo(w, h * 0.25f)
            lineTo(w, h * 0.75f)
            lineTo(w * 0.5f, h)
            lineTo(0f, h * 0.75f)
            lineTo(0f, h * 0.25f)
            close()
        }
        return Outline.Generic(path)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FriendsTab(
    friends: List<FriendEntity>,
    friendSort: FriendSort,
    searchQuery: String,
    onSortChange: (FriendSort) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateToFriend: (String) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onPinFriend: (String) -> Unit,
    onListenAlong: (FriendEntity) -> Unit,
) {
    val sortedFriends = remember(friends, friendSort, searchQuery) {
        val filtered = if (searchQuery.isBlank()) friends else {
            friends.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
        }
        when (friendSort) {
            FriendSort.ALPHA_ASC -> filtered.sortedBy { it.displayName.lowercase() }
            FriendSort.ALPHA_DESC -> filtered.sortedByDescending { it.displayName.lowercase() }
            FriendSort.RECENT -> filtered.sortedByDescending { it.addedAt }
            FriendSort.ACTIVE -> {
                val fourteenDaysAgo = System.currentTimeMillis() / 1000 - 14 * 86400
                filtered.filter { it.cachedTrackTimestamp > fourteenDaysAgo }
                    .sortedByDescending { it.cachedTrackTimestamp }
            }
            FriendSort.ON_AIR -> filtered.filter { it.isOnAir }
                .sortedByDescending { it.cachedTrackTimestamp }
        }
    }

    val friendListState = rememberLazyListState()
    val friendScope = rememberCoroutineScope()

    LaunchedEffect(friendSort, searchQuery) {
        friendListState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CollectionFilterBar(
            sortLabel = friendSort.label,
            sortOptions = FriendSort.entries.map { sort ->
                sort.label to {
                    onSortChange(sort)
                    friendScope.launch { friendListState.scrollToItem(0) }
                }
            },
            selectedSortLabel = friendSort.label,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onClearSearch = onClearSearch,
        )
        var pendingDeleteFriend by remember { mutableStateOf<FriendEntity?>(null) }

        pendingDeleteFriend?.let { f ->
            AlertDialog(
                onDismissRequest = { pendingDeleteFriend = null },
                containerColor = ModalBg,
                titleContentColor = ModalTextActive,
                textContentColor = ModalTextPrimary,
                title = { Text("Remove Friend") },
                text = { Text("Are you sure you want to remove ${f.displayName}?") },
                confirmButton = {
                    TextButton(onClick = {
                        onRemoveFriend(f.id)
                        pendingDeleteFriend = null
                    }) {
                        Text("Remove", color = Color(0xFFEF4444))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteFriend = null }) {
                        Text("Cancel", color = ModalTextPrimary)
                    }
                },
            )
        }

        if (sortedFriends.isEmpty()) {
            EmptyState("No friends yet", Icons.Default.People)
        } else {
            LazyColumn(state = friendListState, modifier = Modifier.fillMaxSize()) {
                items(sortedFriends, key = { it.id }) { friend ->
                    var showMenu by remember { mutableStateOf(false) }
                    val surfaceColor = MaterialTheme.colorScheme.surface

                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(surfaceColor)
                                .hapticCombinedClickable(
                                    onClick = { onNavigateToFriend(friend.id) },
                                    onLongClick = { showMenu = true },
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // Hexagonal avatar with on-air indicator
                            Box(modifier = Modifier.padding(top = 2.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(HexagonShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (!friend.avatarUrl.isNullOrBlank()) {
                                        SubcomposeAsyncImage(
                                            model = friend.avatarUrl,
                                            contentDescription = friend.displayName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(48.dp),
                                            loading = { FriendHexFallback(friend.displayName) },
                                            error = { FriendHexFallback(friend.displayName) },
                                        )
                                    } else {
                                        FriendHexFallback(friend.displayName)
                                    }
                                }
                                if (friend.isOnAir) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 1.dp, y = 1.dp)
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(surfaceColor)
                                            .padding(2.dp)
                                            .clip(CircleShape)
                                            .background(OnAirGreen),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = friend.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    // Service badge
                                    val (badgeText, badgeColor) = when (friend.service) {
                                        "lastfm" -> "Last.fm" to LastFmRed
                                        "listenbrainz" -> "LB" to ListenBrainzOrange
                                        else -> friend.service to MaterialTheme.colorScheme.outline
                                    }
                                    Text(
                                        text = badgeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeColor,
                                        modifier = Modifier
                                            .background(
                                                color = badgeColor.copy(alpha = 0.18f),
                                                shape = RoundedCornerShape(4.dp),
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }

                                // On-air: mini playbar pill. Offline: plain muted text.
                                if (friend.isOnAir && friend.cachedTrackName != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    FriendListMiniPlaybar(
                                        trackName = friend.cachedTrackName!!,
                                        artistName = friend.cachedTrackArtist,
                                        artworkUrl = friend.cachedTrackArtworkUrl,
                                    )
                                } else if (friend.cachedTrackName != null) {
                                    Text(
                                        text = buildString {
                                            append(friend.cachedTrackName)
                                            friend.cachedTrackArtist?.let { append("  ·  $it") }
                                            if (friend.cachedTrackTimestamp > 0) {
                                                append("  ·  ")
                                                append(formatFriendTimeAgo(friend.cachedTrackTimestamp))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        // Always-dark context menu bottom sheet
                        if (showMenu) {
                            ModalBottomSheet(
                                onDismissRequest = { showMenu = false },
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
                                    ContextMenuItem(
                                        icon = Icons.Filled.Person,
                                        label = "View Profile",
                                        onClick = {
                                            showMenu = false
                                            onNavigateToFriend(friend.id)
                                        },
                                    )
                                    if (friend.isOnAir && friend.cachedTrackName != null) {
                                        ContextMenuItem(
                                            icon = Icons.Filled.Headphones,
                                            label = "Listen Along",
                                            onClick = {
                                                showMenu = false
                                                onListenAlong(friend)
                                            },
                                        )
                                    }
                                    if (!friend.pinnedToSidebar) {
                                        ContextMenuItem(
                                            icon = Icons.Filled.PushPin,
                                            label = "Pin to Sidebar",
                                            onClick = {
                                                showMenu = false
                                                onPinFriend(friend.id)
                                            },
                                        )
                                    }
                                    HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                                    ContextMenuItem(
                                        icon = Icons.Filled.PersonRemove,
                                        label = "Remove Friend",
                                        onClick = {
                                            showMenu = false
                                            pendingDeleteFriend = friend
                                        },
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

/** Mini playbar pill for on-air friends — only shown when friend is currently playing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendListMiniPlaybar(
    trackName: String,
    artistName: String?,
    artworkUrl: String?,
) {
    val isDark = ParachordTheme.isDark
    val pillBg = if (isDark) MiniPlaybarBgDark else MiniPlaybarBgLight
    val pillShape = RoundedCornerShape(4.dp)
    val artistTextColor = Color(0xFFD1D5DB)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(pillShape)
            .background(pillBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mini album art
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (!artworkUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(24.dp),
                    loading = { FriendMiniArtFallback() },
                    error = { FriendMiniArtFallback() },
                )
            } else {
                FriendMiniArtFallback()
            }
        }

        // Track info — marquee scroll matching sidebar & homepage
        Text(
            text = buildString {
                append(trackName)
                artistName?.let { append("  ·  $it") }
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 24.sp,
            color = artistTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1000,
                    velocity = 30.dp,
                ),
        )

        // On-air green dot
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(OnAirGreen),
        )
    }
}

@Composable
private fun FriendMiniArtFallback() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(0xFF4B5563)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color(0xFF9CA3AF),
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun FriendHexFallback(name: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(Color(0xFFA78BFA), Color(0xFFF472B6)),
                    start = Offset.Zero,
                    end = Offset(48f, 48f),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

private fun formatFriendTimeAgo(timestampSeconds: Long): String {
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

@Composable
private fun EmptyState(
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSyncClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onSyncClick != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onSyncClick) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync now")
                }
            }
        }
    }
}

/**
 * Small bottom sheet asking which provider's sync wizard to open.
 * Shown when the library top-bar sync icon (or any Collection
 * empty-state "Sync now" button) is tapped. Lists every provider
 * we have UI affordances for; the wizard itself handles the case
 * where the chosen provider isn't authorized yet.
 *
 * Replaces the old behavior where the same button always opened the
 * Spotify wizard — users with both Spotify + AM connected couldn't
 * reach the AM wizard from the Collection screen otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncProviderPickerSheet(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    showListenBrainz: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                "Choose a sync provider",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick which library you want to configure. You can change " +
                    "what's synced (songs, albums, artists, playlists) on the " +
                    "next screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            ProviderPickerRow(
                label = "Spotify",
                accent = Color(0xFF1DB954),
                onClick = { onPick("spotify") },
            )
            Spacer(Modifier.height(8.dp))
            ProviderPickerRow(
                label = "Apple Music",
                accent = Color(0xFFFA243C),
                onClick = { onPick("applemusic") },
            )
            if (showListenBrainz) {
                Spacer(Modifier.height(8.dp))
                ProviderPickerRow(
                    label = "ListenBrainz",
                    accent = ListenBrainzOrange,
                    onClick = { onPick("listenbrainz") },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProviderPickerRow(
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent),
    ) {
        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Configure $label sync…")
    }
}

// Artist context menu is now the shared ArtistContextMenu from ui.components
// Album context menu is now the shared AlbumContextMenu from ui.components
