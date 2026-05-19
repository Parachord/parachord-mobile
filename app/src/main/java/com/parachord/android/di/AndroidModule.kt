package com.parachord.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.parachord.android.ai.AiChatService
import com.parachord.android.ai.AiRecommendationService
import com.parachord.android.ai.ChatCardEnricher
import com.parachord.android.ai.ChatContextProvider
import com.parachord.android.ai.providers.ChatGptProvider
import com.parachord.android.ai.providers.ClaudeProvider
import com.parachord.android.ai.providers.GeminiProvider
import com.parachord.android.ai.tools.DjToolExecutor
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.auth.AndroidAuthTokenProvider
import com.parachord.android.data.auth.AndroidOAuthTokenRefresher
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.android.bridge.JsBridge
import com.parachord.shared.plugin.PluginFileAccess
// SpotifyApi (Retrofit) deleted in Phase 9E.1.8 — replaced by
// com.parachord.shared.api.SpotifyClient bound in sharedModule
import com.parachord.android.data.db.dao.*
import com.parachord.shared.db.DriverFactory
import com.parachord.android.data.metadata.DiscogsProvider
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.data.metadata.LastFmProvider
import com.parachord.android.data.metadata.MbidEnrichmentService
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.MusicBrainzProvider
import com.parachord.android.data.metadata.SpotifyProvider
import com.parachord.android.data.metadata.WikipediaProvider
import com.parachord.android.data.network.NetworkMonitor
import com.parachord.android.data.repository.*
import com.parachord.android.data.scanner.MediaScanner
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.deeplink.DeepLinkHandler
import com.parachord.android.deeplink.DeepLinkViewModel
import com.parachord.android.deeplink.ExternalLinkResolver
import com.parachord.android.playback.*
import com.parachord.android.playback.handlers.*
import com.parachord.android.playback.scrobbler.*
import com.parachord.android.playlist.HostedPlaylistPoller
import com.parachord.android.playlist.HostedPlaylistScheduler
import com.parachord.android.playlist.PlaylistImportManager
import com.parachord.android.plugin.PluginManager
import com.parachord.android.plugin.PluginSyncService
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.sync.AppleMusicSyncProvider
import com.parachord.android.sync.SpotifySyncProvider
import com.parachord.android.sync.SyncEngine
import com.parachord.android.sync.SyncScheduler
import com.parachord.android.ui.MainViewModel
import com.parachord.android.ui.screens.album.AlbumViewModel
import com.parachord.android.ui.screens.artist.ArtistViewModel
import com.parachord.android.ui.screens.chat.ChatViewModel
import com.parachord.android.ui.screens.discover.*
import com.parachord.android.ui.screens.friends.FriendDetailViewModel
import com.parachord.android.ui.screens.friends.FriendsViewModel
import com.parachord.android.ui.screens.history.HistoryViewModel
import com.parachord.android.ui.screens.home.HomeViewModel
import com.parachord.android.ui.screens.library.LibraryViewModel
import com.parachord.android.ui.screens.nowplaying.NowPlayingViewModel
import com.parachord.android.ui.screens.playlists.*
import com.parachord.android.ui.screens.search.SearchViewModel
import com.parachord.android.ui.screens.settings.SettingsViewModel
import com.parachord.android.ui.screens.sync.SyncViewModel
import com.parachord.android.widget.MiniPlayerWidgetUpdater
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * iTunes Search API enrichment for album artwork. Used by the shared
 * [com.parachord.shared.metadata.MetadataService] when an album's
 * `artworkUrl` points at Cover Art Archive (which 404s frequently).
 *
 * Looks up the album by `"<artist> <title>"` against the iTunes Search
 * `/search?media=music&entity=album` endpoint and substitutes the 600x600
 * artwork URL on a successful match. Failures fall through to the
 * original album record — never propagates exceptions back to the
 * caller.
 *
 * Lives in `AndroidModule.kt` next to its only dependency
 * (`AppleMusicClient`) so the shared metadata module stays free of
 * iTunes-specific logic. The previous Android `MetadataService`
 * wrapper class hosted this function as a private method; eliminating
 * the wrapper relocated the helper here.
 */
