package com.parachord.android.ui.screens.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import com.parachord.android.ui.components.hapticClickable
import androidx.compose.foundation.combinedClickable
import com.parachord.android.ui.theme.ParachordTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.AnnouncementBanner
import com.parachord.android.ui.components.AppleMusicReauthBanner
import com.parachord.android.ui.components.SpotifyReauthBanner
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.parachord.android.share.rememberSharePlaylistById
import com.parachord.android.ui.components.AlbumContextMenu
import com.parachord.android.ui.components.ContextMenuItem
import com.parachord.android.ui.components.PlaylistContextMenu
import com.parachord.android.ui.components.hapticCombinedClickable
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalBgDarker
import com.parachord.android.ui.components.ModalDivider
import com.parachord.android.ui.components.ParachordCard
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.SpinningRefreshIcon
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState
import com.parachord.android.ai.AiAlbumSuggestion
import com.parachord.android.ai.AiArtistSuggestion
import com.parachord.android.ai.AiRecommendations
import com.parachord.android.data.repository.WeeklyPlaylistEntry
import com.parachord.android.playback.PlaybackState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToNowPlaying: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToPlaylist: (playlistId: String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToFriend: (String) -> Unit = {},
    onListenAlong: (FriendEntity) -> Unit = {},
    onNavigateToRecommendations: () -> Unit = {},
    onNavigateToCriticalDarlings: () -> Unit = {},
    onNavigateToPopOfTheTops: () -> Unit = {},
    onNavigateToFreshDrops: () -> Unit = {},
    onNavigateToCollection: (tab: Int) -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
    onNavigateToWeeklyPlaylist: (playlistId: String, contextType: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val shareAlbumLite = com.parachord.android.share.rememberShareAlbumLite()
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val hasLibrary by viewModel.hasLibrary.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val recentAlbums by viewModel.recentAlbums.collectAsStateWithLifecycle()
    val recentPlaylists by viewModel.recentPlaylists.collectAsStateWithLifecycle()
    val friendActivity by viewModel.friendActivity.collectAsStateWithLifecycle()
    val stats by viewModel.collectionStats.collectAsStateWithLifecycle()
    val forYouPreview by viewModel.forYouPreview.collectAsStateWithLifecycle()
    val criticalDarlingsPreview by viewModel.criticalDarlingsPreview.collectAsStateWithLifecycle()
    val freshDropsPreview by viewModel.freshDropsPreview.collectAsStateWithLifecycle()
    val popOfTheTopsPreview by viewModel.popOfTheTopsPreview.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val hasAiPlugins by viewModel.hasAiPlugins.collectAsStateWithLifecycle()
    val aiRecommendations by viewModel.aiRecommendations.collectAsStateWithLifecycle()
    val aiLoading by viewModel.aiLoading.collectAsStateWithLifecycle()
    val aiError by viewModel.aiError.collectAsStateWithLifecycle()
    val weeklyJams by viewModel.weeklyJams.collectAsStateWithLifecycle()
    val weeklyExploration by viewModel.weeklyExploration.collectAsStateWithLifecycle()
    val weeklyCovers by viewModel.weeklyCovers.collectAsStateWithLifecycle()
    val weeklyTrackCounts by viewModel.weeklyTrackCounts.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val resolverOrder by viewModel.resolverOrder.collectAsStateWithLifecycle()
    val trackResolvers by viewModel.trackResolvers.collectAsStateWithLifecycle()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()
    val sharePlaylistById = rememberSharePlaylistById()
    val context = LocalContext.current

    // Per-playlist Sync sheet (long-press "Sync…" in Your Playlists).
    var syncTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    syncTarget?.let { pl ->
        com.parachord.android.ui.components.PlaylistSyncChannelsSheet(
            playlistId = pl.id,
            playlistName = pl.name,
            onDismiss = { syncTarget = null },
        )
    }

    // Surface playlist-action toasts (e.g. "Apple Music doesn't allow
    // deletion via the API…" from `deletePlaylistWithSync` returning
    // `DeleteResult.Unsupported`). Same pattern as PlaylistsScreen.
    LaunchedEffect(viewModel) {
        viewModel.toastEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // Track context menu host (for Recent Loves long-press)
    TrackContextMenuHost(
        state = contextMenuState,
        playlists = playlists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { playlist, track -> viewModel.addToPlaylist(playlist, track) },
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        // Recent Loves passes `isInCollection = true` on every long-press
        // (everything in the section is, by definition, in the
        // collection), so the menu always shows "Remove from collection".
        // Without wiring this lambda the menu item rendered, the toast
        // fired ("Removed from collection"), but the actual delete was
        // a silent no-op — `onToggleCollection` on the host is optional.
        onToggleCollection = { track, isInCollection ->
            if (isInCollection) viewModel.removeTrackFromCollection(track)
        },
    )

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.scanLocalMusic()
    }

    // Show splash while hasLibrary is still loading (null)
    if (hasLibrary == null) {
        SplashScreen()
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "PARACHORD",
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
            windowInsets = WindowInsets(0),
        )

        if (hasLibrary == false) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                ParachordCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Welcome to Parachord",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your music, unified.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    if (scanProgress.isScanning) {
                        repeat(3) {
                            ShimmerTrackRow()
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Found ${scanProgress.tracksFound} tracks, ${scanProgress.albumsFound} albums...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(
                            onClick = { permissionLauncher.launch(audioPermission) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("Scan Local Music")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Or connect your accounts in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            // ── Achordion announcements banner (#127) ────────────────────
            val announcementsRepository: com.parachord.shared.repository.AnnouncementsRepository =
                org.koin.compose.koinInject()
            val announcements by announcementsRepository.visibleAnnouncements
                .collectAsStateWithLifecycle()
            val bannerScope = rememberCoroutineScope()
            val firstAnnouncement = announcements.firstOrNull()
            if (firstAnnouncement != null) {
                LaunchedEffect(firstAnnouncement.id) {
                    announcementsRepository.trackView(firstAnnouncement.id)
                }
            }

            // ── Spotify reauth banner ────────────────────────────────────
            val oAuthManager: com.parachord.android.auth.OAuthManager =
                org.koin.compose.koinInject()
            val spotifyReauthRequired by oAuthManager.spotifyReauthRequired
                .collectAsStateWithLifecycle()
            // ── Apple Music reauth banner (stale Music-User-Token) ───────
            val syncEngine: com.parachord.shared.sync.SyncEngine = org.koin.compose.koinInject()
            val musicKitBridge: com.parachord.android.playback.handlers.MusicKitWebBridge =
                org.koin.compose.koinInject()
            val appleMusicReauthRequired by syncEngine.appleMusicReauthRequired
                .collectAsStateWithLifecycle()
            val reauthScope = androidx.compose.runtime.rememberCoroutineScope()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (appleMusicReauthRequired) {
                    item(key = "applemusic-reauth-banner") {
                        AppleMusicReauthBanner(
                            onReconnect = {
                                reauthScope.launch {
                                    runCatching { musicKitBridge.authorize() }
                                    syncEngine.clearAppleMusicReauth()
                                }
                            },
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ),
                        )
                    }
                }
                if (spotifyReauthRequired) {
                    item(key = "spotify-reauth-banner") {
                        SpotifyReauthBanner(
                            onReconnect = {
                                oAuthManager.launchSpotifyAuth(
                                    com.parachord.android.BuildConfig.SPOTIFY_CLIENT_ID,
                                )
                            },
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ),
                        )
                    }
                }
                if (firstAnnouncement != null) {
                    item(key = "announcement-${firstAnnouncement.id}") {
                        AnnouncementBanner(
                            announcement = firstAnnouncement,
                            onDismiss = {
                                bannerScope.launch {
                                    announcementsRepository.dismiss(firstAnnouncement.id)
                                }
                            },
                            onCtaClick = { url ->
                                announcementsRepository.trackCtaClick(firstAnnouncement.id)
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    )
                                } catch (_: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Couldn't open the link",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ),
                        )
                    }
                }

                // ── Continue Listening ──────────────────────────────────
                if (playbackState.currentTrack != null) {
                    item {
                        ContinueListeningCard(
                            playbackState = playbackState,
                            onPlayPause = { viewModel.togglePlayPause() },
                            onClick = onNavigateToNowPlaying,
                        )
                    }
                }

                // ── Recently Added Albums ────────────────────────────────
                if (recentAlbums.isNotEmpty()) {
                    item { SectionHeader("Recently Added") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(recentAlbums, key = { it.id }) { album ->
                                var showAlbumMenu by remember { mutableStateOf(false) }
                                RecentAlbumCard(
                                    album = album,
                                    onClick = { onNavigateToAlbum(album.title, album.artist) },
                                    onLongClick = { showAlbumMenu = true },
                                )
                                if (showAlbumMenu) {
                                    AlbumContextMenu(
                                        albumTitle = album.title,
                                        artistName = album.artist,
                                        artworkUrl = album.artworkUrl,
                                        isInCollection = true,
                                        onDismiss = { showAlbumMenu = false },
                                        onQueueAlbum = {
                                            showAlbumMenu = false
                                            viewModel.queueAlbumByName(album.title, album.artist)
                                        },
                                        onGoToAlbum = {
                                            showAlbumMenu = false
                                            onNavigateToAlbum(album.title, album.artist)
                                        },
                                        onGoToArtist = {
                                            showAlbumMenu = false
                                            onNavigateToArtist(album.artist)
                                        },
                                        onToggleCollection = {
                                            showAlbumMenu = false
                                            viewModel.removeAlbumFromCollection(album.title, album.artist)
                                        },
                                        onShare = { shareAlbumLite(album.title, album.artist, album.artworkUrl) },
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── Your Playlists ───────────────────────────────────────
                if (recentPlaylists.isNotEmpty()) {
                    item { SectionHeader("Your Playlists") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(recentPlaylists, key = { it.id }) { playlist ->
                                PlaylistCard(
                                    playlist = playlist,
                                    onClick = { onNavigateToPlaylist(playlist.id) },
                                    onPlayPlaylist = {
                                        viewModel.playPlaylist(playlist.id, playlist.name)
                                    },
                                    onSharePlaylist = { sharePlaylistById(playlist.id) },
                                    onOpenSync = { syncTarget = playlist },
                                    onDeletePlaylist = { viewModel.deletePlaylist(playlist) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── Discover ─────────────────────────────────────────────
                item { SectionHeader("Discover") }
                item {
                    DiscoverGrid(
                        forYouPreview = forYouPreview,
                        criticalDarlingsPreview = criticalDarlingsPreview,
                        freshDropsPreview = freshDropsPreview,
                        popOfTheTopsPreview = popOfTheTopsPreview,
                        onNavigateToRecommendations = onNavigateToRecommendations,
                        onNavigateToCriticalDarlings = onNavigateToCriticalDarlings,
                        onNavigateToPopOfTheTops = onNavigateToPopOfTheTops,
                        onNavigateToFreshDrops = onNavigateToFreshDrops,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── Weekly Playlists (ListenBrainz) ─────────────────────
                if (weeklyJams != null || weeklyExploration != null) {
                    item {
                        WeeklyPlaylistsSection(
                            jams = weeklyJams,
                            exploration = weeklyExploration,
                            covers = weeklyCovers,
                            trackCounts = weeklyTrackCounts,
                            onOpenPlaylist = { entry, contextType ->
                                onNavigateToWeeklyPlaylist(entry.id, contextType)
                            },
                            onPlayWeekly = { entry, contextType ->
                                viewModel.playWeeklyPlaylist(entry, contextType)
                            },
                            onSaveWeekly = { entry, contextType ->
                                viewModel.saveWeeklyPlaylist(entry, contextType)
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── Friend Activity ──────────────────────────────────────
                if (friendActivity.isNotEmpty()) {
                    item { SectionHeader("Friend Activity") }
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            friendActivity.forEach { friend ->
                                FriendActivityRow(
                                    friend = friend,
                                    onClick = { onNavigateToFriend(friend.id) },
                                    onListenAlong = { onListenAlong(friend) },
                                    onTogglePin = { viewModel.togglePin(friend) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── AI Shuffleupagus ─────────────────────────────────────
                if (hasAiPlugins != null) {
                    if (hasAiPlugins == true) {
                        // AI plugins enabled — show suggestions or loading
                        item {
                            AiSuggestionsSection(
                                recommendations = aiRecommendations,
                                isLoading = aiLoading,
                                error = aiError,
                                onRefresh = { viewModel.refreshAiRecommendations() },
                                onAlbumClick = onNavigateToAlbum,
                                onArtistClick = onNavigateToArtist,
                                onAddAlbumToCollection = { title, artist, artworkUrl ->
                                    viewModel.addAlbumToCollection(title, artist, artworkUrl)
                                },
                                onQueueAlbum = { title, artist ->
                                    viewModel.queueAlbumByName(title, artist)
                                },
                                onGoToArtist = onNavigateToArtist,
                                onPlayArtistTopSongs = { viewModel.playArtistTopSongs(it) },
                                onQueueArtistTopSongs = { viewModel.queueArtistTopSongs(it) },
                                onToggleArtistCollection = { name, imageUrl, inCol ->
                                    viewModel.toggleArtistCollection(name, imageUrl, inCol)
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    } else {
                        // No AI plugins — show configure card
                        item {
                            NoAiPluginsCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                // ── Collection Stats ─────────────────────────────────────
                item { SectionHeader("Your Collection") }
                item {
                    StatsRow(
                        stats = stats,
                        onSongsClick = { onNavigateToCollection(2) },
                        onAlbumsClick = { onNavigateToCollection(1) },
                        onArtistsClick = { onNavigateToCollection(0) },
                        onPlaylistsClick = onNavigateToPlaylists,
                        onFriendsClick = { onNavigateToCollection(3) },
                    )
                }

                // ── Recent Loves ─────────────────────────────────────────
                if (recentTracks.isNotEmpty()) {
                    item { SectionHeader("Recent Loves") }
                    items(recentTracks.take(10), key = { it.id }) { track ->
                        TrackRow(
                            title = track.title,
                            artist = track.artist,
                            artworkUrl = track.artworkUrl,
                            resolvers = trackResolvers["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"]
                                ?: track.availableResolvers(resolverOrder),
                            resolverConfidences = trackResolverConfidences["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"],
                            duration = track.duration,
                            onClick = {
                                viewModel.playTrack(track)
                                onNavigateToNowPlaying()
                            },
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
}

// ── Recently Added Album Card ────────────────────────────────────

@Composable
private fun RecentAlbumCard(
    album: AlbumEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .hapticCombinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AlbumArtCard(
            artworkUrl = album.artworkUrl,
            size = 140.dp,
            cornerRadius = 8.dp,
            elevation = 2.dp,
            placeholderName = album.artist,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Playlist Card ────────────────────────────────────────────────

@Composable
private fun PlaylistCard(
    playlist: PlaylistEntity,
    onClick: () -> Unit,
    onPlayPlaylist: () -> Unit = {},
    onSharePlaylist: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onDeletePlaylist: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(130.dp)
            .hapticCombinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true },
            ),
    ) {
        AlbumArtCard(
            artworkUrl = playlist.artworkUrl,
            size = 130.dp,
            cornerRadius = 8.dp,
            elevation = 2.dp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${playlist.trackCount} tracks",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }

    if (showMenu) {
        PlaylistContextMenu(
            playlistName = playlist.name,
            artworkUrl = playlist.artworkUrl,
            trackCount = playlist.trackCount,
            onDismiss = { showMenu = false },
            onPlayPlaylist = onPlayPlaylist,
            onShare = onSharePlaylist,
            onOpenSync = onOpenSync,
            onDeletePlaylist = onDeletePlaylist,
        )
    }
}

// ── Discover Grid ────────────────────────────────────────────────

@Composable
private fun DiscoverGrid(
    forYouPreview: DiscoverPreview?,
    criticalDarlingsPreview: DiscoverPreview?,
    freshDropsPreview: DiscoverPreview?,
    popOfTheTopsPreview: DiscoverPreview?,
    onNavigateToRecommendations: () -> Unit,
    onNavigateToCriticalDarlings: () -> Unit,
    onNavigateToPopOfTheTops: () -> Unit,
    onNavigateToFreshDrops: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DiscoverCard(
                title = "For You",
                icon = Icons.Filled.Star,
                gradient = listOf(
                    Color(0xFF6366F1), // indigo
                    Color(0xFF8B5CF6), // purple
                    Color(0xFFEC4899), // pink
                ),
                preview = forYouPreview,
                fallbackSubtitle = "Personalized picks",
                onClick = onNavigateToRecommendations,
                modifier = Modifier.weight(1f),
            )
            DiscoverCard(
                title = "Critical Darlings",
                icon = Icons.Filled.Favorite,
                gradient = listOf(
                    Color(0xFFF59E0B), // amber
                    Color(0xFFF97316), // orange
                    Color(0xFFEF4444), // red
                ),
                preview = criticalDarlingsPreview,
                fallbackSubtitle = "Staff picks",
                onClick = onNavigateToCriticalDarlings,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DiscoverCard(
                title = "Pop of the Tops",
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                gradient = listOf(
                    Color(0xFFF97316), // orange
                    Color(0xFFEC4899), // pink
                    Color(0xFF8B5CF6), // purple
                ),
                preview = popOfTheTopsPreview,
                fallbackSubtitle = "Top charts",
                onClick = onNavigateToPopOfTheTops,
                modifier = Modifier.weight(1f),
            )
            DiscoverCard(
                title = "Fresh Drops",
                icon = Icons.Filled.Explore,
                gradient = listOf(
                    Color(0xFF10B981), // emerald
                    Color(0xFF14B8A6), // teal
                    Color(0xFF06B6D4), // cyan
                ),
                preview = freshDropsPreview,
                fallbackSubtitle = "New releases",
                onClick = onNavigateToFreshDrops,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DiscoverCard(
    title: String,
    icon: ImageVector,
    gradient: List<Color>,
    preview: DiscoverPreview?,
    fallbackSubtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(gradient))
            .hapticClickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Header: icon + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Preview content or fallback
            if (preview != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (preview.artworkUrl != null) {
                        AlbumArtCard(
                            artworkUrl = preview.artworkUrl,
                            size = 36.dp,
                            cornerRadius = 4.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preview.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 14.sp,
                        )
                        if (preview.subtitle.isNotBlank()) {
                            Text(
                                text = preview.subtitle,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 12.sp,
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = fallbackSubtitle,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Weekly Playlists Section ─────────────────────────────────────

@Composable
private fun WeeklyPlaylistsSection(
    jams: List<WeeklyPlaylistEntry>?,
    exploration: List<WeeklyPlaylistEntry>?,
    covers: Map<String, List<String>>,
    trackCounts: Map<String, Int>,
    onOpenPlaylist: (WeeklyPlaylistEntry, String) -> Unit,
    onPlayWeekly: (WeeklyPlaylistEntry, String) -> Unit,
    onSaveWeekly: (WeeklyPlaylistEntry, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!jams.isNullOrEmpty()) {
            WeeklyCarouselRow(
                title = "Weekly Jams",
                entries = jams,
                contextType = "weekly-jam",
                covers = covers,
                trackCounts = trackCounts,
                onOpen = onOpenPlaylist,
                onPlay = onPlayWeekly,
                onSave = onSaveWeekly,
            )
        }
        if (!exploration.isNullOrEmpty()) {
            WeeklyCarouselRow(
                title = "Weekly Exploration",
                entries = exploration,
                contextType = "weekly-exploration",
                covers = covers,
                trackCounts = trackCounts,
                onOpen = onOpenPlaylist,
                onPlay = onPlayWeekly,
                onSave = onSaveWeekly,
            )
        }
    }
}

@Composable
private fun WeeklyCarouselRow(
    title: String,
    entries: List<WeeklyPlaylistEntry>,
    contextType: String,
    covers: Map<String, List<String>>,
    trackCounts: Map<String, Int>,
    onOpen: (WeeklyPlaylistEntry, String) -> Unit,
    onPlay: (WeeklyPlaylistEntry, String) -> Unit,
    onSave: (WeeklyPlaylistEntry, String) -> Unit,
) {
    val isDark = ParachordTheme.isDark
    val badgeBg = if (isDark) Color(0xFFE8702A).copy(alpha = 0.20f) else Color(0xFFFFF3E0)
    val badgeText = if (isDark) Color(0xFFFB923C) else Color(0xFFE65100)

    Column {
        // Header with title + ListenBrainz badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ListenBrainz",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = badgeText,
                maxLines = 1,
                modifier = Modifier
                    .background(badgeBg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // Horizontal carousel of cards
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                WeeklyPlaylistCard(
                    entry = entry,
                    covers = covers[entry.id].orEmpty(),
                    trackCount = trackCounts[entry.id],
                    contextType = contextType,
                    onClick = { onOpen(entry, contextType) },
                    onPlay = { onPlay(entry, contextType) },
                    onSave = { onSave(entry, contextType) },
                    modifier = Modifier.width(140.dp),
                )
            }
        }
    }
}

@Composable
private fun WeeklyPlaylistCard(
    entry: WeeklyPlaylistEntry,
    covers: List<String>,
    trackCount: Int?,
    contextType: String,
    onClick: () -> Unit,
    onPlay: () -> Unit = {},
    onSave: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isDark = ParachordTheme.isDark
    val cardBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF3F4F6)
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .hapticCombinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true },
            ),
    ) {
        // 2x2 mosaic album art area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
        ) {
            if (covers.isNotEmpty()) {
                // 2x2 grid of cover art
                Column {
                    Row(modifier = Modifier.weight(1f)) {
                        covers.getOrNull(0)?.let { url ->
                            MosaicImage(url = url, modifier = Modifier.weight(1f).fillMaxSize())
                        } ?: MosaicPlaceholder(modifier = Modifier.weight(1f).fillMaxSize())
                        covers.getOrNull(1)?.let { url ->
                            MosaicImage(url = url, modifier = Modifier.weight(1f).fillMaxSize())
                        } ?: MosaicPlaceholder(modifier = Modifier.weight(1f).fillMaxSize())
                    }
                    Row(modifier = Modifier.weight(1f)) {
                        covers.getOrNull(2)?.let { url ->
                            MosaicImage(url = url, modifier = Modifier.weight(1f).fillMaxSize())
                        } ?: MosaicPlaceholder(modifier = Modifier.weight(1f).fillMaxSize())
                        covers.getOrNull(3)?.let { url ->
                            MosaicImage(url = url, modifier = Modifier.weight(1f).fillMaxSize())
                        } ?: MosaicPlaceholder(modifier = Modifier.weight(1f).fillMaxSize())
                    }
                }
            } else {
                // Shimmer / loading placeholder
                val shimmerAlpha = rememberShimmerAlpha()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shimmerAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

        }

        // Label area below the mosaic
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = entry.weekLabel,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (trackCount != null) "$trackCount tracks" else contextType.replace("-", " ")
                    .replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }

    if (showMenu) {
        // Ephemeral playlist — no Delete (it's not a saved row), no
        // Share yet (smart-link payload would need the full track list,
        // which is async — defer to the detail screen). Save uses the
        // first cover URL for the saved playlist's artwork; full mosaic
        // is rebuilt by `ImageEnrichmentService.regenerateAllPlaylistMosaics`
        // on next app start.
        val previewName = "${entry.weekLabel}'s ${
            if (contextType == "weekly-jam") "Weekly Jams" else "Weekly Exploration"
        }"
        PlaylistContextMenu(
            playlistName = previewName,
            artworkUrl = covers.firstOrNull(),
            trackCount = trackCount ?: 0,
            onDismiss = { showMenu = false },
            onPlayPlaylist = onPlay,
            onSavePlaylist = onSave,
        )
    }
}

@Composable
private fun MosaicImage(url: String, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        error = {
            MosaicPlaceholder(modifier = Modifier.fillMaxSize())
        },
    )
}

@Composable
private fun MosaicPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun rememberShimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    return alpha
}

// ── Friend Activity Row ──────────────────────────────────────────

private val OnAirGreen = Color(0xFF22C55E)
private val LastFmRed = Color(0xFFD51007)
private val ListenBrainzOrange = Color(0xFFE8702A)
private val HomeMiniPlaybarBg = Color(0xF2262626)

/** Hexagonal clip path matching the desktop's friend avatar treatment. */
private val HomeHexagonShape = object : Shape {
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FriendActivityRow(
    friend: FriendEntity,
    onClick: () -> Unit,
    onListenAlong: () -> Unit = {},
    onTogglePin: () -> Unit = {},
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    var showMenu by remember { mutableStateOf(false) }

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true },
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Hexagonal avatar with on-air indicator
        Box(modifier = Modifier.padding(top = 2.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(HomeHexagonShape),
                contentAlignment = Alignment.Center,
            ) {
                if (!friend.avatarUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = friend.avatarUrl,
                        contentDescription = friend.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp),
                        loading = { HomeHexFallback(friend.displayName) },
                        error = { HomeHexFallback(friend.displayName) },
                    )
                } else {
                    HomeHexFallback(friend.displayName)
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
                    style = MaterialTheme.typography.bodyMedium,
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
                HomeMiniPlaybar(
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
                            append(formatTimeAgo(friend.cachedTrackTimestamp))
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

    // Always-dark context menu bottom sheet (matches sidebar friend menu)
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
                if (friend.isOnAir && friend.cachedTrackName != null) {
                    ContextMenuItem(
                        icon = Icons.Filled.Headphones,
                        label = "Listen Along",
                        onClick = {
                            showMenu = false
                            onListenAlong()
                        },
                    )
                    HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                }
                ContextMenuItem(
                    icon = Icons.Filled.Person,
                    label = "View Profile",
                    onClick = {
                        showMenu = false
                        onClick()
                    },
                )
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.PushPin,
                    label = if (friend.pinnedToSidebar) "Unpin from Sidebar" else "Pin to Sidebar",
                    onClick = {
                        showMenu = false
                        onTogglePin()
                    },
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeMiniPlaybar(
    trackName: String,
    artistName: String?,
    artworkUrl: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HomeMiniPlaybarBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                    loading = { HomeMiniArtFallback() },
                    error = { HomeMiniArtFallback() },
                )
            } else {
                HomeMiniArtFallback()
            }
        }
        Text(
            text = buildString {
                append(trackName)
                artistName?.let { append("  ·  $it") }
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 24.sp,
            color = Color(0xFFD1D5DB),
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1000,
                ),
        )
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
private fun HomeMiniArtFallback() {
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
private fun HomeHexFallback(name: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

// ── Continue Listening Card ──────────────────────────────────────

@Composable
private fun ContinueListeningCard(
    playbackState: PlaybackState,
    onPlayPause: () -> Unit,
    onClick: () -> Unit,
) {
    val track = playbackState.currentTrack ?: return
    val isDark = ParachordTheme.isDark

    Column {
        SectionHeader("Continue Listening")

        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = if (isDark) {
                            listOf(
                                Color(0xFF2D1B69).copy(alpha = 0.6f),
                                Color(0xFF1E1B4B).copy(alpha = 0.4f),
                            )
                        } else {
                            listOf(
                                Color(0xFF8B5CF6).copy(alpha = 0.06f),
                                Color(0xFF6366F1).copy(alpha = 0.06f),
                            )
                        },
                    ),
                )
                .hapticClickable(onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Album art
            AlbumArtCard(
                artworkUrl = track.artworkUrl,
                size = 56.dp,
                cornerRadius = 8.dp,
                elevation = 2.dp,
                placeholderName = track.artist,
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Track info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playbackState.upNext.isNotEmpty()) {
                    Text(
                        text = "${playbackState.upNext.size} more in queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // Play/Pause button
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

// ── AI Shuffleupagus — No Plugins Card ──────────────────────────

@Composable
private fun NoAiPluginsCard(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1E1B4B), // indigo-950
                        Color(0xFF312E81), // indigo-900
                        Color(0xFF4C1D95), // purple-900
                    ),
                ),
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Sparkle icon in circle
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(26.dp)) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.558f, h * 1f)
                        lineTo(w * 0.573f, h * 0.974f)
                        cubicTo(w * 0.61f, h * 0.74f, w * 0.64f, h * 0.71f, w * 0.867f, h * 0.668f)
                        lineTo(w * 0.888f, h * 0.668f)
                        cubicTo(w * 0.64f, h * 0.62f, w * 0.608f, h * 0.58f, w * 0.573f, h * 0.346f)
                        lineTo(w * 0.558f, h * 0.326f)
                        lineTo(w * 0.537f, h * 0.346f)
                        cubicTo(w * 0.507f, h * 0.58f, w * 0.47f, h * 0.628f, w * 0.248f, h * 0.668f)
                        lineTo(w * 0.228f, h * 0.668f)
                        cubicTo(w * 0.475f, h * 0.718f, w * 0.505f, h * 0.748f, w * 0.537f, h * 0.974f)
                        close()
                        moveTo(w * 0.477f, h * 0.232f)
                        cubicTo(w * 0.49f, h * 0.153f, w * 0.49f, h * 0.151f, w * 0.57f, h * 0.137f)
                        cubicTo(w * 0.49f, h * 0.12f, w * 0.49f, h * 0.1f, w * 0.477f, h * 0.024f)
                        cubicTo(w * 0.468f, h * 0.1f, w * 0.455f, h * 0.12f, w * 0.383f, h * 0.137f)
                        cubicTo(w * 0.455f, h * 0.151f, w * 0.468f, h * 0.153f, w * 0.477f, h * 0.232f)
                        close()
                    }
                    drawPath(path, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Surprise Me",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Shuffleupagus, your AI companion, can recommend music, provide insights, " +
                    "control your playback experience and integrates with other AI agents so they " +
                    "can control it too. Enable at least one AI plug-in (like ChatGPT, Gemini, or " +
                    "Claude) to get started.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.7f),
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.2f))
                    .hapticClickable { /* TODO: navigate to settings → AI plugins */ }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Configure Plugins",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
    }
}

// ── AI Shuffleupagus — Suggestions Section ──────────────────────

@Composable
private fun AiSuggestionsSection(
    recommendations: AiRecommendations?,
    isLoading: Boolean,
    error: String? = null,
    onRefresh: () -> Unit,
    onAlbumClick: (albumTitle: String, artistName: String) -> Unit,
    onArtistClick: (String) -> Unit,
    onAddAlbumToCollection: (title: String, artist: String, artworkUrl: String?) -> Unit = { _, _, _ -> },
    onQueueAlbum: (title: String, artist: String) -> Unit = { _, _ -> },
    onGoToArtist: (String) -> Unit = {},
    onPlayArtistTopSongs: (String) -> Unit = {},
    onQueueArtistTopSongs: (String) -> Unit = {},
    onToggleArtistCollection: (name: String, imageUrl: String?, isInCollection: Boolean) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val shareAlbumLite = com.parachord.android.share.rememberShareAlbumLite()
    Column(modifier = modifier) {
        if (isLoading && recommendations == null) {
            // Loading shimmer
            AiSuggestionsShimmer(isLoading = true, onRefresh = onRefresh)
        } else if (error != null && (recommendations == null || (recommendations.albums.isEmpty() && recommendations.artists.isEmpty()))) {
            // Error state — show what went wrong with retry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AI SUGGESTIONS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                ShuffleupagusBadge()
                Spacer(modifier = Modifier.weight(1f))
                SpinningRefreshIcon(
                    isLoading = false,
                    onClick = onRefresh,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (recommendations != null && (recommendations.albums.isNotEmpty() || recommendations.artists.isNotEmpty())) {
            // Album Suggestions
            if (recommendations.albums.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "ALBUM SUGGESTIONS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ShuffleupagusBadge()
                    Spacer(modifier = Modifier.weight(1f))
                    SpinningRefreshIcon(
                        isLoading = isLoading,
                        onClick = onRefresh,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(recommendations.albums.size) { index ->
                        val album = recommendations.albums[index]
                        var showAlbumMenu by remember { mutableStateOf(false) }
                        AiAlbumCard(
                            album = album,
                            onClick = { onAlbumClick(album.title, album.artist) },
                            onLongClick = { showAlbumMenu = true },
                        )
                        if (showAlbumMenu) {
                            AlbumContextMenu(
                                albumTitle = album.title,
                                artistName = album.artist,
                                artworkUrl = album.artworkUrl,
                                isInCollection = false,
                                onDismiss = { showAlbumMenu = false },
                                onQueueAlbum = {
                                    showAlbumMenu = false
                                    onQueueAlbum(album.title, album.artist)
                                },
                                onGoToAlbum = {
                                    showAlbumMenu = false
                                    onAlbumClick(album.title, album.artist)
                                },
                                onGoToArtist = {
                                    showAlbumMenu = false
                                    onGoToArtist(album.artist)
                                },
                                onToggleCollection = {
                                    showAlbumMenu = false
                                    onAddAlbumToCollection(album.title, album.artist, album.artworkUrl)
                                },
                                onShare = { shareAlbumLite(album.title, album.artist, album.artworkUrl) },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Artist Suggestions
            if (recommendations.artists.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "ARTIST SUGGESTIONS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ShuffleupagusBadge()
                    Spacer(modifier = Modifier.weight(1f))
                    // Only show refresh on album row to avoid duplicate
                    if (recommendations.albums.isEmpty()) {
                        SpinningRefreshIcon(
                            isLoading = isLoading,
                            onClick = onRefresh,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(recommendations.artists.size) { index ->
                        val artist = recommendations.artists[index]
                        AiArtistCard(
                            artist = artist,
                            onClick = { onArtistClick(artist.name) },
                            onPlayTopSongs = { onPlayArtistTopSongs(artist.name) },
                            onQueueTopSongs = { onQueueArtistTopSongs(artist.name) },
                            onGoToArtist = { onArtistClick(artist.name) },
                            onToggleCollection = { onToggleArtistCollection(artist.name, artist.imageUrl, false) },
                        )
                    }
                }
            }
        } else {
            // Empty state / error — show minimal message
            Text(
                text = "Could not load suggestions. Tap to retry.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .hapticClickable { onRefresh() }
                    .padding(vertical = 24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ShuffleupagusBadge() {
    Text(
        text = "Shuffleupagus",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun AiAlbumCard(
    album: AiAlbumSuggestion,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .hapticCombinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AlbumArtCard(
            artworkUrl = album.artworkUrl,
            size = 120.dp,
            cornerRadius = 6.dp,
            elevation = 2.dp,
            placeholderName = album.artist,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AiArtistCard(
    artist: AiArtistSuggestion,
    onClick: () -> Unit,
    onPlayTopSongs: () -> Unit = {},
    onQueueTopSongs: () -> Unit = {},
    onGoToArtist: () -> Unit = {},
    onToggleCollection: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val shareArtist = com.parachord.android.share.rememberShareArtist()

    Column(
        modifier = Modifier
            .width(100.dp)
            .hapticCombinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumArtCard(
            artworkUrl = artist.imageUrl,
            size = 100.dp,
            cornerRadius = 50.dp,
            elevation = 2.dp,
            placeholderName = artist.name,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

@Composable
private fun AiSuggestionsShimmer(
    isLoading: Boolean = true,
    onRefresh: () -> Unit = {},
) {
    Column {
        // Album shimmer row
        Row(
            modifier = Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ALBUM SUGGESTIONS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            ShuffleupagusBadge()
            Spacer(modifier = Modifier.weight(1f))
            SpinningRefreshIcon(
                isLoading = isLoading,
                onClick = onRefresh,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(5) {
                Column(modifier = Modifier.width(120.dp)) {
                    // Album art placeholder
                    ShimmerTrackRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // Title placeholder
                    ShimmerTrackRow(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Artist placeholder
                    ShimmerTrackRow(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Artist shimmer row
        Row(
            modifier = Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ARTIST SUGGESTIONS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            ShuffleupagusBadge()
            Spacer(modifier = Modifier.weight(1f))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(5) {
                Column(
                    modifier = Modifier.width(100.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Circular artist image placeholder
                    ShimmerTrackRow(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // Name placeholder
                    ShimmerTrackRow(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}

// ── Collection Stats Row ─────────────────────────────────────────

@Composable
private fun StatsRow(
    stats: CollectionStats,
    onSongsClick: () -> Unit = {},
    onAlbumsClick: () -> Unit = {},
    onArtistsClick: () -> Unit = {},
    onPlaylistsClick: () -> Unit = {},
    onFriendsClick: () -> Unit = {},
) {
    val cardBg = MaterialTheme.colorScheme.surfaceVariant

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StatCard(
                icon = Icons.Filled.MusicNote,
                count = stats.tracks,
                label = "Songs",
                bg = cardBg,
                onClick = onSongsClick,
            )
        }
        item {
            StatCard(
                icon = Icons.Filled.Album,
                count = stats.albums,
                label = "Albums",
                bg = cardBg,
                onClick = onAlbumsClick,
            )
        }
        item {
            StatCard(
                icon = Icons.Filled.Person,
                count = stats.artists,
                label = "Artists",
                bg = cardBg,
                onClick = onArtistsClick,
            )
        }
        item {
            StatCard(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                count = stats.playlists,
                label = "Playlists",
                bg = cardBg,
                onClick = onPlaylistsClick,
            )
        }
        item {
            StatCard(
                icon = Icons.Filled.People,
                count = stats.friends,
                label = "Friends",
                bg = cardBg,
                onClick = onFriendsClick,
            )
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    count: Int,
    label: String,
    bg: Color,
    onClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .width(90.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .hapticClickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "$count",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Utility ──────────────────────────────────────────────────────

private fun formatTimeAgo(timestampSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestampSeconds
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 604800 -> "${diff / 86400}d"
        else -> "${diff / 604800}w"
    }
}

// ── Splash Screen ───────────────────────────────────────────────

@Composable
private fun SplashScreen() {
    // Fade-in + slide-up animation matching desktop's loadingFadeInUp
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "splashAlpha",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 24f,
        animationSpec = tween(durationMillis = 700),
        label = "splashOffset",
    )

    // Pulsing dots animation
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dotAlphas = (0..2).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, delayMillis = index * 200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot$index",
        )
    }

    val isDark = ParachordTheme.isDark
    val bg = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color(0xFFF3F4F6) else Color(0xFF111827)
    val dotColor = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha
                translationY = offsetY
            },
        ) {
            Text(
                text = "PARACHORD",
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.3.em,
                color = textColor,
            )
            Spacer(modifier = Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dotAlphas.forEach { dotAlpha ->
                    Canvas(modifier = Modifier.size(6.dp)) {
                        drawCircle(
                            color = dotColor,
                            alpha = dotAlpha.value,
                        )
                    }
                }
            }
        }
    }
}
