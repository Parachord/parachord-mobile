package com.parachord.shared.api

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression guard for the Sept 2026 "Apple Music sign-in loop": the bundled
 * developer token lapsed, `configure()` reported it as a *user* sign-in
 * problem, and tapping Connect silently re-fired the same toast forever.
 * Telling "our key expired" apart from "the user is signed out" is what
 * makes that reportable, so it is pinned here.
 */
class AppleMusicTokenExpiryTest {

    @OptIn(ExperimentalEncodingApi::class)
    private fun jwt(payload: String): String {
        fun seg(s: String) = Base64.UrlSafe.encode(s.encodeToByteArray()).trimEnd('=')
        return "${seg("""{"alg":"ES256","kid":"437JVHZMMK"}""")}.${seg(payload)}.c2ln"
    }

    /** The real token that expired on 2026-09-11 and caused the incident. */
    private val realExpiredToken =
        jwt("""{"iss":"YR3XETE537","iat":1773533579,"exp":1789085579}""")

    @Test
    fun `reads exp from a real-shaped token`() {
        assertEquals(1789085579_000L, AppleMusicTokenExpiry.expiresAtMs(realExpiredToken))
    }

    @Test
    fun `the token that broke Apple Music is expired at the time it broke`() {
        // 2026-09-19, the day it was reported.
        assertTrue(AppleMusicTokenExpiry.isExpired(realExpiredToken, nowMs = 1789831975_000L))
    }

    @Test
    fun `same token was still valid before its exp`() {
        // 2026-09-01, ten days earlier.
        assertFalse(AppleMusicTokenExpiry.isExpired(realExpiredToken, nowMs = 1788278400_000L))
    }

    @Test
    fun `exp boundary is inclusive - at exp it is expired`() {
        assertTrue(AppleMusicTokenExpiry.isExpired(realExpiredToken, nowMs = 1789085579_000L))
        assertFalse(AppleMusicTokenExpiry.isExpired(realExpiredToken, nowMs = 1789085578_999L))
    }

    // ── "not expired" is not the same as "fine" ──────────────────────

    @Test
    fun `absent token is unconfigured, not expired`() {
        assertFalse(AppleMusicTokenExpiry.isExpired(null))
        assertFalse(AppleMusicTokenExpiry.isExpired(""))
        assertFalse(AppleMusicTokenExpiry.isExpired("   "))
        assertNull(AppleMusicTokenExpiry.expiresAtMs(null))
    }

    @Test
    fun `unparseable token is never reported expired - let Apple judge`() {
        // Blocking a token we merely failed to parse would lock users out of
        // a working Apple Music for no reason.
        assertFalse(AppleMusicTokenExpiry.isExpired("not-a-jwt"))
        assertFalse(AppleMusicTokenExpiry.isExpired("only.two"))
        assertFalse(AppleMusicTokenExpiry.isExpired("a.!!!not-base64!!!.c"))
        assertFalse(AppleMusicTokenExpiry.isExpired(jwt("""{"iss":"X"}""")))      // no exp
        assertFalse(AppleMusicTokenExpiry.isExpired(jwt("""{"exp":"soon"}""")))   // non-numeric
    }

    @Test
    fun `payload needing base64url padding still decodes`() {
        // Payload lengths hitting each residue mod 4 — an unpadded decoder
        // throws on some and not others, so cover them all.
        for (pad in listOf("", "a", "ab", "abc")) {
            val t = jwt("""{"exp":1789085579,"p":"$pad"}""")
            assertEquals(
                1789085579_000L,
                AppleMusicTokenExpiry.expiresAtMs(t),
                "padding case '$pad' failed to decode",
            )
        }
    }

    @Test
    fun `base64url alphabet decodes - hyphen and underscore are not standard base64`() {
        // A payload engineered to produce '-'/'_' in its encoding; the
        // standard (non-url-safe) decoder rejects those.
        val payload = """{"exp":1789085579,"x":"~~~?>>>"}"""
        val t = jwt(payload)
        assertEquals(1789085579_000L, AppleMusicTokenExpiry.expiresAtMs(t))
    }

    // ── rotate-before-lockout window ─────────────────────────────────

    @Test
    fun `expiring soon flags a token inside the window but not outside it`() {
        val exp = 1789085579_000L
        val day = 24L * 60 * 60 * 1000
        assertTrue(AppleMusicTokenExpiry.isExpiringSoon(realExpiredToken, nowMs = exp - 5 * day))
        assertFalse(AppleMusicTokenExpiry.isExpiringSoon(realExpiredToken, nowMs = exp - 90 * day))
    }

    @Test
    fun `already-expired token is not merely expiring soon`() {
        // The two states are distinct so tooling can say which happened.
        val past = 1789085579_000L + 1000
        assertFalse(AppleMusicTokenExpiry.isExpiringSoon(realExpiredToken, nowMs = past))
        assertTrue(AppleMusicTokenExpiry.isExpired(realExpiredToken, nowMs = past))
    }
}
