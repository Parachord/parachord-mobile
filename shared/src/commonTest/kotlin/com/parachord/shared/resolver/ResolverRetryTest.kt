package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ResolverRetry]: a resolver that couldn't answer is asked again with a
 * back-off, and the retry's sources join what was already found (#566).
 */
class ResolverRetryTest {

    private fun src(resolver: String, art: String? = null, id: String? = null) =
        ResolvedSource(url = "u-$resolver", sourceType = resolver, resolver = resolver, appleMusicId = id, artworkUrl = art, confidence = 1.0)

    @Test fun `the retry waits double each time up to an hour`() {
        assertEquals(listOf(2L, 4L, 8L, 16L, 32L, 60L, 60L), (1..7).map { ResolverRetry.failedRetryDelayMs(it) / 60_000 })
    }

    @Test fun `a retried resolver source joins the list`() {
        val existing = listOf(src("applemusic", id = "1880091921"))
        val merged = ResolverRetry.merge(existing, ResolveReport(listOf(src("spotify", art = "s.jpg")), asked = setOf("spotify")))
        assertEquals(listOf("applemusic", "spotify"), merged.map { it.resolver })
    }

    @Test fun `a stored id stays and takes the retry artwork`() {
        val existing = listOf(src("applemusic", id = "1880091921"))
        val merged = ResolverRetry.merge(existing, ResolveReport(listOf(src("applemusic", art = "a.jpg", id = "999")), asked = setOf("applemusic")))
        assertEquals("1880091921", merged.single().appleMusicId)
        assertEquals("a.jpg", merged.single().artworkUrl)
    }

    @Test fun `a retry that finds nothing keeps what was there`() {
        val existing = listOf(src("applemusic", id = "1880091921"))
        val merged = ResolverRetry.merge(existing, ResolveReport(emptyList(), asked = setOf("spotify"), failed = setOf("spotify")))
        assertEquals(existing, merged)
    }

    @Test fun `only the ids a track carried is a failed resolve left over`() {
        assertEquals(true, ResolverRetry.isStoredIdOnly(listOf(src("applemusic", id = "1880091921"))))
    }

    @Test fun `a source a search found is not`() {
        assertEquals(false, ResolverRetry.isStoredIdOnly(listOf(src("applemusic", art = "a.jpg", id = "1"))))
        assertEquals(false, ResolverRetry.isStoredIdOnly(listOf(src("applemusic", id = "1"), src("bandcamp"))))
        assertEquals(false, ResolverRetry.isStoredIdOnly(emptyList()))
    }
}
