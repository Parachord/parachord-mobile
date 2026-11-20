package com.parachord.android.ui.screens.home

import android.util.Log
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.ai.AiRecommendationService
import com.parachord.shared.api.Announcement
import com.parachord.shared.home.HomeFeed
import com.parachord.shared.home.HomeRecsLoad
import com.parachord.shared.home.HomeSources
import com.parachord.shared.home.LOCAL_FILES_ID
import com.parachord.shared.home.playsHere
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Resource
import com.parachord.shared.model.Track
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.repository.AnnouncementsRepository
import com.parachord.shared.repository.ChartsRepository
import com.parachord.shared.repository.ConcertArtistSeedSource
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.ConcertsRepository
import com.parachord.shared.repository.CriticalDarlingsRepository
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop
import com.parachord.shared.repository.FreshDropsRepository
import com.parachord.shared.repository.HistoryMixesRepository
import com.parachord.shared.repository.HistoryMixesState
import com.parachord.shared.repository.LibraryRepository
import com.parachord.shared.repository.RecommendationsRepository
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/** Sources that read some settings only when asked again (the Apple Music token, the AI keys): Home asks on each visit. */
interface RefreshableHomeSources {
    fun refresh()
}

/**
 * Android's [HomeSources]: the library, playback and settings flows, and
 * Home's feeds, mapped onto the shared session's inputs. A Koin single, so the
 * feeds a visit loaded stay for the next one.
 *
 * Every flow the getting-started decision reads ([accounts], [hasCollection],
 * [playlists], [folderAdded], [scrobblerUsernames]) is a cold database or
 * settings flow, so its first value is what's stored, never a placeholder.
 */
