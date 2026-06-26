package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistMaterializeDiffTest {
    // Distinct title per track so the `norm` identity tier doesn't union
    // unrelated tracks (all-empty title+artist would share norm `"|"` and
    // collapse every track into one equivalence class). The mbid tier is the
    // strongest present, so the representative is still `mbid-<value>`.
    private fun t(mbid: String) =
        PlaylistTrack(
            playlistId = "p", position = 0,
            trackTitle = "title-$mbid", trackArtist = "artist-$mbid",
            trackRecordingMbid = mbid,
        )

    /** Expected representative key for a `t(mbid)` track (mbid tier, lowercased). */
    private fun reprOf(mbid: String) = "mbid-${mbid.lowercase()}"

    @Test
    fun `add-only when canonical has a track remote lacks`() {
        val d = computeMaterializeDiff(canonical = listOf(t("a"), t("b")), remote = listOf(t("a")))
        assertEquals(1, d.addKeys.size)
        assertEquals(reprOf("b"), d.addKeys.single())
        assertTrue(d.removeKeys.isEmpty())
        // The unified representative lists are 1:1 with their input lists (order + length) —
        // the executor zips them with the track lists, so this length contract is load-bearing.
        assertEquals(2, d.canonicalKeys.size)
        assertEquals(1, d.remoteKeys.size)
    }

    @Test
    fun `remove when remote has a track canonical dropped`() {
        val d = computeMaterializeDiff(canonical = listOf(t("a")), remote = listOf(t("a"), t("b")))
        assertEquals(1, d.removeKeys.size)
        assertTrue(d.addKeys.isEmpty())
    }

    @Test
    fun `norm-bridged same song (remaster suffix) is neither added nor removed`() {
        // Title drifts via a remaster suffix and mbid drifts (m1/m2), but the
        // Step-4 REMASTER_SUFFIX strip collapses BOTH titles to "zombie", so both
        // tracks land on the SAME norm ("x|zombie"). They unify via the NORM tier
        // through computeMaterializeDiff — the shared ISRC is NOT load-bearing here
        // (this guards the remaster-strip bridge, not the ISRC bridge).
        val can = PlaylistTrack(
            playlistId = "p", position = 0, trackTitle = "Zombie", trackArtist = "X",
            trackRecordingMbid = "m1",
        )
        val rem = PlaylistTrack(
            playlistId = "p", position = 0, trackTitle = "Zombie - 2025 Remaster", trackArtist = "X",
            trackRecordingMbid = "m2",
        )
        val d = computeMaterializeDiff(canonical = listOf(can), remote = listOf(rem))
        assertTrue(d.addKeys.isEmpty())
        assertTrue(d.removeKeys.isEmpty())
    }

    @Test
    fun `isrc-bridged same song (different mbid AND norm) is neither added nor removed`() {
        // The P2-incident case: the SAME recording with a DIFFERENT mbid (m1/m2)
        // AND a DIFFERENT norm — only the shared ISRC can bridge them.
        //
        // norm = "<artist.lower.trim>|<title.lower.trim, remaster-suffix stripped>".
        // The artist spelling drifts ("The Cranberries" vs "Cranberries"), and
        // REMASTER_SUFFIX is anchored to the TITLE end so it never touches the
        // artist. Verified norms therefore DIFFER:
        //   can → "the cranberries|zombie"
        //   rem → "cranberries|zombie"
        // mbid differs, norm differs ⇒ neither the mbid nor the norm tier bridges;
        // ONLY the shared ISRC unifies the two into one identity.
        val isrc = "USABC1234567"
        val can = PlaylistTrack(
            playlistId = "p", position = 0, trackTitle = "Zombie", trackArtist = "The Cranberries",
            trackRecordingMbid = "m1", trackIsrc = isrc,
        )
        val rem = PlaylistTrack(
            playlistId = "p", position = 0, trackTitle = "Zombie", trackArtist = "Cranberries",
            trackRecordingMbid = "m2", trackIsrc = isrc,
        )
        val d = computeMaterializeDiff(canonical = listOf(can), remote = listOf(rem))
        assertTrue(d.addKeys.isEmpty())
        assertTrue(d.removeKeys.isEmpty())

        // Negative control: the SAME fixture with DIFFERENT ISRCs no longer bridges
        // (mbid differs, norm differs, isrc now differs) ⇒ one add + one remove.
        // This proves the ISRC above is load-bearing — locks the test against a
        // future change that makes norm/mbid quietly bridge and masks the ISRC.
        val canNoIsrc = can.copy(trackIsrc = "USXYZ9999999")
        val remNoIsrc = rem.copy(trackIsrc = "GBABC0000001")
        val dControl = computeMaterializeDiff(canonical = listOf(canNoIsrc), remote = listOf(remNoIsrc))
        assertEquals(1, dControl.addKeys.size)
        assertEquals(1, dControl.removeKeys.size)
    }

    @Test
    fun `idempotent — equal sets produce no ops`() {
        val d = computeMaterializeDiff(canonical = listOf(t("a"), t("b")), remote = listOf(t("a"), t("b")))
        assertTrue(d.addKeys.isEmpty() && d.removeKeys.isEmpty() && !d.reorderNeeded)
    }

    @Test
    fun `reorder flagged when membership equal but order differs`() {
        val d = computeMaterializeDiff(canonical = listOf(t("a"), t("b")), remote = listOf(t("b"), t("a")))
        assertTrue(d.addKeys.isEmpty() && d.removeKeys.isEmpty() && d.reorderNeeded)
    }

    @Test
    fun `duplicate identity in canonical collapses to a single add`() {
        // Two t("a") share the mbid "a" → same representative. The diff must emit
        // it ONCE, or the executor double-adds the same track.
        val d = computeMaterializeDiff(canonical = listOf(t("a"), t("a")), remote = emptyList())
        assertEquals(1, d.addKeys.size)
        assertEquals(reprOf("a"), d.addKeys.single())
    }

    @Test
    fun `duplicate identity in remote collapses to a single remove`() {
        // Symmetric: remote has the same identity twice, canonical has none → ONE remove.
        val d = computeMaterializeDiff(canonical = emptyList(), remote = listOf(t("a"), t("a")))
        assertEquals(1, d.removeKeys.size)
        assertEquals(reprOf("a"), d.removeKeys.single())
        assertTrue(d.addKeys.isEmpty())
    }

    @Test
    fun `empty canonical and empty remote produce no ops`() {
        val d = computeMaterializeDiff(canonical = emptyList(), remote = emptyList())
        assertTrue(d.addKeys.isEmpty() && d.removeKeys.isEmpty() && !d.reorderNeeded)
    }

    @Test
    fun `empty remote — every canonical track is an add (fresh mirror first push)`() {
        val d = computeMaterializeDiff(canonical = listOf(t("a"), t("b")), remote = emptyList())
        assertEquals(2, d.addKeys.size)
        assertTrue(d.removeKeys.isEmpty())
    }
}
