package com.parachord.shared.di

import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.AppleMusicLibraryClient
import com.parachord.shared.api.GeoLocationClient
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.SeatGeekClient
import com.parachord.shared.api.SmartLinksClient
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.TicketmasterClient
import com.parachord.shared.api.createHttpClient
import com.parachord.shared.config.AppConfig
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** KvStore key for persisting [SpotifyClient]'s rate-limit gate cooldown
 *  epoch-ms. */
private const val SPOTIFY_GATE_COOLDOWN_KEY = "spotify_rate_limit_cooldown_ms"

/**
 * Shared Koin module — provides cross-platform dependencies.
 * Used by both Android and iOS apps.
 */
val sharedModule = module {
    // JSON
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }

    // HTTP Client (platform engine via expect/actual)
    //
    // The lbTokenProvider closure captures SettingsStore so every request to
    // api.listenbrainz.org auto-attaches `Authorization: Token <token>`. Looked
    // up per-request — runtime token changes (Settings UI) take effect without
    // a client rebuild. SettingsStore is registered by the platform-side module
    // (AndroidModule / iOS module); Koin resolves it lazily on first HttpClient
    // injection so module load order doesn't matter.
    single {
        val settings: SettingsStore = get()
        createHttpClient(
            get(),
            get(),
            get(),
            get(),
            lbTokenProvider = { settings.getListenBrainzToken() },
        )
    }

    // API Clients (Ktor, cross-platform)
    // SpotifyClient: per-API auth via AuthTokenProvider for AuthRealm.Spotify.
    // Registered with OAuthRefreshPlugin in HttpClientFactory — 401s on
    // api.spotify.com get refreshed + retried automatically. Phase 9E.1.8
    // is the OAuth refresh canary cutover.
    //
    // Cooldown persistence: the rate-limit gate's cooldown is stored to
    // KvStore so a 3600s `Retry-After` from Spotify's abuse window survives
    // a process restart. See SpotifyClient KDoc for why this matters.
    single {
        val kv: KvStore = get()
        SpotifyClient(
            httpClient = get(),
            tokens = get(),
            loadCooldownEpochMs = { kv.getLong(SPOTIFY_GATE_COOLDOWN_KEY, 0L) },
            saveCooldownEpochMs = { value -> kv.setLong(SPOTIFY_GATE_COOLDOWN_KEY, value) },
        )
    }
    single { LastFmClient(get()) }
    single { MusicBrainzClient(get()) }
    single { TicketmasterClient(get()) }
    single { SeatGeekClient(get()) }
    single { AppleMusicClient(get()) }
    // AppleMusicLibraryClient: requires AuthTokenProvider (platform-supplied
    // by AndroidModule / iOS module). Per-request auth via applyAuth() —
    // not registered with OAuthRefreshPlugin since Apple's documented
    // PATCH/PUT/DELETE 401s are structural, not token-related.
    single { AppleMusicLibraryClient(get(), get()) }
    single { GeoLocationClient(get()) }
    single { ListenBrainzClient(get()) }
    // Smart Links — desktop's Cloudflare Pages share-link backend. Public, no
    // auth, CORS-open. Migrated from Retrofit → Ktor in the Smart Links cutover
    // (closes the last Retrofit footprint).
    single { SmartLinksClient(get()) }
    single {
        AchordionClient(
            httpClient = get(),
            bearerToken = get<AppConfig>().achordionBearerToken,
            clientId = get<AppConfig>().parachordClient,
        )
    }
}
