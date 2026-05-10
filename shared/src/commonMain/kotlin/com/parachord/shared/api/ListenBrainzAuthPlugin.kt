package com.parachord.shared.api

import com.parachord.shared.platform.Log
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders

private const val TAG = "ListenBrainzAuthPlugin"

/**
 * Ktor plugin that auto-attaches `Authorization: Token <token>` to every request
 * targeting `api.listenbrainz.org` (or any subdomain thereof).
 *
 * The token is sourced lazily per-request via [ListenBrainzAuthConfig.tokenProvider]
 * — typically a closure over [com.parachord.shared.settings.SettingsStore.getListenBrainzToken]
 * — so runtime token changes (user pasting a new token in Settings) take effect
 * without rebuilding the HttpClient.
 *
 * Behavior:
 *  - Only matches host `api.listenbrainz.org` (case-insensitive) and subdomains.
 *  - Does NOT clobber an existing `Authorization` header — call sites that set
 *    their own header (e.g. [com.parachord.shared.api.ListenBrainzClient.submitRecordingFeedback])
 *    keep their explicit token.
 *  - If the token provider returns null/blank or throws, the request passes
 *    through with no `Authorization` header. Callers see the natural 401 from
 *    the server rather than a swallowed failure.
 *
 * Mirrors desktop commits 69116a4 / 31078cf. Used by Phase 3's protocol-play
 * Mode C `?url=` initial pool fetch, refill loop fetches, and the listen-along
 * `/playing-now` fetch.
 */
class ListenBrainzAuthConfig {
    /**
     * Looks up the user's ListenBrainz user-token. Called per-request; should
     * be cheap (typically a [com.parachord.shared.store.SecureTokenStore.get]).
     * Return null/blank when the user hasn't configured a token.
     */
    var tokenProvider: suspend () -> String? = { null }
}

val ListenBrainzAuthPlugin = createClientPlugin("ListenBrainzAuth", ::ListenBrainzAuthConfig) {
    val tokenProvider = pluginConfig.tokenProvider
    onRequest { request, _ ->
        val host = request.url.host.lowercase()
        val isLbHost = host == "api.listenbrainz.org" || host.endsWith(".api.listenbrainz.org")
        if (!isLbHost) return@onRequest
        if (request.headers.contains(HttpHeaders.Authorization)) return@onRequest

        val token = try {
            tokenProvider()
        } catch (e: Throwable) {
            // SettingsStore lookup should never throw, but defend anyway —
            // a thrown exception here would kill every LB call. Log so a
            // misconfigured SettingsStore produces a diagnostic trail rather
            // than a silent 401.
            Log.w(TAG, "tokenProvider threw, omitting Authorization: ${e.message}")
            null
        }
        if (!token.isNullOrBlank()) {
            request.headers.append(HttpHeaders.Authorization, "Token $token")
        }
    }
}
