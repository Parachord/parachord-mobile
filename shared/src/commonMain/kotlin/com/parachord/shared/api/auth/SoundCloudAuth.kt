package com.parachord.shared.api.auth

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.settings.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A SoundCloud app's credentials: the person's own, or Parachord's built-in app ([SoundCloudBuiltInApp]). */
data class SoundCloudCredentials(val clientId: String, val clientSecret: String)

/**
 * SoundCloud OAuth 2.1 tokens for both apps: the authorization-code exchange
 * after sign-in, and a valid access token on demand, refreshing it when it has
 * expired. A port of desktop's `main-process/soundcloud-token.js`.
 *
 * SoundCloud access tokens last about an hour, and a refresh token is
 * SINGLE-USE: each refresh returns a new one and the old one is dead from then
 * on. A list resolving a dozen tracks asks for a token a dozen times at once,
 * so two guards (desktop's) keep concurrent callers from signing the user out:
 *  - one refresh at a time; callers waiting on it re-check and reuse its token;
 *  - a dead-refresh-token answer clears the tokens only if the stored refresh
 *    token is still the one that was sent (otherwise another refresh already
 *    rotated it, and what's stored is good).
 *
 * Run it on a client with no auth plugins: the token endpoint is how a bearer
 * is obtained.
 */
