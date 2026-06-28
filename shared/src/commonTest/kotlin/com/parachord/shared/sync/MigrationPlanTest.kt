package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-parity with desktop `tests/sync/migration-plan.test.js` — the migration
 * preview shaper (parachord-mobile#289 brick #5 / parachord#911).
 */
class MigrationPlanTest {

    private fun wouldPush(localId: String, displayName: String, perTarget: List<MigrationPerTarget>) =
        MigrationReconcileResult(status = "would-push", localId = localId, displayName = displayName, perTarget = perTarget)

    @Test
    fun all_noop_fleet_nothing_changed_or_protected() {
        val s = summarizeMigrationPlan(MigrationShadowOutput(results = emptyList(), cycles = 20))
        assertFalse(s.hasChanges)
        assertFalse(s.hasRemoves)
        assertEquals(20, s.noopCount)
        assertEquals(0, s.totalAdds)
        assertEquals(0, s.totalRemoves)
    }

    @Test
    fun classifies_adds_removes_and_tallies_totals() {
        val s = summarizeMigrationPlan(
            MigrationShadowOutput(
                cycles = 3,
                results = listOf(
                    wouldPush(
                        "spotify-a", "Road trip",
                        listOf(MigrationPerTarget("spotify", addTracks = listOf(PreviewTrack("A", "x"), PreviewTrack("B", "y")))),
                    ),
                    wouldPush(
                        "applemusic-b", "Chill mix",
                        listOf(MigrationPerTarget("applemusic", removeTracks = listOf(PreviewTrack("K", "z")))),
                    ),
                ),
            ),
        )
        assertTrue(s.hasChanges)
        assertEquals(2, s.changed.size)
        assertEquals(2, s.totalAdds)
        assertEquals(1, s.totalRemoves)
        assertTrue(s.hasRemoves)
        assertEquals(1, s.noopCount) // 3 cycles − 2 non-noop results
        assertEquals(2, s.changed[0].providers[0].adds.size)
        assertEquals(PreviewTrack("K", "z"), s.changed[1].providers[0].removes[0])
    }

    @Test
    fun empty_diff_would_push_is_not_a_change() {
        val s = summarizeMigrationPlan(
            MigrationShadowOutput(
                cycles = 1,
                results = listOf(wouldPush("spotify-a", "Empty diff", listOf(MigrationPerTarget("spotify")))),
            ),
        )
        assertFalse(s.hasChanges)
        assertEquals(0, s.changed.size)
    }

    @Test
    fun safety_aborts_surface_as_protected() {
        val s = summarizeMigrationPlan(
            MigrationShadowOutput(
                cycles = 2,
                results = listOf(
                    MigrationReconcileResult("total-wipe-abort", "spotify-w", "Liked songs"),
                    MigrationReconcileResult("partial-abort", "applemusic-p", "Deep cuts"),
                ),
            ),
        )
        assertFalse(s.hasChanges)
        assertEquals(2, s.protectedList.size)
        assertEquals(MigrationProtectedPlaylist("spotify-w", "Liked songs", "total-wipe"), s.protectedList[0])
        assertEquals("partial", s.protectedList[1].reason)
    }

    @Test
    fun counts_errors() {
        val s = summarizeMigrationPlan(MigrationShadowOutput(results = emptyList(), cycles = 1, errors = listOf("boom")))
        assertEquals(1, s.errorCount)
    }

    private fun summaryWithOneRemove() = MigrationPlanSummary(
        changed = listOf(
            MigrationChangedPlaylist(
                "applemusic-x", "Chill mix",
                listOf(MigrationProviderDiff("applemusic", adds = emptyList(), removes = listOf(PreviewTrack("Khruangbin", "May ninth")))),
            ),
        ),
        protectedList = emptyList(), noopCount = 0, totalAdds = 0, totalRemoves = 1,
        hasRemoves = true, hasChanges = true, errorCount = 0, cycles = 1,
    )

    @Test
    fun report_has_title_label_and_github_url() {
        val (title, body, githubUrl) = buildMigrationReport(summaryWithOneRemove(), "1.2.3")
        assertTrue(title.contains("1 remove"))
        assertTrue(title.contains("1.2.3"))
        assertTrue(body.contains("Khruangbin — May ninth"))
        assertTrue(body.contains("App version: 1.2.3"))
        assertTrue(githubUrl.startsWith("https://github.com/Parachord/parachord-mobile/issues/new?"))
        assertTrue(githubUrl.contains("title=${encodeUriComponent(title)}"))
        assertTrue(githubUrl.contains("labels=sync"))
    }

    @Test
    fun report_tolerates_missing_version_and_empty_summary() {
        val empty = MigrationPlanSummary(emptyList(), emptyList(), 0, 0, 0, false, false, 0, 0)
        val report = buildMigrationReport(empty, "")
        assertTrue(report.body.contains("App version: unknown"))
        assertTrue(report.githubUrl.startsWith("https://github.com/"))
    }

    // ── buildMigrationPerTarget (Piece B): diff keys → named tracks ─────────

    private fun pt(title: String, artist: String, isrc: String? = null) =
        PlaylistTrack(playlistId = "p", position = 0, trackTitle = title, trackArtist = artist, trackIsrc = isrc)

    @Test
    fun perTarget_names_adds_from_canonical_and_removes_from_remote() {
        val merged = listOf(pt("Song A", "Artist A", "USAAA0000001"), pt("Song B", "Artist B", "USBBB0000002"))
        val remote = listOf(pt("Song A", "Artist A", "USAAA0000001"), pt("Song C", "Artist C", "USCCC0000003"))
        val d = buildMigrationPerTarget("spotify", merged, remote)
        assertEquals("spotify", d.providerId)
        assertEquals(listOf(PreviewTrack("Artist B", "Song B")), d.addTracks)
        assertEquals(listOf(PreviewTrack("Artist C", "Song C")), d.removeTracks)
    }

    @Test
    fun perTarget_is_empty_when_identical() {
        val tracks = listOf(pt("Song A", "Artist A", "USAAA0000001"))
        val d = buildMigrationPerTarget("applemusic", tracks, tracks)
        assertTrue(d.addTracks.isEmpty())
        assertTrue(d.removeTracks.isEmpty())
    }

    @Test
    fun perTarget_bridges_identity_no_churny_add_remove() {
        // Same recording (same ISRC), drifted title across services → matches via the
        // unify, so it's NOT a spurious remove-old + add-new.
        val merged = listOf(pt("Creep - 2008 Remaster", "Radiohead", "GBAYE0601477"))
        val remote = listOf(pt("Creep", "Radiohead", "GBAYE0601477"))
        val d = buildMigrationPerTarget("spotify", merged, remote)
        assertTrue(d.addTracks.isEmpty())
        assertTrue(d.removeTracks.isEmpty())
    }

    // ── resolvePreviewRemoteTracks (#298): a failed fetch is NOT an empty remote ──

    @Test
    fun preview_remote_skips_target_on_failed_fetch() = runTest {
        // A 429 / network throw must surface as null (caller skips the target), NOT an
        // empty list — else every canonical track becomes a phantom "add" (the #298
        // 649-spurious-adds bug).
        val r = resolvePreviewRemoteTracks(
            changedTracks = null,
            externalId = "mbid",
            fetch = { throw RuntimeException("429 Too Many Requests") },
        )
        assertNull(r)
    }

    @Test
    fun preview_remote_returns_fetched_tracks_on_success() = runTest {
        val tracks = listOf(pt("Song A", "Artist A"))
        val r = resolvePreviewRemoteTracks(changedTracks = null, externalId = "mbid", fetch = { tracks })
        assertEquals(tracks, r)
    }

    @Test
    fun preview_remote_empty_successful_fetch_is_a_real_empty_list() = runTest {
        // A genuinely-empty remote fetched successfully is empty (non-null) — distinct
        // from a failed fetch; it legitimately diffs as all-adds.
        val r = resolvePreviewRemoteTracks(changedTracks = null, externalId = "mbid", fetch = { emptyList() })
        assertEquals(emptyList<PlaylistTrack>(), r)
    }

    @Test
    fun preview_remote_uses_changed_tracks_without_fetching() = runTest {
        val changed = listOf(pt("Song B", "Artist B"))
        val r = resolvePreviewRemoteTracks(
            changedTracks = changed,
            externalId = "mbid",
            fetch = { throw IllegalStateException("must not fetch when changed tracks are present") },
        )
        assertEquals(changed, r)
    }

    @Test
    fun preview_remote_null_when_no_provider() = runTest {
        val r = resolvePreviewRemoteTracks(changedTracks = null, externalId = "mbid", fetch = null)
        assertNull(r)
    }
}
