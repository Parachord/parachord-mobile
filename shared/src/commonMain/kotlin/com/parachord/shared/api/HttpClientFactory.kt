package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.transport.OAuthRefreshPlugin
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Platform-specific HTTP client factory.
 *
 * Android: uses OkHttp engine (familiar, proven).
 * iOS: uses Darwin engine (URLSession-based, native performance).
 *
 * Plugin install order (matters for Ktor middleware layering):
 *  1. ContentNegotiation — first, so subsequent plugins can read/write JSON bodies
 *  2. Logging — early, sees behavior post-content-negotiation; sanitizes Authorization
 *  3. DefaultRequest — sets User-Agent + baseline headers; before auth so it applies on retries
 *  4. OAuthRefreshPlugin — 401 → refresh + retry, single-flight per realm
 *  5. ListenBrainzAuthPlugin — auto-attach `Authorization: Token <token>` for api.listenbrainz.org.
 *                              Order vs. OAuthRefreshPlugin doesn't matter (no host overlap — LB
 *                              tokens are static and never refreshed by OAuthRefreshPlugin), but
 *                              kept after OAuth for visual grouping with auth-related plugins.
 *  6. HttpTimeout — last, wraps everything in 60s/15s/30s budget
 */
expect fun createHttpClient(
    json: Json,
    appConfig: AppConfig,
    authProvider: AuthTokenProvider,
    tokenRefresher: OAuthTokenRefresher,
    lbTokenProvider: suspend () -> String?,
): HttpClient

internal fun HttpClientConfig<*>.installSharedPlugins(
    json: Json,
    appConfig: AppConfig,
    authProvider: AuthTokenProvider,
    tokenRefresher: OAuthTokenRefresher,
    lbTokenProvider: suspend () -> String? = { null },
) {
    install(ContentNegotiation) { json(json) }
    install(Logging) {
        level = if (appConfig.isDebug) LogLevel.HEADERS else LogLevel.INFO
        sanitizeHeader { it == HttpHeaders.Authorization }
    }
    defaultRequest {
        header(HttpHeaders.UserAgent, appConfig.userAgent)
    }
    install(OAuthRefreshPlugin) {
        this.tokenProvider = authProvider
        this.tokenRefresher = tokenRefresher
        this.refreshableHosts = mapOf(
            "api.spotify.com" to AuthRealm.Spotify,
            // SoundCloud added when its native client migrates from raw OkHttp
            // calls in a later 9E phase.
        )
    }
    install(ListenBrainzAuthPlugin) {
        this.tokenProvider = lbTokenProvider
    }
    // Survive the device changing networks under an in-flight request.
    //
    // On a handoff (Wi-Fi <-> LTE, carrier IPsec re-establishing) every open
    // socket is aborted and DNS is briefly unresolvable, so EVERY provider in
    // flight fails at the same instant regardless of service or credentials —
    // the "Apple Music and Discogs failing in lockstep" symptom. The hosts
    // resolve fine a second later; nothing was retrying, so a 200ms blip
    // permanently lost those artist images for the session.
    //
    // Scoped tightly on purpose (see isTransientNetworkError): connection
    // failures ONLY, never HTTP status codes. Retrying statuses here would
    // fight RateLimitGate and risk re-poking a rate-limited Spotify account,
    // which is what earns an account-wide abuse ban (#176/#177).
    install(HttpRequestRetry) {
        maxRetries = 2
        retryOnExceptionIf { _, cause -> isTransientNetworkError(cause) }
        // ~250ms then ~500ms: long enough for a handoff to settle, short
        // enough that a user waiting on artwork doesn't notice.
        exponentialDelay(base = 2.0, baseDelayMs = 250, maxDelayMs = 2_000)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000     // AI endpoints take 30–60s (CLAUDE.md "AI generation needs long timeouts")
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}
