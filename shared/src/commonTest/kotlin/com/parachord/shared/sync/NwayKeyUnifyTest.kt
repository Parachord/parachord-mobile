package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vectors for cross-copy key unification — 1:1 with
 * `docs/nway-key-unify-fixtures.json` (the desktop JS port runs the same cases).
 */
class NwayKeyUnifyTest {

    private fun k(isrc: String? = null, mbid: String? = null, norm: String) = TrackKeys(isrc, mbid, norm)

    // ── Step 4: conservative remaster-strip in the norm tier ──────────
    // The no-ISRC bridge for the LB axis (LB playlists carry no per-track ISRC),
    // which is what the data-loss incident actually needed.

    @Test
    fun trackKeysOf_stripsRemasterAnnotations() {
        val variants = listOf(
            "Zombie",
            "Zombie - 2025 Remastered",
            "Zombie - Remastered",
            "Zombie (Remastered)",
            "Zombie (Remastered 2011)",
            "Zombie (2011 Remaster)",
        )
        val norms = variants.map { trackKeysOf(isrc = null, recordingMbid = null, artist = "The Cranberries", title = it).norm }
        // Every remaster variant collapses to the bare-title norm → they unify.
        norms.forEach { assertEquals("the cranberries|zombie", it) }
    }

    @Test
    fun trackKeysOf_preservesGenuinelyDifferentRecordings() {
        // Conservative: Live / Acoustic / feat. are NOT stripped — different
        // recordings the user may legitimately keep both of.
        fun normOf(title: String) = trackKeysOf(null, null, "Oasis", title).norm
        assertEquals("oasis|wonderwall - live", normOf("Wonderwall - Live"))
        assertEquals("oasis|wonderwall (acoustic)", normOf("Wonderwall (Acoustic)"))
        assertEquals("oasis|wonderwall (feat. someone)", normOf("Wonderwall (feat. Someone)"))
    }

    @Test
    fun unify_bridgesRemasterDriftAcrossServices_viaNorm() {
        // THE LB-axis incident: baseline has the original recording (mbid-A, plain
        // title); the LB copy has a DIFFERENT recording-mbid + a remaster title and
        // NO ISRC (LB playlists don't expose per-track ISRC). The remaster-strip
        // makes both norms equal → they unify → the baseline track can't be
        // union-removed as "deleted".
        val baseline = listOf(trackKeysOf(null, "rec-a", "The Cranberries", "Zombie"))
        val lbCopy = listOf(trackKeysOf(null, "rec-b", "The Cranberries", "Zombie - 2025 Remastered"))
        val out = unifyTrackKeys(listOf(baseline, lbCopy))
        // One equivalence class → same representative (min mbid).
        assertEquals(out[0], out[1])
    }

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
        val baseline = listOf(k(isrc = "USABC1234567", mbid = "rec-a", norm = "the cranberries|zombie"))
        val lbCopy = listOf(k(isrc = "USABC1234567", mbid = "rec-b", norm = "the cranberries|zombie - 2025 remastered"))
        val out = unifyTrackKeys(listOf(baseline, lbCopy))
        // Both collapse to the isrc- representative (strongest tier) → ONE track,
        // so the merge can never see the baseline track as "removed".
        assertEquals(listOf(listOf("isrc-USABC1234567"), listOf("isrc-USABC1234567")), out)
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

    // ── P3: norm-bridge guard (parachord#911) — disagreeing ISRCs never collapse
    //    via norm; MBID disagreement still bridges (the variance fix above). 1:1
    //    with the guard_* cases in docs/nway-key-unify-fixtures.json. ────────────

    @Test
    fun guard_disagreeingIsrc_sameNorm_staySeparate() {
        // D-Nway-4: two "Intro" by The xx — album cut vs single edit, different ISRCs.
        val out = unifyTrackKeys(listOf(listOf(
            k(isrc = "GBBKS0700116", norm = "the xx|intro"),
            k(isrc = "GBBKS1300999", norm = "the xx|intro"),
        )))
        assertEquals(listOf(listOf("isrc-GBBKS0700116", "isrc-GBBKS1300999")), out)
    }

    @Test
    fun guard_originalVsRemaster_disagreeingIsrc_staySeparate() {
        // D-Nway-5: same norm after remaster strip, different ISRCs → distinct.
        val out = unifyTrackKeys(listOf(listOf(
            k(isrc = "GBN9Y1100123", norm = "pink floyd|wish you were here"),
            k(isrc = "GBN9Y1100777", norm = "pink floyd|wish you were here"),
        )))
        assertEquals(listOf(listOf("isrc-GBN9Y1100123", "isrc-GBN9Y1100777")), out)
    }

