package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vectors for the pure shadow-mode reconciliation (detect → merge → would-push). */
class NwayShadowTest {

    private fun copy(id: String, keys: List<String>, editedAt: Long, changed: Boolean) =
        NwayCopyState(id, keys, editedAt, changed)

    @Test
    fun noCopyChanged_returnsNull() {
        val base = listOf("a", "b")
        val plan = computeNwayShadowPlan(
            base,
            listOf(
                copy("local", base, 1, changed = false),
                copy("spotify", base, 1, changed = false),
            ),
        )
        assertNull(plan, "nothing changed → skip the playlist (perf short-circuit)")
    }

    @Test
    fun tokenBumpedButTracklistUnchanged_isNoDelta() {
        val base = listOf("a", "b")
        // Spotify's token bumped (changed=true) but its tracklist still == baseline
        // (e.g. a rename). No delta → null.
        val plan = computeNwayShadowPlan(
            base,
            listOf(copy("spotify", base, 9, changed = true)),
        )
        assertNull(plan)
    }

    @Test
    fun oneMirrorAdds_mergedHasIt_wouldPushToTheLaggards() {
        val base = listOf("a", "b")
        val plan = computeNwayShadowPlan(
            base,
            listOf(
                copy("local", base, 1, changed = false),            // unchanged → lags
                copy("spotify", listOf("a", "b", "x"), 9, changed = true),  // added x
                copy("applemusic", base, 1, changed = false),       // unchanged → lags
            ),
        )!!
        assertEquals(listOf("spotify"), plan.changedCopyIds)
        assertEquals(listOf("a", "b", "x"), plan.merged)
        // local + applemusic lag the merge and would be pushed; spotify already matches.
        assertEquals(listOf("local", "applemusic"), plan.wouldPushTo)
    }

    @Test
    fun deleteWins_inShadow_andKeeperWouldBePushed() {
        // Older copy deleted x; newer copy still has x. Delete wins → x dropped.
        val base = listOf("x", "z")
        val plan = computeNwayShadowPlan(
            base,
            listOf(
                copy("spotify", listOf("z"), 5, changed = true),            // deleted x
                copy("applemusic", listOf("x", "z", "y"), 9, changed = true), // kept x, added y
                copy("local", base, 1, changed = false),
            ),
        )!!
        assertEquals(listOf("z", "y"), plan.merged)
        // applemusic still has the dropped x (and lacks nothing else) → must be pushed;
        // local lags (no y, still has x) → pushed; spotify lacks y → pushed.
        assertTrue("applemusic" in plan.wouldPushTo)
        assertTrue("local" in plan.wouldPushTo)
        assertTrue("spotify" in plan.wouldPushTo)
    }

    @Test
    fun massChangeDropFraction_isComputed() {
        assertEquals(0.0, nwayBaselineDropFraction(emptyList(), emptyList()))
        assertEquals(0.0, nwayBaselineDropFraction(listOf("a", "b"), listOf("a", "b", "c")))
        assertEquals(0.5, nwayBaselineDropFraction(listOf("a", "b"), listOf("a")))
        assertEquals(1.0, nwayBaselineDropFraction(listOf("a", "b"), emptyList()))
    }
}
