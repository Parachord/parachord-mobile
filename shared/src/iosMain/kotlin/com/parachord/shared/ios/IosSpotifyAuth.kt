package com.parachord.shared.ios

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.settings.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Spotify authorization-code → token exchange for the iOS shell.
 *
 * IMPORTANT: this MUST run against a PLAIN Ktor [HttpClient] — one with
 * NO [com.parachord.shared.api.transport.OAuthRefreshPlugin] and NO
 * Spotify bearer injection. The token endpoint is unauthenticated (it's
 * how you *get* a bearer); routing it through the auth-plugin'd client
 * would (a) inject a possibly-dead bearer and (b) recurse the 401-refresh
 * machinery back through this very exchange. [IosContainer] builds a
 * dedicated `authHttpClient` for exactly this and the refresher.
 *
 * Mirrors the Android `OAuthManager` Spotify token exchange (same
 * endpoint, form params, and response field names).
 */
class IosSpotifyAuth(
    private val authHttpClient: HttpClient,
    private val settingsStore: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Exchange an authorization code (from [IosOAuthManager.authorize]) for
     * Spotify access + refresh tokens and persist them. Throws on any
     * non-2xx response or a missing client ID.
     */
    suspend fun exchangeCode(code: String, codeVerifier: String, redirectUri: String) {
        val cid = settingsStore.getSpotifyClientId() ?: ""
        require(cid.isNotBlank()) { "Spotify Client ID not set — add yours in Settings → Spotify" }

        val response = authHttpClient.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("client_id", cid)
                append("code_verifier", codeVerifier)
            },
        )

        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Spotify token exchange failed (${response.status.value}): $responseBody",
            )
        }

        val token = json.decodeFromString<SpotifyTokenResponse>(responseBody)
        settingsStore.setSpotifyTokens(token.accessToken, token.refreshToken ?: "")
        token.expiresIn?.let { expiresIn ->
            settingsStore.setSpotifyAccessTokenExpiresAt(currentTimeMillis() + expiresIn * 1000L)
        }
        Log.d(TAG, "Spotify auth successful")
    }

    companion object {
        private const val TAG = "IosSpotifyAuth"
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    }
}

@Serializable
internal data class SpotifyTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)
