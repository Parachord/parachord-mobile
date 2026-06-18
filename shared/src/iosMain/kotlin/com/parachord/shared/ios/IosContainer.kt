package com.parachord.shared.ios

import com.parachord.shared.api.GeoLocation
import com.parachord.shared.api.GeoLocationClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.createHttpClient
import com.parachord.shared.config.AppConfig
import com.parachord.shared.plugin.IosJsRuntime
import com.parachord.shared.plugin.PluginFileAccess
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.plugin.PluginSyncService
import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.metadata.AlbumDetail
import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.ArtistInfo
import com.parachord.shared.metadata.AppleMusicArtistProvider
import com.parachord.shared.metadata.DiscogsProvider
import com.parachord.shared.metadata.LastFmProvider
import com.parachord.shared.metadata.WikipediaProvider
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.metadata.ImageEnrichmentService
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Friend
import com.parachord.shared.model.Track
import com.parachord.shared.playback.scrobbler.LastFmScrobbler
import com.parachord.shared.playback.scrobbler.LibreFmScrobbler
import com.parachord.shared.playback.scrobbler.ListenBrainzScrobbler
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.randomUUID
import kotlinx.coroutines.flow.first
import com.parachord.shared.playback.scrobbler.ScrobbleManager
import com.parachord.shared.playback.scrobbler.ScrobblePluginDispatch
import com.parachord.shared.playback.scrobbler.ScrobbleState
import com.parachord.shared.playback.scrobbler.Scrobbler
import kotlinx.coroutines.flow.MutableStateFlow
import com.parachord.shared.metadata.MusicBrainzProvider
import com.parachord.shared.metadata.SpotifyProvider
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.ChartSong
import com.parachord.shared.model.RecommendedArtist
import com.parachord.shared.model.RecommendedTrack
import com.parachord.shared.model.Resource
import com.parachord.shared.db.DriverFactory
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.FriendDao
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.SearchHistoryDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.SearchHistory
import com.parachord.shared.api.TicketmasterClient
import com.parachord.shared.api.SeatGeekClient
import com.parachord.shared.repository.ChartsRepository
import com.parachord.shared.repository.ConcertsRepository
import com.parachord.shared.repository.ConcertArtist
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.CriticalDarlingsRepository
import com.parachord.shared.repository.HistoryRepository
import com.parachord.shared.model.HistoryTrack
import com.parachord.shared.model.HistoryAlbum
import com.parachord.shared.model.HistoryArtist
import com.parachord.shared.model.RecentTrack
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop
import com.parachord.shared.repository.FreshDropsRepository
import com.parachord.shared.repository.FriendsRepository
import com.parachord.shared.repository.RecommendationsRepository
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverCoordinator
import com.parachord.shared.resolver.ResolverScoring
import com.parachord.shared.repository.WeeklyPlaylistEntry
import com.parachord.shared.repository.WeeklyPlaylistsRepository
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.IosSecureTokenStore
import com.parachord.shared.store.KvStoreFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSBundle

/**
 * Hand-rolled dependency container for the iOS app shell (phase 5.0).
 *
 * The full Koin graph (sharedModule + a platform module supplying
 * AuthTokenProvider / OAuthTokenRefresher / AppConfig) is deferred —
 * the first real SwiftUI screens (Settings) only need [SettingsStore],
 * which depends on nothing but the two iOS storage actuals that already
 * exist (KvStore via NSUserDefaults, SecureTokenStore via Keychain).
 *
 * Constructing it here rather than reaching for Koin keeps the Swift
 * side a single `IosContainer.shared` lookup and avoids dragging in the
 * auth-token plumbing before any screen needs it. When network-backed
 * repositories come online, this grows to host the Ktor client + DAOs
 * (or gets replaced by an `initKoin()` once the auth module lands).
 */
class IosContainer private constructor() {

    companion object {
        /** Single app-wide instance — Swift reads `IosContainer.companion.shared`. */
        val shared: IosContainer by lazy { IosContainer() }

        /** KvStore key for the persisted Spotify rate-limit cooldown (matches
         *  Android's `spotify_rate_limit_cooldown_ms`). */
        private const val SPOTIFY_COOLDOWN_KEY = "spotify_rate_limit_cooldown_ms"
    }

    /** App-wide coroutine scope for fire-and-forget settings writes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Shared KvStore (NSUserDefaults) — used by SettingsStore AND the
     *  Spotify rate-limit cooldown persistence (so the cooldown survives a
     *  restart instead of re-probing an already-rate-limited Spotify). */
    val kvStore by lazy { KvStoreFactory.create() }

    val settingsStore: SettingsStore by lazy {
        SettingsStore(
            secureStore = IosSecureTokenStore(),
            kv = kvStore,
        )
    }

    val appConfig: AppConfig by lazy {
        // Built-in app keys come from Info.plist (same mechanism as Android's
        // BuildConfig). The Last.fm API key powers metadata — album art,
        // artist images/bios, top songs, charts — and is a built-in app key,
        // NOT a user BYO field (matches Android's BuildConfig.LASTFM_API_KEY).
        // The User-Agent matters regardless: MusicBrainz 403s the default UA.
        // .trim() guards against stray whitespace pasted into Info.plist values
        // (a trailing space in an API key silently breaks every request).
        fun plist(key: String): String =
            (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String ?: "").trim()
        AppConfig(
            userAgent = "Parachord/0.1 (iOS; https://parachord.com)",
            parachordClient = "ios",
            isDebug = true,
            spotifyClientId = "",   // BYO — Parachord ships no Spotify key; user adds theirs in Settings
            lastFmApiKey = plist("LastFmApiKey"),
            lastFmSharedSecret = plist("LastFmSharedSecret"),
            // Built-in Apple Music developer token (Bearer) for catalog artist
            // images. From Info.plist -> Secrets.xcconfig $(APPLE_MUSIC_DEVELOPER_TOKEN).
            // Blank = AppleMusicArtistProvider stays inert (no error).
            appleMusicDeveloperToken = plist("AppleMusicDeveloperToken"),
            // Achordion bearer token — pre-warms the track-links cache on scrobble
            // (#215) + share. From Info.plist -> Secrets.xcconfig
            // $(ACHORDION_BEARER_TOKEN). Blank = AchordionClient short-circuits inertly.
            achordionBearerToken = plist("AchordionBearerToken"),
        )
    }

    /**
     * Plain Ktor `HttpClient` for the Spotify token endpoint ONLY — Darwin
     * engine + JSON/form support, with NO auth/refresh plugins. The token
     * exchange ([spotifyAuth]) and the refresher ([spotifyTokenRefresher])
     * both run through this so they can't recurse the shared client's
     * 401-refresh plugin (which would itself call back into the refresher).
     * See CLAUDE.md "avoid an infinite refresh loop".
     */
    val authHttpClient: HttpClient by lazy {
        HttpClient(Darwin) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }

    /**
     * iOS equivalent of Android's `OAuthManager.spotifyReauthRequired` — flips
     * true when a refresh fails terminally (dead grant: revoked, or the
     * six-month expiry, #243), driving the Home reconnect banner. Cleared on a
     * successful (re)connect and on explicit disconnect.
     */
    private val _spotifyReauthRequired = MutableStateFlow(false)

    /** Spotify 401-refresh (plain client — no plugin recursion). */
    val spotifyTokenRefresher: SpotifyTokenRefresher by lazy {
        SpotifyTokenRefresher(settingsStore, authHttpClient) {
            _spotifyReauthRequired.value = true
        }
    }

    /** Request-time Spotify bearer lookup. Cached-read only; the reactive,
     *  single-flighted OAuthRefreshPlugin (wired with [spotifyTokenRefresher])
     *  owns all refresh — mirrors AndroidAuthTokenProvider. */
    val spotifyAuthProvider: SpotifyAuthTokenProvider by lazy {
        SpotifyAuthTokenProvider(settingsStore)
    }

