package com.parachord.shared.repository

import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.RecommendedArtist
import com.parachord.shared.model.RecommendedTrack
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.plugin.PluginNotReadyException
import com.parachord.shared.recommendations.EXPANSION_PAGE_CAP
import com.parachord.shared.recommendations.ExpandedSection
import com.parachord.shared.recommendations.FriendExclusions
import com.parachord.shared.recommendations.PageableSection
import com.parachord.shared.recommendations.ProviderRecommendations
import com.parachord.shared.recommendations.RecommendationCategories
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.ReleaseDates
import com.parachord.shared.recommendations.SimilarUser
import com.parachord.shared.recommendations.buildRecommendationPage
import com.parachord.shared.recommendations.normalizeRecommendationItem
import com.parachord.shared.recommendations.normalizeRecommendationSections
import com.parachord.shared.recommendations.pageSources
import com.parachord.shared.recommendations.shuffleupagusSection
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.ui.UiMessages
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Recommendations page's data, shared: a port of desktop's
 * `loadRecommendations`, `refreshMergedRecommendations` and
 * `loadMoreRecommendationSection` (`app.js` ~L27622–28440), without the UI.
 *
 * - **Providers:** plugins with `capabilities.recommendations` that
 *   [providerIsOn] (the platform's `pluginIsOn`) and that aren't in
 *   `recommendations_off_resolvers`; Last.fm and ListenBrainz only with a
 *   username. Content resolvers first in the user's resolver order, then
 *   meta-services.
 * - **Per provider:** 20 s each ([PROVIDER_TIMEOUT_MS]; a late answer is still
 *   applied and saved); fresh for an hour, shown while it refreshes for 7
 *   days; keyed to the account. An empty answer replaces the stored one but is
 *   never served from the cache. A failure keeps the last answer;
 *   `PluginNotReadyException` drops it. Only the latest load paints; a forced
 *   refresh forgets the paged items.
 * - **Paging:** one request per `pagingKey` at a time, at most
 *   [EXPANSION_PAGE_CAP] pages; an empty page or a cursor that doesn't move
 *   ends it. [openRow] pages every pageable section of a row until done.
 * - **Merge:** [buildRecommendationPage] with Shuffleupagus's picks, the
 *   similar users and the blocklist, re-run on every change so a block takes
 *   effect at once.
 *
 * [getRecommendedTracks] / [getRecommendedArtists] keep today's screens
 * working from the page's Tracks and Artists rows until they move to [page].
 */
class RecommendationsRepository(
    private val source: RecommendationSource,
    private val settingsStore: SettingsStore,
    private val metadataService: MetadataService,
    private val aiRecommendations: ShuffleupagusPicks,
    /** Album release dates; the screens ask for the albums they show ([requestReleaseDates]). */
    val releaseDates: ReleaseDates,
    /** Whether a provider's plugin is on: the platform's `pluginIsOn` with its own settings. */
    private val providerIsOn: suspend (RecommendationProvider) -> Boolean,
    /** Read `recommendations_cache.json`; null if missing. */
    private val cacheRead: suspend () -> String?,
    /** Write `recommendations_cache.json`. */
    private val cacheWrite: suspend (String) -> Unit,
    /** Each similar-users service's people, by service id (Listeners). */
    private val similarUsers: suspend (force: Boolean) -> Map<String, List<SimilarUser>> = { emptyMap() },
    /** Who Listeners leaves out, read at every merge. */
    private val friendExclusions: suspend () -> FriendExclusions = { FriendExclusions() },
    private val nowMs: () -> Long = { currentTimeMillis() },
    /** Runs late answers, Shuffleupagus and release-date lookups past the caller. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    companion object {
        private const val TAG = "RecommendationsRepo"
        const val LASTFM = "lastfm"
        const val LISTENBRAINZ = "listenbrainz"
        const val PROVIDER_TIMEOUT_MS = 20_000L
        const val FRESH_MS = 60L * 60 * 1000
        const val STALE_MS = 7L * 24 * 60 * 60 * 1000
        const val NOT_CONFIGURED_MESSAGE = "Connect Last.fm or ListenBrainz in Settings to get recommendations"
        /** The old screens' failure text. */
        const val FAILED_MESSAGE = "Failed to load recommendations"
        /** The page's, desktop's. */
        const val PAGE_FAILED_MESSAGE = "Failed to load recommendations. Please try again."
        const val HERO_FALLBACK = "Personalized picks from your services"
        fun heroFor(username: String?): String =
            username?.takeIf { it.isNotBlank() }?.let { "Personalized picks for $it" } ?: HERO_FALLBACK
        private const val ARTIST_IMAGE_LOOKUPS = 15
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Guards every field below. Never held across a provider call. */
    private val mutex = Mutex()
    private val rebuildMutex = Mutex()
    private var diskLoaded = false
    private val cache = LinkedHashMap<String, ProviderAnswer>()
    private var shown: List<ProviderRecommendations> = emptyList()
    private var providerNames: Map<String, String> = emptyMap()
    private val expanded = mutableMapOf<String, ExpandedSection>()
    /** Providers whose last answer was empty: no skeleton for them. */
    private val quiet = mutableSetOf<String>()
    private val loadingMore = mutableSetOf<String>()
    private var similar: Map<String, List<SimilarUser>> = emptyMap()
    private var excludedPlaylists: Set<String> = emptySet()
    private var generation = 0
    private var inFlight: Deferred<LoadOutcome>? = null
    private var aiInFlight = false

    /** Photos found for the old Artists list, by lowercased name. */
    @Volatile private var artistImages: Map<String, String> = emptyMap()

    private val _page = MutableStateFlow(RecommendationPageState())
    val page: StateFlow<RecommendationPageState> = _page.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off messages for a snackbar ("Couldn't load more from …"). */
    val messages: SharedFlow<String> get() = _messages

    init {
        scope.launch { releaseDates.changes.collect { rebuild() } }
    }

    // ── Public surface ───────────────────────────────────────────────

    /** Loads the page: every provider (cache rules above), the similar users and Shuffleupagus. */
    suspend fun load(force: Boolean = false) {
        maybeLoadShuffleupagus(force)
        try {
            loadProviders(force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recommendations load failed", e)
            _page.update { it.copy(loading = false, pendingProviders = emptySet(), error = RecommendationsError.Message(PAGE_FAILED_MESSAGE)) }
        }
    }

    /** "See all": pages every pageable section of the row until each is exhausted, capped or fails. */
    suspend fun openRow(rowKey: String) {
        while (true) {
            val row = _page.value.sections.flatMap { it.rows }.firstOrNull { it.key == rowKey } ?: return
            val todo = mutex.withLock { row.pageable.filter { it.pagingKey !in loadingMore } }
            if (todo.isEmpty()) return
            val progressed = coroutineScope { todo.map { async { loadMore(it) } }.awaitAll() }
            if (progressed.none { it }) return
        }
    }

    /** Home's history mixes: play history, not recommendations, so the Playlists row leaves them out. */
    fun excludePlaylistIds(ids: Set<String>) {
        scope.launch {
            mutex.withLock { excludedPlaylists = ids }
            rebuild()
        }
    }

    /** Looks up the release dates of albums on screen; the page re-merges as they land. */
    fun requestReleaseDates(items: List<RecommendationItem>) {
        scope.launch { releaseDates.requestLookups(items) }
    }

    // ── Today's screens (removed when they move to [page]) ───────────

    val cachedTracksList: List<RecommendedTrack>?
        get() = currentTracks().takeIf { it.isNotEmpty() }
    val cachedArtistsList: List<RecommendedArtist>?
        get() = currentArtists().takeIf { it.isNotEmpty() }

    /** The page's Tracks row as [RecommendedTrack]s. */
    fun getRecommendedTracks(): Flow<Resource<List<RecommendedTrack>>> = flow {
        ensureDiskLoaded()
        emit(cachedTracksList?.let { Resource.Success(it) } ?: Resource.Loading)
        try {
            val outcome = loadProviders(false)
            if (!outcome.configured) {
                emit(Resource.Error(NOT_CONFIGURED_MESSAGE))
                return@flow
            }
            val tracks = currentTracks()
            if (tracks.isEmpty()) {
                emitEmpty(outcome)
                return@flow
            }
            emit(Resource.Success(tracks))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommended tracks", e)
            emit(Resource.Error(FAILED_MESSAGE))
        }
    }

    /** The page's Artists row as [RecommendedArtist]s, with photos filled in one by one. */
    fun getRecommendedArtists(): Flow<Resource<List<RecommendedArtist>>> = flow {
        ensureDiskLoaded()
        emit(cachedArtistsList?.let { Resource.Success(it) } ?: Resource.Loading)
        try {
            val outcome = loadProviders(false)
            if (!outcome.configured) {
                emit(Resource.Error(NOT_CONFIGURED_MESSAGE))
                return@flow
            }
            val artists = currentArtists().toMutableList()
            if (artists.isEmpty()) {
                emitEmpty(outcome)
                return@flow
            }
            emit(Resource.Success(artists.toList()))
            for ((index, artist) in artists.withIndex().filter { it.value.imageUrl == null }.take(ARTIST_IMAGE_LOOKUPS)) {
                val url = try {
                    metadataService.getArtistInfo(artist.name)?.imageUrl
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue
                artistImages = artistImages + (artist.name.lowercase().trim() to url)
                artists[index] = artist.copy(imageUrl = url)
                emit(Resource.Success(artists.toList()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommended artists", e)
            emit(Resource.Error(FAILED_MESSAGE))
        }
    }

    private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<Resource<List<T>>>.emitEmpty(outcome: LoadOutcome) {
        if (outcome.anyFailed) {
            // A provider couldn't answer: that isn't "nothing to recommend".
            emit(Resource.Error(FAILED_MESSAGE))
        } else {
            emit(Resource.Success(emptyList()))
            emit(Resource.Error(emptyRecommendationsMessage(outcome.hasListenBrainz, outcome.hasLastFm)))
        }
    }

    private fun rowItems(key: String): List<RecommendationItem> =
        _page.value.sections.flatMap { it.rows }.firstOrNull { it.key == key }?.items.orEmpty()

    private fun currentTracks(): List<RecommendedTrack> = rowItems("type:song").mapNotNull { it.toRecommendedTrack() }

    private fun currentArtists(): List<RecommendedArtist> {
        val images = artistImages
        return rowItems("type:artist").map { item ->
            RecommendedArtist(
                name = item.name,
                imageUrl = item.imageUrl ?: images[item.name.lowercase().trim()],
                reason = item.reason,
                source = item.resolverId.orEmpty(),
            )
        }
    }

    // ── Loading ──────────────────────────────────────────────────────

    /** What one load found, for the old flows' messages. */
    private class LoadOutcome(
        val configured: Boolean,
        val anyFailed: Boolean = false,
        val hasListenBrainz: Boolean = false,
        val hasLastFm: Boolean = false,
    )

    /** One load's providers and answers; [fresh] and [stale] are guarded by [mutex]. */
    private class LoadRun(val generation: Int, val providers: List<RecommendationProvider>) {
        val fresh = mutableMapOf<String, ProviderAnswer>()
        val stale = mutableMapOf<String, ProviderAnswer>()
        var failed = 0
    }

    /** A non-forced load joins one already running (both old flows share one fetch); a forced one starts anew. */
    private suspend fun loadProviders(force: Boolean): LoadOutcome {
        val job = mutex.withLock {
            val running = inFlight
            if (!force && running != null && running.isActive) running
            else scope.async { runLoad(force) }.also { inFlight = it }
        }
        return job.await()
    }

    private suspend fun runLoad(force: Boolean): LoadOutcome {
        ensureDiskLoaded()
        val lastFm = settingsStore.getLastFmUsername()?.takeIf { it.isNotBlank() }
        val listenBrainz = settingsStore.getListenBrainzUsername()?.takeIf { it.isNotBlank() }
        val hero = heroFor(listenBrainz ?: lastFm)
        val all = try {
            source.providers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list recommendation providers: ${e.message}")
            emptyList()
        }
        val off = settingsStore.getRecommendationsOffResolvers()
        val candidates = all.filter { p ->
            p.id !in off && when (p.id) {
                LASTFM -> lastFm != null
                LISTENBRAINZ -> listenBrainz != null
                else -> true
            } && providerIsOn(p)
        }
        val providers = orderProviders(candidates, settingsStore.getResolverOrder())
        val aiPossible = safely(false) { aiRecommendations.hasProvider() && aiRecommendations.sharesHistory() }
        val names = all.associate { it.id to it.name }

        if (lastFm == null && listenBrainz == null && providers.none { !it.isMetaService } && !aiPossible) {
            mutex.withLock {
                generation++
                shown = emptyList()
                providerNames = names
            }
            _page.update {
                it.copy(loading = false, pendingProviders = emptySet(), error = RecommendationsError.NotConfigured, hero = hero)
            }
            rebuild()
            return LoadOutcome(configured = false)
        }

        // A connected service whose plugin isn't loaded (the plugin runtime
        // didn't start) couldn't answer either.
        val missing = listOfNotNull(LASTFM.takeIf { lastFm != null }, LISTENBRAINZ.takeIf { listenBrainz != null })
            .count { id -> id !in off && all.none { it.id == id } }

        val now = nowMs()
        val toFetch = mutableListOf<RecommendationProvider>()
        val run = mutex.withLock {
            val run = LoadRun(++generation, providers)
            for (p in providers) {
                val cached = cache[p.id]?.takeIf { it.account == accountFor(p.id, lastFm, listenBrainz) }
                val hasSections = cached != null && cached.sections.isNotEmpty()
                if (!force && hasSections && now - cached!!.fetchedAt < FRESH_MS) {
                    run.fresh[p.id] = cached
                    continue
                }
                if (hasSections && now - cached!!.fetchedAt < STALE_MS) run.stale[p.id] = cached
                toFetch += p
            }
            if (force) expanded.clear()
            providerNames = names
            run
        }

        applyFresh(run)
        val pending = mutex.withLock { toFetch.filter { it.id !in run.stale && it.id !in quiet }.map { it.id }.toSet() }
        _page.update {
            it.copy(loading = it.sections.isEmpty() && toFetch.isNotEmpty(), pendingProviders = pending, error = null, hero = hero)
        }

        coroutineScope {
            launch {
                val users = try {
                    similarUsers(force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Similar users failed: ${e.message}")
                    null
                }
                if (users != null) {
                    val latest = mutex.withLock { (generation == run.generation).also { if (it) similar = users } }
                    if (latest) rebuild()
                }
            }
            for (provider in toFetch) launch { fetchOne(run, provider, accountFor(provider.id, lastFm, listenBrainz)) }
        }

        save()
        val failed = mutex.withLock { run.failed } + missing
        val outcome = LoadOutcome(configured = true, anyFailed = failed > 0, hasListenBrainz = listenBrainz != null, hasLastFm = lastFm != null)
        if (!isLatest(run)) return outcome

        val anySections = mutex.withLock { shown.any { it.sections.isNotEmpty() } }
        val aiHas = safely(null) { aiRecommendations.cached() }?.let { it.albums.isNotEmpty() || it.artists.isNotEmpty() } == true
        val anyListeners = _page.value.sections.any { it.type == null }
        val error = if (!anySections && !aiHas && !anyListeners) {
            if (failed > 0 && failed == toFetch.size + missing) RecommendationsError.Message(PAGE_FAILED_MESSAGE)
            else RecommendationsError.Message(emptyRecommendationsMessage(listenBrainz != null, lastFm != null))
        } else null
        _page.update { it.copy(loading = false, pendingProviders = emptySet(), error = error) }
        return outcome
    }

    private suspend fun fetchOne(run: LoadRun, provider: RecommendationProvider, account: String) {
        // The result travels as a Result, so a failure reaches this caller
        // (or the late path) and never fails [scope].
        val request = scope.async { runCatching { source.fetch(provider.id) } }
        try {
            val raw = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { request.await() }?.getOrThrow()
            if (raw == null) {
                // The bound only ends this provider's wait; a late answer is
                // still applied (if this load is still the latest) and saved.
                Log.w(TAG, "Recommendations from ${provider.name} timed out")
                scope.launch { applyLate(run, provider, account, request) }
                keepCached(run, provider, account)
            } else {
                accept(run, provider, account, raw)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PluginNotReadyException) {
            // Not ready is an answer: the cached sections belong to a session
            // that can't refresh them, so they go, on screen and on disk.
            Log.d(TAG, "Recommendations from ${provider.name} not ready: ${e.message}")
            mutex.withLock {
                run.failed++
                quiet += provider.id
                run.stale.remove(provider.id)
                cache.remove(provider.id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recommendations from ${provider.name} failed: ${e.message}")
            keepCached(run, provider, account)
        }
        applyFresh(run)
        if (isLatest(run)) _page.update { it.copy(pendingProviders = it.pendingProviders - provider.id) }
    }

    /** A failure says nothing: keep the cached answer, even an expired one. */
    private suspend fun keepCached(run: LoadRun, provider: RecommendationProvider, account: String) {
        mutex.withLock {
            run.failed++
            cache[provider.id]?.takeIf { it.account == account }?.let { run.fresh[provider.id] = it }
        }
    }

    private suspend fun applyLate(run: LoadRun, provider: RecommendationProvider, account: String, request: Deferred<Result<String>>) {
        try {
            accept(run, provider, account, request.await().getOrThrow())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        applyFresh(run)
        save()
        val hasSections = mutex.withLock { run.fresh[provider.id]?.sections?.isNotEmpty() == true }
        if (isLatest(run) && hasSections) _page.update { it.copy(loading = false, error = null) }
    }

    /** Stores an answer even when empty (it replaces the older one; an empty one is never served). */
    private suspend fun accept(run: LoadRun, provider: RecommendationProvider, account: String, raw: String) {
        val sections = normalizeRecommendationSections(json.parseToJsonElement(raw), provider.id, provider.name)
        val answer = ProviderAnswer(provider.id, provider.name, sections, nowMs(), account)
        mutex.withLock {
            run.fresh[provider.id] = answer
            cache[provider.id] = answer
            if (sections.isNotEmpty()) quiet -= provider.id else quiet += provider.id
        }
    }

    private suspend fun isLatest(run: LoadRun) = mutex.withLock { generation == run.generation }

    /** Puts this load's answers on screen, in provider order, if it's the latest. */
    private suspend fun applyFresh(run: LoadRun) {
        val applied = mutex.withLock {
            if (generation != run.generation) return@withLock false
            shown = run.providers.mapNotNull { p ->
                (run.fresh[p.id] ?: run.stale[p.id])?.let { ProviderRecommendations(p.id, p.name, it.sections, p.canExpand) }
            }
            true
        }
        if (applied) rebuild()
    }

    private suspend fun maybeLoadShuffleupagus(force: Boolean) {
        if (!safely(false) { aiRecommendations.hasProvider() }) return
        if (!force && safely(null) { aiRecommendations.cached() } != null) return
        if (!mutex.withLock { if (aiInFlight) false else { aiInFlight = true; true } }) return
        _page.update { it.copy(aiLoading = true) }
        scope.launch {
            try {
                aiRecommendations.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Shuffleupagus failed: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock { aiInFlight = false }
                    _page.update { it.copy(aiLoading = false) }
                }
            }
            rebuild()
            // Its picks may be all an empty page has.
            if (_page.value.sections.isNotEmpty()) {
                _page.update { if (it.error is RecommendationsError.Message) it.copy(error = null) else it }
            }
        }
    }

    // ── Paging ───────────────────────────────────────────────────────

    /** One page of [section]; true when the section changed (a page landed or it ran out), false on a failure. */
    private suspend fun loadMore(section: PageableSection): Boolean {
        val key = section.pagingKey
        if (!mutex.withLock { loadingMore.add(key) }) return false
        rebuild()
        try {
            val raw = source.expand(section.resolverId, section.sectionId, section.nextCursor)
            val obj = raw?.let { json.parseToJsonElement(it) as? JsonObject }
            val items = (obj?.get("items") as? JsonArray)
                ?.mapNotNull { normalizeRecommendationItem(it, section.resolverId) }.orEmpty()
            mutex.withLock {
                val prior = expanded[key] ?: ExpandedSection(emptyList())
                expanded[key] = if (items.isEmpty()) {
                    // Nothing more: the row's "load more" goes (a forced refresh resets it).
                    prior.copy(exhausted = true, lastPageTypes = emptyList(), lastPageCategories = emptyList())
                } else {
                    val answered = (obj?.get("nextCursor") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }
                    // A provider answering the cursor it was sent re-served the same window.
                    val nextCursor = answered?.takeIf { it != section.nextCursor }
                    val pages = prior.pages + 1
                    ExpandedSection(
                        items = prior.items + items,
                        nextCursor = nextCursor,
                        exhausted = nextCursor == null || pages >= EXPANSION_PAGE_CAP,
                        pages = pages,
                        lastPageTypes = items.map { it.type }.distinct(),
                        lastPageCategories = items
                            .filter { it.type == RecommendationType.ALBUM || it.type == RecommendationType.PLAYLIST }
                            .map { "${it.type.wire}:${RecommendationCategories.categoryFor(it.type, it, section.category)}" }
                            .distinct(),
                    )
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to expand ${section.title ?: key}: ${e.message}")
            _messages.tryEmit(UiMessages.couldntLoadMoreFrom(section.resolverName))
            return false
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { loadingMore.remove(key) }
                rebuild()
            }
        }
    }

    // ── Merge ────────────────────────────────────────────────────────

    /** Re-merges the page from what's held now. Cheap; the blocklist applies at every merge. */
    private suspend fun rebuild() = rebuildMutex.withLock {
        val (providers, pages, users, playlists, names, busy) = mutex.withLock {
            Snapshot(shown, expanded.toMap(), similar, excludedPlaylists, providerNames, loadingMore.toSet())
        }
        val blocked = safely(emptySet()) { settingsStore.getBlockedRecommendations() }
        val ai = safely(null) { aiRecommendations.cached() }
        val exclude = safely(FriendExclusions()) { friendExclusions() }
        val sections = withReleaseDates(
            buildRecommendationPage(
                providers = providers,
                shuffleupagus = ai?.let { shuffleupagusSection(it.albums, it.artists) },
                expanded = pages,
                similarUsers = users,
                exclude = exclude,
                excludePlaylistIds = playlists,
                blocked = blocked,
                providerNames = names,
            ),
        )
        _page.update { it.copy(sections = sections, sources = pageSources(sections), loadingMore = busy, paging = pages) }
    }

    private data class Snapshot(
        val providers: List<ProviderRecommendations>,
        val pages: Map<String, ExpandedSection>,
        val users: Map<String, List<SimilarUser>>,
        val playlists: Set<String>,
        val names: Map<String, String>,
        val busy: Set<String>,
    )

    /** Fills each album's release date from [releaseDates] when the provider sent none. */
    private suspend fun withReleaseDates(sections: List<RecommendationPageSection>): List<RecommendationPageSection> =
        sections.map { section ->
            if (section.type != RecommendationType.ALBUM) return@map section
            section.copy(
                rows = section.rows.map { row ->
                    row.copy(
                        items = row.items.map { item ->
                            if (item.releaseDate != null) item
                            else safely(null) { releaseDates.dateFor(item) }?.let { item.copy(releaseDate = it) } ?: item
                        },
                    )
                },
            )
        }

    // ── Disk ─────────────────────────────────────────────────────────

    /** Reads the saved answers once; this account's, younger than 7 days, show at once. */
    private suspend fun ensureDiskLoaded() {
        if (mutex.withLock { diskLoaded }) return
        val saved = try {
            cacheRead()?.let { json.decodeFromString(RecommendationsDiskCache.serializer(), it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recommendations cache unreadable: ${e.message}")
            null
        }
        val lastFm = settingsStore.getLastFmUsername()?.takeIf { it.isNotBlank() }
        val listenBrainz = settingsStore.getListenBrainzUsername()?.takeIf { it.isNotBlank() }
        val now = nowMs()
        val changed = mutex.withLock {
            if (diskLoaded) return@withLock false
            diskLoaded = true
            saved?.answers?.forEach { (id, answer) -> if (id !in cache) cache[id] = answer }
            if (shown.isNotEmpty()) return@withLock false
            shown = cache.values
                .filter { it.sections.isNotEmpty() && now - it.fetchedAt < STALE_MS && it.account == accountFor(it.resolverId, lastFm, listenBrainz) }
                .map { ProviderRecommendations(it.resolverId, it.resolverName, it.sections, canExpand = false) }
            providerNames = cache.values.associate { it.resolverId to it.resolverName }
            shown.isNotEmpty()
        }
        if (changed) rebuild()
    }

    /** Saves the answers that have sections and are young enough to show again, in page order. */
    private suspend fun save() {
        val now = nowMs()
        val answers = mutex.withLock {
            val order = shown.map { it.resolverId } + cache.keys
            val out = LinkedHashMap<String, ProviderAnswer>()
            for (id in order) {
                val a = cache[id] ?: continue
                if (id !in out && a.sections.isNotEmpty() && now - a.fetchedAt < STALE_MS) out[id] = a
            }
            out
        }
        try {
            cacheWrite(json.encodeToString(RecommendationsDiskCache.serializer(), RecommendationsDiskCache(answers, now)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recommendations cache not saved: ${e.message}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun accountFor(id: String, lastFm: String?, listenBrainz: String?): String = when (id) {
        LASTFM -> lastFm.orEmpty()
        LISTENBRAINZ -> listenBrainz.orEmpty()
        else -> ""
    }

    /** Content resolvers in the user's resolver order, then meta-services in their own order. */
    private fun orderProviders(list: List<RecommendationProvider>, order: List<String>): List<RecommendationProvider> {
        val rank = order.withIndex().associate { it.value to it.index }
        val (meta, content) = list.partition { it.isMetaService }
        return content.sortedBy { rank[it.id] ?: Int.MAX_VALUE } + meta
    }

    private suspend inline fun <T> safely(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }
}
