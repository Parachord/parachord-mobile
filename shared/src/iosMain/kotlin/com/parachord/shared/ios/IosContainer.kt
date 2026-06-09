package com.parachord.shared.ios

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.createHttpClient
import com.parachord.shared.config.AppConfig
import com.parachord.shared.plugin.IosJsRuntime
import com.parachord.shared.plugin.PluginFileAccess
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.metadata.AlbumDetail
import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.ArtistInfo
import com.parachord.shared.metadata.LastFmProvider
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.metadata.MusicBrainzProvider
import com.parachord.shared.metadata.SpotifyProvider
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.ChartSong
import com.parachord.shared.repository.ChartsRepository
import com.parachord.shared.resolver.ResolvedSource
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        // The Spotify client ID comes from Info.plist (SpotifyClientID).
        // Other API keys (Last.fm, Ticketmaster, etc.) get populated from
        // the same config mechanism when those services come online. The
        // User-Agent matters today regardless: MusicBrainz 403s the
        // default Ktor UA.
        AppConfig(
            userAgent = "Parachord/0.1 (iOS; https://parachord.com)",
            isDebug = true,
            spotifyClientId = NSBundle.mainBundle
                .objectForInfoDictionaryKey("SpotifyClientID") as? String ?: "",
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

    /** Spotify 401-refresh (plain client — no plugin recursion). */
    val spotifyTokenRefresher: SpotifyTokenRefresher by lazy {
        SpotifyTokenRefresher(settingsStore, authHttpClient) { appConfig.spotifyClientId }
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

    // ── Charts (Pop of the Tops) ───────────────────────────────────────
    val appleMusicClient: AppleMusicClient by lazy { AppleMusicClient(httpClient) }
    val lastFmClient: LastFmClient by lazy { LastFmClient(httpClient) }
    val chartsRepository: ChartsRepository by lazy {
        ChartsRepository(appleMusicClient, lastFmClient, lastFmApiKey = appConfig.lastFmApiKey)
    }

    /** Pop of the Tops — Apple Music (iTunes RSS) top songs. No auth/key. */
    suspend fun loadPopOfTheTops(countryCode: String): List<ChartSong> =
        chartsRepository.getAppleMusicSongs(countryCode)

    // ── Metadata (Album + Artist detail) ───────────────────────────────
    // Cascading providers per CLAUDE.md: MusicBrainz (discography/tracklists)
    // → Last.fm (images/bio/similar) → Spotify (art/IDs).
    val metadataService: MetadataService by lazy {
        MetadataService(
            providers = listOf(
                MusicBrainzProvider(musicBrainzClient),
                LastFmProvider(lastFmClient, appConfig.lastFmApiKey),
                SpotifyProvider(spotifyClient, settingsStore),
            ),
            getDisabledProviders = { emptySet() },
        )
    }

    suspend fun getAlbumDetail(albumTitle: String, artistName: String): AlbumDetail? =
        metadataService.getAlbumTracks(albumTitle, artistName)

    suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        metadataService.getArtistInfo(artistName)

    suspend fun getArtistTopTracks(artistName: String): List<TrackSearchResult> =
        metadataService.getArtistTopTracks(artistName, 10)

    suspend fun getArtistAlbums(artistName: String): List<AlbumSearchResult> =
        metadataService.getArtistAlbums(artistName, 50)

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
        IosSpotifyAuth(authHttpClient, settingsStore) { appConfig.spotifyClientId }
    }

    // ── Swift-callable Spotify auth surface ────────────────────────────

    /**
     * Complete the Spotify OAuth flow: exchange the authorization code (from
     * `IosOAuthManager.authorize`) for tokens and persist them. Throws on
     * non-2xx / missing client ID.
     */
    suspend fun connectSpotify(code: String, codeVerifier: String) {
        spotifyAuth.exchangeCode(code, codeVerifier, "parachord://auth/callback/spotify")
    }

    /** Clear stored Spotify tokens (disconnect). */
    suspend fun disconnectSpotify() {
        settingsStore.clearSpotifyTokens()
    }

    /** Emits true when a Spotify access token is present, false otherwise. */
    fun getSpotifyConnectedFlow(): Flow<Boolean> =
        settingsStore.getSpotifyAccessTokenFlow().map { it != null }

    val listenBrainzClient: ListenBrainzClient by lazy { ListenBrainzClient(httpClient) }

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

    val resolverCoordinator: IosResolverCoordinator by lazy {
        IosResolverCoordinator(
            jsRuntime = pluginJsRuntime,
            pluginManager = pluginManager,
            scoring = resolverScoring,
            settingsStore = settingsStore,
            spotifyClient = spotifyClient,
        )
    }

    /**
     * Resolve a track through the `.axe` pipeline and return the ranked,
     * floor-filtered sources (best first). The Swift `PlaybackRouter` walks
     * this list for the first engine-available source.
     */
    suspend fun resolveSources(artist: String, title: String, album: String?): List<ResolvedSource> =
        resolverCoordinator.resolveSources(artist, title, album)

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
        val result = weeklyPlaylistsRepository.loadWeeklyPlaylists(forceRefresh)
            ?: return emptyList()
        val jams = (result.jams ?: emptyList()).map { it.toIos(kind = "Weekly Jams") }
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

    private fun WeeklyPlaylistEntry.toIos(kind: String) = IosWeeklyEntry(
        id = id,
        title = title,
        weekLabel = weekLabel,
        // LB annotations carry HTML (<p>…</p>, <a>…</a>). Strip tags +
        // decode the few entities that show up so the row reads clean.
        summary = description.stripHtml(),
        kind = kind,
    )

    private fun String.stripHtml(): String =
        replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

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
