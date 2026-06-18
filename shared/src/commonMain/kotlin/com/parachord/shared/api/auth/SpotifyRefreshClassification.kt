package com.parachord.shared.api.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Outcome of a Spotify token-refresh attempt — the terminal-vs-transient
 * split that keeps a Spotify outage from logging users out while still
 * clearing a genuinely-dead grant.
 *
 * - [OK]        — refresh succeeded (2xx).
 * - [TERMINAL]  — the grant is dead (refresh token expired or revoked). The
 *                 caller MUST discard the stored Spotify tokens and prompt
 *                 re-auth; it must NOT retry the dead refresh token.
 * - [TRANSIENT] — a server blip (429 / 5xx / network / unparseable body).
 *                 Keep the stored token, fail only this attempt, retry next time.
 */
enum class SpotifyRefreshOutcome { OK, TERMINAL, TRANSIENT }

/**
 * Classify a Spotify `POST /api/token` (grant_type=refresh_token) response.
 *
 * Byte-for-byte port of desktop's `classifySpotifyRefresh`
 * (Parachord/parachord#905, commit `8bc636d`) so both clients clear state on
 * exactly the same conditions:
 *
 * ```
 * if 200..299: 'ok'
 * err = body?.error
 * if status == 400 and err in (invalid_grant, invalid_client): 'terminal'
 * if status in (401,403) and err in (invalid_grant, invalid_client): 'terminal'
 * else: 'transient'
 * ```
 *
 * Effective 2026-07-20 Spotify expires user refresh tokens after six months;
 * an expired token returns `400 invalid_grant`, which lands here as [TERMINAL].
 *
 * @param status the HTTP status code of the refresh response.
 * @param errorCode the parsed `error` field of the response body, or null when
 *   the body is absent / non-JSON / has no `error` (see [parseSpotifyErrorCode]).
 */
fun classifySpotifyRefresh(status: Int, errorCode: String?): SpotifyRefreshOutcome {
    if (status in 200..299) return SpotifyRefreshOutcome.OK
    val terminalErr = errorCode == "invalid_grant" || errorCode == "invalid_client"
    if (status == 400 && terminalErr) return SpotifyRefreshOutcome.TERMINAL
    if ((status == 401 || status == 403) && terminalErr) return SpotifyRefreshOutcome.TERMINAL
    return SpotifyRefreshOutcome.TRANSIENT
}

private val classifierJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Extract the OAuth `error` field from a Spotify token-endpoint error body.
 * Returns null for an empty / non-JSON / error-less body so [classifySpotifyRefresh]
 * treats it as [SpotifyRefreshOutcome.TRANSIENT] (a transient proxy/HTML error
 * must never be mistaken for a dead grant).
 */
fun parseSpotifyErrorCode(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return try {
        classifierJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}
