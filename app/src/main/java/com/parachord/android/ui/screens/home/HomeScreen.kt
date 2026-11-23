package com.parachord.android.ui.screens.home

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.share.rememberShareAlbumLite
import com.parachord.android.share.rememberSharePlaylistById
import com.parachord.android.ui.components.PageHero
import com.parachord.android.ui.components.AlbumContextMenu
import com.parachord.android.ui.components.DeletePlaylistDialog
import com.parachord.android.ui.components.PlaylistContextMenu
import com.parachord.android.ui.components.PlaylistSyncChannelsSheet
import com.parachord.shared.model.Playlist
import com.parachord.android.ui.components.AnnouncementBanner
import com.parachord.android.ui.components.AppleMusicReauthBanner
import com.parachord.android.ui.components.SpotifyExpiryWarningBanner
import com.parachord.android.ui.components.SpotifyReauthBanner
import com.parachord.android.ui.components.shimmerBrush
import com.parachord.android.ui.theme.composeGradient
import com.parachord.android.ui.theme.current
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.api.Announcement
import com.parachord.shared.home.HomeBannerItem
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.JumpTile
import com.parachord.shared.ui.DesignTokens
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private val OnFill = tokenColor(DesignTokens.ON_ACCENT)

/** Space between Home's sections (the section header adds 16 more above its title). */
private val SectionGap = 16.dp

/** The hero band's height on a phone (desktop's `HOME_HERO_HEIGHT` is 80 px). */

