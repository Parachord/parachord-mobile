package com.parachord.shared.resolver

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverScoringTest {
    private fun scoring(order: List<String>, active: List<String> = emptyList()) =
        ResolverScoring(getResolverOrder = { order }, getActiveResolvers = { active })

    private fun src(resolver: String, conf: Double, title: String = "t", artist: String = "a") =
        ResolvedSource(
            url = "u://$resolver", sourceType = "stream", resolver = resolver,
            confidence = conf, matchedTitle = title, matchedArtist = artist,
        )

    @Test
    fun selectRanked_filters_below_floor() = runTest {
        val s = scoring(listOf("spotify", "soundcloud"))
        val ranked = s.selectRanked(listOf(src("spotify", 0.50), src("soundcloud", 0.95)))
        assertEquals(1, ranked.size)
        assertEquals("soundcloud", ranked.first().resolver)
    }

    @Test
    fun selectRanked_sorts_priority_then_confidence() = runTest {
        val s = scoring(listOf("spotify", "soundcloud"))
        // soundcloud higher confidence but lower priority than spotify
        val ranked = s.selectRanked(listOf(src("soundcloud", 0.99), src("spotify", 0.70)))
        assertEquals(listOf("spotify", "soundcloud"), ranked.map { it.resolver })
    }

    @Test
    fun selectRanked_respects_active_filter() = runTest {
        val s = scoring(listOf("spotify", "soundcloud"), active = listOf("soundcloud"))
        val ranked = s.selectRanked(listOf(src("spotify", 0.95), src("soundcloud", 0.95)))
        assertEquals(listOf("soundcloud"), ranked.map { it.resolver })
    }

    @Test
    fun selectBest_is_head_of_selectRanked() = runTest {
        val s = scoring(listOf("spotify", "soundcloud"))
        val sources = listOf(src("soundcloud", 0.99), src("spotify", 0.70))
        assertEquals(s.selectRanked(sources).firstOrNull(), s.selectBest(sources))
    }

    @Test
    fun selectRanked_empty_for_no_sources() = runTest {
        assertTrue(scoring(listOf("spotify")).selectRanked(emptyList()).isEmpty())
    }

    @Test
    fun selectRanked_preferred_resolver_wins() = runTest {
        val s = scoring(listOf("spotify", "soundcloud"))
        val ranked = s.selectRanked(
            listOf(src("spotify", 0.95), src("soundcloud", 0.95)),
            preferredResolver = "soundcloud",
        )
        assertEquals("soundcloud", ranked.first().resolver)
    }
}
