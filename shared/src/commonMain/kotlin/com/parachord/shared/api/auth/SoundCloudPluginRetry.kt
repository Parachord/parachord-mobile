package com.parachord.shared.api.auth

/** The resolver id of `soundcloud.axe`. */
const val SOUNDCLOUD_RESOLVER_ID = "soundcloud"

/**
 * True when [error] is `soundcloud.axe` (1.3.4+) saying SoundCloud refused its
 * token: its search throws "SoundCloud search failed: 401". Each app wraps the
 * plugin's error in its own message, so this looks for the plugin's words.
 */
fun isSoundCloudRejection(error: Throwable): Boolean =
    error.message?.contains("SoundCloud search failed: 401") == true

/**
 * Runs one `.axe` resolve, and when it's SoundCloud's and SoundCloud refused
 * the token, refreshes the sign-in once and runs it again (#598).
 *
 * The plugin is handed a token that [SoundCloudAuth.accessToken] refreshes
 * before it expires, so a 401 means SoundCloud revoked it early. The native
 * resolver Android used to run refreshed and retried here too.
 * [currentToken] is read before the call, so a refresh another caller already
 * made is reused ([SoundCloudAuth.refreshAfterRejection]). Any other resolver,
 * and any other failure, passes through unchanged. A failed refresh rethrows
 * the 401, so the resolve counts as unanswered, not as a miss (#566).
 */
suspend fun <T> retryAfterSoundCloudRejection(
    resolverId: String,
    currentToken: suspend () -> String?,
    refreshAfterRejection: suspend (rejectedToken: String?) -> Boolean,
    call: suspend () -> T,
): T {
    if (resolverId != SOUNDCLOUD_RESOLVER_ID) return call()
    val tokenBefore = currentToken()
    return try {
        call()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException || !isSoundCloudRejection(e)) throw e
        if (!refreshAfterRejection(tokenBefore)) throw e
        call()
    }
}
