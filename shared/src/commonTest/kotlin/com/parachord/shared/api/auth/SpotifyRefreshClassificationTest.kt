package com.parachord.shared.api.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the desktop-parity terminal/transient split for Spotify token
 * refresh (#243 — six-month refresh-token expiry, effective 2026-07-20).
 *
 * The load-bearing property: a server blip (429/5xx/network) must classify
 * TRANSIENT (keep the token), while a dead grant (invalid_grant /
 * invalid_client) classifies TERMINAL (discard + re-auth). Mis-classifying a
 * transient failure as terminal would log users out during a Spotify outage.
 */
class SpotifyRefreshClassificationTest {

    @Test
    fun `2xx is OK`() {
        assertEquals(SpotifyRefreshOutcome.OK, classifySpotifyRefresh(200, null))
        assertEquals(SpotifyRefreshOutcome.OK, classifySpotifyRefresh(204, "ignored"))
    }

    @Test
    fun `400 invalid_grant is terminal (the six-month-expiry case)`() {
        assertEquals(SpotifyRefreshOutcome.TERMINAL, classifySpotifyRefresh(400, "invalid_grant"))
    }

    @Test
    fun `400 invalid_client is terminal`() {
        assertEquals(SpotifyRefreshOutcome.TERMINAL, classifySpotifyRefresh(400, "invalid_client"))
    }

    @Test
    fun `401 and 403 with dead-grant errors are terminal`() {
        assertEquals(SpotifyRefreshOutcome.TERMINAL, classifySpotifyRefresh(401, "invalid_grant"))
        assertEquals(SpotifyRefreshOutcome.TERMINAL, classifySpotifyRefresh(403, "invalid_client"))
    }

    @Test
    fun `400 with a non-dead-grant error is transient`() {
        // unsupported_grant_type etc. are config bugs, not dead grants —
        // don't nuke the user's stored token over them.
        assertEquals(
            SpotifyRefreshOutcome.TRANSIENT,
            classifySpotifyRefresh(400, "unsupported_grant_type"),
        )
    }

    @Test
    fun `429 and 5xx are transient regardless of body`() {
        assertEquals(SpotifyRefreshOutcome.TRANSIENT, classifySpotifyRefresh(429, null))
        assertEquals(SpotifyRefreshOutcome.TRANSIENT, classifySpotifyRefresh(500, "invalid_grant"))
        assertEquals(SpotifyRefreshOutcome.TRANSIENT, classifySpotifyRefresh(503, null))
    }

    @Test
    fun `401 or 403 without a dead-grant error code is transient`() {
        // A bare 401/403 with no parseable error (proxy HTML, gateway) is a
        // blip, not a confirmed dead grant.
        assertEquals(SpotifyRefreshOutcome.TRANSIENT, classifySpotifyRefresh(401, null))
        assertEquals(SpotifyRefreshOutcome.TRANSIENT, classifySpotifyRefresh(403, "server_error"))
    }

    @Test
    fun `parseSpotifyErrorCode reads the error field`() {
        assertEquals(
            "invalid_grant",
            parseSpotifyErrorCode("{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token revoked\"}"),
        )
    }

    @Test
    fun `parseSpotifyErrorCode is null for empty, non-JSON, or error-less bodies`() {
        assertNull(parseSpotifyErrorCode(null))
        assertNull(parseSpotifyErrorCode(""))
        assertNull(parseSpotifyErrorCode("   "))
        assertNull(parseSpotifyErrorCode("<html>502 Bad Gateway</html>"))
        assertNull(parseSpotifyErrorCode("{\"access_token\":\"abc\"}"))
    }

    @Test
    fun `end-to-end - unparseable body on a 400 is transient not terminal`() {
        // A 400 whose body we can't parse must NOT be treated as a dead grant.
        val code = parseSpotifyErrorCode("<html>bad</html>")
        assertEquals(SpotifyRefreshOutcome.TRANSIENT, classifySpotifyRefresh(400, code))
    }
}
