package com.parachord.shared.home

import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.Log
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.plugin.PluginUserConfig
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.repository.ConcertArtist
import com.parachord.shared.repository.ConcertArtistSeedSource
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.ConcertsRepository
import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The loading behind Home's sources, shared so both apps' HomeSources only
// map their own data: the feeds (Fresh Drops, Critical Darlings, Pop of the
// Tops, concerts), the recommendations' "answered" state and the step-1
// account rules.

/**
 * One of Home's remote lists and its loading rules ([HomeSources.loadFeeds]).
 *
 * - A plain [load] asks when the list has never loaded cleanly: never asked,
 *   failed, or loaded for another [key] (concerts: another location). A list
 *   that loaded stays; a return visit asks nothing.
 * - `force` (Pop of the Tops' Try again) asks only when the list failed or is
 *   empty, keeping a loaded one.
 * - A load already running is left alone. A [Resource.Error] or a throw marks
 *   the list failed (its last items stay) and the next plain load asks again.
 * - The state starts `loading` ([startsLoading], the default) until the first
 *   load answers, so a half shows its skeleton from the start rather than
 *   appearing only once its load begins. A list that may never load (concerts
 *   without a location) starts not loading.
 */
class HomeFeedLoader<T> internal constructor(
    private val what: String,
    /** Its own lock; a test passes one it can hold. */
    private val mutex: Mutex,
    startsLoading: Boolean = true,
) {
    constructor(what: String, startsLoading: Boolean = true) : this(what, Mutex(), startsLoading)

    private val _state = MutableStateFlow(HomeFeed<T>(loading = startsLoading))

    /** A load is under way; kept apart from `state.loading`, which starts true before any load. */
    private var running = false
    val state: StateFlow<HomeFeed<T>> = _state.asStateFlow()

    private var loadedOk = false
    private var loadedKey: Any? = null

    /**
     * Loads through [fetch] when the rules above say so. [fetch] gets whether
     * to bypass its own cache: `force`, or a [key] that changed since the last
     * load. Answers whether it asked.
     */
    suspend fun load(force: Boolean, key: Any? = null, fetch: (refresh: Boolean) -> Flow<Resource<List<T>>>): Boolean {
        val refresh = mutex.withLock {
            val s = _state.value
            if (running) return false
            val keyChanged = loadedKey != null && key != loadedKey
            val want = keyChanged || if (force) s.failed || s.items.isEmpty() else !loadedOk || key != loadedKey
            if (!want) return false
            running = true
            // Another location's concerts aren't this one's.
            _state.value = if (keyChanged) HomeFeed(loading = true) else s.copy(loading = true, failed = false)
            force || keyChanged
        }
        var ok = false
        try {
            fetch(refresh).collect { r ->
                when (r) {
                    is Resource.Success -> _state.update { it.copy(items = r.data, failed = false) }
                    is Resource.Error -> _state.update { it.copy(failed = true) }
                    Resource.Loading -> Unit
                }
            }
            ok = !_state.value.failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HomeFeedLoader", "$what failed: ${e.message}")
            _state.update { it.copy(failed = true) }
        } finally {
            // Non-cancellable: a cancelled load that finds the lock held would
            // otherwise throw here, leave `loading` true, and the feed (a Koin
            // single) would never load again in this process.
            withContext(NonCancellable) {
                mutex.withLock {
                    loadedOk = ok
                    loadedKey = key
                    running = false
                    _state.update { it.copy(loading = false) }
                }
            }
        }
        return true
    }
}

/** A list that answers empty when it couldn't be fetched (the Apple chart feed): empty reads as failed. */
fun <T> listOrFailed(fetch: suspend () -> List<T>): Flow<Resource<List<T>>> = flow {
    val items = fetch()
    emit(if (items.isEmpty()) Resource.Error("empty") else Resource.Success(items))
}

/**
 * Concerts for [location], as the Concerts page and Home load them: the
 * shared artist seed ([ConcertArtistSeedSource.gather]), then the personalized
 * search. No artists means no concerts.
 */