class AndroidHomeSources(
    private val library: LibraryRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val settings: SettingsStore,
    private val pluginManager: PluginManager,
    private val announcementsRepository: AnnouncementsRepository,
    private val historyMixesRepository: HistoryMixesRepository,
    private val recommendationsRepository: RecommendationsRepository,
    private val aiRecommendationService: AiRecommendationService,
    private val freshDropsRepository: FreshDropsRepository,
    private val criticalDarlingsRepository: CriticalDarlingsRepository,
    private val chartsRepository: ChartsRepository,
    private val concertsRepository: ConcertsRepository,
    private val concertArtistSeed: ConcertArtistSeedSource,
    /** Android's plug-in gate (`androidPluginIsOn`): whether a plug-in is on, its connection included. */
    private val pluginIsOn: suspend (id: String, isMetaService: Boolean, needsAccount: Boolean) -> Boolean,
) : HomeSources, RefreshableHomeSources {

    private companion object {
        const val TAG = "AndroidHomeSources"
        const val CHART_COUNTRY = "us"
    }

    /** Bumped on each Home visit, so the reads with no flow are made again. */
    private val refreshTick = MutableStateFlow(0)

    override fun refresh() {
        refreshTick.update { it + 1 }
    }

    // ── Library and playback ─────────────────────────────────────────

    override val playlists: Flow<List<Playlist>> = library.getAllPlaylists()

    override val collectionTracks: Flow<List<Track>> = library.getAllTracks()

    /** Desktop's `hasMusic`: any track (loves and local files), album or artist in the collection. */
    override val hasCollection: Flow<Boolean> = combine(
        library.getAllTracks().map { it.isNotEmpty() }.distinctUntilChanged(),
        library.getAllAlbums().map { it.isNotEmpty() }.distinctUntilChanged(),
        library.getAllArtists().map { it.isNotEmpty() }.distinctUntilChanged(),
    ) { tracks, albums, artists -> tracks || albums || artists }.distinctUntilChanged()

    override val playingContext: Flow<PlaybackContext?> =
        playbackStateHolder.state.map { it.playbackContext }.distinctUntilChanged()

    override val isPlaying: Flow<Boolean> =
        playbackStateHolder.state.map { it.isPlaying }.distinctUntilChanged()

    override val announcements: Flow<List<Announcement>> = announcementsRepository.visibleAnnouncements

    override suspend fun bannerViewed(id: String) {
        announcementsRepository.trackView(id)
    }

    // ── Recommendations and mixes ────────────────────────────────────

    override val historyMixes: StateFlow<HistoryMixesState> = historyMixesRepository.mixes

    override suspend fun loadHistoryMixes(force: Boolean): HomeRecsLoad = historyMixesRepository.load(force)

    /** Set once a recommendations load has answered, so an empty page reads as "none", not "not yet". */
    private val recsAnswered = MutableStateFlow(false)

    override val recommendationSections: Flow<List<RecommendationPageSection>?> =
        combine(recommendationsRepository.page, recsAnswered) { page, answered ->
            page.sections.takeIf { it.isNotEmpty() || answered }
        }.distinctUntilChanged()

    /** Shuffleupagus's cached picks, read again whenever the page changes (its own load ends with one). */
    override val aiAlbums: Flow<List<AiAlbumSuggestion>?> =
        combine(recommendationsRepository.page, recsAnswered) { _, _ -> Unit }
            .map { aiRecommendationService.getCachedRecommendations()?.albums }
            .distinctUntilChanged()

    override suspend fun loadRecommendations(force: Boolean): HomeRecsLoad =
        try {
            recommendationsRepository.load(force)
        } finally {
            recsAnswered.value = true
        }

    // ── Fresh Drops, Critical Darlings, Pop of the Tops ──────────────

    /** One of the feeds, with whether it has answered at least once. */
    private class Feed<T> {
        val state = MutableStateFlow(HomeFeed<T>())
        @Volatile var loaded = false
    }

    private val fresh = Feed<FreshDrop>()
    private val critical = Feed<CriticsPickAlbum>()
    private val chartsFeed = Feed<ChartAlbum>()
    private val concertsFeed = Feed<ConcertEvent>()
    private val feedMutex = Mutex()

    override val freshDrops: Flow<HomeFeed<FreshDrop>> = fresh.state
    override val criticalDarlings: Flow<HomeFeed<CriticsPickAlbum>> = critical.state
    override val charts: Flow<HomeFeed<ChartAlbum>> = chartsFeed.state

    /**
     * Without [force], a feed that has never answered (a return visit asks
     * nothing). With it (Pop of the Tops' Try again), a feed that failed or is
     * empty; a loaded list stays. A feed already loading is left alone.
     */
    private suspend fun <T> claim(feed: Feed<T>, force: Boolean): Boolean = feedMutex.withLock {
        val s = feed.state.value
        val want = if (force) s.failed || s.items.isEmpty() else !feed.loaded
        if (!want || s.loading) return@withLock false
        feed.state.value = s.copy(loading = true, failed = false)
        true
    }

    override suspend fun loadFeeds(force: Boolean) = coroutineScope {
        if (claim(fresh, force)) launch { collectResource(fresh, "Fresh Drops") { freshDropsRepository.getFreshDrops(forceRefresh = force) } }
        if (claim(critical, force)) launch { collectResource(critical, "Critical Darlings") { criticalDarlingsRepository.getCriticsPicks(forceRefresh = force) } }
        if (claim(chartsFeed, force)) launch {
            runLoad(chartsFeed, "Pop of the Tops") {
                // The repository answers its last list, else nothing, when the feed can't be reached.
                val albums = chartsRepository.getAppleMusicAlbums(CHART_COUNTRY)
                chartsFeed.state.update { it.copy(items = albums, failed = albums.isEmpty()) }
            }
        }
        Unit
    }

    // ── Concerts ─────────────────────────────────────────────────────

    override val concerts: Flow<HomeFeed<ConcertEvent>> = concertsFeed.state

    override suspend fun concertLocation(): SettingsStore.ConcertLocation? {
        val l = settings.getConcertLocation()
        val located = (l.latitude != null && l.longitude != null) || !l.city.isNullOrBlank()
        return l.takeIf { located }
    }

    /** As the Concerts page loads them (`ConcertsViewModel.loadEvents`): the shared artist seed, then the personalized search. */
    override suspend fun loadConcerts() {
        val location = concertLocation() ?: return
        // A load that failed is asked again on the next visit.
        if (!claim(concertsFeed, force = concertsFeed.state.value.failed)) return
        collectResource(concertsFeed, "Concerts") {
            flow {
                val artists = concertArtistSeed.gather()
                if (artists.isEmpty()) {
                    emit(Resource.Success(emptyList()))
                } else {
                    concertsRepository.getPersonalizedEvents(
                        artists = artists,
                        lat = location.latitude,
                        lon = location.longitude,
                        radiusMiles = location.radiusMiles,
                    ).collect { emit(it) }
                }
            }
        }
    }

    private suspend fun <T> collectResource(feed: Feed<T>, what: String, source: () -> Flow<Resource<List<T>>>) {
        runLoad(feed, what) {
            source().collect { r ->
                when (r) {
                    is Resource.Success -> feed.state.update { it.copy(items = r.data, failed = false) }
                    is Resource.Error -> feed.state.update { it.copy(failed = true) }
                    Resource.Loading -> Unit
                }
            }
        }
    }

    /** Runs a feed's load; a throw marks it failed, and loading always ends. */
    private suspend fun <T> runLoad(feed: Feed<T>, what: String, block: suspend () -> Unit) {
        try {
            block()
            feed.loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.message}")
            feed.state.update { it.copy(failed = true) }
        } finally {
            feed.state.update { it.copy(loading = false) }
        }
    }

    // ── Shuffleupagus ────────────────────────────────────────────────

    /** The check the old Home made (`checkAiPlugins`): an AI provider with a key. Null until known; asked again each visit. */
    override val chatAvailable: Flow<Boolean?> = refreshTick
        .map<Int, Boolean?> { aiRecommendationService.hasEnabledProvider() }
        .onStart { emit(null) }
        .distinctUntilChanged()

    // ── Getting started ──────────────────────────────────────────────

    /** Starts empty; the session waits for the list. */
    override val plugins: Flow<List<PluginInfo>> = pluginManager.plugins

    /** The connection checks `androidPluginIsOn` makes. Apple Music's token has no flow, so it's read again each visit. */
    override val accounts: Flow<Map<String, Boolean>> = combine(
        settings.getSpotifyAccessTokenFlow(),
        settings.getSoundCloudTokenFlow(),
        refreshTick,
    ) { spotify, soundCloud, _ -> spotify to soundCloud }
        .map { (spotify, soundCloud) ->
            mapOf(
                "spotify" to (spotify != null),
                "applemusic" to (settings.getAppleMusicUserToken() != null),
                "soundcloud" to (soundCloud != null),
            )
        }
        .distinctUntilChanged()

    /** The plug-ins that play here and are on (the Android gate, connection included). */
    override val playbackResolvers: Flow<List<String>> = combine(
        pluginManager.plugins,
        accounts,
        settings.getDisabledPluginsFlow(),
        settings.getDisabledMetaProvidersFlow(),
        settings.getActiveResolversFlow(),
    ) { plugins, accounts, _, _, _ -> plugins to accounts }
        .map { (plugins, accounts) ->
            plugins.filter(::playsHere).filter { p ->
                accounts[p.id] != false &&
                    pluginIsOn(p.id, p.type == "meta-service", com.parachord.shared.plugin.needsAccount(p))
            }.map { it.id }
        }
        .distinctUntilChanged()

    /** Local-file tracks are in the library. */
    override val folderAdded: Flow<Boolean> =
        library.getAllTracks().map { tracks -> tracks.any { it.resolver == LOCAL_FILES_ID } }.distinctUntilChanged()

    /** Last.fm and ListenBrainz usernames; Libre.fm has none saved, so its session stands for it. */
    override val scrobblerUsernames: Flow<List<String?>> = combine(
        settings.getLastFmUsernameFlow(),
        settings.getListenBrainzUsernameFlow(),
        settings.getLibreFmSessionKeyFlow().map { key -> if (key.isNullOrBlank()) null else "librefm" },
    ) { lastFm, listenBrainz, libreFm -> listOf(lastFm, listenBrainz, libreFm) }
        .distinctUntilChanged()

    // ── Clock ────────────────────────────────────────────────────────

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun hour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}
