package com.parachord.shared.ios

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.createHttpClient
import com.parachord.shared.config.AppConfig
import com.parachord.shared.plugin.IosJsRuntime
import com.parachord.shared.plugin.PluginFileAccess
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverScoring
import com.parachord.shared.repository.WeeklyPlaylistEntry
import com.parachord.shared.repository.WeeklyPlaylistsRepository
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.IosSecureTokenStore
import com.parachord.shared.store.KvStoreFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
    }

    /** App-wide coroutine scope for fire-and-forget settings writes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val settingsStore: SettingsStore by lazy {
        SettingsStore(
            secureStore = IosSecureTokenStore(),
            kv = KvStoreFactory.create(),
        )
    }

    val appConfig: AppConfig by lazy {
        // No-auth defaults for now. API keys (Last.fm, Ticketmaster,
        // etc.) and the Spotify client ID get populated from a config
        // mechanism (Info.plist / a generated config) when those
        // services come online. The User-Agent is the one field that
        // matters today: MusicBrainz 403s the default Ktor UA.
        AppConfig(
            userAgent = "Parachord/0.1 (iOS; https://parachord.com)",
            isDebug = true,
        )
    }

    /**
     * Production shared Ktor `HttpClient` — the same one
     * `SharedModule` builds, with the User-Agent injection and shared
     * plugins. Auth providers are no-op stubs for now (below); they
     * just return null for every realm, which is correct for the
     * unauthenticated endpoints the first network screens use
     * (MusicBrainz search, the RSS/critics feeds, etc.). Real token
     * lookup wires in when OAuth lands.
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
            NoAuthTokenProvider,
            NoOpTokenRefresher,
            lbTokenProvider = { settingsStore.getListenBrainzToken() },
        )
    }

    val musicBrainzClient: MusicBrainzClient by lazy { MusicBrainzClient(httpClient) }

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
 * Stub [AuthTokenProvider] for the pre-OAuth iOS shell — every realm
 * is unauthenticated. Returns null so authenticated requests simply
 * fail with 401 (none are made yet); the unauthenticated endpoints
 * the first screens hit don't consult it.
 */
private object NoAuthTokenProvider : AuthTokenProvider {
    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
    override suspend fun invalidate(realm: AuthRealm) {}
}

/** Stub [OAuthTokenRefresher] — no refresh path until OAuth wires in. */
private object NoOpTokenRefresher : OAuthTokenRefresher {
    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
}

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