    @Test
    fun guard_normOnly_bridgesToIsrcTwin() {
        val out = unifyTrackKeys(listOf(
            listOf(k(isrc = "USUM71900764", norm = "billie eilish|bad guy")),
            listOf(k(norm = "billie eilish|bad guy")),
        ))
        assertEquals(listOf(listOf("isrc-USUM71900764"), listOf("isrc-USUM71900764")), out)
    }

    @Test
    fun guard_twoIsrcsPlusWeak_weakDoesNotBridgeThem() {
        // Transitivity: the norm-only node must not merge the two ISRC components.
        val out = unifyTrackKeys(listOf(listOf(
            k(isrc = "XXAAA0000001", norm = "a|b"),
            k(isrc = "XXBBB0000002", norm = "a|b"),
            k(norm = "a|b"),
        )))
        assertEquals(listOf(listOf("isrc-XXAAA0000001", "isrc-XXBBB0000002", "norm-a|b")), out)
    }

    @Test
    fun guard_pureWeakGroup_unions() {
        val out = unifyTrackKeys(listOf(listOf(k(norm = "a|b"), k(norm = "a|b"))))
        assertEquals(listOf(listOf("norm-a|b", "norm-a|b")), out)
    }

    @Test
    fun guard_twoIsrcsPlusTwoWeak_weakUnite_isrcsSeparate() {
        val out = unifyTrackKeys(listOf(listOf(
            k(isrc = "XXAAA0000001", norm = "a|b"),
            k(isrc = "XXBBB0000002", norm = "a|b"),
            k(norm = "a|b"),
            k(norm = "a|b"),
        )))
        assertEquals(
            listOf(listOf("isrc-XXAAA0000001", "isrc-XXBBB0000002", "norm-a|b", "norm-a|b")),
            out,
        )
    }

    @Test
    fun guard_mbidBridgedTwoIsrcs_countComponentsNotValues_unions() {
        // Maintainer's V10: a shared MBID merges two ISRCs into ONE component →
        // cIsrc=1 → the whole group (incl the norm-only node) unions. A
        // distinct-ISRC-value count would get 2 and wrongly separate.
        val m = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val out = unifyTrackKeys(listOf(listOf(
            k(isrc = "XXAAA0000001", mbid = m, norm = "a|b"),
            k(isrc = "XXBBB0000002", mbid = m, norm = "a|b"),
            k(norm = "a|b"),
        )))
        assertEquals(listOf(listOf("isrc-XXAAA0000001", "isrc-XXAAA0000001", "isrc-XXAAA0000001")), out)
    }

    @Test
    fun guard_mbidBridgedPairPlusUnrelatedIsrc_separate() {
        val m = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val out = unifyTrackKeys(listOf(listOf(
            k(isrc = "XXAAA0000001", mbid = m, norm = "a|b"),
            k(isrc = "XXBBB0000002", mbid = m, norm = "a|b"),
            k(isrc = "XXCCC0000003", norm = "a|b"),
        )))
        assertEquals(listOf(listOf("isrc-XXAAA0000001", "isrc-XXAAA0000001", "isrc-XXCCC0000003")), out)
    }

    @Test
    fun guard_mbidBridgedNode_isNotAWeakNormBridge_readingC() {
        // desktop #944 discriminator (B vs C): #1 is mbid-only (node-ISRC-free) but
        // its COMPONENT carries ISRC AAA via the shared MBID (from #0 in a different
        // norm group "a|one"), so #1 must NOT bridge the pure-norm #3 into AAA.
        // Reading (C): weak set + ISRC count are component-level + global → #3 stays
        // norm-only. (Reading (B) would give #3 -> isrc-AAA.)
        val out = unifyTrackKeys(listOf(listOf(
            k(isrc = "XXAAA0000001", mbid = "m-shared", norm = "a|one"),
            k(mbid = "m-shared", norm = "a|two"),
            k(isrc = "XXBBB0000002", norm = "a|two"),
            k(norm = "a|two"),
        )))
        assertEquals(
            listOf(listOf("isrc-XXAAA0000001", "isrc-XXAAA0000001", "isrc-XXBBB0000002", "norm-a|two")),
            out,
        )
    }

    @Test
    fun guard_mbidOnlyNode_notBridgedToIsrc_joinsWeakSet_readingNotA() {
        // Rules out reading (A): an mbid-only node NOT bridged to any ISRC is still
        // weak and unions with the pure-norm node (repr = its mbid).
        val out = unifyTrackKeys(listOf(listOf(
            k(isrc = "XXAAA0000001", norm = "a|b"),
            k(isrc = "XXBBB0000002", norm = "a|b"),
            k(mbid = "m-weak", norm = "a|b"),
            k(norm = "a|b"),
        )))
        assertEquals(
            listOf(listOf("isrc-XXAAA0000001", "isrc-XXBBB0000002", "mbid-m-weak", "mbid-m-weak")),
            out,
        )
    }
}
