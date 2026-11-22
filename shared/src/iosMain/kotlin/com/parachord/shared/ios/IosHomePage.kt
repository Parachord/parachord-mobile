package com.parachord.shared.ios

import com.parachord.shared.home.BannerRole
import com.parachord.shared.home.FilterChipSource
import com.parachord.shared.home.GETTING_STARTED_STEPS
import com.parachord.shared.home.GettingStartedView
import com.parachord.shared.home.HOME_JUMP_TILES
import com.parachord.shared.home.HOME_RECENT_LOVES
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeFeed
import com.parachord.shared.home.HomeHalfBody
import com.parachord.shared.home.HomeHalfCard
import com.parachord.shared.home.HomeMixCard
import com.parachord.shared.home.HomeQueue
import com.parachord.shared.home.HomeQueues
import com.parachord.shared.home.HomeSession
import com.parachord.shared.home.HomeSources
import com.parachord.shared.home.HomeState
import com.parachord.shared.home.JumpTile
import com.parachord.shared.home.JumpTileArt
import com.parachord.shared.home.JumpTileArtLookup
import com.parachord.shared.home.ShownAlbumArtLookup
import com.parachord.shared.home.StepMarkerLook
import com.parachord.shared.home.albumReasonBadge
import com.parachord.shared.home.bannerRole
import com.parachord.shared.home.chartHalfCards
import com.parachord.shared.home.concertArtist
import com.parachord.shared.home.concertDateBadge
import com.parachord.shared.home.concertPhotoUrl
import com.parachord.shared.home.concertPlaceLine
import com.parachord.shared.home.concertTickets
import com.parachord.shared.home.concertTourArtist
import com.parachord.shared.home.concertVenue
import com.parachord.shared.home.criticalHalfCards
import com.parachord.shared.home.exploreHalves
import com.parachord.shared.home.folderTileDescription
import com.parachord.shared.home.folderTileLabel
import com.parachord.shared.home.freeTileDescription
import com.parachord.shared.home.freshHalfCards
import com.parachord.shared.home.halfCardArtKey
import com.parachord.shared.home.halfCardNeedsArt
import com.parachord.shared.home.homeAlbumCardKey
import com.parachord.shared.home.homeHalfBody
import com.parachord.shared.home.homeSectionVisibility
import com.parachord.shared.home.homeSourceChips
import com.parachord.shared.home.jumpTileKey
import com.parachord.shared.home.jumpTileTitle
import com.parachord.shared.home.jumpTilesArt
import com.parachord.shared.home.listenStepLine
import com.parachord.shared.home.mixCardArt
import com.parachord.shared.home.mixCardKey
import com.parachord.shared.home.mixCardSubtitle
import com.parachord.shared.home.openGettingStartedStep
import com.parachord.shared.home.playlistTileDescription
import com.parachord.shared.home.playlistTileTitle
import com.parachord.shared.home.scrobblerTileDescription
import com.parachord.shared.home.scrobblerTileLabel
import com.parachord.shared.home.serviceTileDescription
import com.parachord.shared.home.serviceTileLabel
import com.parachord.shared.home.serviceTileShowsBadge
import com.parachord.shared.home.showSourceChips
import com.parachord.shared.home.stepMarkerLook
import com.parachord.shared.home.stepStateLine
import com.parachord.shared.home.withHalfCardArt
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.recentLoves
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.ui.DesignTokens
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/*
 * Home for SwiftUI: the shared HomeSession's state flattened into plain DTOs,
 * as Android's Home screens draw it (HomeScreen.kt and its section files). No
 * rules live here; every decision is the shared one, applied as it is.
 */

// ── DTOs ─────────────────────────────────────────────────────────────

