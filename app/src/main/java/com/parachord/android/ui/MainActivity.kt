package com.parachord.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.parachord.android.auth.OAuthManager
import com.parachord.android.deeplink.DeepLinkConfirmation
import com.parachord.android.deeplink.DeepLinkNavEvent
import com.parachord.android.deeplink.DeepLinkViewModel
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.handlers.MusicKitWebBridge
import com.parachord.android.ui.components.ActionOverlay
import com.parachord.android.ui.components.NetworkBanner
import com.parachord.android.ui.components.CreatePlaylistDialog
import com.parachord.android.ui.components.DeepLinkConfirmationDialog
import com.parachord.android.ui.components.ImportPlaylistDialog
import com.parachord.android.ui.components.SpotifyDevicePickerDialog
import com.parachord.android.ui.components.DrawerContent
import com.parachord.android.ui.components.MiniPlayer
import com.parachord.android.ui.navigation.BottomNavItem
import com.parachord.android.ui.navigation.ParachordNavHost
import com.parachord.android.ui.navigation.Routes
import com.parachord.android.ui.theme.LocalResolverOrder
import com.parachord.android.ui.theme.ParachordTheme
import org.koin.android.ext.android.inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val oAuthManager: OAuthManager by inject()
    private val musicKitBridge: MusicKitWebBridge by inject()
    private val settingsStore: SettingsStore by inject()

    /** Pending deep link URI stored for the composable to process. */
    internal val pendingDeepLink = MutableStateFlow<Uri?>(null)

    /**
     * Runtime POST_NOTIFICATIONS request, registered as an Activity Result
     * launcher so it can be invoked from [onCreate]. The result is
     * intentionally ignored — granted means the next playback notification
     * will surface as expected; denied means the user can grant later via
     * Settings → Apps → Parachord → Notifications. The OS rate-limits
     * repeat prompts on its own, so we don't add app-side gating.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLink(intent)
        requestNotificationPermissionIfNeeded()
        setContent {
            ParachordApp()
        }
    }

    /**
     * Android 13+ (API 33) made POST_NOTIFICATIONS a runtime permission.
     * Until the user grants it, the OS silently drops every notification
     * the app posts — including the MediaSession transport notification
     * that shows playback controls on the lockscreen. Without this prompt,
     * users see nothing on the lockscreen even while music is playing.
     * Older Android versions get the permission at install time, so this
     * is a no-op there.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        musicKitBridge.setActivity(this)
        // Auto-configure MusicKit on launch if the user has previously set up Apple Music.
        // configure() restores the saved Music User Token, so no login popup is needed.
        if (!musicKitBridge.configured.value) {
            CoroutineScope(Dispatchers.IO).launch {
                val devToken = settingsStore.getAppleMusicDeveloperToken()
                if (!devToken.isNullOrBlank()) {
                    musicKitBridge.configure()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        musicKitBridge.setActivity(null)
    }

    // NOTE: We intentionally do NOT override onStop() to call keepAlive().
    // WebView.onResume()/resumeTimers() can block the main thread when the
    // Chromium renderer is being suspended, causing a background ANR that
    // kills the process. The brief WebView stutter on screen-off (~500ms)
    // recovers on its own and is far less harmful than a process kill.
    // keepAlive() is called from MusicKitWebBridge.play() instead, which
    // runs while the app is in the foreground.

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        // Handle ACTION_SEND from the share sheet (user shared a URL from
        // another app like Spotify, Apple Music, or a browser)
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                // Extract URL from shared text (may contain extra text around the URL)
                val url = extractUrl(sharedText)
                if (url != null) {
                    pendingDeepLink.value = Uri.parse(url)
                    return
                }
            }
            return
        }

        // Handle ACTION_VIEW (direct URL taps)
        val uri = intent?.data ?: return
        // Auth callbacks go directly to OAuthManager (fast path)
        if (uri.scheme == "parachord" && uri.host == "auth") {
            CoroutineScope(Dispatchers.IO).launch {
                oAuthManager.handleRedirect(uri)
            }
            return
        }
        // Store for the composable-scoped ViewModel to process
        pendingDeepLink.value = uri
    }

    /** Extract the first URL from shared text (which may include extra context). */
    private fun extractUrl(text: String): String? {
        // Common patterns: "Check out X on Spotify https://open.spotify.com/..."
        val urlRegex = Regex("""https?://[^\s]+""")
        return urlRegex.find(text)?.value
    }
}

