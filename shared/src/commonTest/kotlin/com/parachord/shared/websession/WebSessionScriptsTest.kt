package com.parachord.shared.websession

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebSessionScriptsTest {

    private val config = WebSessionConfigs.AMAZON_MUSIC

    @Test
    fun `the capture script names the session's hosts and domains and the platform's sink`() {
        val android = WebSessionScripts.captureScript(config, WebSessionScripts.ANDROID_CAPTURE_SINK)
        val ios = WebSessionScripts.captureScript(config, WebSessionScripts.IOS_CAPTURE_SINK)
        for (script in listOf(android, ios)) {
            assertTrue(script.contains("var HOSTS = [\"music.amazon.com\",\"gql.music.amazon.com\"];"))
            assertTrue(script.contains("var DOMAINS = [\"amazon.com\",\"music.amazon.com\",\"www.amazon.com\"];"))
            assertFalse(script.contains("__ALLOWED_HOSTS__") || script.contains("__COOKIE_DOMAINS__") || script.contains("__CAPTURE_SINK__"))
            // Cookie headers never leave the page.
            assertTrue(script.contains("delete headers.cookie; delete headers.cookie2;"))
        }
        assertTrue(android.contains("var sink = window.PcWebSessionCapture;"))
        assertTrue(ios.contains("window.webkit.messageHandlers.pcWebSessionCapture"))
        // The two copies differ only in the sink.
        assertEquals(
            android.replace(WebSessionScripts.ANDROID_CAPTURE_SINK, "SINK"),
            ios.replace(WebSessionScripts.IOS_CAPTURE_SINK, "SINK"),
        )
    }

    @Test
    fun `a report to an allowed host parses`() {
        val r = WebSessionScripts.parseCapture(
            """{"url":"https://gql.music.amazon.com/x","method":"POST","headers":{"x-a":"b"},"body":"q"}""",
            config.allowedHosts,
        )
        assertEquals(CapturedRequest("https://gql.music.amazon.com/x", "POST", mapOf("x-a" to "b"), "q"), r)
    }

    @Test
    fun `a report to another host is dropped`() {
        assertNull(WebSessionScripts.parseCapture("""{"url":"https://www.amazon.com/x"}""", config.allowedHosts))
        assertNull(WebSessionScripts.parseCapture("""{"url":"http://music.amazon.com/x"}""", config.allowedHosts))
    }

    @Test
    fun `a report with no body or method reads as a GET with none`() {
        val r = WebSessionScripts.parseCapture("""{"url":"https://music.amazon.com/a","body":null}""", config.allowedHosts)
        assertEquals("GET", r?.method)
        assertNull(r?.body)
    }

    @Test
    fun `something that isn't a report throws`() {
        assertFailsWith<Exception> { WebSessionScripts.parseCapture("not json", config.allowedHosts) }
        assertFailsWith<Exception> { WebSessionScripts.parseCapture("""{"method":"GET"}""", config.allowedHosts) }
    }
}
