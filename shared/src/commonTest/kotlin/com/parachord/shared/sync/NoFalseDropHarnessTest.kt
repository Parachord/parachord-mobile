package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * No-false-drop / no-dup-add harness — the LAST identity gate before arming
 * N-way real writes (parachord#911 P3, #289 audit action #5). 1:1 with the
 * cross-engine contract `docs/no-false-drop-scenarios.json` (desktop runs the
 * same scenarios via playlist-materialize.js / playlist-reconcile.js).
 *
 * Beyond each scenario's pinned `expect`, the runner **independently enforces the
 * two invariants on every materialize case** so this proves the gate, not just
 * echoes the function:
 *  - NO-FALSE-DROP — no removeKey is the unified key of a canonical track.
 *  - NO-DUP-ADD    — no addKey is already a unified key on the remote.
 *
 * The D-pairs are shaped so a naive provider-id diff (or an un-guarded unify)
 * would violate an invariant; the unify-based diff does not.
 */
class NoFalseDropHarnessTest {

    private fun t(title: String, artist: String, isrc: String? = null, mbid: String? = null) =
        PlaylistTrack(
            playlistId = "p", position = 0, trackTitle = title, trackArtist = artist,
            trackIsrc = isrc, trackRecordingMbid = mbid,
        )

    private fun materialize(
        name: String,
        canonical: List<PlaylistTrack>,
        remote: List<PlaylistTrack>,
        addKeys: List<String>,
        removeKeys: List<String>,
        reorderNeeded: Boolean = false,
    ) {
        val diff = computeMaterializeDiff(canonical, remote)
        assertEquals(addKeys, diff.addKeys, "$name addKeys")
        assertEquals(removeKeys, diff.removeKeys, "$name removeKeys")
        assertEquals(reorderNeeded, diff.reorderNeeded, "$name reorderNeeded")
        // Invariants — enforced on EVERY case (prove the gate, don't echo the fn).
        val canSet = diff.canonicalKeys.toSet()
        val remSet = diff.remoteKeys.toSet()
        assertTrue(diff.removeKeys.none { it in canSet }, "$name NO-FALSE-DROP: a removeKey is a canonical key")
        assertTrue(diff.addKeys.none { it in remSet }, "$name NO-DUP-ADD: an addKey is already a remote key")
    }

    // ── materialize scenarios (audit D-pairs + controls) ────────────────────

    @Test fun relink_isrc_bridges_norm_drift() = materialize(
        "relink_isrc_bridges_norm_drift",
        canonical = listOf(t("Dreams - 2018 Remaster", "Fleetwood Mac", isrc = "USEE10001234")),
        remote = listOf(t("Dreams", "Fleetwood Mac", isrc = "USEE10001234")),
        addKeys = emptyList(), removeKeys = emptyList(),
    )

    @Test fun cross_service_same_isrc_no_dup_add() = materialize(
        "cross_service_same_isrc_no_dup_add",
        canonical = listOf(t("Bad Guy", "Billie Eilish", isrc = "USUM71900764", mbid = "rec-spotify")),
        remote = listOf(t("Bad Guy", "Billie Eilish", isrc = "USUM71900764", mbid = "rec-am")),
        addKeys = emptyList(), removeKeys = emptyList(),
    )

    @Test fun distinct_isrc_same_norm_no_false_drop() = materialize(
        "distinct_isrc_same_norm_no_false_drop",
        canonical = listOf(t("Intro", "The xx", isrc = "GBBKS0700116"), t("Intro", "The xx", isrc = "GBBKS1300999")),
        remote = listOf(t("Intro", "The xx", isrc = "GBBKS0700116"), t("Intro", "The xx", isrc = "GBBKS1300999")),
        addKeys = emptyList(), removeKeys = emptyList(),
    )

    @Test fun mbid_variance_same_norm_no_false_drop() = materialize(
        "mbid_variance_same_norm_no_false_drop",
        canonical = listOf(t("Creep", "Radiohead", mbid = "rec-a")),
        remote = listOf(t("Creep", "Radiohead", mbid = "rec-b")),
        addKeys = emptyList(), removeKeys = emptyList(),
    )

    @Test fun genuine_add_is_an_add() = materialize(
        "genuine_add_is_an_add",
        canonical = listOf(t("A", "x", isrc = "USAAA0000001"), t("B", "y", isrc = "USBBB0000002")),
        remote = listOf(t("A", "x", isrc = "USAAA0000001")),
        addKeys = listOf("isrc-USBBB0000002"), removeKeys = emptyList(),
    )

    @Test fun genuine_remove_with_evidence_is_a_remove() = materialize(
        "genuine_remove_with_evidence_is_a_remove",
        canonical = listOf(t("A", "x", isrc = "USAAA0000001")),
        remote = listOf(t("A", "x", isrc = "USAAA0000001"), t("B", "y", isrc = "USBBB0000002")),
        addKeys = emptyList(), removeKeys = listOf("isrc-USBBB0000002"),
    )

    // ── propagation scenarios (total-wipe guard) ────────────────────────────

    private fun copy(id: String, keys: List<String>, changed: Boolean, editedAt: Long = 5) =
        NwayCopyState(id = id, keys = keys, editedAt = editedAt, changed = changed)

    @Test fun total_wipe_aborts() {
        val plan = computeNwayPropagationPlan(
            baseline = listOf("isrc-USAAA0000001", "isrc-USBBB0000002"),
            copies = listOf(copy("sp", emptyList(), changed = true)),
            writableById = emptyMap(),
        )
        assertNotNull(plan)
        assertEquals(emptyList<String>(), plan.merged)
        assertTrue(plan.massChangeAbort)
    }

    @Test fun legit_large_drop_allowed() {
        val plan = computeNwayPropagationPlan(
            baseline = listOf("isrc-USAAA0000001", "isrc-USBBB0000002", "mbid-c", "mbid-d"),
            copies = listOf(copy("sp", listOf("isrc-USAAA0000001"), changed = true)),
            writableById = emptyMap(),
        )
        assertNotNull(plan)
        assertEquals(listOf("isrc-USAAA0000001"), plan.merged)
        assertEquals(false, plan.massChangeAbort)
    }

    @Test fun unchanged_copy_is_noop() {
        val plan = computeNwayPropagationPlan(
            baseline = listOf("isrc-USAAA0000001"),
            copies = listOf(copy("sp", listOf("isrc-USAAA0000001"), changed = false)),
            writableById = emptyMap(),
        )
        assertNull(plan)
    }
}