fun concertEvents(
    gather: suspend () -> List<ConcertArtist>,
    search: (artists: List<ConcertArtist>, location: SettingsStore.ConcertLocation, refresh: Boolean) -> Flow<Resource<List<ConcertEvent>>>,
    location: SettingsStore.ConcertLocation,
    refresh: Boolean,
): Flow<Resource<List<ConcertEvent>>> = flow {
    val artists = gather()
    if (artists.isEmpty()) emit(Resource.Success(emptyList()))
    else emitAll(search(artists, location, refresh))
}

fun concertEvents(
    seed: ConcertArtistSeedSource,
    repository: ConcertsRepository,
    location: SettingsStore.ConcertLocation,
    refresh: Boolean,
): Flow<Resource<List<ConcertEvent>>> = concertEvents(
    gather = { seed.gather() },
    search = { artists, loc, r ->
        repository.getPersonalizedEvents(artists, loc.latitude, loc.longitude, loc.radiusMiles, forceRefresh = r)
    },
    location = location,
    refresh = refresh,
)

/** A saved concert location Home can use: coordinates or a city; else null. */
fun homeConcertLocation(l: SettingsStore.ConcertLocation?): SettingsStore.ConcertLocation? =
    l?.takeIf { (it.latitude != null && it.longitude != null) || !it.city.isNullOrBlank() }

/** Home's concerts: loaded only with a location, and again when the location changes. */
class HomeConcertsLoader(
    private val events: (location: SettingsStore.ConcertLocation, refresh: Boolean) -> Flow<Resource<List<ConcertEvent>>>,
) {
    constructor(seed: ConcertArtistSeedSource, repository: ConcertsRepository) :
        this({ loc, refresh -> concertEvents(seed, repository, loc, refresh) })

    private val loader = HomeFeedLoader<ConcertEvent>("Concerts", startsLoading = false)
    val state: StateFlow<HomeFeed<ConcertEvent>> = loader.state

    /** With no [location], nothing. The location is the list's key. */
    suspend fun load(location: SettingsStore.ConcertLocation?) {
        val loc = homeConcertLocation(location) ?: return
        loader.load(force = false, key = loc) { refresh -> events(loc, refresh) }
    }
}

/**
 * Home's view of the Recommendations page: its sections, null until a load
 * has answered (then an empty page is "none"); Shuffleupagus's cached albums,
 * read again whenever the page changes (its own load ends with one).
 */
class HomeRecommendations(
    page: Flow<RecommendationPageState>,
    private val loadPage: suspend (force: Boolean) -> HomeRecsLoad,
    cachedAiAlbums: suspend () -> List<AiAlbumSuggestion>?,
) {
    private val answered = MutableStateFlow(false)

    val sections: Flow<List<RecommendationPageSection>?> =
        combine(page, answered) { p, a -> p.sections.takeIf { it.isNotEmpty() || a } }.distinctUntilChanged()

    val aiAlbums: Flow<List<AiAlbumSuggestion>?> =
        combine(page, answered) { _, _ -> Unit }.map { cachedAiAlbums() }.distinctUntilChanged()

    suspend fun load(force: Boolean): HomeRecsLoad =
        try {
            loadPage(force)
        } finally {
            answered.value = true
        }
}

/**
 * Step 1's sign-in state: [host]'s own checks (the services the app signs in
 * itself), plus each plug-in that needs settings the app doesn't set up
 * ([PluginUserConfig.usesGenericSettings]: Subsonic) as configured or not.
 * Without that entry `serviceState` falls back to "it's playing", and such a
 * plug-in is on with nothing set up.
 */
suspend fun homePluginAccounts(
    plugins: List<PluginInfo>,
    host: Map<String, Boolean>,
    isConfigured: suspend (String) -> Boolean,
): Map<String, Boolean> {
    val out = host.toMutableMap()
    for (p in plugins) {
        if (p.id in out || !p.requiresAuth || !PluginUserConfig.usesGenericSettings(p.id)) continue
        out[p.id] = try {
            isConfigured(p.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
    return out
}

/** The plug-ins that play here, are on ([isOn]) and aren't known to be signed out ([accounts]). */
suspend fun homePlaybackResolvers(
    plugins: List<PluginInfo>,
    accounts: Map<String, Boolean>,
    isOn: suspend (PluginInfo) -> Boolean,
): List<String> = plugins.filter { playsHere(it) && accounts[it.id] != false && isOn(it) }.map { it.id }
