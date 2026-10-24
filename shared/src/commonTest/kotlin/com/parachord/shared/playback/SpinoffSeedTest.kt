package com.parachord.shared.playback

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #397 — port of desktop's `resolveSpinoffSeed` (parachord#1005). Runs on both
 * the JVM and the iOS simulator, since both platforms call it.
 */
class SpinoffSeedTest {

    private fun lookup(result: String?): Pair<suspend (String) -> String?, () -> List<String>> {
        val calls = mutableListOf<String>()
        val fn: suspend (String) -> String? = { a -> calls += a; result }
        return fn to { calls.toList() }
    }

    @Test fun titlePresent_isUsedAsIs_andTheTopTrackIsNotLookedUp() = runTest {
        val (fn, calls) = lookup("When the Sun Hits")
        assertEquals(SpinoffSeed("Slowdive", "Alison"), resolveSpinoffSeed("Slowdive", "Alison", fn))
        assertEquals(emptyList(), calls())
    }

    @Test fun artistOnly_seedsFromTheArtistsTopTrack() = runTest {
        val (fn, calls) = lookup("When the Sun Hits")
        assertEquals(SpinoffSeed("Slowdive", "When the Sun Hits"), resolveSpinoffSeed("Slowdive", null, fn))
        assertEquals(listOf("Slowdive"), calls())
    }

    @Test fun blankTitle_isTreatedAsMissing() = runTest {
        val (fn, _) = lookup("When the Sun Hits")
        assertEquals(SpinoffSeed("Slowdive", "When the Sun Hits"), resolveSpinoffSeed("Slowdive", "  ", fn))
    }

    @Test fun noTopTrack_isNull() = runTest {
        assertNull(resolveSpinoffSeed("Nobody", null, lookup(null).first))
        assertNull(resolveSpinoffSeed("Nobody", null, lookup("   ").first))
    }

    @Test fun aLookupFailure_isNull_neverACrash() = runTest {
        assertNull(resolveSpinoffSeed("Slowdive", null) { error("Last.fm down") })
    }

    @Test fun blankArtist_isNull_withoutALookup() = runTest {
        val (fn, calls) = lookup("x")
        assertNull(resolveSpinoffSeed(null, "Alison", fn))
        assertNull(resolveSpinoffSeed("  ", null, fn))
        assertEquals(emptyList(), calls())
    }

    @Test fun noSeedMessage_matchesDesktopWording() {
        assertEquals("Couldn't find a track by Slowdive to seed the radio", noSpinoffSeedMessage("Slowdive"))
    }
}