/**
 * Home, on the shared [com.parachord.shared.home.HomeSession] (desktop
 * `home-page.js`): the hero band, then one scroller with the account banners,
 * the announcement, and either the getting-started layout or the header and
 * the sections. The page waits on nothing: it draws [HomeViewModel.state],
 * a skeleton until the getting-started decision is made.
 */
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToPlaylist: (playlistId: String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToRecommendations: () -> Unit = {},
    onNavigateToCriticalDarlings: () -> Unit = {},
    onNavigateToPopOfTheTops: () -> Unit = {},
    onNavigateToFreshDrops: () -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
    /** Collection's Songs tab: Recent Loves' tile and card open it. */
    onOpenRecentLoves: () -> Unit = {},
    onOpenImportPlaylist: () -> Unit = {},
    /** Settings, on a plugin's sheet when [pluginId] is set. */
    onOpenSettings: (pluginId: String?) -> Unit = {},
    /** Settings on a tab, by [com.parachord.shared.settings.SettingsTab] name. */
    onOpenSettingsTab: (tab: String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenServicePlaylist: (url: String, name: String, source: String, imageUrl: String?) -> Unit = { _, _, _, _ -> },
    onOpenConcerts: () -> Unit = {},
    /** An artist's page on its On Tour tab: a concert card's tap. */
    onOpenArtistTour: (artistName: String) -> Unit = {},
    /** `DeepLinkViewModel.handleInAppChatPrompt`: Ask's prompt, no confirmation. */
    onAsk: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tileArt by viewModel.tileArt.collectAsStateWithLifecycle()
    val halfArt by viewModel.halfArt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ON_RESUME also fires on entering composition, so this is once per visit.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onVisible() }
    // A new phrasing for the Ask example each time Home enters composition
    // (desktop picks its askSeed per mount), not on every resume.
    LaunchedEffect(Unit) { viewModel.reseedAskExample() }

    // Playlist-action toasts (Apple Music's unsupported delete, D8).
    LaunchedEffect(viewModel) {
        viewModel.toastEvents.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }

    val openTile: (JumpTile) -> Unit = { tile ->
        when (tile) {
            is JumpTile.Playlist -> onNavigateToPlaylist(tile.playlist.id)
            is JumpTile.Loves -> onOpenRecentLoves()
            is JumpTile.Album -> onNavigateToAlbum(tile.album.album, tile.album.artist)
            is JumpTile.Mix -> onOpenServicePlaylist(
                tile.mix.url, tile.mix.title, tile.mix.resolverName.orEmpty(), tile.mix.imageUrl,
            )
        }
    }

    // ── Card menus, hosted here so they outlive a card scrolled away ──
    val sharePlaylistById = rememberSharePlaylistById()
    val shareAlbum = rememberShareAlbumLite()
    var playlistMenu by remember { mutableStateOf<Playlist?>(null) }
    var syncTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    var albumMenu by remember { mutableStateOf<AlbumMenuTarget?>(null) }

    playlistMenu?.let { pl ->
        PlaylistContextMenu(
            playlistName = pl.name,
            artworkUrl = pl.artworkUrl,
            trackCount = pl.trackCount,
            onDismiss = { playlistMenu = null },
            onPlayPlaylist = { playlistMenu = null; viewModel.playPlaylist(pl.id, pl.name) },
            onShare = { playlistMenu = null; sharePlaylistById(pl.id) },
            onOpenSync = { playlistMenu = null; syncTarget = pl },
            onDeletePlaylist = { playlistMenu = null; deleteTarget = pl },
        )
    }
    syncTarget?.let { pl ->
        PlaylistSyncChannelsSheet(
            playlistId = pl.id,
            playlistName = pl.name,
            onDismiss = { syncTarget = null },
        )
    }
    // Deleting asks first and names the services, like every other delete (#684).
    deleteTarget?.let { pl ->
        DeletePlaylistDialog(
            playlistId = pl.id,
            playlistName = pl.name,
            loadMirrors = viewModel::getDeletableMirrors,
            onConfirm = { fromProviders ->
                viewModel.deletePlaylist(pl, fromProviders)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
    albumMenu?.let { item ->
        val artist = item.artist
        val inCollection by remember(item.title, artist) { viewModel.isAlbumInCollection(item.title, artist) }.collectAsState(false)
        AlbumContextMenu(
            albumTitle = item.title,
            artistName = artist,
            artworkUrl = item.imageUrl,
            isInCollection = inCollection,
            onDismiss = { albumMenu = null },
            onPlayAlbum = { albumMenu = null; viewModel.playAlbumByName(item.title, artist) },
            onQueueAlbum = { albumMenu = null; viewModel.queueAlbumByName(item.title, artist) },
            onGoToAlbum = { albumMenu = null; onNavigateToAlbum(item.title, artist) },
            onGoToArtist = if (artist.isNotBlank()) {
                { albumMenu = null; onNavigateToArtist(artist) }
            } else null,
            onToggleCollection = {
                albumMenu = null
                if (inCollection) viewModel.removeAlbumFromCollection(item.title, artist)
                else viewModel.addAlbumToCollection(item.title, artist, item.imageUrl)
            },
            onShare = { albumMenu = null; shareAlbum(item.title, artist, item.imageUrl) },
        )
    }

    // ── Account banners, kept from the old Home as they were ─────────
    val oAuthManager: com.parachord.android.auth.OAuthManager = org.koin.compose.koinInject()
    val spotifyReauthRequired by oAuthManager.spotifyReauthRequired.collectAsStateWithLifecycle()
    // Apple Music reauth (stale Music-User-Token).
    val syncEngine: com.parachord.shared.sync.SyncEngine = org.koin.compose.koinInject()
    val musicKitBridge: com.parachord.android.playback.handlers.MusicKitWebBridge = org.koin.compose.koinInject()
    val appleMusicReauthRequired by syncEngine.appleMusicReauthRequired.collectAsStateWithLifecycle()
    val settingsStore: com.parachord.android.data.store.SettingsStore = org.koin.compose.koinInject()
    val reauthScope = rememberCoroutineScope()
    // Spotify pre-expiry warning (#352). Checked each time Home resumes (no
    // timer of its own). Hidden once the grant has actually failed: the
    // reauth banner says so.
    var spotifyExpiryMessage by remember { mutableStateOf<String?>(null) }
    val homeLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val homeResumed = homeLifecycleState == Lifecycle.State.RESUMED
    LaunchedEffect(homeResumed, spotifyReauthRequired) {
        if (!homeResumed) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val authorizedAt = settingsStore.getSpotifyAuthorizedAt()
        val warn = !spotifyReauthRequired &&
            !settingsStore.getSpotifyRefreshToken().isNullOrBlank() &&
            com.parachord.shared.api.auth.SpotifyGrantExpiry.shouldWarn(
                authorizedAt,
                settingsStore.getSpotifyExpiryWarningDismissedAt(),
                now,
            )
        spotifyExpiryMessage = if (warn) {
            com.parachord.shared.api.auth.SpotifyGrantExpiry.message(
                com.parachord.shared.api.auth.SpotifyGrantExpiry.daysLeft(authorizedAt, now),
            )
        } else {
            null
        }
    }
    val bannerPadding = Modifier.padding(horizontal = DesignTokens.SCREEN_GUTTER.dp, vertical = 8.dp)

    Column(modifier = modifier.fillMaxSize()) {
        HomeHero(onOpenDrawer = onOpenDrawer)

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
                        modifier = bannerPadding,
                    )
                }
            }
            spotifyExpiryMessage?.let { message ->
                item(key = "spotify-expiry-warning") {
                    SpotifyExpiryWarningBanner(
                        message = message,
                        onReconnect = {
                            reauthScope.launch {
                                val clientId = settingsStore.getSpotifyClientId()?.trim().orEmpty()
                                if (clientId.isNotBlank()) oAuthManager.launchSpotifyAuth(clientId)
                            }
                        },
                        onDismiss = {
                            spotifyExpiryMessage = null
                            reauthScope.launch {
                                settingsStore.setSpotifyExpiryWarningDismissedAt(System.currentTimeMillis())
                            }
                        },
                        modifier = bannerPadding,
                    )
                }
            }
            if (spotifyReauthRequired) {
                item(key = "spotify-reauth-banner") {
                    SpotifyReauthBanner(
                        onReconnect = {
                            // BYO: reconnect with the user's own stored Client
                            // ID (Parachord ships none). Without one they add
                            // it in Settings → Spotify first.
                            reauthScope.launch {
                                val clientId = settingsStore.getSpotifyClientId()?.trim().orEmpty()
                                if (clientId.isNotBlank()) oAuthManager.launchSpotifyAuth(clientId)
                            }
                        },
                        modifier = bannerPadding,
                    )
                }
            }

            // ── The announcement: one at a time, its view reported once ──
            state.banner?.let { banner ->
                item(key = "announcement-${banner.id}") {
                    LaunchedEffect(banner.id) { viewModel.bannerShown(banner.id) }
                    AnnouncementBanner(
                        announcement = banner.toAnnouncement(),
                        onDismiss = { viewModel.dismissBanner(banner.id) },
                        onCtaClick = { url ->
                            viewModel.bannerCtaClicked(banner.id)
                            openHttpLink(context, url)
                        },
                        modifier = bannerPadding,
                    )
                }
            }

            when {
                !state.ready -> item(key = "skeleton") { HomeSkeleton() }

                state.gettingStarted != null -> {
                    // Only the getting-started layout, then Explore (desktop `home-page.js`).
                    val view = state.gettingStarted!!
                    item(key = "getting-started") {
                        HomeGettingStarted(
                            view = view,
                            hasCollection = state.hasCollection,
                            onSkip = viewModel::skipSetup,
                            onOpenSettings = onOpenSettings,
                            onOpenSettingsTab = onOpenSettingsTab,
                            onOpenImportPlaylist = onOpenImportPlaylist,
                            onOpenPlaylist = onNavigateToPlaylist,
                            onCollectionRadio = viewModel::playCollectionRadio,
                            onSearch = onOpenSearch,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                    item(key = "explore") {
                        HomeExploreSection(
                            state = state,
                            onSeeAllCritical = onNavigateToCriticalDarlings,
                            onSeeAllCharts = onNavigateToPopOfTheTops,
                            onOpen = { onNavigateToAlbum(it.title, it.artist) },
                            onPlay = { viewModel.playAlbumByName(it.title, it.artist) },
                            onLongPress = { albumMenu = AlbumMenuTarget(it.title, it.artist, it.imageUrl) },
                            onRetryCharts = viewModel::retryCharts,
                            modifier = Modifier.padding(top = SectionGap),
                            halfArt = halfArt,
                            onCardShown = viewModel::halfCardShown,
                        )
                    }
                }

                else -> {
                    item(key = "header") {
                        HomeHeader(
                            state = state,
                            onCollectionRadio = viewModel::playCollectionRadio,
                            onSetupAction = { action ->
                                if (action == "getting-started") viewModel.resumeSetup()
                            },
                            onDismissSetupItem = viewModel::dismissSetupItem,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                    item(key = "jump-back-in") {
                        HomeJumpBackIn(
                            state = state,
                            art = tileArt,
                            onOpenTile = openTile,
                            onPlayTile = viewModel::playTile,
                            onTileShown = viewModel::tileShown,
                            onImport = onOpenImportPlaylist,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    item(key = "mixes") {
                        HomeMixesRow(
                            state = state,
                            onSelectSource = viewModel::setMixSource,
                            onOpenLoves = onOpenRecentLoves,
                            onPlayLoves = viewModel::playRecentLoves,
                            onOpenMix = { card ->
                                val mix = card.mix
                                onOpenServicePlaylist(mix.url, mix.title, mix.resolverName.orEmpty(), mix.imageUrl)
                            },
                            onPlayMix = { card -> viewModel.playMix(card.mix) },
                            modifier = Modifier.padding(top = SectionGap),
                        )
                    }
                    item(key = "playlists") {
                        HomePlaylistsRow(
                            state = state,
                            onOpen = { onNavigateToPlaylist(it.id) },
                            onPlay = { viewModel.playPlaylist(it.id, it.name) },
                            onLongPress = { playlistMenu = it },
                            onSeeAll = onNavigateToPlaylists,
                            modifier = Modifier.padding(top = SectionGap),
                        )
                    }
                    item(key = "albums") {
                        HomeAlbumsRow(
                            state = state,
                            onSelectSource = viewModel::setAlbumSource,
                            onShown = viewModel::albumShown,
                            onOpen = { onNavigateToAlbum(it.name, it.artist.orEmpty()) },
                            onPlay = viewModel::playAlbum,
                            onLongPress = { albumMenu = AlbumMenuTarget(it.name, it.artist.orEmpty(), it.imageUrl) },
                            onSeeAll = onNavigateToRecommendations,
                            modifier = Modifier.padding(top = SectionGap),
                        )
                    }

                    // A half's card opens its album by title and artist, as each
                    // page's own cards do (Android's album route has no
                    // release-group form).
                    item(key = "halves") {
                        HomeHalvesSection(
                            state = state,
                            onSeeAllFresh = onNavigateToFreshDrops,
                            onSeeAllCritical = onNavigateToCriticalDarlings,
                            onSeeAllCharts = onNavigateToPopOfTheTops,
                            onOpen = { onNavigateToAlbum(it.title, it.artist) },
                            onPlay = { viewModel.playAlbumByName(it.title, it.artist) },
                            onLongPress = { albumMenu = AlbumMenuTarget(it.title, it.artist, it.imageUrl) },
                            onRetryCharts = viewModel::retryCharts,
                            modifier = Modifier.padding(top = SectionGap + 16.dp),
                            halfArt = halfArt,
                            onCardShown = viewModel::halfCardShown,
                        )
                    }
                    item(key = "concerts") {
                        HomeConcertsSection(
                            state = state,
                            onSeeAll = onOpenConcerts,
                            onOpenArtistTour = onOpenArtistTour,
                            modifier = Modifier.padding(top = SectionGap),
                        )
                    }
                    item(key = "ask") {
                        HomeAsk(
                            example = state.askExample,
                            chatAvailable = state.chatAvailable,
                            onAsk = { prompt -> viewModel.ask(prompt, onAsk) },
                            onSetUp = { onOpenSettings(null) },
                            modifier = Modifier.padding(top = SectionGap + 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Home's page hero (the shared [PageHero]), with the drawer button on it in place of a top bar. */
@Composable
private fun HomeHero(onOpenDrawer: () -> Unit) {
    PageHero(
        page = DesignTokens.Page.HOME,
        navigation = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = HomeCopy.MENU, tint = OnFill)
            }
        },
    )
}

/** While the getting-started decision is pending: header bars, a 2x2 of tiles, a row of cards. */
@Composable
private fun HomeSkeleton() {
    val brush = shimmerBrush()
    val bar = @Composable { w: Int, h: Int ->
        Box(Modifier.width(w.dp).height(h.dp).clip(RoundedCornerShape(DesignTokens.RADIUS_BADGE.dp)).background(brush))
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DesignTokens.SCREEN_GUTTER.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        bar(200, 28)
        bar(160, 14)
        Spacer(Modifier.size(12.dp))
        bar(140, 20)
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) {
                    Box(
                        Modifier.weight(1f).height(72.dp)
                            .clip(RoundedCornerShape(DesignTokens.RADIUS_CARD.dp)).background(brush),
                    )
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        bar(180, 20)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(3) { com.parachord.android.ui.components.CardSkeleton(homeRowCardWidth(), lines = 1) }
        }
    }
}

/** What an album card's long-press menu acts on: a recommended album or a half's card. */
private data class AlbumMenuTarget(val title: String, val artist: String, val imageUrl: String?)

/** The banner component draws an [Announcement]; Home's item carries the same fields. */
private fun HomeBannerItem.toAnnouncement() = Announcement(
    id = id,
    title = title,
    severity = severity,
    body = body,
    iconUrl = iconUrl,
    cta = cta,
)

/** A banner's CTA: http(s) only, as desktop's `home-banner.js`. */
internal fun openHttpLink(context: android.content.Context, url: String) {
    val u = url.trim()
    if (!u.startsWith("https://", ignoreCase = true) && !u.startsWith("http://", ignoreCase = true)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    } catch (_: Exception) {
        Toast.makeText(context, HomeCopy.LINK_FAILED, Toast.LENGTH_SHORT).show()
    }
}
