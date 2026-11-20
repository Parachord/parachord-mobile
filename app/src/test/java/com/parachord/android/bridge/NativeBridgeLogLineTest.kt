package com.parachord.android.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A failed plugin fetch is logged without the keys its URL carries. */
class NativeBridgeLogLineTest {

    @Test
    fun `a failed fetch logs its URL without credentials`() {
        val line = fetchErrorLine(
            "fetchAsync", "GET",
            "https://generativelanguage.googleapis.com/v1beta/models?key=AIzaSyA1234567890abcdefghijk&pageSize=50",
            "Unable to resolve host \"generativelanguage.googleapis.com\"",
        )
        assertEquals(
            "fetchAsync error (GET https://generativelanguage.googleapis.com/v1beta/models?key=REDACTED&pageSize=50): " +
                "Unable to resolve host \"generativelanguage.googleapis.com\"",
            line,
        )
    }

    @Test
    fun `Subsonic token and salt are redacted`() {
        val line = fetchErrorLine("fetch", "GET", "https://music.home.test/rest/search3?u=me&t=0a1b2c&s=salt99&f=json", null)
        assertFalse(line, "0a1b2c" in line || "salt99" in line)
    }
}
