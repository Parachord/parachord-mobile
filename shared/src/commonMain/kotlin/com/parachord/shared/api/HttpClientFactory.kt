package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.transport.OAuthRefreshPlugin
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
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
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000     // AI endpoints take 30–60s (CLAUDE.md "AI generation needs long timeouts")
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}
