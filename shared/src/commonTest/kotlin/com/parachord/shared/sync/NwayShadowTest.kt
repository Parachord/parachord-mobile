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

    // ── propagation plan (Phase 4) ───────────────────────────────────

    @Test
    fun propagation_excludesNonWritableSourceFromPushTargets() {
        // Spotify changed (added x); local + the followed-Spotify source lag.
        // But Spotify is the read-only source (writable=false) → never pushed
        // back to. local IS pushed (writable).
        val base = listOf("a", "b")
        val plan = computeNwayPropagationPlan(
            base,
            listOf(
                copy("local", base, 1, changed = false),
                copy("spotify", listOf("a", "b", "x"), 9, changed = true),
            ),
            writableById = mapOf("spotify" to false, "local" to true),
            massChangeThreshold = 0.5,
        )!!
        assertEquals(listOf("a", "b", "x"), plan.merged)
        assertEquals(listOf("local"), plan.pushTargets) // spotify excluded (non-writable source)
        assertEquals(false, plan.massChangeAbort)
    }

    @Test
    fun propagation_writableMirrorsArePushed() {
        val base = listOf("a", "b")
        val plan = computeNwayPropagationPlan(
            base,
            listOf(
                copy("local", listOf("a", "b", "x"), 9, changed = true), // added x
                copy("applemusic", base, 1, changed = false),            // lags
                copy("listenbrainz", base, 1, changed = false),          // lags
            ),
            writableById = emptyMap(), // all default writable
            massChangeThreshold = 0.5,
        )!!
        assertEquals(listOf("applemusic", "listenbrainz"), plan.pushTargets)
    }

    @Test
    fun propagation_massChangeAbortsWhenDropExceedsThreshold() {
        // Spotify removed 2 of 3 baseline tracks → 66% drop > 25% threshold.
        val base = listOf("a", "b", "c")
        val plan = computeNwayPropagationPlan(
            base,
            listOf(copy("spotify", listOf("a"), 9, changed = true)),
            writableById = emptyMap(),
            massChangeThreshold = 0.25,
        )!!
        assertTrue(plan.massChangeAbort)
    }

    @Test
    fun propagation_nullWhenNothingChanged() {
        val base = listOf("a", "b")
        assertNull(
            computeNwayPropagationPlan(
                base,
                listOf(copy("local", base, 1, changed = false)),
                emptyMap(),
                0.25,
            ),
        )
    }

    @Test
    fun massChangeDropFraction_isComputed() {
        assertEquals(0.0, nwayBaselineDropFraction(emptyList(), emptyList()))
        assertEquals(0.0, nwayBaselineDropFraction(listOf("a", "b"), listOf("a", "b", "c")))
        assertEquals(0.5, nwayBaselineDropFraction(listOf("a", "b"), listOf("a")))
        assertEquals(1.0, nwayBaselineDropFraction(listOf("a", "b"), emptyList()))
    }
}
