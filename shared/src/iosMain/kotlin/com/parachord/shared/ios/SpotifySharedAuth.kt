package com.parachord.shared.ios

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.settings.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * Request-time Spotify credential lookup for the shared Ktor client.
 *
 * Returns the cached access token and lets the SINGLE-FLIGHTED reactive
 * [com.parachord.shared.api.transport.OAuthRefreshPlugin] (installed on the
 * client with [SpotifyTokenRefresher] for `api.spotify.com`) handle all
 * 401-driven refresh + retry. Non-Spotify realms always return null.
 *
 * Deliberately mirrors `AndroidAuthTokenProvider.tokenFor` — a plain cached
 * read, NO proactive refresh.
 *
 * **Why no proactive refresh** (it used to refresh here when the token was
 * within 60s of expiry): `tokenFor` runs per outgoing request and was NOT
 * single-flighted, so a burst of concurrent searches each fired their own
 * refresh. Spotify rotates the refresh token on every call, so the
 * concurrent refreshes invalidated one another — `500 server_error
 * "Failed to remove token"` — and stormed the token endpoint, churning
 * 401s and contributing to 429s. The reactive plugin already refreshes on
 * 401 AND single-flights concurrent refreshes (one refresh for N waiters),
 * which is the correct, tested path. One extra 401 round-trip on the first
 * request after expiry is the only cost, and it matches Android exactly.
 */
class SpotifyAuthTokenProvider(
    private val settingsStore: SettingsStore,
) : AuthTokenProvider {

    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? {
        if (realm != AuthRealm.Spotify) return null
        return settingsStore.getSpotifyAccessToken()?.let { AuthCredential.BearerToken(it) }
    }

    override suspend fun invalidate(realm: AuthRealm) {
        if (realm == AuthRealm.Spotify) settingsStore.clearSpotifyTokens()
    }
}

/**
 * Response-time Spotify token refresh, invoked on 401 by the shared
 * client's [com.parachord.shared.api.transport.OAuthRefreshPlugin] and
 * proactively by [SpotifyAuthTokenProvider].
 *
 * CRITICAL: runs against the PLAIN [authHttpClient] (no auth/refresh
 * plugins) so the refresh request itself can't recurse the refresh
 * machinery. Returns null on any failure (never throws) — the 401 plugin
 * treats null as "couldn't refresh" and escalates to reauth.
 *
 * Mirrors the Android `OAuthManager.refreshSpotifyTokenWithConfig` wire
 * format (same endpoint, form params, response field names, and the
 * rotated-refresh-token handling).
 */
class SpotifyTokenRefresher(
    private val settingsStore: SettingsStore,
    private val authHttpClient: HttpClient,
    private val clientId: () -> String,
) : OAuthTokenRefresher {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? {
        if (realm != AuthRealm.Spotify) return null

        val refreshToken = settingsStore.getSpotifyRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "No refresh token stored — re-auth required")
            return null
        }
        val cid = clientId()
        if (cid.isBlank()) {
            Log.w(TAG, "No Spotify client ID configured")
            return null
        }

        return try {
            val response = authHttpClient.submitForm(
                url = IosSpotifyAuth.TOKEN_URL,
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("client_id", cid)
                },
            )

            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                Log.w(TAG, "Spotify token refresh failed (${response.status.value}): $body")
                return null
            }

            val token = json.decodeFromString<SpotifyTokenResponse>(body)
            // Spotify sometimes rotates the refresh token — persist the new
            // one if present, otherwise keep the existing one.
            settingsStore.setSpotifyTokens(token.accessToken, token.refreshToken ?: refreshToken)
            token.expiresIn?.let { expiresIn ->
                settingsStore.setSpotifyAccessTokenExpiresAt(currentTimeMillis() + expiresIn * 1000L)
            }
            Log.d(TAG, "Spotify token refreshed successfully")
            AuthCredential.BearerToken(token.accessToken)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Spotify token refresh error", e)
            null
        }
    }

    companion object {
        private const val TAG = "SpotifyTokenRefresher"
    }
}
