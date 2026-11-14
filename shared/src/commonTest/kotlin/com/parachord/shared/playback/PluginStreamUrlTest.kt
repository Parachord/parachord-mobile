package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The URL rules for `html5Stream` plugin streams (plan parachord-mobile#664):
 * HTTPS-only for now ([PluginStreamUrl.isPlayable] is the single place a
 * plain-http home-server allowance would go), and a logged stream URL never
 * carries its credentials.
 */
class PluginStreamUrlTest {

    @Test
    fun `an https URL plays`() {
        assertTrue(PluginStreamUrl.isPlayable("https://navidrome.example/rest/stream.view?id=mf3&u=test"))
    }

    @Test
    fun `a plain http URL does not play while the app is https-only`() {
        assertFalse(PluginStreamUrl.isPlayable("http://192.168.0.13:4533/rest/stream.view?id=mf3"))
        assertFalse(PluginStreamUrl.isPlayable("HTTP://navidrome.local:4533/rest/stream.view?id=mf3"))
    }

    @Test
    fun `anything else does not play`() {
        assertFalse(PluginStreamUrl.isPlayable("subsonic:track:mf3"))
        assertFalse(PluginStreamUrl.isPlayable(""))
        assertFalse(PluginStreamUrl.isPlayable("ftp://example.com/x.mp3"))
    }

    @Test
    fun `redact strips the credential query values and keeps the rest`() {
        assertEquals(
            "https://nav.example/rest/stream.view?id=mf3&u=test&t=***&s=***&v=1.16.1&c=parachord",
            PluginStreamUrl.redact("https://nav.example/rest/stream.view?id=mf3&u=test&t=abc123&s=ff99&v=1.16.1&c=parachord"),
        )
    }

    @Test
    fun `redact covers token and api key names in any case`() {
        assertEquals(
            "https://x.example/s?a=1&token=***&API_KEY=***",
            PluginStreamUrl.redact("https://x.example/s?a=1&token=sekrit&API_KEY=sekrit"),
        )
    }

    @Test
    fun `redact keeps the fragment and URLs without a query`() {
        assertEquals("https://x.example/s#f", PluginStreamUrl.redact("https://x.example/s#f"))
        assertEquals("https://x.example/s?a=1&t=***#frag", PluginStreamUrl.redact("https://x.example/s?a=1&t=z#frag"))
        assertEquals("https://x.example/s?", PluginStreamUrl.redact("https://x.example/s?"))
    }
}