    /**
     * Production shared Ktor `HttpClient` — the same one
     * `SharedModule` builds, with the User-Agent injection and shared
     * plugins. Auth is wired to [spotifyAuthProvider] /
     * [spotifyTokenRefresher]: Spotify requests get a bearer (refreshed on
     * 401 via the plain [authHttpClient]); every other realm returns null,
     * which is correct for the unauthenticated endpoints (MusicBrainz
     * search, the RSS/critics feeds, etc.).
     */
    val httpClient: HttpClient by lazy {
        createHttpClient(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
                coerceInputValues = true
            },
            appConfig,
            spotifyAuthProvider,
            spotifyTokenRefresher,
            lbTokenProvider = { settingsStore.getListenBrainzToken() },
        )
    }

    val musicBrainzClient: MusicBrainzClient by lazy { MusicBrainzClient(httpClient) }

    // ── SQLDelight database + DAOs (iOS DB layer) ──────────────────────
    // DriverFactory + ParachordDb.Schema already exist; a fresh iOS install
    // starts with the full v12-equivalent schema. Mirrors AndroidModule's
    // DriverFactory → ParachordDb → DAO wiring.
    val sqlDriver by lazy { DriverFactory().createDriver() }
    val database: ParachordDb by lazy { ParachordDb(sqlDriver) }
    val trackDao: TrackDao by lazy { TrackDao(database, sqlDriver) }
    val albumDao: AlbumDao by lazy { AlbumDao(database) }
    val artistDao: ArtistDao by lazy { ArtistDao(database) }
    val playlistDao: PlaylistDao by lazy { PlaylistDao(database) }
    val playlistTrackDao: PlaylistTrackDao by lazy { PlaylistTrackDao(database) }
    val friendDao: FriendDao by lazy { FriendDao(database) }

    // Shared FriendsRepository — full add/remove/pin/sync/activity surface.
    // Backs the Collection → Friends tab (#195). Mirrors AndroidModule's binding.
    val friendsRepository: FriendsRepository by lazy {
        FriendsRepository(
            friendDao = friendDao,
            lastFmClient = lastFmClient,
            listenBrainzClient = listenBrainzClient,
            metadataService = metadataService,
            settingsStore = settingsStore,
            lastFmApiKey = appConfig.lastFmApiKey,
        )
    }

    // ── Collection (#195) — DAO-backed facade ──────────────────────────
    // Mirrors the NON-SYNC slice of the shared LibraryRepository. Deliberately
    // does NOT construct LibraryRepository (its ctor requires SyncEngine — that's
    // the #194 epic). Decision recorded in docs/plans/2026-06-17-ios-collection-195.md.
    // Reads are reactive (FlowWatcher → Swift callback, cancel on disappear).

    fun watchCollectionTracks(onEach: (List<Track>) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(trackDao.getAll()) { onEach((it as? List<Track>) ?: emptyList()) }
    fun watchCollectionAlbums(onEach: (List<Album>) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(albumDao.getAll()) { onEach((it as? List<Album>) ?: emptyList()) }
    fun watchCollectionArtists(onEach: (List<Artist>) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(artistDao.getAll()) { onEach((it as? List<Artist>) ?: emptyList()) }
    fun watchCollectionFriends(onEach: (List<Friend>) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(friendsRepository.getAllFriends()) { onEach((it as? List<Friend>) ?: emptyList()) }

    // Refresh each friend's cached now-playing (mirrors Android's friend activity
    // refresh on the Friends tab). Fire-and-forget; failures are swallowed in the repo.
    fun refreshFriendsActivity() { appScope.launch { runCatching { friendsRepository.refreshAllActivity() } } }
    suspend fun removeFriend(friendId: String) { friendsRepository.removeFriend(friendId) }
    suspend fun pinFriend(friendId: String, pinned: Boolean) { friendsRepository.pinFriend(friendId, pinned) }

    // Reactive in-collection checks — back the collection-toggle UI on detail/cards.
    fun watchTrackInCollection(title: String, artist: String, onEach: (Boolean) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(trackDao.existsByTitleAndArtist(title, artist)) { onEach((it as? Boolean) ?: false) }
    fun watchAlbumInCollection(title: String, artist: String, onEach: (Boolean) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(albumDao.existsByTitleAndArtist(title, artist)) { onEach((it as? Boolean) ?: false) }
    fun watchArtistInCollection(name: String, onEach: (Boolean) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(artistDao.existsByName(name)) { onEach((it as? Boolean) ?: false) }

    // Add to collection: upsert + fire MBID enrichment. (Tombstone-clear and
    // loves-push are intentionally no-ops here until #194 / #226 wire them.)
    suspend fun addTrackToCollection(track: Track) {
        trackDao.insert(track)
        mbidEnrichmentService.enrichInBackground(track.id, track.artist, track.title)
    }
    suspend fun addAlbumToCollection(album: Album) { albumDao.insert(album) }
    suspend fun addArtistToCollection(artist: Artist) { artistDao.insert(artist) }

    // Remove from collection — LOCAL ONLY. #194 wires SyncEngine.onTrackRemoved
    // (remote remove + tombstone) in here so removals propagate + don't re-import.
    suspend fun removeTrackFromCollection(track: Track) { trackDao.delete(track) }
    suspend fun removeAlbumFromCollection(album: Album) { albumDao.delete(album) }
    suspend fun removeArtistFromCollection(artist: Artist) { artistDao.delete(artist) }

    // Toggle album/artist collection from detail screens (#195 M4). ID + row
    // construction live in Kotlin so the `hashCode()`-based ID matches Android
    // exactly (Swift's String.hashValue differs). year/trackCount use 0 as the
    // "unknown" sentinel to keep the Swift→Kotlin bridge on primitive Int.
    suspend fun toggleAlbumCollection(title: String, artist: String, artworkUrl: String?, year: Int, trackCount: Int) {
        val existing = albumDao.getByTitleAndArtist(title, artist)
        if (existing != null) { albumDao.delete(existing); return }
        albumDao.insert(Album(
            id = "album-${title.hashCode()}-${artist.hashCode()}",
            title = title, artist = artist, artworkUrl = artworkUrl,
            year = year.takeIf { it > 0 }, trackCount = trackCount.takeIf { it > 0 },
        ))
    }

    suspend fun toggleArtistCollection(name: String, imageUrl: String?) {
        val existing = artistDao.getByName(name).first()
        if (existing != null) { artistDao.delete(existing); return }
        artistDao.insert(Artist(id = "manual-${randomUUID()}", name = name, imageUrl = imageUrl))
    }

    // ── Charts (Pop of the Tops) ───────────────────────────────────────
    val appleMusicClient: AppleMusicClient by lazy { AppleMusicClient(httpClient) }
    /**
     * Achordion track-links submit client (#215). Used by [scrobbleManager] to
     * pre-warm the per-service link cache on scrobble. Bearer token from
     * [appConfig]; blank token = submit short-circuits inertly.
     */
    val achordionClient: AchordionClient by lazy {
        AchordionClient(
            httpClient = httpClient,
            bearerToken = appConfig.achordionBearerToken,
            clientId = appConfig.parachordClient,
        )
    }
    val lastFmClient: LastFmClient by lazy { LastFmClient(httpClient) }
    val chartsRepository: ChartsRepository by lazy {
        ChartsRepository(appleMusicClient, lastFmClient, lastFmApiKey = appConfig.lastFmApiKey)
    }

    /** Pop of the Tops — Apple Music (iTunes RSS) top albums (default tab) +
     *  top songs. No auth/key. Mirrors Android's Albums/Songs tabs. */
    suspend fun loadPopOfTheTopsAlbums(countryCode: String): List<ChartAlbum> =
        chartsRepository.getAppleMusicAlbums(countryCode)

    suspend fun loadPopOfTheTops(countryCode: String): List<ChartSong> =
        chartsRepository.getAppleMusicSongs(countryCode)

    // ── Metadata (Album + Artist detail) ───────────────────────────────
    // Cascading providers per CLAUDE.md: MusicBrainz (discography/tracklists)
    // → Last.fm (images/bio/similar) → Spotify (art/IDs).
    val metadataService: MetadataService by lazy {
        val mbProvider = MusicBrainzProvider(musicBrainzClient)
        MetadataService(
            // Priority order (cascade fills gaps): MB 0, Wikipedia 5, Last.fm 10,
            // Discogs 15, Spotify 20 — matches AndroidModule's provider set so
            // artist images/bios fall back to Wikipedia/Discogs (no auth) when
            // Spotify isn't connected.
            providers = listOf(
                mbProvider,
                WikipediaProvider(musicBrainzClient, mbProvider, httpClient),
                LastFmProvider(lastFmClient, appConfig.lastFmApiKey),
                DiscogsProvider(httpClient, settingsStore),
                SpotifyProvider(spotifyClient, settingsStore),
                // Gap-filler (priority 25, last): Apple Music catalog artist art
                // when nothing above had an image. Inert without a dev token.
                AppleMusicArtistProvider(httpClient, appConfig.appleMusicDeveloperToken),
            ),
            getDisabledProviders = { settingsStore.getDisabledPlugins() },
            // Mirrors AndroidModule.enrichAlbumArtworkViaItunes: upgrade Cover
            // Art Archive URLs to iTunes art via the shared AppleMusicClient.
            enrichAlbumArtwork = { artistName, albums ->
                enrichAlbumArtworkViaItunes(appleMusicClient, artistName, albums)
            },
            // Persistent artist-image URL cache lives in the shared service now
            // (was the iOS-only artist-images.json); reuse it across sessions.
            artistImageCacheRead = { IosFileCache.read("artist_images_cache.json") },
            artistImageCacheWrite = { IosFileCache.write("artist_images_cache.json", it) },
        )
    }

    /** Verbatim port of AndroidModule.enrichAlbumArtworkViaItunes — a DI
     *  closure over the shared AppleMusicClient, not enrichment logic. */
    private suspend fun enrichAlbumArtworkViaItunes(
        appleMusicClient: AppleMusicClient,
        artistName: String,
        albums: List<AlbumSearchResult>,
    ): List<AlbumSearchResult> = coroutineScope {
        albums.map { album ->
            async {
                val url = album.artworkUrl
                if (url != null && url.contains("coverartarchive.org")) {
                    try {
                        val response = appleMusicClient.search(
                            term = "$artistName ${album.title}",
                            entity = "album",
                            limit = 3,
                        )
                        val match = response.results.firstOrNull { item ->
                            item.collectionName != null &&
                                item.artistName?.lowercase()?.contains(artistName.lowercase()) == true
                        }
                        val itunesArt = match?.artworkUrl100?.replace("100x100", "600x600")
                        if (itunesArt != null) album.copy(artworkUrl = itunesArt) else album
                    } catch (_: Exception) {
                        album
                    }
                } else {
                    album
                }
            }
        }.awaitAll()
    }

    suspend fun getAlbumDetail(albumTitle: String, artistName: String): AlbumDetail? =
        metadataService.getAlbumTracks(albumTitle, artistName)

    suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        metadataService.getArtistInfo(artistName)

    /** Fast artist-IMAGE lookup (Apple-Music-first, short-circuit) for the iOS
     *  ArtistImageCache — header + related-artist images (#187). */
    suspend fun getArtistImage(artistName: String): String? =
        metadataService.getArtistImage(artistName)

    suspend fun getArtistTopTracks(artistName: String): List<TrackSearchResult> =
        metadataService.getArtistTopTracks(artistName, 10)

    suspend fun getArtistAlbums(artistName: String): List<AlbumSearchResult> =
        metadataService.getArtistAlbums(artistName, 50)

    /** Artist's upcoming tour dates (unfiltered) for the On Tour tab (#201). Collects
     *  the cached-first Resource flow to its terminal list. */
    suspend fun getArtistEvents(artistName: String): List<ConcertEvent> {
        var result = emptyList<ConcertEvent>()
        concertsRepository.getArtistEvents(artistName).collect { res ->
            if (res is Resource.Success) result = res.data
        }
        return result
    }

    /** Whether the artist has upcoming shows near the user's saved concert location
     *  (#201 Now Playing dot). null location → any upcoming show counts. */
    suspend fun checkOnTour(artistName: String): Boolean {
        val loc = settingsStore.getConcertLocation()
        return concertsRepository.checkOnTour(artistName, loc.latitude, loc.longitude, loc.radiusMiles)
    }

    // ── Recommendations ("For You") ────────────────────────────────────
    // The other curated repos (Critical Darlings, Fresh Drops) need the iOS DB
    // (ImageEnrichmentService's DAOs / TrackDao) and stay blocked until it
    // lands. Recommendations only needs ListenBrainz + MetadataService. Disk
    // cache is a no-op for now (stale-while-revalidate optimization only).
    val recommendationsRepository: RecommendationsRepository by lazy {
        RecommendationsRepository(
            httpClient = httpClient,
            listenBrainzClient = listenBrainzClient,
            settingsStore = settingsStore,
            // Spotify-free: RecommendationsRepository's only MetadataService use
            // is getArtistInfo per recommended artist (artist images/bios) — a
            // ~15-artist Spotify-search burst on the shared key. Tracks come
            // from ListenBrainz, not metadata, so this costs nothing (artist
            // images already came from Last.fm/MusicBrainz, not Spotify).
            metadataService = enrichMetadataService,
            cacheRead = { IosFileCache.read("recommendations_cache.json") },
            cacheWrite = { IosFileCache.write("recommendations_cache.json", it) },
        )
    }

    suspend fun loadRecommendedTracks(): List<RecommendedTrack> {
        var out = emptyList<RecommendedTrack>()
        recommendationsRepository.getRecommendedTracks().collect { res ->
            if (res is Resource.Success) out = res.data
        }
        return out
    }

    suspend fun loadRecommendedArtists(): List<RecommendedArtist> {
        var out = emptyList<RecommendedArtist>()
        recommendationsRepository.getRecommendedArtists().collect { res ->
            if (res is Resource.Success) out = res.data
        }
        return out
    }

    // ── Curated lists that need DB DAOs (now unblocked) ────────────────
    // composeMosaic is a no-op on iOS for now (single art instead of a 2x2
    // playlist mosaic). Disk/rotation caches are no-ops (fetch fresh).
    // Spotify-FREE metadata service for image enrichment. Album art's primary
    // source is MusicBrainz → Cover Art Archive, upgraded to iTunes via the
    // same enrichAlbumArtwork lambda as the full service. Spotify is only a
    // priority-20 last-resort art gap-filler, so dropping it from enrichment
    // loses almost no art while removing the per-album Spotify search burst
    // that re-trips the shared key's 3600s window on every cold Discover load.
    // User-initiated Album/Artist screens keep the full Spotify cascade.
    private val enrichMetadataService: MetadataService by lazy {
        val mbProvider = MusicBrainzProvider(musicBrainzClient)
        MetadataService(
            // Spotify-free (avoids the shared-key burst on cold Discover loads),
            // but DOES include Wikipedia + Discogs so background artist-image
            // enrichment (ImageEnrichmentService) and recommendations get real
            // artist images without a connected Spotify. No auth on either.
            providers = listOf(
                mbProvider,
                WikipediaProvider(musicBrainzClient, mbProvider, httpClient),
                LastFmProvider(lastFmClient, appConfig.lastFmApiKey),
                DiscogsProvider(httpClient, settingsStore),
                AppleMusicArtistProvider(httpClient, appConfig.appleMusicDeveloperToken),
            ),
            getDisabledProviders = { settingsStore.getDisabledPlugins() },
            enrichAlbumArtwork = { artistName, albums ->
                enrichAlbumArtworkViaItunes(appleMusicClient, artistName, albums)
            },
        )
    }

    val imageEnrichmentService: ImageEnrichmentService by lazy {
        ImageEnrichmentService(
            enrichMetadataService, artistDao, albumDao, trackDao, playlistDao, playlistTrackDao,
            httpClient,
            composeMosaic = { _, _ -> null },
        )
    }

    val criticalDarlingsRepository: CriticalDarlingsRepository by lazy {
        CriticalDarlingsRepository(
            httpClient, musicBrainzClient, imageEnrichmentService,
            cacheRead = { IosFileCache.read("critical_darlings_cache.json") },
            cacheWrite = { IosFileCache.write("critical_darlings_cache.json", it) },
        )
    }

    suspend fun loadCriticalDarlings(): List<CriticsPickAlbum> {
        var out = emptyList<CriticsPickAlbum>()
        criticalDarlingsRepository.getCriticsPicks(false).collect { res ->
            if (res is Resource.Success) out = res.data
        }
        return out
    }

    /**
     * Reactive Critical Darlings — the repository already emits its disk-cached
     * value FIRST, then the fresh one (stale-while-revalidate), and persists via
     * its own cacheRead/cacheWrite. Swift watches this `Flow` (FlowWatcher) so the
     * cached list shows instantly on cold start and fresh data fades in — no
     * iOS-side duplicate cache needed (architecture realignment, plan 2026-06-12).
     */
    fun criticsPicksFlow(): Flow<List<CriticsPickAlbum>> =
        criticalDarlingsRepository.getCriticsPicks(false)
            .mapNotNull { (it as? Resource.Success)?.data }

    // Same stale-while-revalidate flows for the other curated lists — the repos own
    // their caches; Swift watches these so no iOS-side duplicate cache is needed
    // (architecture realignment, plan 2026-06-12).
    fun freshDropsFlow(): Flow<List<FreshDrop>> =
        freshDropsRepository.getFreshDrops(false)
            .mapNotNull { (it as? Resource.Success)?.data }

    fun recommendedTracksFlow(): Flow<List<RecommendedTrack>> =
        recommendationsRepository.getRecommendedTracks()
            .mapNotNull { (it as? Resource.Success)?.data }

    fun recommendedArtistsFlow(): Flow<List<RecommendedArtist>> =
        recommendationsRepository.getRecommendedArtists()
            .mapNotNull { (it as? Resource.Success)?.data }

    /**
     * Concerts is two-step (recommended artists → personalized events). We build
     * the artist seed, then stream the ConcertsRepository's own cached-first +
     * fresh emissions (it persists to concerts_cache.json). Swift watches this;
     * no iOS-side duplicate cache (fixes the two-caches-fighting bug).
     */
    fun concertsFlow(): Flow<List<ConcertEvent>> = flow {
        val artists = (try { loadRecommendedArtists() } catch (e: Exception) { emptyList() })
            .take(12)
            .map { ConcertArtist(name = it.name, source = "history", imageUrl = it.imageUrl) }
        if (artists.isEmpty()) { emit(emptyList()); return@flow }
        val loc = settingsStore.getConcertLocation()
        emitAll(
            concertsRepository.getPersonalizedEvents(
                artists, lat = loc.latitude, lon = loc.longitude, radiusMiles = loc.radiusMiles,
            ).mapNotNull { (it as? Resource.Success)?.data },
        )
    }

    // ── Concerts ────────────────────────────────────────────────────────
    private val ticketmasterClient by lazy { TicketmasterClient(httpClient) }
    private val seatGeekClient by lazy { SeatGeekClient(httpClient) }
    val concertsRepository: ConcertsRepository by lazy {
        ConcertsRepository(
            ticketmasterClient, seatGeekClient, settingsStore,
            cacheRead = { IosFileCache.read("concerts_cache.json") },
            cacheWrite = { IosFileCache.write("concerts_cache.json", it) },
            ticketmasterApiKeyFallback = "",
            seatGeekClientIdFallback = "",
        )
    }

    /** Upcoming shows from the user's top recommended artists (the iOS stand-in
     *  for the library until the DB lands). Bounded to keep the per-artist
     *  Ticketmaster/SeatGeek fan-out small. */
    suspend fun loadConcerts(): List<ConcertEvent> {
        val artists = (try { loadRecommendedArtists() } catch (e: Exception) { emptyList() })
            .take(12)
            .map { ConcertArtist(name = it.name, source = "history", imageUrl = it.imageUrl) }
        if (artists.isEmpty()) return emptyList()
        val loc = settingsStore.getConcertLocation()
        var out = emptyList<ConcertEvent>()
        concertsRepository.getPersonalizedEvents(
            artists, lat = loc.latitude, lon = loc.longitude, radiusMiles = loc.radiusMiles,
        ).collect { res ->
            if (res is Resource.Success) out = res.data
        }
        return out
    }

    // ── Geo location (geoIP detect + Nominatim typeahead/reverse) ───────
    // Shared client used by the concert-location picker (#199). Reuses the
    // shared httpClient (global User-Agent satisfies Nominatim's UA policy).
    // GPS detection is platform-specific (CoreLocation) and lives in Swift;
    // these wrappers cover the geoIP fallback + typeahead + reverse-geocode.
    val geoLocationClient: GeoLocationClient by lazy { GeoLocationClient(httpClient) }

    /** GeoIP detect (ipapi.co → ip-api.com → ipwho.is), reverse-geocoded to a
     *  city name. Coarse on cellular — the Swift caller treats this as a
     *  confirmable suggestion, NOT a direct commit (unlike GPS). */
    suspend fun detectLocationByIp(): GeoLocation? = geoLocationClient.detectLocationByIp()

    /** Nominatim forward-geocode typeahead for the city search field. */
    suspend fun searchLocations(query: String): List<GeoLocation> =
        geoLocationClient.searchLocations(query)

    /** Reverse-geocode GPS coords → a "City, State" display name. */
    suspend fun reverseGeocode(lat: Double, lng: Double): String? =
        geoLocationClient.reverseGeocode(lat, lng)

    // ── History (Last.fm top charts + Last.fm/ListenBrainz recent) ──────
    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(lastFmClient, listenBrainzClient, settingsStore, metadataService, appConfig.lastFmApiKey)
    }
    suspend fun loadTopTracks(period: String): List<HistoryTrack> {
        var out = emptyList<HistoryTrack>()
        historyRepository.getTopTracks(period, 50).collect { if (it is Resource.Success) out = it.data }
        return out
    }
    suspend fun loadTopAlbums(period: String): List<HistoryAlbum> {
        var out = emptyList<HistoryAlbum>()
        historyRepository.getTopAlbums(period, 50).collect { if (it is Resource.Success) out = it.data }
        return out
    }
    suspend fun loadTopArtists(period: String): List<HistoryArtist> {
        var out = emptyList<HistoryArtist>()
        historyRepository.getTopArtists(period, 50).collect { if (it is Resource.Success) out = it.data }
        return out
    }
    suspend fun loadRecentTracks(): List<RecentTrack> {
        var out = emptyList<RecentTrack>()
        historyRepository.getRecentTracks().collect { if (it is Resource.Success) out = it.data }
        return out
    }

    val freshDropsRepository: FreshDropsRepository by lazy {
        FreshDropsRepository(
            musicBrainzClient, lastFmClient, listenBrainzClient, settingsStore, trackDao,
            cacheRead = { IosFileCache.read("fresh_drops_cache.json") },
            cacheWrite = { IosFileCache.write("fresh_drops_cache.json", it) },
            rotationRead = { IosFileCache.read("fresh_drops_rotation.json") },
            rotationWrite = { IosFileCache.write("fresh_drops_rotation.json", it) },
            mbidLookupCached = { null },
            mbidLookupViaMapper = { artist, title -> listenBrainzClient.mbidMapperLookup(artist, title)?.artistMbid },
            imageEnrichmentService = imageEnrichmentService,
            lastFmApiKey = appConfig.lastFmApiKey,
        )
    }

    suspend fun loadFreshDrops(): List<FreshDrop> {
        var out = emptyList<FreshDrop>()
        freshDropsRepository.getFreshDrops(false).collect { res ->
            if (res is Resource.Success) out = res.data
        }
        return out
    }

    /** Shared Spotify Web API client, authed via [spotifyAuthProvider]. The
     *  rate-limit cooldown is persisted to [kvStore] (matching Android) so a
     *  429 abuse window (Spotify's `Retry-After` can be 1h+) is honored across
     *  restarts — without this, every relaunch re-probes Spotify and gets a
     *  FRESH window, restarting the punishment clock (CLAUDE.md SpotifyClient). */
    val spotifyClient: SpotifyClient by lazy {
        SpotifyClient(
            httpClient,
            spotifyAuthProvider,
            loadCooldownEpochMs = { kvStore.getLong(SPOTIFY_COOLDOWN_KEY, 0L) },
            saveCooldownEpochMs = { kvStore.setLong(SPOTIFY_COOLDOWN_KEY, it) },
        )
    }

    /** Spotify authorization-code → token exchange (plain client). */
    val spotifyAuth: IosSpotifyAuth by lazy {
        IosSpotifyAuth(authHttpClient, settingsStore)
    }

    // ── BYO Spotify Client ID (Parachord ships none — user supplies their own) ──
    suspend fun getSpotifyClientId(): String = settingsStore.getSpotifyClientId() ?: ""
    suspend fun setSpotifyClientId(clientId: String) = settingsStore.setSpotifyClientId(clientId)
    suspend fun clearSpotifyClientId() = settingsStore.clearSpotifyClientId()

    // ── Swift-callable Spotify auth surface ────────────────────────────

    /**
     * Complete the Spotify OAuth flow: exchange the authorization code (from
     * `IosOAuthManager.authorize`) for tokens and persist them. Throws on
     * non-2xx / missing client ID.
     */
    suspend fun connectSpotify(code: String, codeVerifier: String) {
        spotifyAuth.exchangeCode(code, codeVerifier, "parachord://auth/callback/spotify")
        _spotifyReauthRequired.value = false // a fresh grant clears the reauth banner
    }

    /** Clear stored Spotify tokens (disconnect). */
    suspend fun disconnectSpotify() {
        settingsStore.clearSpotifyTokens()
        _spotifyReauthRequired.value = false // user-initiated; not a reauth-needed state
    }

    /** Emits true when a Spotify access token is present, false otherwise. */
    fun getSpotifyConnectedFlow(): Flow<Boolean> =
        settingsStore.getSpotifyAccessTokenFlow().map { it != null }

    /**
     * Emits true when a Spotify token refresh failed terminally (dead grant) —
     * drives the Home reconnect banner. Distinct from [getSpotifyConnectedFlow]:
     * a user who never connected is "not connected" but does NOT need re-auth.
     */
    fun getSpotifyReauthRequiredFlow(): Flow<Boolean> = _spotifyReauthRequired

    // ── Libre.fm (auth.getMobileSession via the shared LibreFmClient) ───
    val libreFmClient: com.parachord.shared.api.LibreFmClient by lazy {
        com.parachord.shared.api.LibreFmClient(httpClient)
    }
    /** Connect with username + password; persists the session key on success. */
    suspend fun connectLibreFm(username: String, password: String): Boolean {
        val key = libreFmClient.authenticate(username, password) ?: return false
        settingsStore.setLibreFmSession(key)
        return true
    }
    suspend fun disconnectLibreFm() { settingsStore.clearLibreFmSession() }
    fun getLibreFmConnectedFlow(): Flow<Boolean> =
        settingsStore.getLibreFmSessionKeyFlow().map { it != null }

    // ── Last.fm (web-auth token → auth.getSession session key, for scrobbling) ──
    /**
     * Exchange the Last.fm web-auth `token` for a session key + username
     * (#193). The session key is what scrobbling/now-playing/love require —
     * the read-only username alone can't authenticate writes.
     */
    suspend fun connectLastFm(token: String): Boolean {
        val result = lastFmClient.getSession(token, appConfig.lastFmApiKey, appConfig.lastFmSharedSecret)
            ?: return false
        settingsStore.setLastFmSession(result.key)
        result.name?.let { settingsStore.setLastFmUsername(it) }
        return true
    }
    suspend fun disconnectLastFm() { settingsStore.clearLastFmSession() }
    fun getLastFmConnectedFlow(): Flow<Boolean> =
        settingsStore.getLastFmSessionKeyFlow().map { it != null }

    val listenBrainzClient: ListenBrainzClient by lazy { ListenBrainzClient(httpClient) }

    // ── Scrobbling (#193 — shared stack, identical to Android) ─────────
    val mbidEnrichmentService: MbidEnrichmentService by lazy {
        MbidEnrichmentService(
            listenBrainzClient = listenBrainzClient,
            trackDao = trackDao,
            cacheRead = { IosFileCache.read("mbid_mapper_cache.json") },
            cacheWrite = { IosFileCache.write("mbid_mapper_cache.json", it) },
            // Service-agnostic ISRC → MBID fallback when the LB mapper is down.
            musicBrainzClient = musicBrainzClient,
        )
    }

    private val scrobblers: Set<Scrobbler> by lazy {
        setOf(
            LastFmScrobbler(settingsStore, lastFmClient, appConfig),
            ListenBrainzScrobbler(settingsStore, listenBrainzClient, mbidEnrichmentService),
            LibreFmScrobbler(settingsStore, lastFmClient),
        )
    }

    /**
     * Live playback snapshot the scrobble threshold reads. Swift's playback
     * coordinator MUST push updates via [updateScrobbleState] on play / pause /
     * track-change / position tick — otherwise no scrobbles fire (#193 Step 3
     * Swift hookup).
     */
    private val scrobbleState = MutableStateFlow(
        ScrobbleState(currentTrack = null, isPlaying = false, position = 0L, duration = 0L),
    )

    val scrobbleManager: ScrobbleManager by lazy {
        ScrobbleManager(
            settingsStore = settingsStore,
            playbackStateFlow = scrobbleState,
            scrobblers = scrobblers,
            trackDao = trackDao,
            mbidEnrichment = mbidEnrichmentService,
            // .axe playback-telemetry dispatch (achordion) into the JSC
            // `window.scrobbleManager` registry — parity with Android's WebView
            // path. Fire-and-forget; evaluateJs initializes the runtime if cold.
            dispatchToPlugins = { event, track ->
                appScope.launch {
                    try {
                        pluginManager.evaluateJs(ScrobblePluginDispatch.dispatchScript(event, track))
                    } catch (e: Exception) {
                        Log.w("IosScrobbleDispatch", "dispatch($event) failed: ${e.message}")
                    }
                }
            },
            // Native Achordion track-links submit on scrobble (#215) — reliable
            // link-cache pre-warm independent of the achordion .axe JS plugin.
            achordionClient = achordionClient,
        )
    }

    /** Begin observing playback state for scrobbling. Call once at app start. */
    fun startScrobbling() { scrobbleManager.startObserving() }

    /**
     * Push the current playback snapshot from Swift (positions in ms). Drives
     * the now-playing + scrobble-threshold logic in the shared [ScrobbleManager].
     */
    fun updateScrobbleState(currentTrack: Track?, isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        scrobbleState.value = ScrobbleState(currentTrack, isPlaying, positionMs, durationMs)
    }

    // ── .axe plugin host (the cross-platform resolver system) ──────────

    /**
     * The JSC-backed runtime that hosts the same `.axe` plugins as
     * desktop + Android. Swift MUST set [IosJsRuntime.nativeBridgeInstaller]
     * (via `JsPolyfills.installNativeBridge`) before [pluginManager] is
     * initialized — the bootstrap polyfills reference `NativeBridge`.
     */
    val pluginJsRuntime: IosJsRuntime by lazy { IosJsRuntime() }

    /**
     * Shared [PluginManager] — common to all platforms. Reads bundled
     * `.axe` files via [PluginFileAccess] (NSBundle on iOS) and drives
     * them through [pluginJsRuntime].
     */
    val pluginManager: PluginManager by lazy {
        PluginManager(PluginFileAccess(), pluginJsRuntime)
    }

    /**
     * Initialize the plugin host (idempotent) and return the loaded
     * plugin IDs. The Swift caller attaches the NativeBridge installer
     * before calling this.
     */
    suspend fun loadPluginsAndList(): List<String> {
        pluginManager.ensureInitialized()
        return pluginManager.plugins.value.map { it.id }
    }

    /**
     * Resolve a track through a specific `.axe` resolver, awaiting the
     * async result properly. Returns the raw JSON result string, or null
     * for no-match.
     *
     * The shared `PluginManager.resolve` uses an `(async () => …)()` IIFE
     * and reads its return synchronously — which yields `[object Promise]`
     * on any runtime (the async hasn't settled yet; CLAUDE.md mistake
     * #27). The resolver-loader exposes `window.__lastPluginResult` for
     * exactly this: kick off the async work storing its outcome there,
     * then poll. Polling with `delay()` yields the main run loop so the
     * fetch callbacks (URLSession → MainActor → `__fetchCallbacks` → JS
     * Promise) can fire and settle the resolve.
     */
    suspend fun pluginResolveAsync(resolverId: String, artist: String, title: String): String? {
        pluginManager.ensureInitialized()
        val a = artist.replace("\\", "\\\\").replace("'", "\\'")
        val t = title.replace("\\", "\\\\").replace("'", "\\'")
        pluginJsRuntime.evaluate(
            """
            window.__lastPluginResult = 'pending';
            (async () => {
                try {
                    var r = window.__resolverLoader.getResolver('$resolverId');
                    if (!r || !r.resolve) { window.__lastPluginResult = 'null'; return; }
                    var result = await r.resolve('$a', '$t', null, r.config || {});
                    window.__lastPluginResult = result ? JSON.stringify(result) : 'null';
                } catch (e) {
                    window.__lastPluginResult = 'error: ' + ((e && e.message) ? e.message : String(e));
                }
            })();
            """.trimIndent()
        )
        repeat(50) {
            kotlinx.coroutines.delay(100)
            val r = pluginJsRuntime.evaluate("window.__lastPluginResult")
            if (r != null && r != "pending") {
                return if (r == "null") null else r
            }
        }
        return null
    }

    /** Full plugin metadata for the Settings UI (id/name/version/type/icon/color/capabilities). */
    suspend fun loadPlugins(): List<PluginManager.PluginInfo> {
        pluginManager.ensureInitialized()
        return pluginManager.plugins.value
    }
    /** Includes platform-filtered plugins (e.g. youtube) — for the "N plugins loaded" count. */
    suspend fun loadAllPlugins(): List<PluginManager.PluginInfo> {
        pluginManager.ensureInitialized()
        return pluginManager.allLoadedPlugins.value
    }

    /**
     * Resolver ids that resolve tracks but can't stream audio (stream:false,
     * e.g. Bandcamp) — playback opens these in the browser. Mirrors Android's
     * PlaybackRouter check (`capabilities["stream"] == false && resolve == true`).
     */
    suspend fun nonStreamingResolverIds(): Set<String> {
        pluginManager.ensureInitialized()
        return pluginManager.plugins.value
            .filter { it.capabilities["stream"] == false && it.capabilities["resolve"] == true }
            .map { it.id }.toSet()
    }

    // ── Plugin marketplace (GitHub parachord-plugins → hot-reload) ──────
    val pluginSyncService: PluginSyncService by lazy {
        PluginSyncService(
            httpClient, pluginManager, PluginFileAccess(),
            getLastSyncTimestamp = { settingsStore.getLastPluginSyncTimestamp() },
            setLastSyncTimestamp = { settingsStore.setLastPluginSyncTimestamp(it) },
        )
    }
    suspend fun syncPluginsNow(): PluginSyncService.SyncResult = pluginSyncService.sync()

    /** 24h-debounced marketplace sync for app start (Android parity:
     *  MainViewModel.syncIfNeeded). Downloads new/updated `.axe` (e.g. the
     *  achordion playback-telemetry plugin) + hot-reloads them so they register.
     *  Returns null when the debounce window hasn't elapsed. */
    suspend fun syncPluginsIfNeeded(): PluginSyncService.SyncResult? = pluginSyncService.syncIfNeeded()

    // ── Resolver pipeline (.axe-only, the iOS ResolverManager-equivalent) ──

    /**
     * Shared priority + confidence scoring, wired to the user's resolver
     * order + active set from [settingsStore]. Same instance Android builds
     * in its Koin graph.
     */
    val resolverScoring: ResolverScoring by lazy {
        ResolverScoring(
            getResolverOrder = { settingsStore.getResolverOrder() },
            getActiveResolvers = { settingsStore.getActiveResolvers() },
        )
    }

    /**
     * Platform-specific resolver execution (#210, D2): native Apple Music
     * (catalog HTTP) + `.axe` via the JSC unique-key polling workaround. The
     * shared [com.parachord.shared.resolver.ResolverCoordinator] owns the
     * fan-out / gating / re-score / ranking on top of this.
     */
    val resolverRuntime: IosResolverRuntime by lazy {
        IosResolverRuntime(
            jsRuntime = pluginJsRuntime,
            settingsStore = settingsStore,
            httpClient = httpClient,
            appleMusicDeveloperToken = appConfig.appleMusicDeveloperToken,
        )
    }

    /**
     * Spotify resolve lambda — the EXACT old iOS Spotify branch from
     * `resolveSources`: gate on a present access token, then
     * `spotifyClient.searchTrack("$artist $title")` in a try/catch that
     * rethrows CancellationException and returns null on other exceptions.
     * Wired once here; reused by the shared coordinator AND
     * [IosResolverCoordinator.resolveSingle].
     */
    private val resolveSpotify: suspend (String) -> ResolvedSource? = { query ->
        if (settingsStore.getSpotifyAccessToken() == null) {
            null
        } else {
            try {
                spotifyClient.searchTrack(query)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Shared resolver fan-out (#210): native Spotify branch + native Apple
     * Music ([resolverRuntime]) + `.axe` resolvers, gated + re-scored + ranked.
     * Same shape Android builds in its Koin graph.
     */
    val sharedResolverCoordinator: ResolverCoordinator by lazy {
        ResolverCoordinator(
            runtime = resolverRuntime,
            scoring = resolverScoring,
            getActive = { settingsStore.getActiveResolvers() },
            getDisabled = { settingsStore.getDisabledPlugins() },
            getPlugins = { pluginManager.plugins.value },
            // Single source of truth — same lambda reused by resolveSingle, so the
            // Spotify gate can't drift between the two paths.
            resolveSpotify = resolveSpotify,
        )
    }

    val resolverCoordinator: IosResolverCoordinator by lazy {
        IosResolverCoordinator(
            coordinator = sharedResolverCoordinator,
            pluginManager = pluginManager,
        )
    }

    /**
     * Resolve a track through the `.axe` pipeline and return the ranked,
     * floor-filtered sources (best first). The Swift `PlaybackRouter` walks
     * this list for the first engine-available source.
     */
    suspend fun resolveSources(
        artist: String,
        title: String,
        album: String?,
        spotifyId: String? = null,
        appleMusicId: String? = null,
    ): List<ResolvedSource> =
        resolverCoordinator.resolveSources(artist, title, album, spotifyId, appleMusicId)

    /**
     * Return [track] enriched with the streaming IDs + active resolver taken from
     * the best-ranked [sources]. The now-playing/scrobble Track MUST carry these:
     * achordion's played-source confidence, the native Achordion submit (#215),
     * and LB source-enrichment all read `spotifyId`/`appleMusicId`/`soundcloudId`
     * off the Track. Without this, an Apple-Music-streamed track still reaches the
     * scrobble path with all IDs null → achordion sees confidence 0.00 and the
     * native submit sees zero links.
     *
     * Additive — never overwrites an ID the track already has. The `id` is
     * preserved (copy), so it doesn't re-trigger now-playing detection. Mirrors
     * Android, whose `PlaybackController` scrobbles the resolved `routedTrack`.
     */
    fun trackWithResolvedSources(track: Track, sources: List<ResolvedSource>): Track {
        if (sources.isEmpty()) return track
        fun pick(get: (ResolvedSource) -> String?): String? =
            sources.firstNotNullOfOrNull { get(it)?.takeIf { s -> s.isNotBlank() } }
        return track.copy(
            resolver = track.resolver ?: sources.first().resolver,
            spotifyId = track.spotifyId ?: pick { it.spotifyId },
            spotifyUri = track.spotifyUri ?: pick { it.spotifyUri },
            appleMusicId = track.appleMusicId ?: pick { it.appleMusicId },
            soundcloudId = track.soundcloudId ?: pick { it.soundcloudId },
            // Walk + validate ISRC with desktop-parity precedence (#217): carried
            // top-level first, then sources (skipping noMatch), canonical-or-null.
            isrc = com.parachord.shared.resolver.pickTrackIsrc(track.isrc, sources),
        )
    }

    /**
     * Fire-and-forget recording-MBID enrichment at playback start, mirroring
     * Android's `PlaybackController.enrichInBackground` (#215). Warms the mapper
     * cache (and persists to the DB for library-backed tracks) so the MBID is
     * present by scrobble time — the scrobble path's on-demand `getRecordingMbid`
     * then hits the cache instead of depending on a live mapper call 30s in.
     * Without it iOS had no enrichment hook, so every track relied on the live
     * mapper at scrobble (and submitted nothing while the mapper was down).
     * The service dedups + persists; no-ops on blank artist/title.
     */
    fun enrichTrackMbidInBackground(track: Track) {
        mbidEnrichmentService.enrichInBackground(track.id, track.artist, track.title)
    }

    /**
     * Resolve a recording MBID for the achordion LOVE path (#215), mirroring
     * Android's `NativeBridge.resolveMbidForLove`. The achordion plugin's
     * `resolveMbid(track)` fallback calls `window.resolveMbidForLove(track)`
     * when the loved track's row carries no MBID; the Swift `NativeBridge`
     * polyfill forwards the achordion track JSON here and resumes the JS promise
     * with the result.
     *
     * Parses the track JSON; returns an already-present, well-formed MBID
     * verbatim (short-circuit, no mapper call), else resolves (artist, title)
     * through the shared mapper. Returns null when the track lacks artist/title
     * or the mapper can't map it (correctly leaving it un-submitted). The
     * parsing/logic lives here (shared, mirrors Android) so the Swift bridge
     * stays a thin callback shim.
     */
    suspend fun resolveMbidForLove(trackJson: String): String? {
        return try {
            val obj = Json.parseToJsonElement(trackJson).jsonObject
            val existing = obj["mbid"]?.jsonPrimitive?.contentOrNull
            if (existing != null && MBID_REGEX.matches(existing)) {
                existing
            } else {
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val artist = obj["artist"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (title != null && artist != null) {
                    mbidEnrichmentService.getRecordingMbid(artist, title)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w("IosContainer", "resolveMbidForLove failed: ${e.message}")
            null
        }
    }

    private val MBID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Additive single-resolver resolution for [IosTrackResolverCache] when a
     *  resolver is enabled after a track was already cached (#1). */
    suspend fun resolveSingleResolver(resolverId: String, artist: String, title: String, album: String?): ResolvedSource? =
        resolverCoordinator.resolveSingle(resolverId, artist, title, album)

    /** Re-rank merged sources (best first) after an additive merge (#1). */
    suspend fun rankSources(sources: List<ResolvedSource>): List<ResolvedSource> =
        resolverScoring.selectRanked(sources, null)

    /**
     * Serialize / deserialize the persistent resolver-ID cache (Swift
     * `IosTrackResolverCache`). iOS has no DB yet, so without this every app
     * session re-searches every tracklist via Spotify/iTunes catalog search —
     * the volume that keeps re-arming Spotify's shared-key abuse window. The
     * Swift cache persists this blob to disk and reloads it on launch so a
     * track resolved once never re-searches. Mirrors Android's resolver-ID
     * backfill intent.
     */
    private val resolverCacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeResolverCache(map: Map<String, List<ResolvedSource>>): String =
        resolverCacheJson.encodeToString(map)

    fun decodeResolverCache(blob: String): Map<String, List<ResolvedSource>> =
        try {
            resolverCacheJson.decodeFromString<Map<String, List<ResolvedSource>>>(blob)
        } catch (e: Exception) {
            emptyMap()
        }

    // (Removed: the Swift-managed curated-list view caches — Critical Darlings /
    // Concerts / Fresh Drops now watch the repository value-flows directly via
    // FlowWatcher, so the repos' own disk caches surface cached-first with no
    // parallel iOS cache. Architecture realignment, plan 2026-06-12.)

    /**
     * Weekly Jams / Weekly Exploration from ListenBrainz. Needs only the
     * LB client + the SettingsStore (for the username the user set in
     * Settings). No auth — the createdfor playlists endpoint is public.
     */
    val weeklyPlaylistsRepository: WeeklyPlaylistsRepository by lazy {
        WeeklyPlaylistsRepository(
            listenBrainzClient = listenBrainzClient,
            settingsStore = settingsStore,
        )
    }

    /**
     * Load the Weekly Jams + Weekly Exploration entries, flattened into
     * a single Swift-friendly list tagged by kind. Returns empty if no
     * LB username is set. Powers the iOS Discover screen (phase 5.3).
     */
    suspend fun loadWeeklyPlaylists(forceRefresh: Boolean): List<IosWeeklyEntry> {
        // Self-heal: older iOS builds stored only the LB *token*, not the
        // *username* the public `createdfor` weekly endpoint needs (Android
        // derives both at token-save time via validateToken; iOS regressed to
        // token-only). If the token is present but the username is missing,
        // derive + persist it so Discover/Home weekly carousels load without
        // the user re-entering anything.
        if (settingsStore.getListenBrainzUsername() == null) {
            val token = settingsStore.getListenBrainzToken()
            if (!token.isNullOrBlank()) {
                listenBrainzClient.validateToken(token)?.let {
                    settingsStore.setListenBrainzUsername(it)
                }
            }
        }
        val result = weeklyPlaylistsRepository.loadWeeklyPlaylists(forceRefresh)
            ?: return emptyList()
        // "Weekly Jam" (singular) to parallel "Weekly Exploration" in the detail
        // header. NOTE: keep DiscoverViewModel's `kind == "Weekly Jam"` filter in sync.
        val jams = (result.jams ?: emptyList()).map { it.toIos(kind = "Weekly Jam") }
        val exploration = (result.exploration ?: emptyList())
            .map { it.toIos(kind = "Weekly Exploration") }
        return jams + exploration
    }

    /**
     * Load the tracks of a single weekly playlist (by MBID), flattened
     * to Swift-friendly DTOs. These are metadata-only LB tracks (no
     * resolver / streaming IDs yet) — browse-only until the iOS resolver
     * pipeline is wired. Powers the Playlist Detail screen (phase 5.4).
     */
    suspend fun loadWeeklyPlaylistTracks(playlistId: String): List<IosPlaylistTrack> {
        return weeklyPlaylistsRepository.loadPlaylistTracks(playlistId).map {
            IosPlaylistTrack(
                id = it.id,
                title = it.title,
                artist = it.artist,
                album = it.album,
                albumArt = it.albumArt,
                durationMs = it.durationMs,
            )
        }
    }

    /**
     * Save an ephemeral ListenBrainz weekly playlist to the library (#236),
     * mirroring Android's WeeklyPlaylistViewModel.saveToLibrary +
     * LibraryRepository.createPlaylistWithTracks. The container can't use
     * LibraryRepository (its ctor needs SyncEngine, not wired on iOS), so this
     * writes the playlist + track rows directly via the DAOs. Saved id is
     * "listenbrainz-<playlistId>" (Android parity). Returns the saved id.
     */
    suspend fun saveWeeklyPlaylist(
        playlistId: String,
        title: String,
        description: String?,
        artworkUrl: String?,
        tracks: List<Track>,
    ): String {
        val id = "listenbrainz-$playlistId"
        val now = com.parachord.shared.platform.currentTimeMillis()
        playlistDao.insert(
            com.parachord.shared.model.Playlist(
                id = id,
                name = title,
                description = description,
                artworkUrl = artworkUrl,
                trackCount = tracks.size,
                createdAt = now,
                updatedAt = now,
                lastModified = now,
                ownerName = "ListenBrainz",
            ),
        )
        playlistTrackDao.insertAll(
            tracks.mapIndexed { index, t ->
                com.parachord.shared.model.PlaylistTrack(
                    playlistId = id,
                    position = index,
                    trackTitle = t.title,
                    trackArtist = t.artist,
                    trackAlbum = t.album,
                    trackDuration = t.duration,
                    trackArtworkUrl = t.artworkUrl,
                    trackSourceUrl = t.sourceUrl,
                    trackResolver = t.resolver,
                    trackSpotifyUri = t.spotifyUri,
                    trackSoundcloudId = t.soundcloudId,
                    trackSpotifyId = t.spotifyId,
                    trackAppleMusicId = t.appleMusicId,
                    trackRecordingMbid = t.recordingMbid,
                )
            },
        )
        return id
    }

    /** Reactive "is this weekly playlist already saved?" for the Save button (#236). */
    fun watchWeeklyPlaylistSaved(playlistId: String, onEach: (Boolean) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(playlistDao.getByIdFlow("listenbrainz-$playlistId")) { onEach(it != null) }

    // ── Saved-playlist library (iOS playlist views — list / detail / edit) ────
    // Reads via the existing playlist DAOs (the container owns them); writes
    // (rename / delete / remove-track / reorder) renumber positions + bump
    // lastModified+locallyModified so a future sync picks the change up.

    fun watchSavedPlaylists(onEach: (List<Playlist>) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(playlistDao.getAll()) { onEach((it as? List<Playlist>) ?: emptyList()) }

    fun watchPlaylist(id: String, onEach: (Playlist?) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(playlistDao.getByIdFlow(id)) { onEach(it as? Playlist) }

    fun watchPlaylistTracks(id: String, onEach: (List<PlaylistTrack>) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(playlistTrackDao.getByPlaylistId(id)) { onEach((it as? List<PlaylistTrack>) ?: emptyList()) }

    suspend fun renamePlaylist(id: String, name: String) {
        val p = playlistDao.getById(id) ?: return
        playlistDao.update(p.copy(name = name, lastModified = com.parachord.shared.platform.currentTimeMillis(), locallyModified = true))
    }

    suspend fun deletePlaylist(id: String) {
        playlistTrackDao.deleteByPlaylistId(id)
        playlistDao.getById(id)?.let { playlistDao.delete(it) }
    }

    suspend fun removePlaylistTrackAt(id: String, index: Int) {
        val tracks = playlistTrackDao.getByPlaylistIdSync(id).toMutableList()
        if (index < 0 || index >= tracks.size) return
        tracks.removeAt(index)
        persistReorderedTracks(id, tracks)
    }

    suspend fun movePlaylistTrack(id: String, from: Int, to: Int) {
        val tracks = playlistTrackDao.getByPlaylistIdSync(id).toMutableList()
        if (from < 0 || from >= tracks.size || to < 0 || to >= tracks.size || from == to) return
        val moved = tracks.removeAt(from)
        tracks.add(to, moved)
        persistReorderedTracks(id, tracks)
    }

    /** Create a new empty local playlist (#242); returns the new id. */
    suspend fun createPlaylist(name: String): String {
        val id = "local-${com.parachord.shared.platform.randomUUID()}"
        val now = com.parachord.shared.platform.currentTimeMillis()
        playlistDao.insert(
            Playlist(id = id, name = name, trackCount = 0, createdAt = now, updatedAt = now, lastModified = now, locallyModified = true),
        )
        return id
    }

    /** One-shot saved-playlist list for the "Add to Playlist" picker (#240). */
    suspend fun getSavedPlaylistsOnce(): List<Playlist> = playlistDao.getAllSync()

    /** One-shot playlist tracks (for "Play Playlist" from the context menu). */
    suspend fun getPlaylistTracksOnce(id: String): List<PlaylistTrack> =
        playlistTrackDao.getByPlaylistIdSync(id)

    /** Append a track to a playlist at the end (#240). Bumps trackCount + flags. */
    suspend fun addTrackToPlaylist(playlistId: String, track: Track) {
        val pos = playlistTrackDao.getByPlaylistIdSync(playlistId).size
        playlistTrackDao.insertAll(
            listOf(
                PlaylistTrack(
                    playlistId = playlistId,
                    position = pos,
                    trackTitle = track.title,
                    trackArtist = track.artist,
                    trackAlbum = track.album,
                    trackDuration = track.duration,
                    trackArtworkUrl = track.artworkUrl,
                    trackSourceUrl = track.sourceUrl,
                    trackResolver = track.resolver,
                    trackSpotifyUri = track.spotifyUri,
                    trackSoundcloudId = track.soundcloudId,
                    trackSpotifyId = track.spotifyId,
                    trackAppleMusicId = track.appleMusicId,
                    trackRecordingMbid = track.recordingMbid,
                ),
            ),
        )
        playlistDao.getById(playlistId)?.let {
            playlistDao.update(it.copy(trackCount = pos + 1, lastModified = com.parachord.shared.platform.currentTimeMillis(), locallyModified = true))
        }
    }

    /** Renumber positions + atomically replace the rows, then bump the playlist. */
    private suspend fun persistReorderedTracks(id: String, tracks: List<PlaylistTrack>) {
        playlistTrackDao.replaceAll(id, tracks.mapIndexed { i, t -> t.copy(position = i) })
        playlistDao.getById(id)?.let {
            playlistDao.update(it.copy(trackCount = tracks.size, lastModified = com.parachord.shared.platform.currentTimeMillis(), locallyModified = true))
        }
    }

    private fun WeeklyPlaylistEntry.toIos(kind: String) = IosWeeklyEntry(
        id = id,
        title = title,
        weekLabel = weekLabel,
        // #205: show the playlist's creation date ("Jun 15, 2026") rather than a
        // relative "This Week"/"Last Week" label (matches desktop). The shared
        // repo formats it; fall back to the relative label if it's blank.
        dateLabel = this.dateLabel.ifBlank { weekLabel },
        // Description is already HTML-stripped by the shared repo.
        summary = description,
        kind = kind,
    )

    /**
     * Search MusicBrainz for artists + releases, returning flat
     * Swift-friendly DTOs (the upstream MB response types have nested
     * artist-credit / release-group structures that bridge awkwardly).
     * No auth needed. Powers the iOS Search screen (phase 5.1).
     */
    suspend fun search(query: String, limit: Int): IosSearchResults {
        if (query.isBlank()) return IosSearchResults(emptyList(), emptyList())
        val artists = try {
            musicBrainzClient.searchArtists(query, limit).artists.map {
                IosSearchArtist(
                    id = it.id,
                    name = it.name,
                    disambiguation = it.disambiguation,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        val releases = try {
            musicBrainzClient.searchReleases(query, limit).releases.map {
                IosSearchRelease(
                    id = it.id,
                    title = it.title,
                    artist = it.artistName,
                    year = it.year?.toString(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        return IosSearchResults(artists = artists, releases = releases)
    }

    // ── Full search (#233, Android parity) ───────────────────────────────────
    // Android's SearchViewModel surfaces: local DB tracks ("Library"), remote
    // artists / tracks / albums (MetadataService cascade), and search history.
    // TrackSearchResult / AlbumSearchResult are already Swift-consumed elsewhere
    // (DetailScreens), so they're returned directly; ArtistInfo is flattened.

    val searchHistoryDao: SearchHistoryDao by lazy { SearchHistoryDao(database) }

    /** Local DB tracks matching title/artist (the "Library" section). */
    suspend fun searchLocalTracks(query: String): List<Track> =
        if (query.isBlank()) emptyList() else try { trackDao.searchOnce(query) } catch (e: Exception) { emptyList() }

    suspend fun searchTracksRemote(query: String, limit: Int = 20): List<TrackSearchResult> =
        if (query.isBlank()) emptyList() else try { metadataService.searchTracks(query, limit) } catch (e: Exception) { emptyList() }

    suspend fun searchAlbumsRemote(query: String, limit: Int = 10): List<AlbumSearchResult> =
        if (query.isBlank()) emptyList() else try { metadataService.searchAlbums(query, limit) } catch (e: Exception) { emptyList() }

    suspend fun searchArtistsRemote(query: String, limit: Int = 10): List<IosArtistResult> =
        if (query.isBlank()) emptyList() else try {
            metadataService.searchArtists(query, limit).map { IosArtistResult(it.name, it.imageUrl, it.mbid) }
        } catch (e: Exception) { emptyList() }

    // Search history (shared SearchHistoryDao; mirrors Android's save-on-select +
    // dedup-by-query + trim-to-limit).
    fun watchSearchHistory(onEach: (List<SearchHistory>) -> Unit): Cancellable =
        FlowWatcher(appScope).watch(searchHistoryDao.getRecent()) { onEach((it as? List<SearchHistory>) ?: emptyList()) }

    suspend fun saveSearchHistory(
        query: String,
        resultType: String,
        resultName: String,
        resultArtist: String?,
        artworkUrl: String?,
    ) {
        val q = query.trim()
        if (q.isBlank()) return
        searchHistoryDao.deleteByQuery(q)
        searchHistoryDao.insert(
            SearchHistory(
                query = q,
                resultType = resultType,
                resultName = resultName,
                resultArtist = resultArtist,
                artworkUrl = artworkUrl,
                timestamp = com.parachord.shared.platform.currentTimeMillis(),
            ),
        )
        searchHistoryDao.trimToLimit()
    }

    suspend fun deleteSearchHistory(id: Long) { searchHistoryDao.deleteById(id) }
    suspend fun clearSearchHistory() { searchHistoryDao.clearAll() }
}

/** Flat Swift-friendly search result bundle. */
data class IosSearchResults(
    val artists: List<IosSearchArtist>,
    val releases: List<IosSearchRelease>,
)

data class IosSearchArtist(
    val id: String,
    val name: String,
    val disambiguation: String?,
)

data class IosSearchRelease(
    val id: String,
    val title: String,
    val artist: String,
    val year: String?,
)

/** Flat artist result for the #233 search screen (MetadataService cascade →
 *  name + image + MBID; the nested ArtistInfo bio/tags/similar aren't needed). */
data class IosArtistResult(
    val name: String,
    val imageUrl: String?,
    val mbid: String?,
)

/**
 * Flat Swift-friendly playlist track. Metadata-only (no resolver /
 * streaming IDs) — these come from ListenBrainz playlists and need the
 * resolver pipeline before they're playable.
 */
data class IosPlaylistTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArt: String?,
    val durationMs: Long?,
)

/**
 * Flat Swift-friendly weekly-playlist entry, tagged by kind.
 *
 * NOTE: the blurb field is `summary`, NOT `description` — a Kotlin
 * property named `description` collides with ObjC's `NSObject.description`
 * on the Swift side, so `entry.description` would return the data-class
 * toString instead of the field value. Avoid `description` on any
 * Swift-facing Kotlin model.
 */
data class IosWeeklyEntry(
    val id: String,
    val title: String,
    val weekLabel: String,
    /** Playlist creation date, formatted "Jun 15, 2026" (#205). */
    val dateLabel: String,
    val summary: String,
    val kind: String,   // "Weekly Jams" | "Weekly Exploration"
)

/**
 * Bridges a Kotlin [Flow] to a Swift callback. Swift can't `collect` a
 * suspend Flow directly, so this launches the collection on a coroutine
 * scope and forwards each emission to `onEach`. Returns a [Cancellable]
 * the Swift side stores and calls in `onDisappear` / deinit to stop the
 * collection.
 *
 * The flow's element type erases to `Any?` across the ObjC bridge
 * (generics aren't preserved), so the Swift `onEach` closure receives
 * `Any?` and casts to the concrete type. Flow is covariant, so any
 * `Flow<T>` is assignable to the `Flow<Any?>` parameter.
 */
class FlowWatcher(private val scope: CoroutineScope) {

    fun watch(flow: Flow<Any?>, onEach: (Any?) -> Unit): Cancellable {
        val job = scope.launch {
            flow.collect { value -> onEach(value) }
        }
        return Cancellable { job.cancel() }
    }
}

/** Opaque cancellation handle for a [FlowWatcher.watch] subscription. */
class Cancellable(private val onCancel: () -> Unit) {
    fun cancel() = onCancel()
}