/** Everything iOS's Home draws, in the order Android's Home draws it. */
data class IosHomePage(
    /** False until the getting-started decision is made: the page shows its skeleton. */
    val ready: Boolean,
    val greeting: String,
    val changeLine: String?,
    val hasCollection: Boolean,
    /** The "Finish setting up" pill, only while an item is open. */
    val setup: IosHomeSetup?,
    val banner: IosHomeBanner?,
    /** Non-null: the page shows only the getting-started layout, then the albums, halves, concerts and Ask. */
    val gettingStarted: IosGettingStarted?,
    /** At most [HOME_JUMP_TILES], a 2x2 grid. */
    val tiles: List<IosJumpTile>,
    /** "Build your collection" in Jump back in's place. */
    val showImportCard: Boolean,
    val mixCards: List<IosHomeMixCard>,
    /** "All" first, then a chip per source. */
    val mixChips: List<IosHomeChip>,
    val mixSource: String,
    val showMixChips: Boolean,
    val playlists: List<IosHomePlaylistCard>,
    val albums: List<IosHomeAlbum>,
    /** "All" first, then a chip per source. */
    val albumChips: List<IosHomeChip>,
    val albumSource: String,
    val showAlbumChips: Boolean,
    /** No albums yet and a load is coming: the row shows skeleton cards. */
    val albumsLoading: Boolean,
    /** Fresh Drops (when shown), Critical Darlings, then Pop of the Tops (only while Fresh Drops isn't shown). */
    val halves: List<IosHomeHalf>,
    val concerts: List<IosHomeConcert>,
    val askExample: String,
    /** "yes", "no" (offer "Set up Shuffleupagus") or "unknown". */
    val chatAvailable: String,
    val showBanner: Boolean,
    val showJump: Boolean,
    val showImport: Boolean,
    val showMixes: Boolean,
    val showPlaylists: Boolean,
    /** With albums, or while they're loading. */
    val showAlbums: Boolean,
    val showConcerts: Boolean,
    val showAsk: Boolean,
)

/** [IosJumpTile.kind] values. */
const val JUMP_KIND_PLAYLIST = "playlist"
const val JUMP_KIND_LOVES = "loves"
const val JUMP_KIND_ALBUM = "album"
const val JUMP_KIND_MIX = "mix"

/** [IosHomeHalf.kind] values. */
const val HALF_KIND_FRESH = "fresh"
const val HALF_KIND_CRITICAL = "critical"
const val HALF_KIND_CHARTS = "charts"

/** [IosHomeHalf.body] values. */
const val HALF_BODY_CARDS = "cards"
const val HALF_BODY_LOADING = "loading"
const val HALF_BODY_FAILED = "failed"

data class IosHomeSetup(val pill: String, val items: List<IosHomeSetupItem>)

data class IosHomeSetupItem(val id: String, val label: String, val dismissLabel: String)

/** [role] is how to colour it ([bannerRole]); [text] is the body. */
data class IosHomeBanner(
    val id: String,
    val title: String,
    val text: String?,
    val role: BannerRole,
    val ctaLabel: String?,
    val ctaUrl: String?,
)

/**
 * A Jump back in tile. [kind] is a `JUMP_KIND_*`. What a
 * tap opens: a playlist's [targetId] (its id) and [targetName]; Recent Loves
 * ([targetId] "loves"); an album's [targetName] (title) and [targetArtist]; a
 * mix's [targetUrl], [targetName] (title) and [targetArtist] (its source's name).
 */
data class IosJumpTile(
    val key: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val mosaic: List<String>,
    val targetId: String,
    val targetName: String,
    val targetArtist: String?,
    val targetUrl: String?,
)

/** A "Made from your listening" card; a mix's [url] and [source] (its service's name) open its view. */
data class IosHomeMixCard(
    val key: String,
    val isLoves: Boolean,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val mosaic: List<String>,
    val url: String?,
    val source: String?,
)

/** A source chip; [colorRgb] is `0xRRGGBB` (the "All" chip: Home's page fill). */
data class IosHomeChip(val id: String, val label: String, val colorRgb: Int)

/** A playlist card: the "Δ N" [badge], "Refreshed 2h ago" and "Since 2023". */
data class IosHomePlaylistCard(
    val id: String,
    val name: String,
    val badge: String?,
    val line1: String,
    val line2: String?,
    val imageUrl: String?,
)

