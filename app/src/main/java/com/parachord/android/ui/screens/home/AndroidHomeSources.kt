package com.parachord.android.ui.screens.home

import android.util.Log
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.ai.AiRecommendationService
import com.parachord.shared.api.Announcement
import com.parachord.shared.home.HomeConcertsLoader
import com.parachord.shared.home.HomeFeed
import com.parachord.shared.home.HomeFeedLoader
import com.parachord.shared.home.HomeRecommendations
import com.parachord.shared.home.HomeRecsLoad
import com.parachord.shared.home.HomeSources
import com.parachord.shared.home.LOCAL_FILES_ID
import com.parachord.shared.home.homeConcertLocation
import com.parachord.shared.home.homePlaybackResolvers
import com.parachord.shared.home.homePluginAccounts
import com.parachord.shared.home.listOrFailed
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.plugin.needsAccount
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Android's [HomeSources]: the library, playback and settings flows mapped
 * onto the shared session's inputs. The loading rules live in shared code
 * ([HomeFeedLoader], [HomeConcertsLoader], [HomeRecommendations],
 * [homePluginAccounts]); this only wires Android's repositories to them. A
 * Koin single, so the feeds a visit loaded stay for the next one.
 *
 * Every flow the getting-started decision reads ([accounts], [hasCollection],
 * [playlists], [folderAdded], [scrobblerAccounts]) is a cold database or
 * settings flow, so its first value is what's stored, never a placeholder.
 * [plugins], [accounts] and [playbackResolvers] come from one snapshot, so the
 * plug-in list is never ahead of the accounts and resolvers worked out from it.
 */
