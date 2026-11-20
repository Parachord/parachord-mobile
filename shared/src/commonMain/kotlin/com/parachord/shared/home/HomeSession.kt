package com.parachord.shared.home

import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.api.Announcement
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.recommendations.homeRecommendationAlbums
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop
import com.parachord.shared.repository.HistoryMixesState
import com.parachord.shared.repository.recentLoves
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Home, for both apps: gathers [HomeSources] into one [HomeState] through the
 * shared Home rules, and runs Home's loads. Android's `HomeViewModel` and
 * iOS's bridge wrap it and only draw.
 *
 * - **Loads** ([onVisible]): recommendations and history mixes, each on its
 *   own 10-minute throttle ([shouldLoadHomeRecommendations]; a load counts
 *   only when [shouldStampHomeRecommendationsLoad] says so, and one that
 *   throws never counts), the feeds where not loaded, and concerts only with a saved
 *   location. Each runs on its own; a failure is logged and the rest go on.
 * - **Getting started:** decided once from the saved state, else
 *   [initialGettingStarted] on the first values of the sources, and saved.
 *   Nothing is decided (and [HomeState.ready] stays false) until the plugins
 *   have loaded, at most [GETTING_STARTED_DECIDE_TIMEOUT_MS]; past that the
 *   layout is off and nothing is saved. Playback starting while it's on saves `played`; when every
 *   step is done it goes off and is saved.
 * - **Jump back in** leaves out what's playing: [ActiveContexts] with the
 *   playing context only (mobile queue entries carry no context).
 * - **The Ask example** has one phrasing per visit ([reseedAskExample] picks a
 *   new one); once it's built from artists it stays for the visit.
 *
 * All work runs on a child of [scope] that carries a [CoroutineExceptionHandler]
 * (an uncaught exception on a handler-less scope aborts the iOS process).
 */
