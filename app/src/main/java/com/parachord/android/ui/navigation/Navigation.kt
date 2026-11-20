package com.parachord.android.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/** Top-level route definitions. */
/** Collection's Songs tab index (LibraryScreen: Artists, Albums, Songs, Friends). */
private const val COLLECTION_SONGS_TAB = 2

object Routes {
    const val HOME = "home"
    const val COLLECTION = "collection?tab={tab}"
    const val COLLECTION_BASE = "collection"
    const val SEARCH = "search"
    const val PLAYLISTS = "playlists"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val PLAYLIST_EDIT = "playlist/{playlistId}/edit"
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"
    /** [SETTINGS] opening one plugin's sheet (`plugin` is a plugin or resolver id; optional). */
    const val SETTINGS_WITH_PLUGIN = "settings?plugin={plugin}"

    fun settings(pluginId: String?): String =
        if (pluginId == null) SETTINGS else "settings?plugin=${Uri.encode(pluginId)}"
    /** `mbid`: the artist's MusicBrainz id when the caller has one (#478). */
    const val ARTIST = "artist/{artistName}?tab={tab}&mbid={mbid}"
    const val ALBUM = "album/{albumTitle}/{artistName}"

    const val CHAT = "chat"

    // Drawer destinations
    const val HISTORY = "history"
    /** [HISTORY] with a deep link's tab segment and period (#470). */
    const val HISTORY_WITH_ARGS = "history?tab={tab}&period={period}"
    const val FRESH_DROPS = "fresh_drops"
    const val RECOMMENDATIONS = "recommendations"
    const val POP_OF_THE_TOPS = "pop_of_the_tops"
    const val CRITICAL_DARLINGS = "critical_darlings"
    const val CONCERTS = "concerts"
    const val FRIENDS = "friends"
    const val FRIEND_DETAIL = "friend/{friendId}?tab={tab}&period={period}"
    const val WEEKLY_PLAYLIST = "weekly_playlist/{playlistId}/{contextType}"

    const val SERVICE_PLAYLIST = "service_playlist?url={url}&name={name}&source={source}&image={image}"

    /** A recommended service playlist's own view (desktop's `openServicePlaylist`). */
    fun servicePlaylist(url: String, name: String, sourceName: String, imageUrl: String?): String =
        "service_playlist?url=${Uri.encode(url)}&name=${Uri.encode(name)}&source=${Uri.encode(sourceName)}&image=${Uri.encode(imageUrl ?: "")}"

    fun weeklyPlaylist(playlistId: String, contextType: String): String =
        "weekly_playlist/${Uri.encode(playlistId)}/${Uri.encode(contextType)}"

    fun artist(name: String, tab: String? = null, mbid: String? = null): String {
        val args = listOfNotNull(
            tab?.let { "tab=${Uri.encode(it)}" },
            mbid?.let { "mbid=${Uri.encode(it)}" },
        )
        return "artist/${Uri.encode(name)}" + if (args.isEmpty()) "" else args.joinToString("&", prefix = "?")
    }

    fun album(albumTitle: String, artistName: String): String =
        "album/${Uri.encode(albumTitle)}/${Uri.encode(artistName)}"

    fun playlistDetail(playlistId: String): String =
        "playlist/${Uri.encode(playlistId)}"

    fun playlistEdit(playlistId: String): String =
        "playlist/${Uri.encode(playlistId)}/edit"

    fun friendDetail(friendId: String, tab: String? = null, period: String? = null): String =
        "friend/${Uri.encode(friendId)}" + linkArgs(tab, period)

    fun history(tab: String? = null, period: String? = null): String = HISTORY + linkArgs(tab, period)

    private fun linkArgs(tab: String?, period: String?): String =
        listOfNotNull(tab?.let { "tab=${Uri.encode(it)}" }, period?.let { "period=${Uri.encode(it)}" })
            .joinToString("&").let { if (it.isEmpty()) "" else "?$it" }

    fun collection(tab: Int = 0): String =
        if (tab > 0) "collection?tab=$tab" else "collection"
}

