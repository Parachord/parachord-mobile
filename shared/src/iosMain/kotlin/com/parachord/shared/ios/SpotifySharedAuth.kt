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

/** Refresh access tokens this many ms before they actually expire. */
private const val EXPIRY_SKEW_MS = 60_000L

/**
 * Request-time Spotify credential lookup for the shared Ktor client.
 *
 * Reads the cached access token from [SettingsStore]. If it's expiring
 * within [EXPIRY_SKEW_MS] and a refresh token exists, it proactively
 * refreshes via [refresher] (the dedicated plain client — see
 * [SpotifyTokenRefresher]) before handing the bearer back. Non-Spotify
 * realms always return null.
 *
 * Depends on the [OAuthTokenRefresher] — construct the refresher first
 * and pass it in.
 */
class SpotifyAuthTokenProvider(
    private val settingsStore: SettingsStore,
    private val refresher: OAuthTokenRefresher,
) : AuthTokenProvider {

    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? {
        if (realm != AuthRealm.Spotify) return null

        val cached = settingsStore.getSpotifyAccessToken()
        val expiresAt = settingsStore.getSpotifyAccessTokenExpiresAt()
        val expiringSoon = currentTimeMillis() >= expiresAt - EXPIRY_SKEW_MS
        val hasRefresh = !settingsStore.getSpotifyRefreshToken().isNullOrBlank()

        if (expiringSoon && hasRefresh) {
            val refreshed = refresher.refresh(AuthRealm.Spotify)
            if (refreshed != null) return refreshed
            // Refresh failed transiently — fall back to the cached token if
            // we still have one; the 401-refresh plugin handles a hard fail.
        }

        return cached?.let { AuthCredential.BearerToken(it) }
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
