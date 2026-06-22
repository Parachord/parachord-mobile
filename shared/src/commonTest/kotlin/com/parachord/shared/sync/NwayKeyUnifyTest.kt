package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vectors for cross-copy key unification — 1:1 with
 * `docs/nway-key-unify-fixtures.json` (the desktop JS port runs the same cases).
 */
class NwayKeyUnifyTest {

    private fun k(isrc: String? = null, mbid: String? = null, norm: String) = TrackKeys(isrc, mbid, norm)

    @Test
    fun singleton_yieldsCanonicalKey() {
        // No bridging → same key canonicalTrackKey would produce.
        val out = unifyTrackKeys(listOf(listOf(k(mbid = "x", norm = "a|t"), k(norm = "b|t"))))
        assertEquals(listOf(listOf("mbid-x", "norm-b|t")), out)
    }

    @Test
    fun normBridges_unenrichedTrack_toItsMbidTwin() {
        // baseline has the track with an MBID; a copy has the SAME song norm-only
        // (un-enriched). They unify → both become the mbid- representative.
        val baseline = listOf(k(mbid = "x", norm = "radiohead|creep"))
        val copy = listOf(k(norm = "radiohead|creep"))
        val out = unifyTrackKeys(listOf(baseline, copy))
        assertEquals(listOf(listOf("mbid-x"), listOf("mbid-x")), out)
    }

    @Test
    fun normBridges_recordingMbidVariance() {
        // Same song, two DIFFERENT recording MBIDs across services → unify via norm.
        val local = listOf(k(mbid = "rec-a", norm = "song|x"))
        val lb = listOf(k(mbid = "rec-b", norm = "song|x"))
        val out = unifyTrackKeys(listOf(local, lb))
        // Representative = min mbid across the class.
        assertEquals(listOf(listOf("mbid-rec-a"), listOf("mbid-rec-a")), out)
    }

    @Test
    fun isrcBridges_whenMbidAndNormBothDiffer() {
        // THE incident case (reconciliation redesign Step 1): the SAME recording
        // across services with a DIFFERENT recording-MBID AND a drifted normalized
        // title (remaster suffix) — neither mbid nor norm matches, so only the
        // ISRC can bridge them. Without the now-persisted isrc tier these were
        // treated as different tracks → union-removed → DATA LOSS.
        val baseline = listOf(k(isrc = "usabc1234567", mbid = "rec-a", norm = "the cranberries|zombie"))
        val lbCopy = listOf(k(isrc = "usabc1234567", mbid = "rec-b", norm = "the cranberries|zombie - 2025 remastered"))
        val out = unifyTrackKeys(listOf(baseline, lbCopy))
        // Both collapse to the isrc- representative (strongest tier) → ONE track,
        // so the merge can never see the baseline track as "removed".
        assertEquals(listOf(listOf("isrc-usabc1234567"), listOf("isrc-usabc1234567")), out)
    }

    @Test
    fun isrcIsStrongestRepresentative() {
        val a = listOf(k(isrc = "USRC17607839", mbid = "m1", norm = "n|n"))
        val b = listOf(k(mbid = "m2", norm = "n|n")) // bridges via norm
        val out = unifyTrackKeys(listOf(a, b))
        assertEquals(listOf(listOf("isrc-USRC17607839"), listOf("isrc-USRC17607839")), out)
    }

    @Test
    fun isrcMatch_unifiesEvenWhenNormDiffers() {
        val a = listOf(k(isrc = "GBAYE0601477", norm = "radiohead|creep"))
        val b = listOf(k(isrc = "GBAYE0601477", norm = "radiohead|creep - remaster"))
        val out = unifyTrackKeys(listOf(a, b))
        assertEquals(listOf(listOf("isrc-GBAYE0601477"), listOf("isrc-GBAYE0601477")), out)
    }

    @Test
    fun distinctTracks_stayDistinct() {
        val a = listOf(k(mbid = "x", norm = "artist|one"))
        val b = listOf(k(mbid = "y", norm = "artist|two"))
        val out = unifyTrackKeys(listOf(a, b))
        assertEquals(listOf(listOf("mbid-x"), listOf("mbid-y")), out)
    }

    @Test
    fun guitarmageddon_likeScenario_collapsesFalseDrop() {
        // baseline: a mix of mbid + norm keys (un-enriched locals).
        val baseline = listOf(
            k(norm = "nirvana|about a girl"),          // un-enriched
            k(mbid = "rec-1", norm = "pearl jam|alive"),
            k(mbid = "rec-2", norm = "tool|forty six & 2"),
        )
        // LB (MBID-native): same 3 songs, but the un-enriched one now has an MBID,
        // and one has recording variance.
        val lb = listOf(
            k(mbid = "rec-9", norm = "nirvana|about a girl"), // norm bridges to baseline norm-only
            k(mbid = "rec-1", norm = "pearl jam|alive"),       // mbid match
            k(mbid = "rec-2b", norm = "tool|forty six & 2"),   // variance → norm bridge
        )
        val (b, l) = unifyTrackKeys(listOf(baseline, lb))
        // Both copies map to the SAME representative set → merge sees no false drop.
        assertEquals(b.toSet(), l.toSet())
        assertEquals(3, b.toSet().size)
    }

    @Test
    fun orderIndependent_determinism() {
        val one = unifyTrackKeys(
            listOf(
                listOf(k(mbid = "b", norm = "s|x"), k(mbid = "a", norm = "s|x")),
            ),
        )
        val two = unifyTrackKeys(
            listOf(
                listOf(k(mbid = "a", norm = "s|x"), k(mbid = "b", norm = "s|x")),
            ),
        )
        // Same component, representative = min mbid "a", regardless of input order.
        assertEquals(listOf("mbid-a", "mbid-a"), one.single())
        assertEquals(listOf("mbid-a", "mbid-a"), two.single())
    }

    @Test
    fun feedsPlaylistMerge_noFalseRemove() {
        // End-to-end: unify then merge. baseline norm-only; LB has the mbid twin
        // plus an added track. Without unification the merge would drop the
        // baseline track (false remove); with it, only the real add applies.
        val baseline = listOf(k(norm = "a|t"), k(mbid = "x", norm = "b|t"))
        val lb = listOf(k(mbid = "m-a", norm = "a|t"), k(mbid = "x", norm = "b|t"), k(mbid = "y", norm = "c|t"))
        val unified = unifyTrackKeys(listOf(baseline, lb))
        val mergedBaseline = unified[0]
        val merged = PlaylistMerge.merge(
            mergedBaseline,
            listOf(PlaylistMergeCopy("listenbrainz", unified[1], editedAt = 5)),
        )
        // a|t survives (bridged, not dropped); c|t added. Nothing falsely removed.
        assertEquals(setOf("mbid-m-a", "mbid-x", "mbid-y"), merged.toSet())
    }
}
