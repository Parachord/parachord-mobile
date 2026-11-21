package com.parachord.shared.resolver

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one "not playing" list (#707) leaves a plug-in out of what plays (source
 * selection, badges, the switcher) while the active list stays as it was:
 * an empty active list still means every resolver.
 */
class NotPlayingSelectionTest {
    private fun src(resolver: String, confidence: Double = 0.95) =
        ResolvedSource(url = "u:$resolver", sourceType = "stream", resolver = resolver, confidence = confidence)

    private val sources = listOf(src("amazonmusic"), src("spotify"), src("bandcamp"))
    private val order = listOf("amazonmusic", "spotify", "bandcamp")

    @Test
    fun `selection skips a plug-in that doesn't play with an empty active list`() = runTest {
        val scoring = ResolverScoring({ order }, { emptyList() }, { setOf("amazonmusic") })
        assertEquals(listOf("spotify", "bandcamp"), scoring.selectRanked(sources).map { it.resolver })
        assertEquals("spotify", scoring.selectBest(sources)?.resolver)
    }

    @Test
    fun `badges and the playable check leave it out`() {
        assertEquals(listOf("spotify", "bandcamp"), ResolverBadges.badges(sources, order, notPlaying = listOf("amazonmusic")).map { it.resolver })
        assertFalse(ResolverBadges.hasPlayableSource(listOf(src("amazonmusic")), listOf("amazonmusic")))
        assertTrue(ResolverBadges.hasPlayableSource(listOf(src("amazonmusic")), emptyList()))
    }

    @Test
    fun `the source switcher doesn't offer it`() {
        assertEquals(listOf("spotify", "bandcamp"), SourceReselection.switchableSources(sources, order, listOf("amazonmusic")).map { it.resolver })
    }
}