/**
 * An album card. [key] (artist + title) is unique in the row, while an item's
 * id may be blank; [albumShown] / [albumQueue] take either.
 */
data class IosHomeAlbum(val item: IosRecItem, val reasonBadge: String?, val key: String)

/**
 * One of the Fresh Drops / Critical Darlings / Pop of the Tops halves. [kind]
 * is a `HALF_KIND_*`; [pageColorId] the `DesignTokens.Page`
 * name; [body] a `HALF_BODY_*` (Pop of the Tops' error, with
 * [errorTitle], [errorText] and Try again).
 */
data class IosHomeHalf(
    val kind: String,
    val title: String,
    val pageColorId: String,
    val body: String,
    val errorTitle: String?,
    val errorText: String?,
    val cards: List<IosHalfCard>,
)

/**
 * A half's album card. With [badgeColorRgb] the [badge] is Fresh Drops' release
 * type pill (top-right, in that colour); without, it's a Critical Darlings
 * review (the reason badge). [needsArt]: report it shown ([IosHomeSession.halfCardShown]).
 */
data class IosHalfCard(
    val key: String,
    val title: String,
    val artist: String,
    val imageUrl: String?,
    val badge: String?,
    val badgeColorRgb: Int?,
    val needsArt: Boolean,
)

/**
 * A concert card. [tourArtist] null: no tap and no photo lookup, and
 * [openLabel] is null. [a11yLabel] is the artist, date, venue and place.
 */
data class IosHomeConcert(
    val id: String,
    val artist: String,
    val tourArtist: String?,
    val venue: String,
    val place: String,
    val dateBadge: String?,
    val photoUrl: String?,
    val tickets: List<IosTicket>,
    val a11yLabel: String,
    val openLabel: String?,
)

data class IosTicket(val label: String, val url: String)

/** The getting-started layout. [openStep] is the step whose panel shows. */
data class IosGettingStarted(
    val steps: List<IosGsStep>,
    val openStep: String,
    val services: List<IosGsTile>,
    val free: List<IosGsTile>,
    val folder: IosGsTile?,
    val scrobblers: List<IosGsTile>,
    /** Step 3's playlists; [IosGsTile.pluginId] is the playlist id. */
    val playlists: List<IosGsTile>,
    val listenLine: String,
    val hasMusic: Boolean,
    /** "Explore while you set up": Critical Darlings, then Pop of the Tops. */
    val explore: List<IosHomeHalf>,
)

/** [marker] is "done", "current" or "todo"; [stateLine] "Done", "Next" or "Not yet". */
data class IosGsStep(val id: String, val title: String, val stateLine: String, val marker: String)

/**
 * A getting-started tile. [text] is the line under the name; [label] the whole
 * tile's accessibility label; [connected] a service's or scrobbler's
 * "Connected" badge, or a free plug-in's and the folder's On.
 */
data class IosGsTile(
    val pluginId: String,
    val name: String,
    val text: String,
    val label: String,
    val connected: Boolean,
)

/**
 * What a tap plays: [tracks] from the top under [context]. [shuffle] true plays
 * them shuffled; false keeps the current setting. No tracks and a [message]:
 * show it; no tracks and no message: nothing to do.
 */
data class IosHomeQueue(
    val tracks: List<Track>,
    val context: PlaybackContext?,
    val shuffle: Boolean,
    val message: String?,
)

// ── The page ─────────────────────────────────────────────────────────

/**
 * The page for [state]: [tileArt] by `jumpTileKey` ([jumpTilesArt]), [halfArt]
 * by `halfCardArtKey`, [pickedStep] the getting-started step the person opened
 * (null: the current one), [playlistArt] how a stored playlist cover is read
 * (iOS re-points a mosaic after a container move).
 */
