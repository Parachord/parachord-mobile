package com.parachord.shared.api.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class LastFmSignatureTest {
    /**
     * Fixture from the Last.fm API authentication docs:
     * https://www.last.fm/api/desktopauth — Signing Calls
     *
     * Given params: { method=auth.gettoken, api_key=xxxxxxxxxxxxxxxx }
     * Shared secret: "tropicalsnowstorm"
     *
     * Concat string: "api_keyxxxxxxxxxxxxxxxxmethodauth.gettokentropicalsnowstorm"
     * Verified externally: echo -n "..." | md5sum = 3ff89d6d9b055a2192e06326182955f9
     */
    @Test
    fun lastFmSignature_simpleParams_matchesKnownDigest() {
        // The signature helper sorts params internally, so map order here is irrelevant.
        val params = mapOf(
            "method" to "auth.gettoken",
            "api_key" to "xxxxxxxxxxxxxxxx",
        )
        val sharedSecret = "tropicalsnowstorm"
        val sig = lastFmSignature(params, sharedSecret)
        assertEquals("3ff89d6d9b055a2192e06326182955f9", sig)
    }

    @Test
    fun lastFmSignature_skipsApiSigParam() {
        // If api_sig is in the input map (defensive), it should NOT be included
        // in the concat — that's circular.
        val params = mapOf(
            "api_sig" to "should-be-ignored",
            "api_key" to "xxxxxxxxxxxxxxxx",
            "method" to "auth.gettoken",
        )
        val sig = lastFmSignature(params, "tropicalsnowstorm")
        assertEquals("3ff89d6d9b055a2192e06326182955f9", sig)
    }

    @Test
    fun lastFmSignature_skipsFormatParam() {
        // Per Last.fm docs, the "format" param (json/xml) is NOT signed.
        val params = mapOf(
            "api_key" to "xxxxxxxxxxxxxxxx",
            "format" to "json",
            "method" to "auth.gettoken",
        )
        val sig = lastFmSignature(params, "tropicalsnowstorm")
        assertEquals("3ff89d6d9b055a2192e06326182955f9", sig)
    }

    @Test
    fun lastFmSignature_lowercaseHex() {
        // Output must be lowercase hex; Last.fm rejects uppercase.
        val params = mapOf("method" to "test")
        val sig = lastFmSignature(params, "secret")
        assertEquals(sig, sig.lowercase())
        assertEquals(32, sig.length)
    }

    @Test
    fun lastFmSignature_paramOrderingIndependent() {
        // Inputs given in different orders should produce the same signature
        // because the algorithm sorts params alphabetically.
        val a = lastFmSignature(linkedMapOf("z" to "1", "a" to "2", "m" to "3"), "s")
        val b = lastFmSignature(linkedMapOf("a" to "2", "m" to "3", "z" to "1"), "s")
        assertEquals(a, b)
    }
}