@Composable
fun ParachordApp() {
    val mainViewModel: MainViewModel = koinViewModel()
    val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val resolverOrder by mainViewModel.resolverOrder.collectAsStateWithLifecycle()

    ParachordTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(LocalResolverOrder provides resolverOrder) {
            ParachordAppContent(mainViewModel)
        }
    }
}

@Composable
private fun ParachordAppContent(mainViewModel: MainViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val fullScreenRoutes = setOf(Routes.NOW_PLAYING, Routes.CHAT)
    val showBottomBar = currentDestination?.route !in fullScreenRoutes

    val playbackState by mainViewModel.playbackState.collectAsStateWithLifecycle()
    val currentTrack = playbackState.currentTrack
    val isCurrentTrackFavorited by mainViewModel.isCurrentTrackFavorited.collectAsStateWithLifecycle()
    val isCurrentArtistOnTour by mainViewModel.isOnTour.collectAsStateWithLifecycle()
    val isOnline by mainViewModel.isOnline.collectAsStateWithLifecycle()
    val friends by mainViewModel.friends.collectAsStateWithLifecycle()
    val listenAlongFriend by mainViewModel.listenAlongFriend.collectAsStateWithLifecycle()

    // Observe toast events from ViewModel (listen-along notifications, etc.)
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        mainViewModel.toastEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Navigate to Settings when Apple Music sign-in is required
    LaunchedEffect(Unit) {
        mainViewModel.navigateToSettings.collect {
            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
        }
    }

    // Handle deep link navigation events
    val deepLinkViewModel: DeepLinkViewModel = koinViewModel()

    // Wire the listen-along stopper for the protocol-play teardown (#120).
    // The teardown lives as a Koin singleton (it can't depend on a ViewModel
    // directly), so we hand it a callback at composition time. Idempotent —
    // safe to set multiple times across recompositions.
    val protocolTeardown: com.parachord.android.deeplink.AndroidProtocolPlayTeardown =
        org.koin.compose.koinInject()
    LaunchedEffect(mainViewModel) {
        protocolTeardown.setListenAlongStopper { mainViewModel.stopListenAlong(silent = true) }
    }

    // Process pending deep links from the Activity (intent handling).
    // The Activity stores the URI; the composable-scoped ViewModel processes it.
    val activity = context as? MainActivity
    LaunchedEffect(Unit) {
        activity?.pendingDeepLink?.collect { uri ->
            if (uri != null) {
                deepLinkViewModel.handleUri(uri)
                activity.pendingDeepLink.value = null
            }
        }
    }

    // Show confirmation dialog for deep link actions that need user approval
    val pendingConfirmation by deepLinkViewModel.pendingConfirmation.collectAsStateWithLifecycle()
    pendingConfirmation?.let { confirmation ->
        DeepLinkConfirmationDialog(
            confirmation = confirmation,
            onConfirm = { deepLinkViewModel.confirmPendingAction() },
            onDismiss = { deepLinkViewModel.dismissPendingAction() },
        )
    }

    LaunchedEffect(Unit) {
        deepLinkViewModel.navEvents.collect { event ->
            when (event) {
                is DeepLinkNavEvent.Artist ->
                    navController.navigate(Routes.artist(event.name)) { launchSingleTop = true }
                is DeepLinkNavEvent.Album ->
                    navController.navigate(Routes.album(event.title, event.artist)) { launchSingleTop = true }
                is DeepLinkNavEvent.Playlist ->
                    navController.navigate(Routes.playlistDetail(event.id)) { launchSingleTop = true }
                is DeepLinkNavEvent.Home ->
                    navController.navigate(Routes.HOME) { launchSingleTop = true }
                is DeepLinkNavEvent.Library ->
                    navController.navigate(Routes.collection(event.tab)) { launchSingleTop = true }
                is DeepLinkNavEvent.History ->
                    navController.navigate(Routes.HISTORY) { launchSingleTop = true }
                is DeepLinkNavEvent.Friend ->
                    navController.navigate(Routes.friendDetail(event.id)) { launchSingleTop = true }
                is DeepLinkNavEvent.Recommendations ->
                    navController.navigate(Routes.RECOMMENDATIONS) { launchSingleTop = true }
                is DeepLinkNavEvent.Charts ->
                    navController.navigate(Routes.POP_OF_THE_TOPS) { launchSingleTop = true }
                is DeepLinkNavEvent.CriticalDarlings ->
                    navController.navigate(Routes.CRITICAL_DARLINGS) { launchSingleTop = true }
                is DeepLinkNavEvent.Playlists ->
                    navController.navigate(Routes.PLAYLISTS) { launchSingleTop = true }
                is DeepLinkNavEvent.Settings ->
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                is DeepLinkNavEvent.Search ->
                    navController.navigate(Routes.SEARCH) { launchSingleTop = true }
                is DeepLinkNavEvent.Chat ->
                    navController.navigate(Routes.CHAT) { launchSingleTop = true }
                is DeepLinkNavEvent.Toast ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is DeepLinkNavEvent.StartListenAlong -> {
                    // The dispatcher already ran the listen-along
                    // handover teardown (exit-spinoff + clear-queue,
                    // skipping listen-along stop). startListenAlong
                    // calls stopListenAlong(silent=true) at its top so
                    // the swap from any active friend is atomic.
                    mainViewModel.startListenAlong(event.friend)
                }
            }
        }
    }

    // Spotify device picker dialog (matches desktop's device picker)
    val devicePickerRequest by mainViewModel.spotifyDevicePickerRequest.collectAsStateWithLifecycle()
    devicePickerRequest?.let { request ->
        SpotifyDevicePickerDialog(
            devices = request.devices,
            onDeviceSelected = { mainViewModel.onSpotifyDevicePicked(it) },
            onDismiss = { mainViewModel.onSpotifyDevicePicked(null) },
        )
    }

    var showActionOverlay by rememberSaveable { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showImportPlaylistDialog by remember { mutableStateOf(false) }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
                mainViewModel.createPlaylist(name)
                showCreatePlaylistDialog = false
                // Navigate to playlists to see the new playlist
                navController.navigate(Routes.PLAYLISTS) {
                    launchSingleTop = true
                }
            },
        )
    }

    if (showImportPlaylistDialog) {
        val importLoading by mainViewModel.importLoading.collectAsStateWithLifecycle()
        val importError by mainViewModel.importError.collectAsStateWithLifecycle()

        ImportPlaylistDialog(
            onDismiss = {
                showImportPlaylistDialog = false
                mainViewModel.clearImportError()
            },
            onImportUrl = { url ->
                mainViewModel.importPlaylistFromUrl(url) { playlistId ->
                    showImportPlaylistDialog = false
                    navController.navigate(Routes.playlistDetail(playlistId)) {
                        launchSingleTop = true
                    }
                }
            },
            onImportFile = { uri ->
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (content != null) {
                    val filename = uri.lastPathSegment
                    mainViewModel.importPlaylistFromFile(content, filename) { playlistId ->
                        showImportPlaylistDialog = false
                        navController.navigate(Routes.playlistDetail(playlistId)) {
                            launchSingleTop = true
                        }
                    }
                }
            },
            isLoading = importLoading,
            errorMessage = importError,
        )
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val onOpenDrawer: () -> Unit = {
        if (currentDestination?.route !in fullScreenRoutes) {
            scope.launch { drawerState.open() }
        }
    }
    val onOpenChat: () -> Unit = {
        if (currentDestination?.route !in fullScreenRoutes) {
            navController.navigate(Routes.CHAT) { launchSingleTop = true }
        }
    }

    // NestedScrollConnection: catches HorizontalPager boundary overscroll on tab screens.
    // Left overscroll (swipe right) → open drawer; right overscroll (swipe left) → open chat.
    val overscrollConnection = remember(onOpenDrawer, onOpenChat) {
        object : NestedScrollConnection {
            private var accumulatedX = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.x != 0f && (available.x > 0f) != (accumulatedX > 0f)) {
                    accumulatedX = 0f
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    accumulatedX += available.x
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val accumulated = accumulatedX
                accumulatedX = 0f
                if (accumulated > 60f || available.x > 400f) {
                    onOpenDrawer()
                } else if (accumulated < -60f || available.x < -400f) {
                    onOpenChat()
                }
                return Velocity.Zero
            }
        }
    }

    // Right-edge swipe threshold for opening chat
    val density = LocalDensity.current
    val edgeSwipeThresholdPx = with(density) { 60.dp.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = showBottomBar, // Disable drawer swipe on full-screen routes
            drawerContent = {
                ModalDrawerSheet {
                    DrawerContent(
                        currentRoute = currentDestination?.route,
                        friends = friends,
                        onItemClick = { route ->
                            scope.launch { drawerState.close() }
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                        onFriendClick = { friendId ->
                            scope.launch { drawerState.close() }
                            navController.navigate(Routes.friendDetail(friendId)) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                        onListenAlong = { friend ->
                            scope.launch { drawerState.close() }
                            mainViewModel.startListenAlong(friend)
                        },
                        onUnpinFriend = { friendId ->
                            mainViewModel.unpinFriend(friendId)
                        },
                    )
                }
            },
        ) {
            Scaffold(
                bottomBar = {
                    if (showBottomBar) {
                        Column {
                            if (currentTrack != null) {
                                val progress = if (playbackState.duration > 0) {
                                    playbackState.position.toFloat() / playbackState.duration.toFloat()
                                } else 0f
                                val streamMeta = playbackState.streamingMetadata
                                MiniPlayer(
                                    trackTitle = streamMeta?.title ?: currentTrack.title,
                                    artistName = streamMeta?.artist ?: currentTrack.artist,
                                    artworkUrl = streamMeta?.artworkUrl ?: currentTrack.artworkUrl,
                                    isPlaying = playbackState.isPlaying,
                                    isBuffering = playbackState.isBuffering,
                                    isFavorited = isCurrentTrackFavorited,
                                    isOnTour = isCurrentArtistOnTour,
                                    progress = progress,
                                    onPlayPause = { mainViewModel.togglePlayPause() },
                                    onSkipNext = { mainViewModel.skipNext() },
                                    onToggleFavorite = { mainViewModel.toggleCurrentTrackFavorite() },
                                    onClick = {
                                        navController.navigate(Routes.NOW_PLAYING) {
                                            launchSingleTop = true
                                        }
                                    },
                                )
                            }
                            // Subtle top border
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                            val navBarBg = if (ParachordTheme.isDark) Color(0xFF262626) else Color(0xFF1F2937)
                            val selectedTabBg = Color.White.copy(alpha = 0.12f)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(navBarBg)
                                    .navigationBarsPadding()
                                    .height(56.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BottomNavItem.entries.forEach { item ->
                                    val selected = item.route != null &&
                                        currentDestination?.hierarchy?.any { it.route == item.route } == true

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(if (selected) selectedTabBg else Color.Transparent)
                                            .clickable {
                                                if (item.isAction) {
                                                    showActionOverlay = !showActionOverlay
                                                } else {
                                                    item.route?.let { route ->
                                                        navController.navigate(route) {
                                                            popUpTo(navController.graph.findStartDestination().id) {
                                                                inclusive = false
                                                            }
                                                            launchSingleTop = true
                                                        }
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (item.isAction) {
                                            Icon(
                                                item.icon,
                                                contentDescription = item.label,
                                                tint = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.size(28.dp),
                                            )
                                        } else {
                                            Icon(
                                                item.icon,
                                                contentDescription = item.label,
                                                tint = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                                                modifier = Modifier.size(26.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .nestedScroll(overscrollConnection),
                ) {
                    // Network connectivity banner — slides in when offline
                    NetworkBanner(isOnline = isOnline)

                    Box(modifier = Modifier.weight(1f)) {
                    ParachordNavHost(
                        navController = navController,
                        onOpenDrawer = onOpenDrawer,
                        onOpenChat = onOpenChat,
                        onListenAlong = { friend ->
                            mainViewModel.startListenAlong(friend)
                        },
                        listenAlongFriend = listenAlongFriend,
                        onStopListenAlong = { mainViewModel.stopListenAlong() },
                    )

                    // Right-edge swipe zone — swipe left from the right edge to open chat.
                    // The ModalNavigationDrawer handles left-edge swipe for the sidebar.
                    if (showBottomBar) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(24.dp)
                                .fillMaxHeight()
                                .pointerInput(onOpenChat) {
                                    var accumulated = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { accumulated = 0f },
                                        onDragEnd = {
                                            if (accumulated < -edgeSwipeThresholdPx) {
                                                onOpenChat()
                                            }
                                            accumulated = 0f
                                        },
                                        onDragCancel = { accumulated = 0f },
                                        onHorizontalDrag = { _, dragAmount ->
                                            accumulated += dragAmount
                                        },
                                    )
                                },
                        )
                    }
                    }
                }
            }
        }

        // Full-screen action overlay on top of everything
        ActionOverlay(
            visible = showActionOverlay,
            onDismiss = { showActionOverlay = false },
            onCreatePlaylist = {
                showActionOverlay = false
                showCreatePlaylistDialog = true
            },
            onImportPlaylist = {
                showActionOverlay = false
                showImportPlaylistDialog = true
            },
            onAddFriend = {
                showActionOverlay = false
                navController.navigate(Routes.FRIENDS) {
                    launchSingleTop = true
                }
            },
            onChatWithShuffleupagus = {
                showActionOverlay = false
                navController.navigate(Routes.CHAT) {
                    launchSingleTop = true
                }
            },
        )
    }
}