fun iosHomePage(
    state: HomeState,
    tileArt: Map<String, JumpTileArt>,
    halfArt: Map<String, String>,
    today: LocalDate,
    pickedStep: String? = null,
    playlistArt: (String) -> String = { it },
): IosHomePage {
    val tiles = state.tiles.take(HOME_JUMP_TILES)
    val visibility = homeSectionVisibility(state)
    return IosHomePage(
        ready = state.ready,
        greeting = state.greeting,
        changeLine = state.changeLine,
        hasCollection = state.hasCollection,
        setup = state.setup?.takeIf { it.mode == "setup" }?.let { s ->
            IosHomeSetup(
                pill = HomeCopy.setupPill(s.done, s.total),
                items = s.items.map { IosHomeSetupItem(it.id, it.label, HomeCopy.dismissLabel(it.label)) },
            )
        },
        banner = state.banner?.let {
            IosHomeBanner(it.id, it.title, it.body, bannerRole(it.severity), it.cta?.label, it.cta?.url)
        },
        gettingStarted = state.gettingStarted?.let { gettingStarted(it, state, halfArt, pickedStep) },
        tiles = tiles.map { tile(it, tileArt[jumpTileKey(it)] ?: JumpTileArt(), playlistArt) },
        showImportCard = state.showImportCard,
        mixCards = state.mixRow.map(::mixCard),
        mixChips = chips(state.mixSources),
        mixSource = state.mixSource,
        showMixChips = showSourceChips(state.mixSources),
        playlists = state.playlists.map { c ->
            IosHomePlaylistCard(
                id = c.playlist.id,
                name = c.playlist.name,
                badge = c.badge,
                line1 = c.subtitle.refreshed,
                line2 = c.subtitle.since,
                imageUrl = c.playlist.artworkUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(playlistArt),
            )
        },
        albums = state.albums.map { item ->
            // Desktop's Home album card has no "Coming" pill.
            IosHomeAlbum(iosRecItem(item, today).copy(comingLabel = null), albumReasonBadge(item), homeAlbumCardKey(item))
        },
        albumChips = chips(state.albumSources),
        albumSource = state.albumSource,
        showAlbumChips = showSourceChips(state.albumSources),
        albumsLoading = visibility.albumsLoading,
        halves = halves(state, halfArt),
        concerts = state.concerts.map { concert(it, state.concertArtistImages) },
        askExample = state.askExample,
        chatAvailable = when (state.chatAvailable) {
            true -> "yes"
            false -> "no"
            null -> "unknown"
        },
        showBanner = visibility.showBanner,
        showJump = visibility.showJump,
        showImport = visibility.showImport,
        showMixes = visibility.showMixes,
        showPlaylists = visibility.showPlaylists,
        showAlbums = visibility.showAlbums,
        showConcerts = visibility.showConcerts,
        showAsk = visibility.showAsk,
    )
}

private fun chips(sources: List<FilterChipSource>): List<IosHomeChip> =
    homeSourceChips(sources).map { IosHomeChip(it.id, it.label, it.colorRgb ?: DesignTokens.Page.HOME.fill) }

private fun tile(tile: JumpTile, art: JumpTileArt, playlistArt: (String) -> String): IosJumpTile {
    val key = jumpTileKey(tile)
    val title = jumpTileTitle(tile)
    val image = if (tile is JumpTile.Playlist) art.imageUrl?.let(playlistArt) else art.imageUrl
    val base = IosJumpTile(
        key = key, kind = "", title = title, subtitle = tile.subtitle, imageUrl = image, mosaic = art.mosaic,
        targetId = key, targetName = title, targetArtist = null, targetUrl = null,
    )
    return when (tile) {
        is JumpTile.Playlist -> base.copy(kind = JUMP_KIND_PLAYLIST, targetId = tile.playlist.id, targetName = tile.playlist.name)
        is JumpTile.Loves -> base.copy(kind = JUMP_KIND_LOVES, targetId = "loves", targetName = HomeCopy.RECENT_LOVES)
        is JumpTile.Album -> base.copy(kind = JUMP_KIND_ALBUM, targetName = tile.album.album, targetArtist = tile.album.artist)
        is JumpTile.Mix -> base.copy(
            kind = JUMP_KIND_MIX, targetId = tile.mix.id, targetName = tile.mix.title,
            targetArtist = tile.mix.resolverName.orEmpty(), targetUrl = tile.mix.url,
        )
    }
}

