package com.parachord.shared.metadata

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the #186 fix: an EXPIRED Apple Music developer token must not report
 * itself as available. Before this, `isAvailable()` only checked `isNotBlank()`,
 * so an expired token 401'd every request and artist images vanished silently.
 */
class DeveloperTokenExpiryTest {

    @OptIn(ExperimentalEncodingApi::class)
    private fun jwt(payload: String): String {
        // Real JWTs are base64url WITHOUT padding — reproduce that exactly, since
        // handling the missing padding is the part most likely to break.
        fun seg(s: String) = Base64.UrlSafe.encode(s.encodeToByteArray()).trimEnd('=')
        return "${seg("""{"alg":"ES256","kid":"K"}""")}.${seg(payload)}.c2ln"
    }

    @Test
    fun `reads exp from a well-formed token`() {
        val token = jwt("""{"iss":"TEAM","iat":1000,"exp":1789085579}""")
        assertEquals(1789085579L, DeveloperTokenExpiry.expEpochSeconds(token))
    }

    @Test
    fun `expired token is expired, valid token is not`() {
        val exp = 1_800_000_000L
        val token = jwt("""{"iss":"TEAM","iat":1,"exp":$exp}""")
        assertTrue(DeveloperTokenExpiry.isExpired(token, exp + 1))
        assertTrue(DeveloperTokenExpiry.isExpired(token, exp), "exp itself counts as expired")
        assertFalse(DeveloperTokenExpiry.isExpired(token, exp - 1))
    }

    @Test
    fun `daysRemaining is positive before expiry and negative after`() {
        val exp = 1_800_000_000L
        val token = jwt("""{"exp":$exp}""")
        assertEquals(10L, DeveloperTokenExpiry.daysRemaining(token, exp - 10 * 86_400))
        assertEquals(-2L, DeveloperTokenExpiry.daysRemaining(token, exp + 2 * 86_400))
    }

    @Test
    fun `malformed input fails OPEN, never reporting a usable token as expired`() {
        // Fail-open is deliberate: a parsing regression must not disable a
        // provider whose token is actually fine.
        listOf(
            "",
            "not-a-jwt",
            "only.two",
            "a.!!!not-base64!!!.c",
            jwt("""{"iss":"TEAM"}"""), // no exp claim
        ).forEach { bad ->
            assertNull(DeveloperTokenExpiry.expEpochSeconds(bad), "exp should be null for: $bad")
            assertFalse(DeveloperTokenExpiry.isExpired(bad, 9_999_999_999L), "must fail open for: $bad")
        }
    }
}