@Composable
fun ParachordNavHost(
    navController: NavHostController,
    onOpenDrawer: () -> Unit,
    onOpenChat: () -> Unit = {},
    /** MainActivity's import-playlist dialog. */
    onOpenImportPlaylist: () -> Unit = {},
    /** Ask Shuffleupagus from Home: `DeepLinkViewModel.handleInAppChatPrompt`. */
    onAsk: (String) -> Unit = {},
    listenAlongFriend: com.parachord.android.data.db.entity.FriendEntity? = null,
    onStopListenAlong: () -> Unit = {},
    listenAlongSuspendedQueue: List<com.parachord.android.data.db.entity.TrackEntity> = emptyList(),
    listenAlongCanAdvance: Boolean = false,
    onListenAlongNext: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                initialOffsetX = { it / 4 },
                animationSpec = tween(300),
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300),
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                targetOffsetX = { it / 4 },
                animationSpec = tween(300),
            )
        },
    ) {
        composable(Routes.HOME) {
            com.parachord.android.ui.screens.home.HomeScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToPlaylist = { playlistId ->
                    navController.navigate(Routes.playlistDetail(playlistId))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
                onNavigateToRecommendations = { navController.navigate(Routes.RECOMMENDATIONS) },
                onNavigateToCriticalDarlings = { navController.navigate(Routes.CRITICAL_DARLINGS) },
                onNavigateToPopOfTheTops = { navController.navigate(Routes.POP_OF_THE_TOPS) },
                onNavigateToFreshDrops = { navController.navigate(Routes.FRESH_DROPS) },
                onNavigateToPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                // Collection's Songs tab (LibraryScreen's tabs: Artists, Albums, Songs, Friends).
                onOpenRecentLoves = { navController.navigate(Routes.collection(COLLECTION_SONGS_TAB)) },
                onOpenImportPlaylist = onOpenImportPlaylist,
                onOpenSettings = { pluginId -> navController.navigate(Routes.settings(pluginId)) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenServicePlaylist = { url, name, source, imageUrl ->
                    navController.navigate(Routes.servicePlaylist(url, name, source, imageUrl))
                },
                onOpenConcerts = { navController.navigate(Routes.CONCERTS) },
                onAsk = onAsk,
            )
        }
        composable(
            Routes.COLLECTION,
            arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 }),
        ) { backStackEntry ->
            val initialTab = backStackEntry.arguments?.getInt("tab") ?: 0
            com.parachord.android.ui.screens.library.CollectionScreen(
                initialTab = initialTab,
                onOpenDrawer = onOpenDrawer,
                onNavigateToFriend = { friendId ->
                    navController.navigate(Routes.friendDetail(friendId))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
            )
        }
        composable(Routes.PLAYLISTS) {
            com.parachord.android.ui.screens.playlists.PlaylistsScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToPlaylist = { playlistId ->
                    navController.navigate(Routes.playlistDetail(playlistId))
                },
            )
        }
        composable(
            route = Routes.PLAYLIST_DETAIL,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getString("playlistId") ?: ""
            com.parachord.android.ui.screens.playlists.PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToEdit = {
                    navController.navigate(Routes.playlistEdit(playlistId))
                },
            )
        }
        composable(
            route = Routes.WEEKLY_PLAYLIST,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.StringType },
                navArgument("contextType") { type = NavType.StringType },
            ),
        ) {
            com.parachord.android.ui.screens.playlists.WeeklyPlaylistScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
            )
        }
        composable(
            route = Routes.SERVICE_PLAYLIST,
            arguments = listOf("url", "name", "source", "image").map { key ->
                navArgument(key) { type = NavType.StringType; defaultValue = "" }
            },
        ) {
            com.parachord.android.ui.screens.playlists.ServicePlaylistScreen(
                onBack = { navController.popBackStack() },
                onOpenSaved = { playlistId ->
                    navController.navigate(Routes.playlistDetail(playlistId)) {
                        popUpTo(Routes.SERVICE_PLAYLIST) { inclusive = true }
                    }
                },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
            )
        }
        composable(
            route = Routes.PLAYLIST_EDIT,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) {
            com.parachord.android.ui.screens.playlists.EditPlaylistScreen(
                onBack = { navController.popBackStack() },
                onPlaylistDeleted = {
                    // Pop back to the playlists list (remove both edit and detail from stack)
                    navController.popBackStack(Routes.PLAYLISTS, false)
                },
            )
        }
        composable(Routes.SEARCH) {
            com.parachord.android.ui.screens.search.SearchScreen(
                onNavigateToArtist = { name, mbid -> navController.navigate(Routes.artist(name, mbid = mbid)) },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onOpenDrawer = onOpenDrawer,
            )
        }
        composable(
            Routes.NOW_PLAYING,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400),
                )
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400),
                )
            },
            popEnterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400),
                )
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400),
                )
            },
        ) {
            com.parachord.android.ui.screens.nowplaying.NowPlayingScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                onNavigateToArtistOnTour = { name ->
                    navController.navigate(Routes.artist(name, tab = "On Tour"))
                },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToPlaylist = { playlistId ->
                    navController.navigate(Routes.playlistDetail(playlistId))
                },
                // A recommended playlist's queue: its view by link (the source name isn't carried).
                onNavigateToServicePlaylist = { url, name ->
                    navController.navigate(Routes.servicePlaylist(url, name, sourceName = "", imageUrl = null))
                },
                listenAlongFriend = listenAlongFriend,
                onStopListenAlong = onStopListenAlong,
                suspendedQueue = listenAlongSuspendedQueue,
                listenAlongCanAdvance = listenAlongCanAdvance,
                onListenAlongNext = onListenAlongNext,
            )
        }
        composable(
            route = Routes.SETTINGS_WITH_PLUGIN,
            arguments = listOf(
                navArgument("plugin") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            com.parachord.android.ui.screens.settings.SettingsScreen(
                initialPluginId = backStackEntry.arguments?.getString("plugin"),
            )
        }
        composable(
            route = Routes.ARTIST,
            arguments = listOf(
                navArgument("artistName") { type = NavType.StringType },
                navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("mbid") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            val initialTab = backStackEntry.arguments?.getString("tab")
            com.parachord.android.ui.screens.artist.ArtistScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                initialTab = initialTab,
            )
        }
        composable(
            route = Routes.ALBUM,
            arguments = listOf(
                navArgument("albumTitle") { type = NavType.StringType },
                navArgument("artistName") { type = NavType.StringType },
            ),
        ) {
            com.parachord.android.ui.screens.album.AlbumScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
            )
        }

        composable(
            Routes.CHAT,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(350),
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(350),
                )
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(350),
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(350),
                )
            },
        ) {
            com.parachord.android.ui.screens.chat.ChatScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
            )
        }

        // ── Drawer Destinations ─────────────────────────────────────────
        composable(
            route = Routes.HISTORY_WITH_ARGS,
            arguments = linkArguments,
        ) {
            com.parachord.android.ui.screens.history.HistoryScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
        composable(Routes.FRESH_DROPS) {
            com.parachord.android.ui.screens.discover.FreshDropsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
        composable(Routes.RECOMMENDATIONS) {
            com.parachord.android.ui.screens.discover.RecommendationsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToWeekly = { id, contextType ->
                    navController.navigate(Routes.weeklyPlaylist(id, contextType))
                },
                onNavigateToServicePlaylist = { url, name, source, image ->
                    navController.navigate(Routes.servicePlaylist(url, name, source, image))
                },
                onNavigateToFriend = { friendId ->
                    navController.navigate(Routes.friendDetail(friendId))
                },
                onNavigateToPluginSettings = { pluginId ->
                    navController.navigate(Routes.settings(pluginId))
                },
            )
        }
        composable(Routes.POP_OF_THE_TOPS) {
            com.parachord.android.ui.screens.discover.PopOfTheTopsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
        composable(Routes.CRITICAL_DARLINGS) {
            com.parachord.android.ui.screens.discover.CriticalDarlingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
        composable(Routes.CONCERTS) {
            com.parachord.android.ui.screens.discover.ConcertsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
        composable(Routes.FRIENDS) {
            com.parachord.android.ui.screens.friends.FriendsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToFriend = { friendId ->
                    navController.navigate(Routes.friendDetail(friendId))
                },
            )
        }
        composable(
            route = Routes.FRIEND_DETAIL,
            arguments = listOf(navArgument("friendId") { type = NavType.StringType }) + linkArguments,
        ) {
            com.parachord.android.ui.screens.friends.FriendDetailScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
    }
}

/** The optional `tab` and `period` a history or friend deep link carries (#470). */
private val linkArguments = listOf(
    navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null },
    navArgument("period") { type = NavType.StringType; nullable = true; defaultValue = null },
)
