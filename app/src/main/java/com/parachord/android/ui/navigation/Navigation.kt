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
    const val ARTIST = "artist/{artistName}?tab={tab}"
    const val ALBUM = "album/{albumTitle}/{artistName}"

    const val CHAT = "chat"

    // Drawer destinations
    const val HISTORY = "history"
    const val FRESH_DROPS = "fresh_drops"
    const val RECOMMENDATIONS = "recommendations"
    const val POP_OF_THE_TOPS = "pop_of_the_tops"
    const val CRITICAL_DARLINGS = "critical_darlings"
    const val CONCERTS = "concerts"
    const val FRIENDS = "friends"
    const val FRIEND_DETAIL = "friend/{friendId}"
    const val WEEKLY_PLAYLIST = "weekly_playlist/{playlistId}/{contextType}"

    fun weeklyPlaylist(playlistId: String, contextType: String): String =
        "weekly_playlist/${Uri.encode(playlistId)}/${Uri.encode(contextType)}"

    fun artist(name: String, tab: String? = null): String =
        if (tab != null) "artist/${Uri.encode(name)}?tab=${Uri.encode(tab)}"
        else "artist/${Uri.encode(name)}"

    fun album(albumTitle: String, artistName: String): String =
        "album/${Uri.encode(albumTitle)}/${Uri.encode(artistName)}"

    fun playlistDetail(playlistId: String): String =
        "playlist/${Uri.encode(playlistId)}"

    fun playlistEdit(playlistId: String): String =
        "playlist/${Uri.encode(playlistId)}/edit"

    fun friendDetail(friendId: String): String =
        "friend/${Uri.encode(friendId)}"

    fun collection(tab: Int = 0): String =
        if (tab > 0) "collection?tab=$tab" else "collection"
}

@Composable
fun ParachordNavHost(
    navController: NavHostController,
    onOpenDrawer: () -> Unit,
    onOpenChat: () -> Unit = {},
    onListenAlong: (com.parachord.android.data.db.entity.FriendEntity) -> Unit = {},
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
                onNavigateToNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
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
                onNavigateToFriend = { friendId ->
                    navController.navigate(Routes.friendDetail(friendId))
                },
                onListenAlong = onListenAlong,
                onNavigateToRecommendations = { navController.navigate(Routes.RECOMMENDATIONS) },
                onNavigateToCriticalDarlings = { navController.navigate(Routes.CRITICAL_DARLINGS) },
                onNavigateToPopOfTheTops = { navController.navigate(Routes.POP_OF_THE_TOPS) },
                onNavigateToFreshDrops = { navController.navigate(Routes.FRESH_DROPS) },
                onNavigateToCollection = { tab -> navController.navigate(Routes.collection(tab)) },
                onNavigateToPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                onNavigateToWeeklyPlaylist = { playlistId, contextType ->
                    navController.navigate(Routes.weeklyPlaylist(playlistId, contextType))
                },
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
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
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
                listenAlongFriend = listenAlongFriend,
                onStopListenAlong = onStopListenAlong,
                suspendedQueue = listenAlongSuspendedQueue,
                listenAlongCanAdvance = listenAlongCanAdvance,
                onListenAlongNext = onListenAlongNext,
            )
        }
        composable(Routes.SETTINGS) {
            com.parachord.android.ui.screens.settings.SettingsScreen()
        }
        composable(
            route = Routes.ARTIST,
            arguments = listOf(
                navArgument("artistName") { type = NavType.StringType },
                navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null },
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
        composable(Routes.HISTORY) {
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
            arguments = listOf(navArgument("friendId") { type = NavType.StringType }),
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
