package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec for the probe-gated dead-mirror reconcile. The load-bearing property is
 * [confirmedGone] — a partial fetch must produce ZERO confirmed removals.
 */
class DeadMirrorReconcileTest {

    @Test
    fun suspectedGone_returns_only_tracked_ids_absent_from_remote() {
        val result = DeadMirrorReconcile.suspectedGone(
            localExternalIds = setOf("a", "b", "c"),
            bulkRemoteIds = setOf("b"),
        )
        assertEquals(setOf("a", "c"), result)
    }

    @Test
    fun confirmedGone_only_includes_ids_with_explicit_false_probe() {
        // a probed -> gone; b probed -> still exists; c never probed (uncertain)
        val result = DeadMirrorReconcile.confirmedGone(
            suspected = setOf("a", "b", "c"),
            probeExists = mapOf("a" to false, "b" to true),
        )
        assertEquals(setOf("a"), result)
    }

    @Test
    fun confirmedGone_is_empty_when_no_probe_confirms__partial_fetch_safety() {
        // Simulates a partial/truncated fetch: every locally-tracked id looks
        // absent, but no probe confirms deletion -> NOTHING is removed.
        val result = DeadMirrorReconcile.confirmedGone(
            suspected = setOf("a", "b", "c", "d"),
            probeExists = emptyMap(),
        )
        assertEquals(emptySet(), result)
    }

    @Test
    fun overrideAfterDetach_drops_dead_provider_keeps_other_live_mirrors() {
        val result = DeadMirrorReconcile.overrideAfterDetach(
            effectiveChannels = setOf("spotify", "applemusic", "listenbrainz"),
            deadProvider = "spotify",
        )
        assertEquals(setOf("applemusic", "listenbrainz"), result)
    }

    @Test
    fun overrideAfterDetach_is_empty_when_dead_was_the_only_channel() {
        val result = DeadMirrorReconcile.overrideAfterDetach(
            effectiveChannels = setOf("spotify"),
            deadProvider = "spotify",
        )
        assertEquals(emptySet(), result)
    }
}
