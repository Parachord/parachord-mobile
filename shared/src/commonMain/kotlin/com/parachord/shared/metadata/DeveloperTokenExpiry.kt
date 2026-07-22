package com.parachord.shared.metadata

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Reads the `exp` claim out of a JWT so an EXPIRED token can be detected before
 * it is used (parachord-mobile#186).
 *
 * Why this exists: `AppleMusicArtistProvider.isAvailable()` used to check only
 * `isNotBlank()`. A token past its `exp` therefore looked perfectly available,
 * every catalog request 401'd, and Apple Music artist images simply stopped
 * appearing with NO error anywhere — the failure was invisible until someone
 * noticed blank artist art. Since the Apple Music developer token is capped at
 * 6 months, that was guaranteed to happen eventually.
 *
 * This does NOT verify the signature — it only reads the claim. That's correct
 * for the purpose: we're sanity-checking OUR OWN build-time-embedded token to
 * decide whether to bother calling the API, not authenticating a token from an
 * untrusted party. Anything malformed returns null and is treated as "unknown
 * expiry", so a parse failure can never make a working token look expired.
 */
object DeveloperTokenExpiry {

    private const val SECONDS_PER_DAY = 24L * 60 * 60

    /** Warn this far ahead so a rotation can happen before images break. */
    const val WARN_WITHIN_DAYS = 14L

    /**
     * The JWT's `exp` claim in epoch seconds, or null if the token is blank,
     * malformed, or carries no `exp`.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun expEpochSeconds(token: String): Long? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        return try {
            // JWT uses base64url WITHOUT padding; Kotlin's decoder wants it.
            val payload = parts[1]
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = Base64.UrlSafe.decode(padded).decodeToString()
            // Deliberately a narrow regex rather than full JSON parsing: this
            // runs on a value we generated, and must never throw.
            Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True only when the token has a readable `exp` that is in the past.
     * Unknown expiry (null) is NOT treated as expired — fail open, so a parsing
     * change can't disable a provider that would otherwise work.
     */
    fun isExpired(token: String, nowEpochSeconds: Long): Boolean {
        val exp = expEpochSeconds(token) ?: return false
        return nowEpochSeconds >= exp
    }

    /** Whole days until `exp`; null if unknown. Negative once expired. */
    fun daysRemaining(token: String, nowEpochSeconds: Long): Long? {
        val exp = expEpochSeconds(token) ?: return null
        return (exp - nowEpochSeconds) / SECONDS_PER_DAY
    }
}
