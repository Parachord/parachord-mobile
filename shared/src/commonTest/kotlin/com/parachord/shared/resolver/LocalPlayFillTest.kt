package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Desktop's `tests/renderer/enrichment-gate.test.js` vectors, over source lists (#769). */
class LocalPlayFillTest {
    private fun src(resolver: String, confidence: Double = 0.95, noMatch: Boolean = false) =
        ResolvedSource(url = "u:$resolver", sourceType = "stream", resolver = resolver, confidence = confidence, noMatch = noMatch)

    @Test fun `only a local file needs a fill`() =
        assertTrue(LocalPlayFill.needsPlayFill(listOf(src("localfiles"))))

    @Test fun `a local file beside sources that can't play needs a fill`() =
        assertTrue(LocalPlayFill.needsPlayFill(listOf(
            src("localfiles"), src("spotify", noMatch = true), src("applemusic", confidence = 0.5),
        )))

    @Test fun `a local file beside a real source doesn't`() =
        assertFalse(LocalPlayFill.needsPlayFill(listOf(src("localfiles"), src("spotify"))))

    @Test fun `a streaming-only track doesn't`() =
        assertFalse(LocalPlayFill.needsPlayFill(listOf(src("spotify"))))

    @Test fun `no sources don't`() = assertFalse(LocalPlayFill.needsPlayFill(emptyList()))

    @Test fun `a local play with no cache entry is filled`() =
        assertTrue(LocalPlayFill.shouldFill("localfiles", null))

    @Test fun `a local play whose cache has only misses is filled`() =
        assertTrue(LocalPlayFill.shouldFill("localfiles", listOf(src("spotify", confidence = 0.5))))

    @Test fun `a local play that already has another source isn't`() =
        assertFalse(LocalPlayFill.shouldFill("localfiles", listOf(src("localfiles"), src("applemusic"))))

    @Test fun `a streaming play isn't`() = assertFalse(LocalPlayFill.shouldFill("spotify", null))

    @Test fun `merge keeps what the track has and adds new resolvers`() {
        val local = src("localfiles")
        val merged = LocalPlayFill.mergeFoundSources(listOf(local), listOf(src("localfiles", 0.99), src("spotify")))
        assertEquals(listOf("localfiles", "spotify"), merged.map { it.resolver })
        assertEquals(local, merged.first())
    }

    @Test fun `merge drops misses and below-floor finds`() {
        val merged = LocalPlayFill.mergeFoundSources(
            listOf(src("localfiles")),
            listOf(src("spotify", noMatch = true), src("applemusic", confidence = 0.5), src("soundcloud")),
        )
        assertEquals(listOf("localfiles", "soundcloud"), merged.map { it.resolver })
    }

    @Test fun `merge with nothing current takes the playable finds`() =
        assertEquals(listOf("spotify"), LocalPlayFill.mergeFoundSources(null, listOf(src("spotify"))).map { it.resolver })

    @Test fun `a real find replaces nothing but fills a resolver that only missed`() {
        val merged = LocalPlayFill.mergeFoundSources(listOf(src("spotify", confidence = 0.5)), listOf(src("spotify")))
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.resolver == "spotify" && ResolverBadges.isPlayable(it) })
    }

    @Test fun `the tracker claims a key once`() {
        val t = PlayFillTracker()
        assertTrue(t.claim("a|b"))
        assertFalse(t.claim("a|b"))
        assertTrue(t.claim("c|d"))
    }
}