class AndroidHomeSources(
    private val library: LibraryRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val settings: SettingsStore,
    private val pluginManager: PluginManager,
    private val announcementsRepository: AnnouncementsRepository,
    private val historyMixesRepository: HistoryMixesRepository,
    recommendationsRepository: RecommendationsRepository,
    private val aiRecommendationService: AiRecommendationService,
    private val freshDropsRepository: FreshDropsRepository,
    private val criticalDarlingsRepository: CriticalDarlingsRepository,
    private val chartsRepository: ChartsRepository,
    concertsRepository: ConcertsRepository,
    concertArtistSeed: ConcertArtistSeedSource,
    /** The app-wide concert artist photos, shared with the Concerts page. */
    override val concertArtistImages: com.parachord.shared.home.ConcertArtistImages,
    /** Android's plug-in gate (`androidPluginIsOn`): whether a plug-in is on, its connection included. */
    private val pluginIsOn: suspend (id: String, isMetaService: Boolean, needsAccount: Boolean) -> Boolean,
    /** Shares the plug-in snapshot between its three flows. */
    scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("AndroidHomeSources", "Home sources failed", e) },
    ),
) : HomeSources {

    private companion object {
        const val CHART_COUNTRY = "us"
    }

    /** Bumped on each Home visit, so the reads with no flow are made again. */
    private val refreshTick = MutableStateFlow(0)

    override suspend fun refresh() {
        refreshTick.update { it + 1 }
    }

    // ── Library and playback ─────────────────────────────────────────

    override val playlists: Flow<List<Playlist>> = library.getAllPlaylists()

    /**
     * The collection's tracks, read once for Home: [hasCollection],
     * [folderAdded] and the view model's Recent Loves all derive from this one
     * shared flow. No replay is kept once nobody listens, so a new visit's
     * first value is what's stored, not the last visit's list.
     */
    override val collectionTracks: Flow<List<Track>> = library.getAllTracks().shareIn(
        scope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        replay = 1,
    )

    /** Desktop's `hasMusic`: any track (loves and local files), album or artist in the collection. */
    override val hasCollection: Flow<Boolean> = combine(
        collectionTracks.map { it.isNotEmpty() }.distinctUntilChanged(),
        library.getAllAlbums().map { it.isNotEmpty() }.distinctUntilChanged(),
        library.getAllArtists().map { it.isNotEmpty() }.distinctUntilChanged(),
    ) { tracks, albums, artists -> tracks || albums || artists }.distinctUntilChanged()

    override val playingContext: Flow<PlaybackContext?> =
        playbackStateHolder.state.map { it.playbackContext }.distinctUntilChanged()

    /** Playing, not still starting: a play that starts and then fails doesn't count. */
    override val isPlaying: Flow<Boolean> =
        playbackStateHolder.state.map { it.isPlaying && !it.isBuffering }.distinctUntilChanged()

    override val announcements: Flow<List<Announcement>> = announcementsRepository.visibleAnnouncements

    override suspend fun bannerViewed(id: String) {
        announcementsRepository.trackView(id)
    }

    // ── Recommendations and mixes ────────────────────────────────────

    override val historyMixes: StateFlow<HistoryMixesState> = historyMixesRepository.mixes

    override suspend fun loadHistoryMixes(force: Boolean): HomeRecsLoad = historyMixesRepository.load(force)

    private val recommendations = HomeRecommendations(
        page = recommendationsRepository.page,
        loadPage = { force -> recommendationsRepository.load(force) },
        cachedAiAlbums = { aiRecommendationService.getCachedRecommendations()?.albums },
    )

    override val recommendationSections: Flow<List<RecommendationPageSection>?> = recommendations.sections
    override val aiAlbums: Flow<List<AiAlbumSuggestion>?> = recommendations.aiAlbums
    override suspend fun loadRecommendations(force: Boolean): HomeRecsLoad = recommendations.load(force)

    // ── Fresh Drops, Critical Darlings, Pop of the Tops ──────────────

    private val fresh = HomeFeedLoader<FreshDrop>("Fresh Drops")
    private val critical = HomeFeedLoader<CriticsPickAlbum>("Critical Darlings")
    private val chartsFeed = HomeFeedLoader<ChartAlbum>("Pop of the Tops")

    override val freshDrops: Flow<HomeFeed<FreshDrop>> = fresh.state
    override val criticalDarlings: Flow<HomeFeed<CriticsPickAlbum>> = critical.state
    override val charts: Flow<HomeFeed<ChartAlbum>> = chartsFeed.state

    override suspend fun loadFeeds(force: Boolean) = coroutineScope {
        launch { fresh.load(force) { refresh -> freshDropsRepository.getFreshDrops(forceRefresh = refresh) } }
        launch { critical.load(force) { refresh -> criticalDarlingsRepository.getCriticsPicks(forceRefresh = refresh) } }
        // The repository answers its last list, else nothing, when the chart can't be reached.
        launch { chartsFeed.load(force) { listOrFailed { chartsRepository.getAppleMusicAlbums(CHART_COUNTRY) } } }
        Unit
    }

    // ── Concerts ─────────────────────────────────────────────────────

    private val concertsLoader = HomeConcertsLoader(concertArtistSeed, concertsRepository)

    override val concerts: Flow<HomeFeed<ConcertEvent>> = concertsLoader.state

    override suspend fun concertLocation(): SettingsStore.ConcertLocation? = homeConcertLocation(settings.getConcertLocation())

    override suspend fun loadConcerts() = concertsLoader.load(concertLocation())

    // ── Shuffleupagus ────────────────────────────────────────────────

    /** The check the old Home made (`checkAiPlugins`): an AI provider with a key. Null until known; asked again each visit. */
    override val chatAvailable: Flow<Boolean?> = refreshTick
        .map<Int, Boolean?> { aiRecommendationService.hasEnabledProvider() }
        .onStart { emit(null) }
        .distinctUntilChanged()

    // ── Getting started ──────────────────────────────────────────────

    private data class PluginSnapshot(
        val plugins: List<PluginInfo>,
        val accounts: Map<String, Boolean>,
        val resolvers: List<String>,
    )

    /**
     * The plug-ins with the accounts and resolvers worked out from them, in one
     * value. Accounts: `androidPluginIsOn`'s connection checks (Spotify,
     * SoundCloud, Apple Music; Apple Music's token has no flow, so it's read
     * again each visit), plus the plug-ins the app doesn't set up (Subsonic)
     * as configured or not.
     */
    private val snapshot: StateFlow<PluginSnapshot?> = combine(
        combine(pluginManager.plugins, settings.getSpotifyAccessTokenFlow(), settings.getSoundCloudTokenFlow(), refreshTick) { p, sp, sc, _ ->
            Triple(p, sp, sc)
        },
        combine(settings.getDisabledPluginsFlow(), settings.getDisabledMetaProvidersFlow(), settings.getActiveResolversFlow()) { _, _, _ -> Unit },
    ) { t, _ -> t }
        .map { (plugins, spotify, soundCloud) ->
            val host = mapOf(
                "spotify" to (spotify != null),
                "applemusic" to (settings.getAppleMusicUserToken() != null),
                "soundcloud" to (soundCloud != null),
            )
            val accounts = homePluginAccounts(plugins, host) { id -> pluginManager.isPluginConfiguredOrNull(id) }
            val resolvers = homePlaybackResolvers(plugins, accounts) { p ->
                pluginIsOn(p.id, p.type == "meta-service", needsAccount(p))
            }
            PluginSnapshot(plugins, accounts, resolvers)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Starts empty; the session waits for the list. */
    override val plugins: Flow<List<PluginInfo>> = snapshot.filterNotNull().map { it.plugins }.distinctUntilChanged()
    override val accounts: Flow<Map<String, Boolean>> = snapshot.filterNotNull().map { it.accounts }.distinctUntilChanged()
    override val playbackResolvers: Flow<List<String>> = snapshot.filterNotNull().map { it.resolvers }.distinctUntilChanged()

    /** Local-file tracks are in the library. */
    override val folderAdded: Flow<Boolean> =
        collectionTracks.map { tracks -> tracks.any { it.resolver == LOCAL_FILES_ID } }.distinctUntilChanged()

    /** Last.fm and ListenBrainz by their usernames; Libre.fm saves none, so its session stands for it. */
    override val scrobblerAccounts: Flow<Map<String, Boolean>> = combine(
        settings.getLastFmUsernameFlow(),
        settings.getListenBrainzUsernameFlow(),
        settings.getLibreFmSessionKeyFlow(),
    ) { lastFm, listenBrainz, libreFm ->
        mapOf(
            "lastfm" to !lastFm.isNullOrBlank(),
            "listenbrainz" to !listenBrainz.isNullOrBlank(),
            "librefm" to !libreFm.isNullOrBlank(),
        )
    }
        .distinctUntilChanged()

    // ── Clock ────────────────────────────────────────────────────────

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun hour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}
