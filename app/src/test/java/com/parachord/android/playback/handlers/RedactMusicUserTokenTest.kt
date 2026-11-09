package com.parachord.android.playback.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [redactMusicUserToken]: MusicKitWebBridge logged `authorize()`'s result,
 * which carries the user's Music User Token, in release builds too.
 */
class RedactMusicUserTokenTest {

    private val token = "0.AvekFAKE/Token+Value=="

    @Test
    fun `the token is removed from the plain JSON result`() {
        val raw = """{"success":true,"authorized":true,"musicUserToken":"$token"}"""
        val redacted = redactMusicUserToken(raw)
        assertFalse(redacted.contains(token))
        assertEquals("""{"success":true,"authorized":true,"musicUserToken":"<redacted>"}""", redacted)
    }

    @Test
    fun `the token is removed from the JSON-in-a-string form evaluateJavascript returns`() {
        val raw = """"{\"success\":true,\"authorized\":true,\"musicUserToken\":\"$token\"}""""
        val redacted = redactMusicUserToken(raw)
        assertFalse(redacted.contains(token))
        assertEquals(""""{\"success\":true,\"authorized\":true,\"musicUserToken\":\"<redacted>\"}"""", redacted)
    }

    @Test
    fun `a result without a token is unchanged`() {
        val raw = """{"success":true,"authorized":true,"musicUserToken":null}"""
        assertEquals(raw, redactMusicUserToken(raw))
        val failed = """{"success":false,"authorized":false,"error":"denied"}"""
        assertEquals(failed, redactMusicUserToken(failed))
    }
}
