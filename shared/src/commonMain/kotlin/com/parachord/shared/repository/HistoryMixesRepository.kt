package com.parachord.shared.repository

import com.parachord.shared.home.HOME_MIX_COVER_ATTEMPTS
import com.parachord.shared.home.HOME_MIX_COVER_RETRY_MS
import com.parachord.shared.home.HistoryMix
import com.parachord.shared.home.HistoryMixCacheEntry
import com.parachord.shared.home.HistoryMixEntry
import com.parachord.shared.home.flattenHistoryMixes
import com.parachord.shared.home.historyMixCacheEntry
import com.parachord.shared.home.historyMixEntriesFor
import com.parachord.shared.home.mixesNeedingMosaic
import com.parachord.shared.home.normalizeHistoryMixes
import com.parachord.shared.home.shouldLoadHomeRecommendations
import com.parachord.shared.home.shouldStampHomeRecommendationsLoad
import com.parachord.shared.home.withMixMosaics
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.plugin.PluginNotReadyException
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What Home shows of the history mixes: per provider, flattened (with mosaics), and whether a load is running. */
data class HistoryMixesState(
    val entries: List<HistoryMixEntry> = emptyList(),
    val flat: List<HistoryMix> = emptyList(),
    val loading: Boolean = false,
)

@Serializable
internal data class HistoryMixesDiskCache(
    val providers: Map<String, HistoryMixCacheEntry> = emptyMap(),
    val savedAt: Long = 0,
)

/**
 * Home's "Made from your listening" mixes, shared: a port of desktop's
 * `loadHistoryMixes` / `loadHomeHistoryMixes` and the Home mosaic effect
 * (`app.js` ~L28127–28330).
 *
 * - **Providers:** plugins with `capabilities.historyMixes` that
 *   [providerIsOn], content resolvers first in the user's resolver order,
 *   then meta-services.
 * - **Per provider:** 20 s each ([PROVIDER_TIMEOUT_MS]; a late answer is still
 *   applied and saved); fresh for an hour, shown while it refreshes for 7
 *   days; keyed to the account (a meta-service's username), so a changed
 *   ListenBrainz user never sees the old one's mixes. An empty answer replaces
 *   the stored one but is never served. A failure keeps the last answer;
 *   `PluginNotReadyException` drops it, on screen and on disk. Only the latest
 *   load paints.
 * - **Throttle:** a plain [load] is skipped while one runs or within 10 minutes
 *   of a load that answered with mixes ([shouldLoadHomeRecommendations]),
 *   unless the provider set or an account changed; `force` always loads and
 *   refetches.
 * - **After each paint:** [onMixIds] gets every mix id (Recommendations leaves
 *   those playlists out), and each mix with no cover gets a mosaic from
 *   [mosaicCovers], one at a time; a lookup that throws is retried
 *   [HOME_MIX_COVER_RETRY_MS] later, [HOME_MIX_COVER_ATTEMPTS] tries in all.
 */
