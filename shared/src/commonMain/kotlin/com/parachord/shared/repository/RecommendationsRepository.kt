package com.parachord.shared.repository

import com.parachord.shared.home.HomeRecsLoad
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
import com.parachord.shared.recommendations.RecommendationImages
import com.parachord.shared.recommendations.SimilarUser
import com.parachord.shared.recommendations.buildRecommendationPage
import com.parachord.shared.recommendations.normalizeRecommendationItem
import com.parachord.shared.recommendations.normalizeRecommendationSections
import com.parachord.shared.recommendations.pageSources
import com.parachord.shared.recommendations.shuffleupagusSection
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.ui.UiMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * - **Listeners:** [cachedSimilarUsers] show at once, then [similarUsers]
 *   (a [SimilarUsersStore]) replaces them when it answers.
 * - **Merge:** [buildRecommendationPage] with Shuffleupagus's picks, the
 *   similar users and the blocklist, re-run on every change so a block takes
 *   effect at once.
 * - **Shuffleupagus:** asked when it has no cached answer; a plain load
 *   doesn't ask again within [AI_RETRY_MS] of the last try (a forced one does).
 *
 * The Recommendations screens draw [page].
 */
class RecommendationsRepository(
    private val source: RecommendationSource,
    private val settingsStore: SettingsStore,
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
    /** Some similar-users provider is on and ready, so [similarUsers] will really ask (the Listeners skeleton shows only then). */
    private val canAskSimilarUsers: suspend () -> Boolean = { false },
    /** The saved similar users, shown at once while [similarUsers] asks again (an older shape among them). */
    private val cachedSimilarUsers: suspend () -> Map<String, List<SimilarUser>> = { emptyMap() },
    private val nowMs: () -> Long = { currentTimeMillis() },
    /**
     * Runs late answers, Shuffleupagus and release-date lookups past the caller.
     * The default logs an uncaught failure: on a handler-less scope it would
     * crash Android and abort the process on iOS.
     */
    private val scope: CoroutineScope = recommendationsBackgroundScope(),
    /** Album covers and artist photos for cards that came without one; the screens ask for the cards they show ([requestImages]). */
    val images: RecommendationImages = RecommendationImages({ null }, { _, _ -> null }),
) {
    companion object {
        private const val TAG = "RecommendationsRepo"
        const val LASTFM = "lastfm"
        const val LISTENBRAINZ = "listenbrainz"
        const val PROVIDER_TIMEOUT_MS = 20_000L
        const val FRESH_MS = 60L * 60 * 1000
        const val STALE_MS = 7L * 24 * 60 * 60 * 1000
        /** The page's, desktop's. */
        const val PAGE_FAILED_MESSAGE = "Failed to load recommendations. Please try again."
        const val HERO_FALLBACK = "Personalized picks from your services"
        fun heroFor(username: String?): String =
            username?.takeIf { it.isNotBlank() }?.let { "Personalized picks for $it" } ?: HERO_FALLBACK
        /** A Shuffleupagus answer is a 30–60 s paid call: with none cached, a plain load waits this long after the last try. */
        const val AI_RETRY_MS = 30L * 60 * 1000
        /** How long [cardShown] gathers cards before asking about them. */
        const val CARD_BATCH_MS = 300L
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
    /** When Shuffleupagus was last asked; a non-forced load doesn't ask again within [AI_RETRY_MS]. */
    private var lastAiAttemptMs: Long? = null

    private val _page = MutableStateFlow(RecommendationPageState())
    val page: StateFlow<RecommendationPageState> = _page.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off messages for a snackbar ("Couldn't load more from …"). */
    val messages: SharedFlow<String> get() = _messages

    init {
        scope.launch { releaseDates.changes.collect { rebuild() } }
        scope.launch { images.changes.collect { rebuild() } }
    }

    // ── Public surface ───────────────────────────────────────────────

    /**
     * Loads the page: every provider (cache rules above), the similar users and
     * Shuffleupagus. Answers what the load did, for Home's throttle: the
     * providers asked, how many of those failed, and the sections there are to
     * show (Shuffleupagus's cached picks count as one).
     */
    suspend fun load(force: Boolean = false): HomeRecsLoad {
        maybeLoadShuffleupagus(force)
        return try {
            val outcome = loadProviders(force)
            val sections = mutex.withLock { shown.sumOf { it.sections.size } }
            val aiHas = safely(null) { aiRecommendations.cached() }?.albums?.isNotEmpty() == true
            HomeRecsLoad(outcome.fetched, outcome.failed, sections + (if (aiHas) 1 else 0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recommendations load failed", e)
            _page.update { it.copy(loading = false, pendingProviders = emptySet(), error = RecommendationsError.Message(PAGE_FAILED_MESSAGE), listenersPending = false) }
            HomeRecsLoad(fetched = 0, failed = 0, sectionCount = 0)
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

    private val shownMutex = Mutex()
    private val shownBatch = mutableListOf<RecommendationItem>()
    private var shownBatchJob: Job? = null

    /**
     * A card came on screen. Cards shown within [CARD_BATCH_MS] of the first are
     * asked about together: the albums' release dates ([requestReleaseDates]),
     * then covers and photos for every card that lacks one ([requestImages]).
     * Both apps call it from each card as it appears.
     */
    fun cardShown(item: RecommendationItem) {
        scope.launch {
            shownMutex.withLock {
                shownBatch.add(item)
                if (shownBatchJob?.isActive == true) return@withLock
                shownBatchJob = scope.launch {
                    delay(CARD_BATCH_MS)
                    val batch = shownMutex.withLock { shownBatch.toList().also { shownBatch.clear() } }
                    if (batch.isEmpty()) return@launch
                    val albums = batch.filter { it.type == RecommendationType.ALBUM }
                    if (albums.isNotEmpty()) requestReleaseDates(albums)
                    requestImages(batch)
                }
            }
        }
    }

    /** Looks up the release dates of albums on screen; the page re-merges as they land. */
    fun requestReleaseDates(items: List<RecommendationItem>) {
        scope.launch { releaseDates.requestLookups(items) }
    }

    /** Looks up covers and photos for the album and artist cards on screen that have none ([images]). */
    fun requestImages(items: List<RecommendationItem>) {
        scope.launch { images.requestLookups(items) }
    }

    // ── Loading ──────────────────────────────────────────────────────

    /** What one load found: the providers it asked (cache misses), and how many of those couldn't answer. */
    private class LoadOutcome(
        val fetched: Int = 0,
        val failed: Int = 0,
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
                it.copy(loading = false, pendingProviders = emptySet(), listenersPending = false, error = RecommendationsError.NotConfigured, hero = hero)
            }
            rebuild()
            return LoadOutcome()
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

        val savedUsers = safely(emptyMap()) { cachedSimilarUsers() }
        if (savedUsers.isNotEmpty()) mutex.withLock { if (generation == run.generation) similar = savedUsers }
        applyFresh(run)
        val pending = mutex.withLock { toFetch.filter { it.id !in run.stale && it.id !in quiet }.map { it.id }.toSet() }
        val asking = safely(false) { canAskSimilarUsers() }
        _page.update {
            it.copy(
                loading = it.sections.isEmpty() && toFetch.isNotEmpty(),
                pendingProviders = pending,
                // The similar-users lookup is out and no Listeners row is on screen yet.
                listenersPending = asking && it.sections.none { section -> section.type == null },
                error = null,
                hero = hero,
            )
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
                val latest = mutex.withLock { (generation == run.generation).also { if (it && users != null) similar = users } }
                if (latest && users != null) rebuild()
                if (latest) _page.update { it.copy(listenersPending = false) }
            }
            for (provider in toFetch) launch { fetchOne(run, provider, accountFor(provider.id, lastFm, listenBrainz)) }
        }

        save()
        val failed = mutex.withLock { run.failed } + missing
        val outcome = LoadOutcome(fetched = toFetch.size + missing, failed = failed)
        if (!isLatest(run)) return outcome

        val anySections = mutex.withLock { shown.any { it.sections.isNotEmpty() } }
        val aiHas = safely(null) { aiRecommendations.cached() }?.let { it.albums.isNotEmpty() || it.artists.isNotEmpty() } == true
        val anyListeners = _page.value.sections.any { it.type == null }
        val error = if (!anySections && !aiHas && !anyListeners) {
            if (failed > 0 && failed == toFetch.size + missing) RecommendationsError.Message(PAGE_FAILED_MESSAGE)
            else RecommendationsError.Message(emptyRecommendationsMessage(listenBrainz != null, lastFm != null))
        } else null
        _page.update { it.copy(loading = false, pendingProviders = emptySet(), listenersPending = false, error = error) }
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
        val answer = ProviderAnswer(provider.id, provider.name, sections, nowMs(), account, provider.isMetaService, provider.needsAccount)
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
        val now = nowMs()
        val go = mutex.withLock {
            val recent = lastAiAttemptMs?.let { now - it < AI_RETRY_MS } == true
            if (aiInFlight || (!force && recent)) false
            else { aiInFlight = true; lastAiAttemptMs = now; true }
        }
        if (!go) return
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

    /**
     * Re-merges the page from what's held now. Cheap; the blocklist applies at
     * every merge. A failure keeps the page as it was, so a later change can
     * still rebuild it.
     */
    private suspend fun rebuild() = rebuildMutex.withLock {
        try {
            val (providers, pages, users, playlists, names, busy) = mutex.withLock {
                Snapshot(shown, expanded.toMap(), similar, excludedPlaylists, providerNames, loadingMore.toSet())
            }
            val blocked = safely(emptySet()) { settingsStore.getBlockedRecommendations() }
            val ai = safely(null) { aiRecommendations.cached() }
            val exclude = safely(FriendExclusions()) { friendExclusions() }
            val sections = withImages(withReleaseDates(
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
            ))
            // Only the providers carry weekly playlists, so this build skips
            // Shuffleupagus and the Listeners row, and runs only when
            // something is excluded (desktop's buildRecommendationViews).
            val weeklySections = if (playlists.isEmpty()) sections else buildRecommendationPage(
                providers = providers,
                expanded = pages,
                blocked = blocked,
                providerNames = names,
            )
            _page.update {
                it.copy(
                    sections = sections, weeklySections = weeklySections, sources = pageSources(sections),
                    loadingMore = busy, paging = pages,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recommendations page rebuild failed; keeping the last page", e)
        }
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

    /** Fills each album's cover and artist's photo from [images] when the provider sent none. */
    private suspend fun withImages(sections: List<RecommendationPageSection>): List<RecommendationPageSection> =
        sections.map { section ->
            section.copy(
                rows = section.rows.map { row ->
                    row.copy(
                        items = row.items.map { item ->
                            if (!item.imageUrl.isNullOrBlank() || RecommendationImages.imageKey(item) == null) item
                            else safely(null) { images.imageFor(item) }?.let { item.copy(imageUrl = it) } ?: item
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
        // The same gate a load applies, so a provider switched off since its
        // answer was saved never shows, not even until the load replaces it.
        val off = safely(emptySet()) { settingsStore.getRecommendationsOffResolvers() }
        val allowed = saved?.answers?.values.orEmpty()
            .filter { a ->
                a.resolverId !in off && safely(false) {
                    providerIsOn(
                        RecommendationProvider(
                            id = a.resolverId,
                            name = a.resolverName,
                            isMetaService = a.isMetaService || a.resolverId == LASTFM || a.resolverId == LISTENBRAINZ,
                            needsAccount = a.needsAccount,
                        ),
                    )
                }
            }
            .map { it.resolverId }.toSet()
        val changed = mutex.withLock {
            if (diskLoaded) return@withLock false
            diskLoaded = true
            saved?.answers?.forEach { (id, answer) -> if (id !in cache) cache[id] = answer }
            if (shown.isNotEmpty()) return@withLock false
            shown = cache.values
                .filter { it.resolverId in allowed && it.sections.isNotEmpty() && now - it.fetchedAt < STALE_MS && it.account == accountFor(it.resolverId, lastFm, listenBrainz) }
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

    private fun orderProviders(list: List<RecommendationProvider>, order: List<String>): List<RecommendationProvider> =
        orderRecommendationProviders(list, order)

    private suspend inline fun <T> safely(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }
}

/** Content resolvers in the user's resolver order, then meta-services in their own order (desktop's `orderCatalogResolvers`). */
internal fun orderRecommendationProviders(list: List<RecommendationProvider>, order: List<String>): List<RecommendationProvider> {
    val rank = order.withIndex().associate { it.value to it.index }
    val (meta, content) = list.partition { it.isMetaService }
    return content.sortedBy { rank[it.id] ?: Int.MAX_VALUE } + meta
}

/**
 * A handler-less scope would crash Android and abort the process on iOS on an
 * uncaught failure; this one logs it. A top-level function, not a lambda in
 * the default argument: Kotlin/Native 2.1.0 can't compile a local
 * `CoroutineExceptionHandler` class there.
 */
internal fun recommendationsBackgroundScope(tag: String = "RecommendationsRepo"): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e -> Log.e(tag, "Background work failed (ignored): ${e.message}", e) },
)
