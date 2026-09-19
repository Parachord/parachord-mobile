package com.parachord.shared.api

import com.parachord.shared.platform.currentTimeMillis
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Expiry inspection for the Apple Music **developer token** (the ES256 JWT
 * signed with the team's `.p8` AuthKey), shared so Android, iOS and any
 * build-time check agree on exactly one answer.
 *
 * Apple caps this token at **180 days**, and it is hand-pasted into several
 * places (see CLAUDE.md "Apple Music developer token — rotate BOTH
 * platforms"). When it lapses, every catalog request 401s and MusicKit
 * refuses to configure — which historically surfaced to the user as a bogus
 * "Sign in to Apple Music" prompt that no amount of signing in could fix,
 * because the failure is the app's own key, not the user's Apple ID.
 * [isExpired] exists so callers can tell those two states apart and say the
 * true thing.
 *
 * This deliberately does **not** verify the signature — only Apple can do
 * that. It reads the unverified `exp` claim, which is all that is needed to
 * distinguish "our key lapsed" from "the user is signed out".
 */
object AppleMusicTokenExpiry {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Epoch **milliseconds** at which [token] expires, or null when the token
     * is absent, malformed, or carries no numeric `exp` claim.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun expiresAtMs(token: String?): Long? {
        if (token.isNullOrBlank()) return null
        val parts = token.split(".")
        if (parts.size != 3) return null
        val payload = parts[1]
        // JWT uses base64url WITHOUT padding; Kotlin's decoder requires it.
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val bytes = try {
            Base64.UrlSafe.decode(padded)
        } catch (_: Exception) {
            return null
        }
        return try {
            json.parseToJsonElement(bytes.decodeToString())
                .jsonObject["exp"]?.jsonPrimitive?.longOrNull
                ?.times(1000L)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True when [token] is present and definitively past its `exp`.
     *
     * A null/blank token is **not** "expired" — it is *unconfigured*, a
     * different failure the caller reports differently. A token we cannot
     * parse is also not reported as expired: we let Apple be the judge rather
     * than blocking a token that might be perfectly valid.
     */
    fun isExpired(token: String?, nowMs: Long = currentTimeMillis()): Boolean {
        val exp = expiresAtMs(token) ?: return false
        return nowMs >= exp
    }

    /**
     * True when [token] is valid now but lapses within [withinMs] (default 30
     * days). Build tooling uses this to rotate *before* users are locked out,
     * rather than after.
     */
    fun isExpiringSoon(
        token: String?,
        nowMs: Long = currentTimeMillis(),
        withinMs: Long = 30L * 24 * 60 * 60 * 1000,
    ): Boolean {
        val exp = expiresAtMs(token) ?: return false
        return exp - nowMs in 0..withinMs
    }
}