private fun mixCard(card: HomeMixCard): IosHomeMixCard {
    val art = mixCardArt(card)
    return when (card) {
        is HomeMixCard.Loves -> IosHomeMixCard(
            mixCardKey(card), true, HomeCopy.RECENT_LOVES, mixCardSubtitle(card), art.imageUrl, art.mosaic, null, null,
        )
        is HomeMixCard.Mix -> IosHomeMixCard(
            mixCardKey(card), false, card.mix.title, mixCardSubtitle(card), art.imageUrl, art.mosaic,
            card.mix.url, card.mix.resolverName.orEmpty(),
        )
    }
}

/** A half's kind, its feed's cards (before art) and whether the feed is loading. */
private class HalfFeed(val kind: String, val page: DesignTokens.Page, val title: String, val feed: HomeFeed<*>, val cards: List<HomeHalfCard>) {
    val failed: Boolean get() = kind == HALF_KIND_CHARTS && feed.failed
}

private fun freshFeed(state: HomeState) =
    HalfFeed(HALF_KIND_FRESH, DesignTokens.Page.FRESH_DROPS, HomeCopy.FRESH_DROPS, state.fresh, freshHalfCards(state.fresh.items))

private fun criticalFeed(state: HomeState) =
    HalfFeed(HALF_KIND_CRITICAL, DesignTokens.Page.CRITICAL_DARLINGS, HomeCopy.CRITICAL_DARLINGS, state.critical, criticalHalfCards(state.critical.items))

private fun chartsFeed(state: HomeState) =
    HalfFeed(HALF_KIND_CHARTS, DesignTokens.Page.POP_OF_THE_TOPS, HomeCopy.POP_OF_THE_TOPS, state.charts, chartHalfCards(state.charts.items))

/** Every half card's key (see [halfCardKey]) to the card and whether its feed is loading. */
internal fun halfCardsByKey(state: HomeState): Map<String, Pair<HomeHalfCard, Boolean>> =
    listOf(freshFeed(state), criticalFeed(state), chartsFeed(state))
        .flatMap { f -> f.cards.map { halfCardKey(f.kind, it) to (it to f.feed.loading) } }
        .toMap()

/** A half card's key across halves: its half's kind, then its key in the half. */
private fun halfCardKey(kind: String, card: HomeHalfCard): String = "$kind:${card.key}"

private fun halves(state: HomeState, halfArt: Map<String, String>): List<IosHomeHalf> {
    val h = state.halves
    // As desktop and Android draw them; Pop of the Tops shows only while Fresh Drops doesn't.
    return listOfNotNull(
        freshFeed(state).takeIf { h.fresh },
        criticalFeed(state).takeIf { h.critical },
        chartsFeed(state).takeIf { h.charts },
    ).map { half(it, halfArt) }
}

private fun half(f: HalfFeed, halfArt: Map<String, String>): IosHomeHalf {
    val cards = withHalfCardArt(f.cards, halfArt)
    val body = homeHalfBody(cards.size, f.feed.loading, f.failed)
    return IosHomeHalf(
        kind = f.kind,
        title = f.title,
        pageColorId = f.page.name,
        body = when (body) {
            HomeHalfBody.CARDS -> HALF_BODY_CARDS
            HomeHalfBody.LOADING -> HALF_BODY_LOADING
            HomeHalfBody.ERROR -> HALF_BODY_FAILED
        },
        errorTitle = if (body == HomeHalfBody.ERROR) HomeCopy.CHARTS_ERROR_TITLE else null,
        errorText = if (body == HomeHalfBody.ERROR) HomeCopy.CHARTS_ERROR_BODY else null,
        cards = cards.map { c ->
            IosHalfCard(
                key = halfCardKey(f.kind, c),
                title = c.title,
                artist = c.artist,
                imageUrl = c.imageUrl,
                badge = c.releaseType ?: c.review,
                badgeColorRgb = c.releaseType?.let { DesignTokens.releaseTypeColor(c.releaseTypeKey) },
                needsArt = halfCardNeedsArt(c, f.feed.loading),
            )
        },
    )
}

