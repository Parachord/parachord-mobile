package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverCacheCodecTest {
    private val spotify = ResolvedSource(url = "spotify:track:1", sourceType = "spotify", resolver = "spotify", confidence = 0.95)

    @Test
    fun failedResolvesAreNotWrittenToDisk() {
        val blob = ResolverCacheCodec.encode(mapOf("a|found|" to listOf(spotify), "b|rate-limited|" to emptyList()))
        assertEquals(setOf("a|found|"), ResolverCacheCodec.decode(blob).keys)
    }

    @Test
    fun emptyEntriesAnOlderBuildWroteAreDroppedOnLoad() {
        val old = """{"a|found|":[{"url":"spotify:track:1","sourceType":"spotify","resolver":"spotify"}],"b|failed|":[]}"""
        val loaded = ResolverCacheCodec.decode(old)
        assertEquals(setOf("a|found|"), loaded.keys)
        assertEquals("spotify", loaded.getValue("a|found|").single().resolver)
    }

    @Test
    fun anUnreadableBlobLoadsAsEmpty() {
        assertTrue(ResolverCacheCodec.decode("not json").isEmpty())
    }
}