private suspend fun enrichAlbumArtworkViaItunes(
    appleMusicClient: com.parachord.shared.api.AppleMusicClient,
    artistName: String,
    albums: List<com.parachord.shared.metadata.AlbumSearchResult>,
): List<com.parachord.shared.metadata.AlbumSearchResult> = coroutineScope {
    albums.map { album ->
        async {
            val url = album.artworkUrl
            if (url != null && url.contains("coverartarchive.org")) {
                try {
                    val term = "$artistName ${album.title}"
                    val response = appleMusicClient.search(
                        term = term,
                        entity = "album",
                        limit = 3,
                    )
                    val match = response.results.firstOrNull { item ->
                        item.collectionName != null &&
                            item.artistName?.lowercase()
                                ?.contains(artistName.lowercase()) == true
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

/**
 * Android Koin module — provides all Android-specific dependencies.
 * Replaces the 4 Hilt modules: ApiModule, AppModule, DatabaseModule, ScrobblerModule.
 */
val androidModule = module {

    // ── Core Infrastructure ──────────────────────────────────────────

    single {
        com.parachord.shared.config.AppConfig(
            achordionBearerToken = com.parachord.android.BuildConfig.ACHORDION_BEARER_TOKEN,
            lastFmApiKey = com.parachord.android.BuildConfig.LASTFM_API_KEY,
            lastFmSharedSecret = com.parachord.android.BuildConfig.LASTFM_SHARED_SECRET,
            spotifyClientId = com.parachord.android.BuildConfig.SPOTIFY_CLIENT_ID,
            soundCloudClientId = com.parachord.android.BuildConfig.SOUNDCLOUD_CLIENT_ID,
            soundCloudClientSecret = com.parachord.android.BuildConfig.SOUNDCLOUD_CLIENT_SECRET,
            appleMusicDeveloperToken = com.parachord.android.BuildConfig.APPLE_MUSIC_DEVELOPER_TOKEN,
            ticketmasterApiKey = com.parachord.android.BuildConfig.TICKETMASTER_API_KEY,
            seatGeekClientId = com.parachord.android.BuildConfig.SEATGEEK_CLIENT_ID,
            userAgent = "Parachord/${com.parachord.android.BuildConfig.VERSION_NAME} (Android; https://parachord.app)",
            isDebug = com.parachord.android.BuildConfig.DEBUG,
        )
    }

    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }

    single {
        OkHttpClient.Builder()
            // Identify the app on every outbound request. MusicBrainz
            // specifically rate-limits / 403s requests with the default
            // okhttp/x.y.z User-Agent — without this, Fresh Drops and
            // Critical Darlings artwork fetches both fail. Good practice
            // for every other API too.
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Parachord/${com.parachord.android.BuildConfig.VERSION_NAME} (Android; https://parachord.app)",
                    )
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    single<DataStore<Preferences>> { androidContext().dataStore }

    single { DriverFactory(androidContext()).createDriver() }
    single {
        val driver = get<app.cash.sqldelight.db.SqlDriver>()
        // Belt-and-suspenders: existing installs upgraded from the original
        // Room v12 schema won't pick up new tables through SQLDelight's
        // version machinery. Every .sq in this project uses CREATE TABLE IF
        // NOT EXISTS and is safe to re-run; we explicitly run the ones added
        // after the initial port here so the app never starts with a missing
        // table.
        driver.execute(
            null,
            """CREATE TABLE IF NOT EXISTS sync_playlist_link (
                localPlaylistId TEXT NOT NULL,
                providerId      TEXT NOT NULL,
                externalId      TEXT NOT NULL,
                syncedAt        INTEGER NOT NULL,
                PRIMARY KEY (localPlaylistId, providerId)
            )""".trimIndent(),
            0,
        )
        // Multi-provider sync: separate "syncedFrom" source-of-record table.
        // One row per local playlist (at most one pull source), distinct from
        // sync_playlist_link's (localPlaylistId, providerId) many-push-targets.
        driver.execute(
            null,
            """CREATE TABLE IF NOT EXISTS sync_playlist_source (
                localPlaylistId TEXT NOT NULL PRIMARY KEY,
                providerId      TEXT NOT NULL,
                externalId      TEXT NOT NULL,
                snapshotId      TEXT,
                ownerId         TEXT,
                syncedAt        INTEGER NOT NULL
            )""".trimIndent(),
            0,
        )
        // Hosted XSPF polling columns. SQLite has no ADD COLUMN IF NOT EXISTS,
        // so a second run throws "duplicate column name" — harmless, swallow it.
        for (col in listOf("sourceUrl", "sourceContentHash")) {
            try {
                driver.execute(null, "ALTER TABLE playlists ADD COLUMN $col TEXT", 0)
            } catch (_: Exception) {
                // Column already present (idempotent on repeat launches).
            }
        }
        // Multi-provider sync: snapshotId lets us detect remote-side changes
        // per-link. Same idempotent ALTER pattern as above.
        try {
            driver.execute(null, "ALTER TABLE sync_playlist_link ADD COLUMN snapshotId TEXT", 0)
        } catch (_: Exception) {
            // Column already present (idempotent on repeat launches).
        }
        try {
            driver.execute(null, "ALTER TABLE sync_playlist_link ADD COLUMN pendingAction TEXT", 0)
        } catch (_: Exception) {
            // Column already present (idempotent on repeat launches).
        }
        try {
            driver.execute(null, "ALTER TABLE playlists ADD COLUMN localOnly INTEGER NOT NULL DEFAULT 0", 0)
        } catch (_: Exception) {
            // Column already present (idempotent on repeat launches).
        }
        // Slow-trickle cross-resolver enrichment (#150): tracks the last time
        // we tried to backfill streaming-service IDs for a localfiles-only
        // track. Same idempotent ALTER pattern as above.
        try {
            driver.execute(null, "ALTER TABLE tracks ADD COLUMN crossResolverEnrichedAt INTEGER", 0)
        } catch (_: Exception) {
            // Column already present (idempotent on repeat launches).
        }
        com.parachord.shared.db.ParachordDb(driver)
    }

    // ShareManager binds the shared `SmartLinksClient` (Ktor) — the Retrofit
    // `SmartLinkApi` was the last Retrofit footprint and went away in the
    // Smart Links cutover. `SmartLinksClient` itself is registered in
    // `sharedModule`; per-platform `OkHttp`/`Darwin` Ktor engines + global
    // User-Agent + sanitized Authorization-stripping logging come for free
    // through the shared `HttpClientFactory`.
    single { com.parachord.android.share.ShareManager(get(), get(), get(), get()) }

    // Spotify Web API — migrated to shared Ktor client (SpotifyClient) in
    // Phase 9E.1.8. Binding lives in sharedModule; per-request auth via
    // AuthTokenProvider for AuthRealm.Spotify. Spotify is the OAuth refresh
    // canary — 401s on api.spotify.com flow through OAuthRefreshPlugin
    // (single-flight refresh, two-strikes → ReauthRequiredException).

    // Apple Music Library + Storefront API — migrated to shared Ktor
    // client (AppleMusicLibraryClient) in Phase 9E.1.7. Binding lives in
    // sharedModule; auth headers (dev-token + MUT) are applied per-request
    // via AuthTokenProvider for AuthRealm.AppleMusicLibrary.

    // ── DAOs ─────────────────────────────────────────────────────────

    single { TrackDao(get(), get()) }
    single { AlbumDao(get()) }
    single { ArtistDao(get()) }
    single { PlaylistDao(get()) }
    single { PlaylistTrackDao(get()) }
    single { FriendDao(get()) }
    single { ChatMessageDao(get(), get()) }
    single { SearchHistoryDao(get()) }
    single { SyncSourceDao(get()) }
    single { SyncPlaylistLinkDao(get()) }
    single { SyncPlaylistSourceDao(get()) }

    // ── Settings & Auth ──────────────────────────────────────────────

    // SecureTokenStore — Android impl is EncryptedSharedPreferences over Android
    // Keystore (AES-256-GCM). The interface lives in shared/commonMain so the
    // shared SettingsStore binds to it without an Android dep. security: C4.
    single<com.parachord.shared.store.SecureTokenStore> {
        com.parachord.shared.store.AndroidSecureTokenStore(androidContext())
    }
    // KvStore — KMP-friendly key-value store backed by SharedPreferences
    // (Phase 9B Stage 1). Stage 3 fully consolidates SettingsStore onto KvStore.
    single { com.parachord.shared.store.KvStoreFactory.create(androidContext()) }
    // One-shot DataStore→KvStore migration (Phase 9B Stage 3). Runs the first
    // time SettingsStore accesses KvStore on an upgraded install; subsequent
    // launches see the `_migration_v1` marker and skip the copy.
    single<com.parachord.shared.store.SettingsMigration> {
        com.parachord.android.data.store.AndroidDataStoreMigration(get())
    }
    single {
        com.parachord.shared.settings.SettingsStore(
            secureStore = get(),
            kv = get(),
            migration = get(),
            appleMusicDeveloperTokenFallback = com.parachord.android.BuildConfig.APPLE_MUSIC_DEVELOPER_TOKEN,
        )
    } bind com.parachord.shared.sync.SyncSettingsProvider::class
    singleOf(::OAuthManager)
    singleOf(::NetworkMonitor)

    // Auth providers for the shared Ktor HTTP client (OAuthRefreshPlugin, etc.)
    single<AuthTokenProvider> { AndroidAuthTokenProvider(get(), get()) }
    single<OAuthTokenRefresher> { AndroidOAuthTokenRefresher(get(), get()) }

    // ── Playback ─────────────────────────────────────────────────────

    singleOf(::PlaybackStateHolder)
    singleOf(::QueueManager)
    singleOf(::QueuePersistence)
    singleOf(::PlaybackRouter)
    singleOf(::PlaybackController)
    singleOf(::ScrobbleManager)
    singleOf(::SpotifyPlaybackHandler)
    singleOf(::AppleMusicPlaybackHandler)
    singleOf(::SoundCloudPlaybackHandler)
    singleOf(::MusicKitWebBridge)

    // ── Scrobblers (as set) ──────────────────────────────────────────

    singleOf(::LastFmScrobbler)
    singleOf(::ListenBrainzScrobbler)
    singleOf(::LibreFmScrobbler)
    single<Set<Scrobbler>> {
        setOf(get<LastFmScrobbler>(), get<ListenBrainzScrobbler>(), get<LibreFmScrobbler>())
    }

    // Loves push (issue #125) — orchestrates per-service loveTrack
    // dispatch + idempotency cache for both single-track (live toggle)
    // and bulk backfill paths.
    singleOf(::LovesPushService)

    // ── Metadata ─────────────────────────────────────────────────────

    // MetadataService is constructed directly from the shared cascading
    // orchestrator. The Android wrapper class was eliminated in favor of
    // building the shared service inline here — the only meaningful
    // contribution of the old wrapper was the iTunes-search artwork
    // enrichment lambda that fixes Cover Art Archive 404s, which now
    // lives next to its only dependency (AppleMusicClient).
    single {
        val musicBrainz: MusicBrainzProvider = get()
        val wikipedia: WikipediaProvider = get()
        val lastFm: LastFmProvider = get()
        val discogs: DiscogsProvider = get()
        val spotify: SpotifyProvider = get()
        val settingsStore: com.parachord.shared.settings.SettingsStore = get()
        val appleMusicClient: com.parachord.shared.api.AppleMusicClient = get()

        val providers = listOf(musicBrainz, wikipedia, lastFm, discogs, spotify)
            .sortedBy { it.priority }

        com.parachord.shared.metadata.MetadataService(
            providers = providers,
            getDisabledProviders = { settingsStore.getDisabledMetaProviders() },
            enrichAlbumArtwork = { artistName, albums ->
                enrichAlbumArtworkViaItunes(appleMusicClient, artistName, albums)
            },
        )
    }
    singleOf(::MusicBrainzProvider)
    // LastFmProvider takes the api key as a constructor parameter so the
    // shared class doesn't depend on Android BuildConfig. Sourced here from
    // the same BuildConfig field that previously lived inside the provider.
    single {
        com.parachord.shared.metadata.LastFmProvider(
            api = get(),
            apiKey = com.parachord.android.BuildConfig.LASTFM_API_KEY,
        )
    }
    singleOf(::SpotifyProvider)
    singleOf(::WikipediaProvider)
    singleOf(::DiscogsProvider)
    // ImageEnrichmentService — shared. The 2x2 mosaic composite step is
    // platform-specific (Coil + Bitmap), forwarded via a suspend lambda.
    // The Ktor HttpClient is also injected so the service can HEAD-check
    // candidate art URLs (used by Critical Darlings + Fresh Drops to
    // verify CAA URLs before showing them to the user).
    single {
        val context = androidContext()
        com.parachord.shared.metadata.ImageEnrichmentService(
            metadataService = get(),
            artistDao = get(),
            albumDao = get(),
            trackDao = get(),
            playlistDao = get(),
            playlistTrackDao = get(),
            httpClient = get(),
            composeMosaic = { playlistId, urls ->
                com.parachord.android.data.metadata.composeMosaicAndroid(context, playlistId, urls)
            },
        )
    }
    // MbidEnrichmentService — shared. Disk cache lives at
    // `<filesDir>/mbid_mapper_cache.json` and is wired in via suspend lambdas.
    single {
        val context = androidContext()
        val cacheFile = java.io.File(context.filesDir, "mbid_mapper_cache.json")
        com.parachord.shared.metadata.MbidEnrichmentService(
            listenBrainzClient = get(),
            trackDao = get(),
            cacheRead = {
                try {
                    if (cacheFile.exists()) cacheFile.readText() else null
                } catch (_: Exception) { null }
            },
            cacheWrite = { json ->
                try { cacheFile.writeText(json) } catch (_: Exception) { /* swallow */ }
            },
        )
    }

    // ── Resolvers ────────────────────────────────────────────────────

    singleOf(::ResolverManager)
    singleOf(::ResolverScoring)
    singleOf(::TrackResolverCache)

    // ── Slow-trickle cross-resolver enrichment (#150) ────────────────
    // The service itself lives in shared/commonMain; the Android Koin
    // binding closes the resolver lambda over ResolverManager.resolveWithHints.
    // Per-source confidence gate at 0.95 — well above the 0.60 floor in
    // ResolverScoring. We don't want to pollute Achordion's match cache
    // with wrong-song matches that scored just above the playback floor.
    single {
        val resolverManager: ResolverManager = get()
        com.parachord.shared.enrichment.CrossResolverEnrichmentService(
            trackDao = get(),
            mbidEnrichmentService = get(),
            achordionClient = get(),
            resolveByTitleArtist = { title, artist ->
                val sources = resolverManager.resolveWithHints(
                    query = "$artist - $title",
                    targetTitle = title,
                    targetArtist = artist,
                )
                fun pick(name: String) = sources
                    .filter { it.resolver == name && (it.confidence ?: 0.0) >= 0.95 }
                    .maxByOrNull { it.confidence ?: 0.0 }
                com.parachord.shared.enrichment.CrossResolverEnrichmentService.ResolvedSources(
                    spotifyId = pick("spotify")?.spotifyId,
                    appleMusicId = pick("applemusic")?.appleMusicId,
                    soundcloudId = pick("soundcloud")?.soundcloudId,
                )
            },
        )
    }
    single { com.parachord.android.enrichment.CrossResolverEnrichmentScheduler(androidContext()) }

    // ── Repositories ─────────────────────────────────────────────────

    // LibraryRepository — shared. MbidEnrichmentService is also shared,
    // but LibraryRepository keeps the lambda forwards for symmetry / so
    // the shared-side signature isn't tightly coupled to MbidEnrichmentService.
    single {
        val mbidEnrichment: com.parachord.android.data.metadata.MbidEnrichmentService = get()
        val lovesPush: com.parachord.android.playback.LovesPushService = get()
        com.parachord.shared.repository.LibraryRepository(
            trackDao = get(),
            albumDao = get(),
            artistDao = get(),
            playlistDao = get(),
            playlistTrackDao = get(),
            syncEngine = get(),
            syncPlaylistLinkDao = get(),
            syncPlaylistSourceDao = get(),
            mbidEnrichTrack = { trackId, artist, title ->
                mbidEnrichment.enrichInBackground(trackId, artist, title)
            },
            mbidEnrichBatch = { tracks ->
                mbidEnrichment.enrichBatchInBackground(
                    tracks.map {
                        com.parachord.android.data.metadata.TrackEnrichmentRequest(
                            it.id, it.artist, it.title,
                        )
                    }
                )
            },
            // Issue #125 — fire love push when the user adds a track to
            // their collection AND has the per-service toggle enabled.
            // LovesPushService is fire-and-forget internally; this lambda
            // returns immediately.
            pushLovedTrack = { track -> lovesPush.pushLoved(track) },
        )
    }
    // ChartsRepository takes `lastFmApiKey` as a constructor parameter
    // (sourced here from BuildConfig). Apple Music marketing-tools RSS
    // feeds + Last.fm chart endpoints all flow through shared Ktor clients
    // — no more raw OkHttp/JSONObject in the chart pipeline.
    single {
        com.parachord.shared.repository.ChartsRepository(
            appleMusicClient = get(),
            lastFmClient = get(),
            lastFmApiKey = com.parachord.android.BuildConfig.LASTFM_API_KEY,
        )
    }
    // History + Friends repositories take `lastFmApiKey` as a constructor
    // parameter so the shared classes don't depend on Android BuildConfig.
    // Sourced from the same field that previously lived inline in the repo bodies.
    single {
        com.parachord.shared.repository.HistoryRepository(
            lastFmClient = get(),
            listenBrainzClient = get(),
            settingsStore = get(),
            metadataService = get(),
            lastFmApiKey = com.parachord.android.BuildConfig.LASTFM_API_KEY,
        )
    }
    single {
        com.parachord.shared.repository.FriendsRepository(
            friendDao = get(),
            lastFmClient = get(),
            listenBrainzClient = get(),
            metadataService = get(),
            settingsStore = get(),
            lastFmApiKey = com.parachord.android.BuildConfig.LASTFM_API_KEY,
        )
    }
    // RecommendationsRepository — shared (Ktor for Last.fm web endpoint, file I/O via lambdas).
    single {
        val context = androidContext()
        val cacheFile = java.io.File(context.filesDir, "recommendations_cache.json")
        com.parachord.shared.repository.RecommendationsRepository(
            httpClient = get(),
            listenBrainzClient = get(),
            settingsStore = get(),
            metadataService = get(),
            cacheRead = {
                try {
                    if (cacheFile.exists()) cacheFile.readText() else null
                } catch (_: Exception) { null }
            },
            cacheWrite = { json ->
                try { cacheFile.writeText(json) } catch (_: Exception) { /* swallow */ }
            },
        )
    }
    // FreshDropsRepository takes file I/O + MBID lookups as suspend lambdas
    // so the shared class doesn't depend on `Context` or the (Android-only)
    // `MbidEnrichmentService`. Two cache files + two enrichment-service forwards.
    single {
        val context = androidContext()
        val cacheFile = java.io.File(context.filesDir, "fresh_drops_cache.json")
        val rotationFile = java.io.File(context.filesDir, "fresh_drops_rotation.json")
        val mbidEnrichment: com.parachord.android.data.metadata.MbidEnrichmentService = get()
        com.parachord.shared.repository.FreshDropsRepository(
            musicBrainzClient = get(),
            lastFmClient = get(),
            listenBrainzClient = get(),
            settingsStore = get(),
            trackDao = get(),
            cacheRead = {
                try {
                    if (cacheFile.exists()) cacheFile.readText() else null
                } catch (_: Exception) { null }
            },
            cacheWrite = { json ->
                try { cacheFile.writeText(json) } catch (_: Exception) { /* swallow */ }
            },
            rotationRead = {
                try {
                    if (rotationFile.exists()) rotationFile.readText() else null
                } catch (_: Exception) { null }
            },
            rotationWrite = { json ->
                try { rotationFile.writeText(json) } catch (_: Exception) { /* swallow */ }
            },
            mbidLookupCached = { artistName -> mbidEnrichment.getCachedArtistMbid(artistName) },
            mbidLookupViaMapper = { artist, title -> mbidEnrichment.getArtistMbid(artist, title) },
            imageEnrichmentService = get(),
            lastFmApiKey = com.parachord.android.BuildConfig.LASTFM_API_KEY,
        )
    }
    // CriticalDarlingsRepository takes file I/O as suspend lambdas + a Ktor
    // HttpClient (replacing the prior OkHttp `Request.Builder` for the RSS
    // fetch). Cache lives at `<filesDir>/critical_darlings_cache.json`.
    single {
        val context = androidContext()
        val cacheFile = java.io.File(context.filesDir, "critical_darlings_cache.json")
        com.parachord.shared.repository.CriticalDarlingsRepository(
            httpClient = get(),
            musicBrainzClient = get(),
            imageEnrichmentService = get(),
            cacheRead = {
                try {
                    if (cacheFile.exists()) cacheFile.readText() else null
                } catch (_: Exception) { null }
            },
            cacheWrite = { json ->
                try { cacheFile.writeText(json) } catch (_: Exception) { /* swallow */ }
            },
        )
    }
    singleOf(::WeeklyPlaylistsRepository)
    // ConcertsRepository takes file I/O as suspend lambdas + per-API
    // BuildConfig key fallbacks (the shared class is platform-agnostic).
    // Cache lives at `<filesDir>/concerts_cache.json`; failures are swallowed.
    single {
        val context = androidContext()
        val cacheFile = java.io.File(context.filesDir, "concerts_cache.json")
        com.parachord.shared.repository.ConcertsRepository(
            ticketmasterClient = get(),
            seatGeekClient = get(),
            settingsStore = get(),
            cacheRead = {
                try {
                    if (cacheFile.exists()) cacheFile.readText() else null
                } catch (_: Exception) { null }
            },
            cacheWrite = { json ->
                try { cacheFile.writeText(json) } catch (_: Exception) { /* swallow */ }
            },
            ticketmasterApiKeyFallback = com.parachord.android.BuildConfig.TICKETMASTER_API_KEY,
            seatGeekClientIdFallback = com.parachord.android.BuildConfig.SEATGEEK_CLIENT_ID,
        )
    }

    // ── AI ────────────────────────────────────────────────────────────

    // AiChatService — shared. DjToolExecutor is now a shared interface;
    // the Android `DjToolExecutor` class implements it (its concrete
    // dispatch reaches into PlaybackController / MCP / Parachord
    // controls — those stay Android-only). Koin auto-resolves through
    // the interface since the concrete class is bound below.
    single {
        com.parachord.shared.ai.AiChatService(
            toolExecutor = get<DjToolExecutor>(),
            contextProvider = get(),
            chatMessageDao = get(),
            json = get(),
        )
    }
    // AiRecommendationService — shared. Concrete provider classes are
    // assembled into a Map<String, AiProviderEntry> so the shared service
    // doesn't depend on Android-only provider implementations directly.
    // Disk cache wired in via suspend lambdas pointing at filesDir.
    single {
        val context = androidContext()
        val cacheFile = java.io.File(context.filesDir, "ai_suggestions_cache.json")
        com.parachord.shared.ai.AiRecommendationService(
            settingsStore = get(),
            historyRepository = get(),
            libraryRepository = get(),
            providers = mapOf(
                "chatgpt" to com.parachord.shared.ai.AiProviderEntry(
                    provider = get<ChatGptProvider>(),
                    defaultModel = "gpt-4o-mini",
                ),
                "claude" to com.parachord.shared.ai.AiProviderEntry(
                    provider = get<ClaudeProvider>(),
                    defaultModel = "claude-sonnet-4-6-20250320",
                ),
                "gemini" to com.parachord.shared.ai.AiProviderEntry(
                    provider = get<GeminiProvider>(),
                    defaultModel = "gemini-2.0-flash",
                ),
            ),
            metadataService = get(),
            cacheRead = {
                try {
                    if (cacheFile.exists()) cacheFile.readText() else null
                } catch (_: Exception) { null }
            },
            cacheWrite = { json ->
                try { cacheFile.writeText(json) } catch (_: Exception) { /* swallow */ }
            },
        )
    }
    // ChatContextProvider — shared. PlaybackStateHolder stays Android-only
    // (PlaybackState carries Android-specific types); the chat context
    // receives only a small snapshot via a suspend lambda.
    single {
        val playbackStateHolder: com.parachord.android.playback.PlaybackStateHolder = get()
        com.parachord.shared.ai.ChatContextProvider(
            getPlaybackSnapshot = {
                val s = playbackStateHolder.state.value
                com.parachord.shared.ai.ChatPlaybackSnapshot(
                    currentTrack = s.currentTrack,
                    isPlaying = s.isPlaying,
                    upNext = s.upNext,
                    shuffleEnabled = s.shuffleEnabled,
                )
            },
            settingsStore = get(),
            historyRepository = get(),
            libraryRepository = get(),
        )
    }
    singleOf(::ChatCardEnricher)
    singleOf(::ChatGptProvider)
    singleOf(::ClaudeProvider)
    singleOf(::GeminiProvider)
    singleOf(::DjToolExecutor)

    // ── Plugins ──────────────────────────────────────────────────────

    singleOf(::JsBridge)
    single { PluginFileAccess(androidContext()) }
    single { com.parachord.shared.plugin.PluginManager(get<PluginFileAccess>(), get<JsBridge>()) }
    single {
        val settingsStore: com.parachord.android.data.store.SettingsStore = get()
        com.parachord.shared.plugin.PluginSyncService(
            httpClient = get(),
            pluginManager = get(),
            fileAccess = get(),
            getLastSyncTimestamp = { settingsStore.getLastPluginSyncTimestamp() },
            setLastSyncTimestamp = { settingsStore.setLastPluginSyncTimestamp(it) },
        )
    }

    // ── Deep Links ───────────────────────────────────────────────────

    singleOf(::DeepLinkHandler)
    singleOf(::ExternalLinkResolver)

    // Protocol play surface (#119–#121): concrete resolver/teardown impls
    // + handler orchestrator. The teardown's listen-along stopper is wired
    // separately at MainActivity startup via setListenAlongStopper().
    single<com.parachord.shared.deeplink.ProtocolInputResolver> {
        com.parachord.android.deeplink.AndroidProtocolInputResolver(
            musicBrainzClient = get(),
            spotifyClient = get(),
            appleMusicClient = get(),
            metadataService = get(),
            httpClient = get(),
        )
    }
    single { com.parachord.android.deeplink.AndroidProtocolPlayTeardown(playbackController = get()) }
    single<com.parachord.shared.deeplink.ProtocolPlayTeardown> { get<com.parachord.android.deeplink.AndroidProtocolPlayTeardown>() }
    single {
        com.parachord.android.deeplink.ProtocolPlayHandler(
            resolver = get(),
            teardown = get(),
            playbackController = get(),
            trackResolverCache = get(),
        )
    }
    single {
        com.parachord.android.deeplink.PlayRadioDispatcher(
            playbackController = get(),
            teardown = get(),
            protocolPlayHandler = get(),
        )
    }
    single {
        com.parachord.android.deeplink.ListenAlongDispatcher(
            friendsRepository = get(),
            teardown = get(),
        )
    }

    // ── Sync ─────────────────────────────────────────────────────────

    singleOf(::SyncEngine)
    // Bind as SyncProvider so getAll<SyncProvider>() picks it up. Future
    // providers (Apple Music, Tidal) register the same way; SyncEngine
    // accepts the resulting List<SyncProvider> with no per-provider Koin
    // changes required here.
    singleOf(::SpotifySyncProvider) bind com.parachord.shared.sync.SyncProvider::class
    singleOf(::AppleMusicSyncProvider) bind com.parachord.shared.sync.SyncProvider::class
    single<List<com.parachord.shared.sync.SyncProvider>> { getAll() }
    singleOf(::SyncScheduler)
    singleOf(::HostedPlaylistPoller)
    single { HostedPlaylistScheduler(androidContext(), get()) }
    singleOf(::MediaScanner)
    singleOf(::PlaylistImportManager)

    // ── Widget ───────────────────────────────────────────────────────

    single { MiniPlayerWidgetUpdater(get(), get(), lazy { get<PlaybackController>() }) }

    // ── ViewModels ───────────────────────────────────────────────────

    viewModelOf(::MainViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::LibraryViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::NowPlayingViewModel)
    viewModelOf(::ChatViewModel)
    viewModelOf(::HistoryViewModel)
    viewModelOf(::FriendsViewModel)
    viewModelOf(::FriendDetailViewModel)
    viewModelOf(::ArtistViewModel)
    viewModelOf(::AlbumViewModel)
    viewModelOf(::PlaylistsViewModel)
    viewModelOf(::PlaylistDetailViewModel)
    viewModelOf(::EditPlaylistViewModel)
    viewModelOf(::WeeklyPlaylistViewModel)
    viewModelOf(::RecommendationsViewModel)
    viewModelOf(::FreshDropsViewModel)
    viewModelOf(::CriticalDarlingsViewModel)
    viewModelOf(::PopOfTheTopsViewModel)
    viewModelOf(::ConcertsViewModel)
    viewModelOf(::SyncViewModel)
    viewModelOf(::DeepLinkViewModel)
}