class HistoryMixesRepository(
    private val source: HistoryMixSource,
    private val settingsStore: SettingsStore,
    /** Whether a provider's plugin is on: the platform's `pluginIsOn` with its own settings. */
    private val providerIsOn: suspend (RecommendationProvider) -> Boolean,
    /** Read `history_mixes_cache.json`; null if missing. */
    private val cacheRead: suspend () -> String?,
    /** Write `history_mixes_cache.json`. */
    private val cacheWrite: suspend (String) -> Unit,
    /** A mix's first four distinct track covers (the registry's playlist lookup). Throws when the lookup couldn't be reached. */
    private val mosaicCovers: suspend (HistoryMix) -> List<String>,
    /** Every mix id, after each paint (wired to `RecommendationsRepository.excludePlaylistIds`). */
    private val onMixIds: (Set<String>) -> Unit,
    private val nowMs: () -> Long = { currentTimeMillis() },
    /** Runs late answers and mosaic lookups past the caller; the default logs an uncaught failure. */
    private val scope: CoroutineScope = recommendationsBackgroundScope(TAG),
) {
    companion object {
        private const val TAG = "HistoryMixesRepo"
        const val FRESH_MS = 3_600_000L
        const val STALE_MS = 604_800_000L
        const val PROVIDER_TIMEOUT_MS = 20_000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Guards every field below. Never held across a provider call. */
    private val mutex = Mutex()
    private val mosaicMutex = Mutex()
    private var diskLoaded = false
    private val cache = LinkedHashMap<String, HistoryMixCacheEntry>()
    private var generation = 0
    private var loading = false
    private var lastFullLoadAt = 0L
    /** The providers and accounts the last load asked; a change loads past the throttle. */
    private var lastProviderKey: String? = null
    private var entries: List<HistoryMixEntry> = emptyList()
    private val covers = mutableMapOf<String, List<String>>()
    private val tried = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Int>()
    private var lastIdsSent: Set<String>? = null

    private val _mixes = MutableStateFlow(HistoryMixesState())
    val mixes: StateFlow<HistoryMixesState> = _mixes.asStateFlow()

    private class LoadRun(val generation: Int, val providers: List<RecommendationProvider>) {
        val fresh = mutableMapOf<String, HistoryMixCacheEntry>()
        val stale = mutableMapOf<String, HistoryMixCacheEntry>()
        var failed = 0
    }

    suspend fun load(force: Boolean = false) {
        ensureDiskLoaded()
        val lastFm = safely(null) { settingsStore.getLastFmUsername() }?.takeIf { it.isNotBlank() }
        val listenBrainz = safely(null) { settingsStore.getListenBrainzUsername() }?.takeIf { it.isNotBlank() }
        val all = try {
            source.providers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The plugins aren't loaded: an empty list here would wipe the mixes on screen.
            Log.w(TAG, "Couldn't list history-mix providers: ${e.message}")
            return
        }
        val providers = orderRecommendationProviders(
            all.filter { safely(false) { providerIsOn(it) } },
            safely(emptyList()) { settingsStore.getResolverOrder() },
        )
        val accounts = providers.associate { it.id to accountFor(it.id, lastFm, listenBrainz) }
        val key = providers.joinToString("|") { "${it.id}:${accounts[it.id]}" }
        val now = nowMs()

        val run = mutex.withLock {
            val keyChanged = key != lastProviderKey
            if (!force && !keyChanged &&
                !shouldLoadHomeRecommendations(loading = loading, loadStarted = true, lastFullLoadAt = lastFullLoadAt, nowMs = now)
            ) return
            lastProviderKey = key
            loading = true
            val run = LoadRun(++generation, providers)
            for (p in providers) {
                val cached = historyMixCacheEntry(cache[p.id], accounts[p.id])
                val hasItems = cached != null && cached.items.isNotEmpty()
                if (!force && hasItems && now - cached!!.fetchedAt < FRESH_MS) {
                    run.fresh[p.id] = cached
                    continue
                }
                if (hasItems && now - cached!!.fetchedAt < STALE_MS) run.stale[p.id] = cached
            }
            run
        }
        _mixes.update { it.copy(loading = true) }
        val toFetch = providers.filter { it.id !in run.fresh }
        try {
            // Last time's mixes first; each provider replaces its own as it answers.
            apply(run)
            if (toFetch.isNotEmpty()) {
                coroutineScope {
                    for (p in toFetch) launch { fetchOne(run, p, accounts[p.id].orEmpty()) }
                }
                save()
            }
            val (failed, count) = mutex.withLock {
                run.failed to providers.sumOf { p -> (run.fresh[p.id] ?: run.stale[p.id])?.items?.size ?: 0 }
            }
            if (shouldStampHomeRecommendationsLoad(fetched = toFetch.size, failed = failed, sectionCount = count)) {
                mutex.withLock { lastFullLoadAt = nowMs() }
            }
        } finally {
            withContext(NonCancellable) {
                val latest = mutex.withLock { (generation == run.generation).also { if (it) loading = false } }
                if (latest) _mixes.update { it.copy(loading = false) }
            }
        }
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
                Log.w(TAG, "History mixes from ${provider.name} timed out")
                scope.launch { applyLate(run, provider, account, request) }
                keepCached(run, provider, account)
            } else {
                accept(run, provider, account, raw)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PluginNotReadyException) {
            // Not ready (signed out) is an answer: this account can't get its
            // mixes now, so the cached ones go, on screen and on disk.
            Log.d(TAG, "History mixes from ${provider.name} not ready: ${e.message}")
            mutex.withLock {
                run.failed++
                run.stale.remove(provider.id)
                run.fresh.remove(provider.id)
                cache.remove(provider.id)
            }
        } catch (e: Exception) {
            // The message only: a config can carry a token.
            Log.w(TAG, "History mixes from ${provider.name} failed: ${e.message}")
            keepCached(run, provider, account)
        }
        apply(run)
    }

    /** A transient failure says nothing: keep the cached mixes, even expired ones. */
    private suspend fun keepCached(run: LoadRun, provider: RecommendationProvider, account: String) {
        mutex.withLock {
            run.failed++
            historyMixCacheEntry(cache[provider.id], account)?.let { run.fresh[provider.id] = it }
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
        apply(run)
        save()
    }

    /** Stores an answer even when empty (it replaces the older one; an empty one is never served). */
    private suspend fun accept(run: LoadRun, provider: RecommendationProvider, account: String, raw: String) {
        val entry = HistoryMixCacheEntry(
            resolverId = provider.id,
            resolverName = provider.name.ifBlank { provider.id },
            items = normalizeHistoryMixes(raw, provider.id, provider.name),
            fetchedAt = nowMs(),
            account = account,
        )
        mutex.withLock {
            run.fresh[provider.id] = entry
            cache[provider.id] = entry
        }
    }

    /** Puts this load's answers on screen, in provider order, if it's the latest. */
    private suspend fun apply(run: LoadRun) {
        val painted = mutex.withLock {
            if (generation != run.generation) return@withLock false
            entries = historyMixEntriesFor(run.providers) { p -> run.fresh[p.id] ?: run.stale[p.id] }
            true
        }
        if (painted) publish(kickMosaics = true)
    }

    private suspend fun publish(kickMosaics: Boolean) {
        val (shown, flat, ids) = mutex.withLock {
            val plain = flattenHistoryMixes(entries)
            val ids = plain.map { it.id }.toSet()
            val send = ids.takeIf { it != lastIdsSent }?.also { lastIdsSent = it }
            Triple(entries, withMixMosaics(plain, covers.toMap()), send)
        }
        _mixes.update { it.copy(entries = shown, flat = flat) }
        if (ids != null) {
            try {
                onMixIds(ids)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "onMixIds failed: ${e.message}")
            }
        }
        if (kickMosaics) scope.launch { buildMosaics() }
    }

    /** Builds the mosaics of the mixes on screen that need one, one at a time. */
    private suspend fun buildMosaics(): Unit = mosaicMutex.withLock {
        while (true) {
            val mix = mutex.withLock {
                mixesNeedingMosaic(_mixes.value.flat, tried).firstOrNull()?.also { tried += it.id }
            } ?: return@withLock
            try {
                val found = mosaicCovers(mix)
                if (found.isNotEmpty()) {
                    mutex.withLock { covers[mix.id] = found.take(4) }
                    publish(kickMosaics = false)
                }
                // No covers: the mix keeps its placeholder for the session.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val n = mutex.withLock { ((attempts[mix.id] ?: 0) + 1).also { attempts[mix.id] = it } }
                Log.w(TAG, "No mosaic for mix \"${mix.name}\" (attempt $n of $HOME_MIX_COVER_ATTEMPTS): ${e.message}")
                if (n < HOME_MIX_COVER_ATTEMPTS) {
                    scope.launch {
                        delay(HOME_MIX_COVER_RETRY_MS)
                        mutex.withLock { tried -= mix.id }
                        buildMosaics()
                    }
                }
            }
        }
    }

    // ── Disk ─────────────────────────────────────────────────────────

    /** Reads the saved answers once, into the cache only; the first load checks the account and paints them. */
    private suspend fun ensureDiskLoaded() {
        if (mutex.withLock { diskLoaded }) return
        val saved = try {
            cacheRead()?.let { json.decodeFromString(HistoryMixesDiskCache.serializer(), it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "History mixes cache unreadable: ${e.message}")
            null
        }
        mutex.withLock {
            if (diskLoaded) return@withLock
            diskLoaded = true
            saved?.providers?.forEach { (id, entry) -> if (id !in cache) cache[id] = entry }
        }
    }

    /** Saves the answers that have mixes and are young enough to show again. */
    private suspend fun save() {
        val now = nowMs()
        val providers = mutex.withLock {
            cache.filterValues { it.items.isNotEmpty() && now - it.fetchedAt < STALE_MS }
        }
        try {
            cacheWrite(json.encodeToString(HistoryMixesDiskCache.serializer(), HistoryMixesDiskCache(providers, now)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "History mixes cache not saved: ${e.message}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** The account a provider's mixes belong to: a meta-service's username; "" for one whose account the app can't see. */
    private fun accountFor(id: String, lastFm: String?, listenBrainz: String?): String = when (id) {
        RecommendationsRepository.LASTFM -> lastFm.orEmpty()
        RecommendationsRepository.LISTENBRAINZ -> listenBrainz.orEmpty()
        else -> ""
    }

    private suspend inline fun <T> safely(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }
}
