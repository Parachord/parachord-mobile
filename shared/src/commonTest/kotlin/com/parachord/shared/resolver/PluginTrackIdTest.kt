package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A plugin resolver's own track id (`<resolverId>Id`) and the non-secret
 * placeholder URL its url-less results ride on (plan parachord-mobile#664).
 */
class PluginTrackIdTest {

    @Test
    fun `the id rides on the resolverId-Id key, as number or string`() {
        assertEquals("mf3", pluginTrackId("""{"subsonicId":"mf3","title":"Pyramid Song"}""", "subsonic"))
        assertEquals("42", pluginTrackId("""{"subsonicId":42}""", "subsonic"))
        assertNull(pluginTrackId("""{"subsonicId":""}""", "subsonic"))
        assertNull(pluginTrackId("""{"otherId":"x"}""", "subsonic"))
        assertNull(pluginTrackId("""{"subsonicId":true}""", "subsonic"))
        assertNull(pluginTrackId("not json", "subsonic"))
    }

    @Test
    fun `the placeholder URL round-trips the resolver and its track id`() {
        val url = pluginTrackUrl("subsonic", "mf3")
        assertEquals("subsonic:track:mf3", url)
        assertEquals("subsonic" to "mf3", parsePluginTrackUrl(url))
        assertEquals("foo-bar" to "x1", parsePluginTrackUrl(pluginTrackUrl("foo-bar", "x1")))
        assertEquals("x1", parsePluginTrackUrl(pluginTrackUrl("subsonic", "x1"))?.second)
    }

    @Test
    fun `other URLs don't parse as placeholders`() {
        assertNull(parsePluginTrackUrl("https://nav.example/rest/stream.view?id=1"))
        assertNull(parsePluginTrackUrl("applemusic:song:123"))
        assertNull(parsePluginTrackUrl("subsonic:album:al1"))
        assertNull(parsePluginTrackUrl(""))
        assertNull(parsePluginTrackUrl(":track:x"))
        assertNull(parsePluginTrackUrl("subsonic:track:"))
    }
}
