package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #592: one badge rule for both apps, as desktop draws them. */
class ResolverBadgesTest {
    private fun src(resolver: String, confidence: Double?, noMatch: Boolean = false) =
        ResolvedSource(url = "", sourceType = "stream", resolver = resolver, confidence = confidence, noMatch = noMatch)

    private val order = listOf("spotify", "applemusic", "bandcamp", "soundcloud", "localfiles")

    @Test
    fun aWrongSongMatchOrANoMatchShowsNoBadge() {
        val badges = ResolverBadges.badges(
            listOf(src("spotify", 0.50), src("applemusic", 0.95, noMatch = true), src("localfiles", 0.95), src("soundcloud", null)),
            order,
        )
        assertEquals(listOf("localfiles"), badges.map { it.resolver })
    }

    @Test
    fun theFloorItselfShows() {
        assertEquals(listOf("bandcamp"), ResolverBadges.badges(listOf(src("bandcamp", 0.60)), order).map { it.resolver })
    }

    @Test
    fun aResolverTheUserTurnedOffShowsNoBadge() {
        val sources = listOf(src("spotify", 1.0), src("applemusic", 0.95), src("bandcamp", 0.95))
        assertEquals(listOf("applemusic", "bandcamp"), ResolverBadges.badges(sources, order, disabled = listOf("spotify")).map { it.resolver })
        assertEquals(listOf("spotify", "bandcamp"), ResolverBadges.badges(sources, order, active = listOf("spotify", "bandcamp")).map { it.resolver })
        // An empty active list means every resolver is on.
        assertEquals(3, ResolverBadges.badges(sources, order, active = emptyList()).size)
    }

    @Test
    fun oneBadgePerResolverWithItsBestSource() {
        val badges = ResolverBadges.badges(listOf(src("soundcloud", 0.70), src("soundcloud", 0.95), src("soundcloud", 0.80)), order)
        assertEquals(listOf(ResolverBadge("soundcloud", 0.95)), badges)
    }

    @Test
    fun orderedByTheUsersOrderThenConfidenceAndUnknownResolversLast() {
        val badges = ResolverBadges.badges(
            listOf(src("qobuz", 1.0), src("localfiles", 0.95), src("spotify", 0.70), src("tidal", 0.95)),
            order,
        )
        assertEquals(listOf("spotify", "localfiles", "qobuz", "tidal"), badges.map { it.resolver })
        // With no order, confidence decides.
        assertEquals(listOf("qobuz", "localfiles", "spotify"),
            ResolverBadges.badges(listOf(src("spotify", 0.70), src("qobuz", 1.0), src("localfiles", 0.95)), emptyList()).map { it.resolver })
    }

    @Test
    fun dimmedAtOrBelowPointEight() {
        assertTrue(ResolverBadge("spotify", 0.80).dimmed)
        assertTrue(ResolverBadge("spotify", 0.65).dimmed)
        assertFalse(ResolverBadge("spotify", 0.81).dimmed)
        assertFalse(ResolverBadge("spotify", 0.95).dimmed)
    }

    @Test
    fun aRowWithNoPlayableSourceIsGreyedOut() {
        assertFalse(ResolverBadges.hasPlayableSource(listOf(src("spotify", 0.50), src("applemusic", 0.95, noMatch = true))))
        assertTrue(ResolverBadges.hasPlayableSource(listOf(src("spotify", 0.50), src("localfiles", 0.95))))
        assertFalse(ResolverBadges.hasPlayableSource(emptyList()))
    }
}