class SoundCloudAuth(
    private val httpClient: HttpClient,
    private val settingsStore: SettingsStore,
    private val credentials: suspend () -> SoundCloudCredentials? = {
        resolveSoundCloudCredentials(settingsStore.getSoundCloudClientId(), settingsStore.getSoundCloudClientSecret())
    },
    private val nowMs: () -> Long = ::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val refreshMutex = Mutex()

    /** The app a sign-in uses now; the authorize URL must name this client id. */
    suspend fun credentialsInUse(): SoundCloudCredentials? = credentials()

    /**
     * Exchange the sign-in's authorization [code] (with its PKCE
     * [codeVerifier]) for tokens and store them. [redirectUri] must be the one
     * the authorize step sent: desktop's loopback address
     * ([SoundCloudLoopback.REDIRECT_URI]). Throws with a readable
     * message when the credentials are missing or SoundCloud refuses.
     */
    suspend fun exchangeCode(code: String, codeVerifier: String, redirectUri: String = SoundCloudLoopback.REDIRECT_URI) {
        val creds = credentials()
            ?: throw IllegalStateException("Add your SoundCloud app's Client ID and Secret first.")
        val response = httpClient.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("client_id", creds.clientId)
                append("client_secret", creds.clientSecret)
                append("code_verifier", codeVerifier)
            },
        )
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<TokenError>(body) }.getOrNull()
            Log.e(TAG, "SoundCloud token exchange failed (${response.status.value}): $body")
            throw IllegalStateException(
                "SoundCloud sign-in failed: " + (error?.description ?: error?.error ?: "HTTP ${response.status.value}"),
            )
        }
        val token = json.decodeFromString<TokenResponse>(body)
        store(token, previousRefreshToken = null)
        Log.d(TAG, "SoundCloud auth successful")
    }

    /**
     * A usable access token, or null when signed out. Refreshes an expired
     * token, and refreshes a still-valid one once a week so the refresh token
     * doesn't expire from disuse (desktop's PROACTIVE_REFRESH_MS). A failed
     * proactive refresh still returns the valid token.
     */
    suspend fun accessToken(): String? {
        if (!needsRefresh()) return validStoredToken()
        return refreshMutex.withLock {
            // Another caller may have refreshed while this one waited.
            if (!needsRefresh()) return@withLock validStoredToken()
            val refreshToken = settingsStore.getSoundCloudRefreshToken()
            if (refreshToken.isNullOrBlank()) return@withLock validStoredToken()
            refresh(refreshToken) ?: validStoredToken()
        }
    }

    /**
     * Refresh after SoundCloud rejected [rejectedAccessToken] (a 401). When
     * another caller has already replaced that token, its replacement is used
     * instead of spending the refresh token again. True when a different token
     * is now stored.
     */
    suspend fun refreshAfterRejection(rejectedAccessToken: String?): Boolean = refreshMutex.withLock {
        val stored = settingsStore.getSoundCloudToken()
        if (!stored.isNullOrBlank() && stored != rejectedAccessToken) return@withLock true
        val refreshToken = settingsStore.getSoundCloudRefreshToken()
        if (refreshToken.isNullOrBlank()) return@withLock false
        refresh(refreshToken) != null
    }

    /** Sign out: forget the tokens, keep the app credentials. */
    suspend fun disconnect() = settingsStore.clearSoundCloudToken()

    /**
     * The config `soundcloud.axe` expects (desktop's `getResolverConfig`):
     * `{ token }`, or `{}` when signed out. The plugin's search, resolve and
     * URL lookups all return nothing without a token.
     */
    suspend fun pluginConfigJson(): String = buildJsonObject {
        accessToken()?.let { put("token", it) }
    }.toString()

    private suspend fun needsRefresh(): Boolean {
        if (validStoredToken() == null) return true
        val lastRefresh = settingsStore.getSoundCloudLastRefreshAt() ?: return false
        return nowMs() - lastRefresh > PROACTIVE_REFRESH_MS
    }

    /** The stored token while it's unexpired. No recorded expiry counts as expired. */
    private suspend fun validStoredToken(): String? {
        val token = settingsStore.getSoundCloudToken()?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = settingsStore.getSoundCloudTokenExpiresAt() ?: return null
        return if (nowMs() < expiresAt) token else null
    }

    /** One refresh; the new access token, or null. Call with [refreshMutex] held. */
    private suspend fun refresh(refreshToken: String): String? {
        val creds = credentials() ?: return null
        return try {
            val response = httpClient.submitForm(
                url = TOKEN_URL,
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("client_id", creds.clientId)
                    append("client_secret", creds.clientSecret)
                },
            )
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val error = runCatching { json.decodeFromString<TokenError>(body) }.getOrNull()
                Log.e(TAG, "SoundCloud token refresh failed (${response.status.value}) ${error?.error.orEmpty()}")
                // 401, or RFC 6749 §5.2 invalid_grant (SoundCloud sends it with
                // a 400): the refresh token we SENT is dead.
                val dead = response.status.value == 401 || error?.error == "invalid_grant"
                if (dead) {
                    if (settingsStore.getSoundCloudRefreshToken() == refreshToken) {
                        Log.w(TAG, "SoundCloud refresh token expired or revoked; signing out")
                        settingsStore.clearSoundCloudToken()
                    } else {
                        return validStoredToken()
                    }
                }
                return null
            }
            val token = json.decodeFromString<TokenResponse>(body)
            store(token, previousRefreshToken = refreshToken)
            Log.d(TAG, "SoundCloud token refreshed")
            token.accessToken
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SoundCloud token refresh error: ${e.message}")
            null
        }
    }

    private suspend fun store(token: TokenResponse, previousRefreshToken: String?) {
        val now = nowMs()
        settingsStore.setSoundCloudTokens(
            token.accessToken,
            token.refreshToken ?: previousRefreshToken ?: "",
        )
        settingsStore.setSoundCloudTokenTimes(
            expiresAt = now + (token.expiresIn ?: DEFAULT_EXPIRES_IN_S) * 1000L,
            refreshedAt = now,
        )
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
    )

    @Serializable
    private data class TokenError(
        val error: String? = null,
        @SerialName("error_description") val description: String? = null,
    )

    companion object {
        private const val TAG = "SoundCloudAuth"
        const val AUTHORIZE_URL = "https://secure.soundcloud.com/authorize"
        const val TOKEN_URL = "https://secure.soundcloud.com/oauth/token"
        /** The redirect every sign-in uses, desktop's (see [SoundCloudLoopback]). */
        const val REDIRECT_URI = SoundCloudLoopback.REDIRECT_URI

        /** A refresh token unused for long enough expires; refreshing weekly keeps it alive. */
        const val PROACTIVE_REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        const val DEFAULT_EXPIRES_IN_S = 3600L
    }
}