class HomeSession(
    private val sources: HomeSources,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "HomeSession"

        /** How long the getting-started decision waits for the plugins and the other inputs. */
        const val GETTING_STARTED_DECIDE_TIMEOUT_MS = 8_000L
    }

    private val scope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Home work failed (ignored): ${e.message}", e) },
    )

    private data class GsHolder(val ready: Boolean = false, val state: GettingStartedState? = null)
    private data class AskSeed(val generation: Int, val seed: Int)
    private data class AskLatch(val generation: Int, val text: String, val generic: Boolean)

    private val gs = MutableStateFlow(GsHolder())
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())
    private val mixSource = MutableStateFlow(HOME_ALL_SOURCES)
    private val albumSource = MutableStateFlow(HOME_ALL_SOURCES)
    private val location = MutableStateFlow<SettingsStore.ConcertLocation?>(null)
    private val recsInFlight = MutableStateFlow(false)
    private val recsAttempted = MutableStateFlow(false)
    private val askSeed = MutableStateFlow(AskSeed(0, seedNow()))
    /** Bumped on each visit, so the greeting and the time phrases are worked out again. */
    private val tick = MutableStateFlow(0)

    /** Read and written only by the state's single collector. */
    private var askLatch: AskLatch? = null

    private val gsMutex = Mutex()
    private val throttleMutex = Mutex()
    private var recsLoading = false
    private var recsLastAt: Long? = null
    private var mixesLoading = false
    private var mixesLastAt: Long? = null
    private val bannersViewed = mutableSetOf<String>()

    // ── Inputs ──────────────────────────────────────────────────────────────

    private data class GsInputs(
        val pluginsLoaded: Boolean,
        val stepOne: StepOnePlugins,
        val accounts: Map<String, Boolean>,
        val resolvers: List<String>,
        val folderAdded: Boolean,
        val scrobbling: Boolean,
        val hasMusic: Boolean,
    ) {
        val connectDone: Boolean
            get() = serviceConnected(stepOne.services, accounts, playbackResolvers = resolvers) || folderAdded
        val musicDone: Boolean get() = hasMusic || scrobbling

        fun status(played: Boolean): GettingStartedStatus = gettingStartedSteps(
            connected = serviceConnected(stepOne.services, accounts, playbackResolvers = resolvers),
            folderAdded = folderAdded,
            hasMusic = hasMusic,
            scrobbling = scrobbling,
            played = played,
        )
    }

    private val gsInputs: Flow<GsInputs> = combine(
        combine(sources.plugins, sources.accounts, sources.playbackResolvers) { p, a, r -> Triple(p, a, r) },
        combine(sources.folderAdded, sources.scrobblerUsernames, sources.hasCollection, sources.playlists) { f, u, c, pl ->
            Triple(f, scrobblerConnected(u), c || pl.isNotEmpty())
        },
    ) { (plugins, accounts, resolvers), (folder, scrobbling, hasMusic) ->
        GsInputs(plugins.isNotEmpty(), stepOnePlugins(plugins), accounts, resolvers, folder, scrobbling, hasMusic)
    }

    private data class LibraryIn(
        val playlists: List<Playlist>,
        val tracks: List<Track>,
        val hasCollection: Boolean,
        val playing: PlaybackContext?,
        val announcements: List<Announcement>,
    )

    private data class RecsIn(
        val mixes: HistoryMixesState,
        val sections: List<RecommendationPageSection>?,
        val aiAlbums: List<AiAlbumSuggestion>?,
        val recsInFlight: Boolean,
        val recsAttempted: Boolean,
    )

    private data class FeedsIn(
        val fresh: HomeFeed<FreshDrop>,
        val critical: HomeFeed<CriticsPickAlbum>,
        val charts: HomeFeed<ChartAlbum>,
        val concerts: HomeFeed<ConcertEvent>,
        val location: SettingsStore.ConcertLocation?,
    )

    private data class UiIn(
        val gs: GsHolder,
        val dismissed: Set<String>,
        val mixSource: String,
        val albumSource: String,
        val askSeed: AskSeed,
        val tick: Int,
    )

    private val library = combine(
        sources.playlists, sources.collectionTracks, sources.hasCollection, sources.playingContext, sources.announcements,
    ) { p, t, c, ctx, a -> LibraryIn(p, t, c, ctx, a) }

    private val recs = combine(
        sources.historyMixes, sources.recommendationSections, sources.aiAlbums, recsInFlight, recsAttempted,
    ) { m, s, ai, f, at -> RecsIn(m, s, ai, f, at) }

    private val feeds = combine(
        sources.freshDrops, sources.criticalDarlings, sources.charts, sources.concerts, location,
    ) { f, c, ch, co, l -> FeedsIn(f, c, ch, co, l) }

    private val ui = combine(gs, dismissed, combine(mixSource, albumSource) { m, a -> m to a }, askSeed, tick) { g, d, (m, a), s, t ->
        UiIn(g, d, m, a, s, t)
    }

    private val chat = sources.chatAvailable

    val state: StateFlow<HomeState> = combine(library, recs, feeds, ui, combine(gsInputs, chat) { g, c -> g to c }) {
            lib, r, f, u, (inputs, chatAvailable) ->
        build(lib, r, f, u, inputs, chatAvailable)
    }.stateIn(this.scope, SharingStarted.Eagerly, HomeState())

    init {
        this.scope.launch { decideGettingStarted() }
        this.scope.launch { refreshLocation() }
        // Every step done: the layout goes off, and that's saved.
        this.scope.launch {
            combine(gs, gsInputs) { holder, inputs -> holder to inputs }.collect { (holder, inputs) ->
                val st = holder.state ?: return@collect
                if (st.active && inputs.status(st.played).allDone) updateGs { it.copy(active = false) }
            }
        }
        // Playback while the layout is on counts as played (desktop: `isPlaying`;
        // a failed play or a restored, paused queue doesn't).
        this.scope.launch {
            combine(gs, sources.isPlaying) { holder, playing -> holder to playing }.collect { (holder, playing) ->
                val st = holder.state ?: return@collect
                if (playing && st.active && !st.played) updateGs { it.copy(played = true) }
            }
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    /** Home came on screen: [HomeSources.refresh], then the loads, under their throttles. Nothing waits on the network. */
    fun onVisible() {
        tick.value = tick.value + 1
        scope.launch { guard("refresh") { sources.refresh() } }
        scope.launch { loadRecommendations() }
        scope.launch { loadMixes() }
        scope.launch { guard("feeds") { sources.loadFeeds(false) } }
        scope.launch {
            refreshLocation()
            if (hasLocation(location.value)) guard("concerts") { sources.loadConcerts() }
        }
    }

    /** Pop of the Tops' Try again. */
    fun retryCharts() {
        scope.launch { guard("charts") { sources.loadFeeds(true) } }
    }

    /** A mix source chip; [HOME_ALL_SOURCES] goes back to All. */
    fun setMixSource(id: String) { mixSource.value = id }

    fun setAlbumSource(id: String) { albumSource.value = id }

    /** "Skip setup for now": the layout goes, and the pill offers it until the steps are done. */
    fun skipSetup() {
        scope.launch { updateGs { it.copy(active = false, skipped = true) } }
    }

    /** The pill's "Finish getting started": the layout comes back, steps as they were. */
    fun resumeSetup() {
        scope.launch { updateGs { it.copy(active = true, skipped = false) } }
    }

    /** The pill's ×: that item is gone for good. */
    fun dismissSetupItem(id: String) {
        scope.launch {
            val next = gsMutex.withLock {
                (dismissed.value + id).also { dismissed.value = it }
            }
            // Merged with the saved set, in case this lands before it was read.
            guard("save dismissed setup items") { settings.setHomeSetupDismissed(settings.getHomeSetupDismissed() + next) }
        }
    }

    /** The banner Home painted; its view is reported once per id. */
    fun bannerShown(id: String) {
        if (id.isEmpty()) return
        scope.launch {
            val first = throttleMutex.withLock { bannersViewed.add(id) }
            if (first) guard("banner view") { sources.bannerViewed(id) }
        }
    }

    /** A new visit: a new phrasing for the Ask example. */
    fun reseedAskExample() {
        val current = askSeed.value
        askSeed.value = AskSeed(current.generation + 1, seedNow())
    }

    // ── Loads ───────────────────────────────────────────────────────────────

    private suspend fun loadRecommendations() {
        val go = throttleMutex.withLock {
            if (!shouldLoadHomeRecommendations(recsLoading, loadStarted = true, lastFullLoadAt = recsLastAt, nowMs = sources.nowMs())) {
                false
            } else {
                recsLoading = true
                true
            }
        }
        if (!go) return
        recsInFlight.value = true
        try {
            val result = sources.loadRecommendations(false)
            if (shouldStampHomeRecommendationsLoad(result.fetched, result.failed, result.sectionCount)) {
                throttleMutex.withLock { recsLastAt = sources.nowMs() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recommendations load failed: ${e.message}")
        } finally {
            throttleMutex.withLock { recsLoading = false }
            recsAttempted.value = true
            recsInFlight.value = false
        }
    }

    private suspend fun loadMixes() {
        val go = throttleMutex.withLock {
            if (!shouldLoadHomeRecommendations(mixesLoading, loadStarted = true, lastFullLoadAt = mixesLastAt, nowMs = sources.nowMs())) {
                false
            } else {
                mixesLoading = true
                true
            }
        }
        if (!go) return
        try {
            val result = sources.loadHistoryMixes(false)
            if (shouldStampHomeRecommendationsLoad(result.fetched, result.failed, result.sectionCount)) {
                throttleMutex.withLock { mixesLastAt = sources.nowMs() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "History mixes load failed: ${e.message}")
        } finally {
            throttleMutex.withLock { mixesLoading = false }
        }
    }

    private suspend fun refreshLocation() {
        try {
            location.value = sources.concertLocation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the concert location: ${e.message}")
        }
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

    // ── Getting started ─────────────────────────────────────────────────────

    private suspend fun decideGettingStarted() {
        try {
            val saved = settings.getHomeSetupDismissed()
            gsMutex.withLock { dismissed.value = saved + dismissed.value }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read dismissed setup items: ${e.message}")
        }
        val saved = try {
            settings.getHomeGettingStarted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the getting-started state: ${e.message}")
            null
        }
        // A saved state wins, with no need to wait. Otherwise desktop decides
        // only once resolvers have loaded: an empty list here would read a
        // connected user as having nothing to play from. Plugins that never
        // load (the JS runtime failed) get the existing-user default, unsaved,
        // so the page still draws and the next launch decides again.
        val inputs = if (saved != null) null else withTimeoutOrNull(GETTING_STARTED_DECIDE_TIMEOUT_MS) {
            sources.plugins.first { it.isNotEmpty() }
            gsInputs.first { it.pluginsLoaded }
        }
        if (saved == null && inputs == null) Log.w(TAG, "Plugins didn't load in time; Home draws without getting started")
        val decided = when {
            saved != null -> saved
            inputs != null -> initialGettingStarted(null, inputs.connectDone, inputs.musicDone)
            else -> GettingStartedState(active = false)
        }
        gsMutex.withLock {
            // An action that came first (skip, resume) wins.
            if (gs.value.state == null) gs.value = GsHolder(ready = true, state = decided)
            else gs.value = gs.value.copy(ready = true)
        }
        if (saved == null && inputs != null) guard("save getting-started state") { settings.setHomeGettingStarted(gs.value.state ?: decided) }
    }

    private suspend fun updateGs(change: (GettingStartedState) -> GettingStartedState) {
        val next = gsMutex.withLock {
            val current = gs.value.state ?: GettingStartedState()
            val next = change(current)
            if (next == current && gs.value.state != null) return
            gs.value = gs.value.copy(state = next)
            next
        }
        guard("save getting-started state") { settings.setHomeGettingStarted(next) }
    }

    // ── State ───────────────────────────────────────────────────────────────

    private fun build(
        lib: LibraryIn,
        r: RecsIn,
        f: FeedsIn,
        u: UiIn,
        inputs: GsInputs,
        chatAvailable: Boolean?,
    ): HomeState {
        val now = sources.nowMs()
        val loves = recentLoves(lib.tracks, HOME_RECENT_LOVES)
        val mixes = r.mixes.flat

        // Getting started and the pill.
        val gsState = u.gs.state
        val status = inputs.status(gsState?.played == true)
        val gettingStarted = if (showGettingStarted(gsState, status)) {
            GettingStartedView(
                status = status,
                services = inputs.stepOne.services.map { it to serviceState(it, inputs.accounts, playbackResolvers = inputs.resolvers) },
                free = inputs.stepOne.free,
                folder = inputs.stepOne.folder,
                hasMusic = inputs.hasMusic,
            )
        } else null
        val setup = homeSetupItems(gettingStartedLeft(gsState, status), u.dismissed).takeIf { it.items.isNotEmpty() }

        // Header and banner.
        val bannerItems = homeBannerItems(lib.announcements)
        val changeLine = freshestPlaylists(lib.playlists, 1).firstOrNull()?.let { freshChangeLine(it, now) }

        // Jump back in.
        val active = ActiveContexts(playing = lib.playing, queued = null)
        val tiles = jumpBackInTiles(lib.playlists, loves, mixes, active, now)

        // Made from your listening.
        val mixSources = chips(mixes.map { Triple(it.resolverId, it.resolverName, emptyList<String>()) })
        val mixPick = effectiveSource(u.mixSource, mixSources)
        val lovesCard = if (mixPick == HOME_ALL_SOURCES && loves.isNotEmpty()) {
            HomeMixCard.Loves(loves, lovesMosaicAlbums(loves), HomeCopy.lovesSubtitle(loves.size))
        } else null
        val mixCards = filterBySource(mixes, mixPick, { it.resolverId }, { emptyList() })
            .take(HOME_ROW_CARDS - (if (lovesCard != null) 1 else 0))
            .map { HomeMixCard.Mix(it) }
        val mixRow = listOfNotNull(lovesCard) + mixCards

        // Your playlists, freshest first.
        val playlists = freshestPlaylists(lib.playlists, HOME_ROW_CARDS)
            .map { HomePlaylistCard(it, changeBadge(it, now), playlistSubtitle(it, now)) }

        // Albums you might love.
        val allAlbums = homeRecommendationAlbums(r.sections, r.aiAlbums)
        val albumSources = chips(allAlbums.map { Triple(it.resolverId, it.resolverName, it.sourceIds) })
        val albumPick = effectiveSource(u.albumSource, albumSources)
        val albums = filterBySource(allAlbums, albumPick, { it.resolverId }, { it.sourceIds }).take(HOME_ROW_CARDS)
        val albumsLoading = allAlbums.isEmpty() &&
            (r.recsInFlight || (!r.recsAttempted && r.sections == null && r.aiAlbums == null))

        // Fresh Drops / Critical Darlings / Pop of the Tops.
        val fresh = f.fresh.copy(items = f.fresh.items.take(HOME_HALF_ROW_CARDS))
        val critical = f.critical.copy(items = f.critical.items.take(HOME_HALF_ROW_CARDS))
        val charts = f.charts.copy(items = f.charts.items.take(HOME_HALF_ROW_CARDS))
        val halves = homeHalves(
            freshCount = fresh.items.size, freshLoading = fresh.loading,
            criticalCount = critical.items.size, criticalLoading = critical.loading,
            chartsCount = charts.items.size, chartsLoading = charts.loading, chartsFailed = charts.failed,
        )

        // Concerts near you.
        val located = hasLocation(f.location)
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).date
        val concerts = if (located) concertsNearYou(f.concerts.items, f.location, today, HOME_CONCERT_CARDS) else emptyList()
        val concertsLoading = located && concerts.isEmpty() && f.concerts.loading && !f.concerts.failed

        val sections = homeSections(
            bannerItems = bannerItems,
            tiles = tiles,
            mixes = mixes,
            loves = loves,
            playlists = playlists,
            albums = allAlbums,
            fresh = fresh.items,
            critical = critical.items,
            concerts = concerts,
            concertsLocation = locationLabel(f.location),
            hasCollection = lib.hasCollection,
        )

        return HomeState(
            ready = u.gs.ready,
            greeting = homeGreeting(sources.hour()),
            changeLine = changeLine,
            hasCollection = lib.hasCollection,
            setup = setup,
            banner = bannerItems.firstOrNull(),
            gettingStarted = gettingStarted,
            tiles = tiles,
            showImportCard = sections.importCard && tiles.isEmpty(),
            mixRow = mixRow,
            mixSources = mixSources,
            mixSource = mixPick,
            playlists = playlists,
            albums = albums,
            albumSources = albumSources,
            albumSource = albumPick,
            albumsLoading = albumsLoading,
            halves = halves,
            fresh = fresh,
            critical = critical,
            charts = charts,
            concerts = concerts,
            concertsLoading = concertsLoading,
            askExample = askExample(loves, u.askSeed),
            chatAvailable = chatAvailable,
            sections = sections,
        )
    }

    /** One per visit; once built from artists it stays for the visit (desktop's `askPrompt` ref). */
    private fun askExample(loves: List<Track>, seed: AskSeed): String {
        val latch = askLatch
        if (latch != null && latch.generation == seed.generation && !latch.generic) return latch.text
        val artists = homeTopArtists(loves)
        val text = exampleShufflePrompt(artists, seed.seed)
        askLatch = AskLatch(seed.generation, text, generic = artists.isEmpty())
        return text
    }

    private fun seedNow(): Int = (sources.nowMs() % 4).toInt()

    /**
     * One chip per source with an item, in first-seen order (desktop's
     * `sourceChips` without its "All"). A source is named by the item it leads
     * (`resolverName`), else by its id.
     */
    private fun chips(items: List<Triple<String?, String?, List<String>>>): List<FilterChipSource> {
        val names = mutableMapOf<String, String>()
        for ((id, name, _) in items) if (!id.isNullOrEmpty() && !name.isNullOrEmpty() && id !in names) names[id] = name
        val out = LinkedHashMap<String, FilterChipSource>()
        for ((id, _, ids) in items) {
            for (sid in listOf(id) + ids) {
                if (sid.isNullOrEmpty() || sid in out) continue
                out[sid] = FilterChipSource(sid, names[sid] ?: sid)
            }
        }
        return out.values.toList()
    }

    /** A chip whose source has gone leaves the row on All. */
    private fun effectiveSource(picked: String, chips: List<FilterChipSource>): String =
        if (picked == HOME_ALL_SOURCES || chips.any { it.id == picked }) picked else HOME_ALL_SOURCES

    private fun hasLocation(l: SettingsStore.ConcertLocation?): Boolean =
        l != null && (!l.city.isNullOrBlank() || (l.latitude != null && l.longitude != null))

    private fun locationLabel(l: SettingsStore.ConcertLocation?): String? = when {
        l == null -> null
        !l.city.isNullOrBlank() -> l.city
        l.latitude != null && l.longitude != null -> "${l.latitude},${l.longitude}"
        else -> null
    }
}
