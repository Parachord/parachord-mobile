package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginServiceUrlTest {
    @Test
    fun readsTheResolversOwnUrlField() {
        val bandcamp = """{"id":"bandcamp-1","title":"Love Is","bandcampUrl":"https://doveellis.bandcamp.com/track/love-is","purchaseUrl":"x"}"""
        assertEquals("https://doveellis.bandcamp.com/track/love-is", pluginServiceUrl(bandcamp, "bandcamp"))
        assertEquals("https://soundcloud.com/a/b", pluginServiceUrl("""{"soundcloudUrl":"https://soundcloud.com/a/b"}""", "soundcloud"))
    }

    @Test
    fun absentBlankOrUnreadableIsNull() {
        assertNull(pluginServiceUrl("""{"url":"https://x"}""", "bandcamp"))
        assertNull(pluginServiceUrl("""{"bandcampUrl":""}""", "bandcamp"))
        assertNull(pluginServiceUrl("""{"bandcampUrl":42}""", "bandcamp"))
        assertNull(pluginServiceUrl("not json", "bandcamp"))
    }
}