private fun concert(e: ConcertEvent, images: Map<String, String>): IosHomeConcert {
    val artist = concertArtist(e)
    val tourArtist = concertTourArtist(e)
    val venue = concertVenue(e)
    val place = concertPlaceLine(e)
    val date = concertDateBadge(e.date)
    return IosHomeConcert(
        id = e.id,
        artist = artist,
        tourArtist = tourArtist,
        venue = venue,
        place = place,
        dateBadge = date,
        photoUrl = concertPhotoUrl(e, images),
        tickets = concertTickets(e).map { IosTicket(it.label, it.url) },
        a11yLabel = listOfNotNull(artist, date, venue, place).filter { it.isNotBlank() }.joinToString(", "),
        openLabel = tourArtist?.let { HomeCopy.concertsOf(it) },
    )
}

private fun gettingStarted(
    view: GettingStartedView,
    state: HomeState,
    halfArt: Map<String, String>,
    pickedStep: String?,
): IosGettingStarted {
    val current = view.status.current
    val explore = exploreHalves(
        criticalCount = state.critical.items.size,
        criticalLoading = state.critical.loading,
        chartsCount = state.charts.items.size,
        chartsLoading = state.charts.loading,
        chartsFailed = state.charts.failed,
    )
    return IosGettingStarted(
        steps = GETTING_STARTED_STEPS.map { info ->
            val s = view.status.steps.firstOrNull { it.id == info.id }
            IosGsStep(
                id = info.id,
                title = info.title,
                stateLine = stepStateLine(s, current),
                marker = when (stepMarkerLook(s, current)) {
                    StepMarkerLook.DONE -> "done"
                    StepMarkerLook.CURRENT -> "current"
                    StepMarkerLook.NOT_YET -> "todo"
                },
            )
        },
        openStep = openGettingStartedStep(pickedStep, view.status),
        services = view.services.map { (p, st) ->
            IosGsTile(p.id, p.name, serviceTileDescription(st), serviceTileLabel(p.name, st), serviceTileShowsBadge(st))
        },
        free = view.free.map { p ->
            val on = view.isOn(p)
            val text = freeTileDescription(on)
            IosGsTile(p.id, p.name, text, "${p.name}, $text", on)
        },
        folder = view.folder?.let { f ->
            val on = view.isOn(f)
            IosGsTile(f.id, HomeCopy.MUSIC_FOLDER, folderTileDescription(on, view.folderAdded), folderTileLabel(view.folderAdded), on)
        },
        scrobblers = view.scrobblers.map { (p, connected) ->
            IosGsTile(p.id, p.name, scrobblerTileDescription(connected), scrobblerTileLabel(p.name, connected), connected)
        },
        playlists = view.playlists.map { p ->
            val title = playlistTileTitle(p)
            val text = playlistTileDescription(p)
            IosGsTile(p.id, title, text, "$title, $text", false)
        },
        listenLine = listenStepLine(view.hasMusic),
        hasMusic = view.hasMusic,
        explore = listOfNotNull(
            criticalFeed(state).takeIf { explore.critical },
            chartsFeed(state).takeIf { explore.charts },
        ).map { half(it, halfArt) },
    )
}

// ── The session ──────────────────────────────────────────────────────

/**
 * iOS's Home, one for the whole app (`IosContainer.homeSession`): the shared
 * [HomeSession] plus the art lookups and what each tap plays. One instance, so
 * coming back to Home (a re-tapped tab, a return from a pushed page) keeps the
 * loads' 10-minute throttles and the Ask example, as Android's view model does.
 * Every suspend call catches and answers a DTO; none throws into Swift.
 */
class IosHomeSession internal constructor(
    private val sources: HomeSources,
    settings: SettingsStore,
    private val queues: HomeQueues,
    parentScope: CoroutineScope,
    lookupAlbumArt: suspend (album: String, artist: String) -> String?,
    /** `RecommendationsRepository.cardShown`: the album's release date and missing cover. */
    private val albumCardShown: (RecommendationItem) -> Unit,
    private val dismissAnnouncement: suspend (id: String) -> Unit,
    private val ctaClicked: (id: String) -> Unit,
    private val playlistArt: (String) -> String,
    private val today: () -> LocalDate,
) {
    private companion object {
        const val TAG = "IosHomeSession"
    }

    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Home work failed (ignored): ${e.message}", e) },
    )

    private val session = HomeSession(sources, settings, scope)

    private val tileArtLookup = JumpTileArtLookup(scope, lookupAlbumArt)
    private val halfArtLookup = ShownAlbumArtLookup(scope, lookupAlbumArt)
    private val pickedStep = MutableStateFlow<String?>(null)

    private val loves = sources.collectionTracks
        .map { recentLoves(it, HOME_RECENT_LOVES) }
        .distinctUntilChanged()

    /** Each tile's art by `jumpTileKey` ([jumpTilesArt]). */
    private val tileArt: StateFlow<Map<String, JumpTileArt>> = combine(
        session.state.map { it.tiles }.distinctUntilChanged(),
        loves,
        tileArtLookup.found,
    ) { tiles, l, found -> jumpTilesArt(tiles, l, found) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val state: HomeState get() = session.state.value

    /** The page as Swift draws it, rebuilt on every change. */
    fun watch(): Flow<IosHomePage> = combine(session.state, tileArt, halfArtLookup.found, pickedStep) { s, art, half, step ->
        iosHomePage(s, art, half, today(), step, playlistArt)
    }

    // ── Session actions ──────────────────────────────────────────────

    /** Home came on screen (a fresh entry or a return from a pushed page): the loads, under their throttles. */
    fun onVisible() = session.onVisible()

    /** A fresh entry into Home (not a return from a pushed page): a new phrasing for the Ask example. */
    fun enteredHome() = session.reseedAskExample()

    fun retryCharts() = session.retryCharts()
    fun setMixSource(id: String) = session.setMixSource(id)
    fun setAlbumSource(id: String) = session.setAlbumSource(id)
    fun skipSetup() = session.skipSetup()
    fun resumeSetup() = session.resumeSetup()
    fun dismissSetupItem(id: String) = session.dismissSetupItem(id)

    // ── Banner ───────────────────────────────────────────────────────

    /** The banner Home painted; its view is reported once per id. */
    fun bannerShown(id: String) = session.bannerShown(id)

    fun dismissBanner(id: String) {
        scope.launch { guard("dismiss banner") { dismissAnnouncement(id) } }
    }

    fun bannerCtaClicked(id: String) {
        try {
            ctaClicked(id)
        } catch (e: Exception) {
            Log.w(TAG, "Banner CTA report failed: ${e.message}")
        }
    }

    // ── Getting started ──────────────────────────────────────────────

    /** A step header was tapped: that step's panel opens ([openGettingStartedStep]). */
    fun openStep(id: String) { pickedStep.value = id }

    // ── Art ──────────────────────────────────────────────────────────

    /** A Jump back in tile came on screen: an album tile with no cover looks one up. */
    fun tileShown(key: String) {
        val tile = state.tiles.firstOrNull { jumpTileKey(it) == key } ?: return
        tileArtLookup.shown(tile, tileArt.value[key] ?: JumpTileArt())
    }

    /** A half card came on screen; one its feed finished without a cover looks one up. */
    fun halfCardShown(key: String) {
        val (card, loading) = halfCardsByKey(state)[key] ?: return
        val shown = withHalfCardArt(listOf(card), halfArtLookup.found.value).first()
        if (!halfCardNeedsArt(shown, loading)) return
        halfArtLookup.shown(halfCardArtKey(card), card.title, card.artist)
    }

    /** An album card came on screen: its release date and, with none, its cover. [key] is its id or [IosHomeAlbum.key]. */
    fun albumShown(key: String) {
        val item = albumFor(key) ?: return
        try {
            albumCardShown(item)
        } catch (e: Exception) {
            Log.w(TAG, "Album card report failed: ${e.message}")
        }
    }

    private fun albumFor(key: String): RecommendationItem? =
        state.albums.firstOrNull { homeAlbumCardKey(it) == key } ?: state.albums.firstOrNull { it.id.isNotEmpty() && it.id == key }

    // ── Queues (never throw) ─────────────────────────────────────────

    @Throws(Throwable::class)
    suspend fun tileQueue(key: String): IosHomeQueue {
        val tile = state.tiles.firstOrNull { jumpTileKey(it) == key } ?: return STALE
        return run(jumpTileTitle(tile)) { queues.tile(tile) }
    }

    @Throws(Throwable::class)
    suspend fun mixQueue(key: String): IosHomeQueue {
        val card = state.mixRow.firstOrNull { mixCardKey(it) == key } ?: return STALE
        return when (card) {
            is HomeMixCard.Loves -> run(HomeCopy.RECENT_LOVES) { queues.loves() }
            is HomeMixCard.Mix -> run(card.mix.title) { queues.mix(card.mix) }
        }
    }

    /** A playlist card's, a playlist tile's or a getting-started playlist's Play. */
    @Throws(Throwable::class)
    suspend fun playlistQueue(id: String): IosHomeQueue {
        val s = state
        val playlist = s.playlists.firstOrNull { it.playlist.id == id }?.playlist
            ?: s.gettingStarted?.playlists?.firstOrNull { it.id == id }
            ?: s.tiles.filterIsInstance<JumpTile.Playlist>().firstOrNull { it.playlist.id == id }?.playlist
            ?: return STALE
        return run(playlist.name) { queues.playlist(playlist.id, playlist.name) }
    }

    /** An "Albums you might love" card's Play. [key] is its id or [IosHomeAlbum.key]. */
    @Throws(Throwable::class)
    suspend fun albumQueue(key: String): IosHomeQueue {
        val item = albumFor(key) ?: return STALE
        return run(item.name) { queues.album(item.name, item.artist.orEmpty()) }
    }

    /** A half card's Play. */
    @Throws(Throwable::class)
    suspend fun halfAlbumQueue(key: String): IosHomeQueue {
        val (card, _) = halfCardsByKey(state)[key] ?: return STALE
        return run(card.title) { queues.album(card.title, card.artist) }
    }

    /** "Play Collection Radio". */
    @Throws(Throwable::class)
    suspend fun collectionRadio(): IosHomeQueue = run(HomeCopy.COLLECTION_RADIO) { queues.collectionRadio() }

    /**
     * Flattens a shared [HomeQueue] for Swift. The shared [HomeQueues] already turns a
     * load that throws into its "Could not load" failure; the catch here is only a last
     * resort, since a bridged suspend must never throw into Swift.
     */
    private suspend fun run(name: String, block: suspend () -> HomeQueue): IosHomeQueue = try {
        when (val q = block()) {
            is HomeQueue.Play -> IosHomeQueue(q.tracks, q.context, q.shuffle == true, null)
            is HomeQueue.Failed -> IosHomeQueue(emptyList(), null, false, q.message)
            HomeQueue.Empty -> STALE
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load '$name' to play: ${e.message}")
        IosHomeQueue(emptyList(), null, false, HomeCopy.couldNotLoad(name))
    }

    private suspend fun guard(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Home $what failed: ${e.message}")
        }
    }
}

/** A tap whose item has gone, or that has nothing to play: no tracks, no message. */
private val STALE = IosHomeQueue(emptyList(), null, false, null)
