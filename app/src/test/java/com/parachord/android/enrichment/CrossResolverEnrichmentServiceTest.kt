package com.parachord.android.enrichment

import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.SubmitResult
import com.parachord.shared.api.SubmitTrackLinksRequest
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.enrichment.CrossResolverEnrichmentService
import com.parachord.shared.enrichment.CrossResolverEnrichmentService.ResolvedSources
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.metadata.TrackEnrichmentRequest
import com.parachord.shared.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the contract of [CrossResolverEnrichmentService]:
 *
 *  - markCrossResolverEnriched ALWAYS fires (even on empty/no-match runs)
 *    so un-findable tracks aren't thrashed each cycle.
 *  - DB persistence is additive — never overwrites an existing ID.
 *  - Achordion submit only fires when MBID + >= 1 streaming ID are
 *    BOTH present.
 *  - The slow-trickle delay is enforced between tracks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossResolverEnrichmentServiceTest {

    private fun newTrack(
        id: String = "t1",
        title: String = "Song",
        artist: String = "Artist",
        album: String? = "Album",
        spotifyId: String? = null,
        appleMusicId: String? = null,
        soundcloudId: String? = null,
        recordingMbid: String? = null,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        resolver = "localfiles",
        spotifyId = spotifyId,
        appleMusicId = appleMusicId,
        soundcloudId = soundcloudId,
        recordingMbid = recordingMbid,
    )

    private fun newService(
        trackDao: TrackDao,
        mbidService: MbidEnrichmentService = mockk(relaxed = true),
        achordion: AchordionClient = mockk(relaxed = true),
        resolve: suspend (String, String) -> ResolvedSources = { _, _ -> ResolvedSources() },
    ) = CrossResolverEnrichmentService(
        trackDao = trackDao,
        mbidEnrichmentService = mbidService,
        achordionClient = achordion,
        resolveByTitleArtist = resolve,
    )

    @Test
    fun `runOnce with no candidates returns zero counts and makes no API calls`() = runTest {
        val dao = mockk<TrackDao>(relaxed = true)
        coEvery { dao.selectCrossResolverEnrichmentCandidates(any(), any()) } returns emptyList()
        val achordion = mockk<AchordionClient>(relaxed = true)
        val service = newService(dao, achordion = achordion)

        val result = service.runOnce()

        assertEquals(0, result.visited)
        assertEquals(0, result.enrichedAnyId)
        assertEquals(0, result.submittedToAchordion)
        coVerify(exactly = 0) { dao.markCrossResolverEnriched(any(), any()) }
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun `runOnce with one track that finds no MBID and no resolver results stamps timestamp only`() = runTest {
        val track = newTrack()
        val dao = mockk<TrackDao>(relaxed = true)
        coEvery { dao.selectCrossResolverEnrichmentCandidates(any(), any()) } returns listOf(track)
        coEvery { dao.getById("t1") } returns track
        val achordion = mockk<AchordionClient>(relaxed = true)
        val service = newService(dao, achordion = achordion) { _, _ -> ResolvedSources() }

        val result = service.runOnce()

        assertEquals(1, result.visited)
        assertEquals(0, result.enrichedAnyId)
        assertEquals(0, result.submittedToAchordion)
        coVerify(exactly = 1) { dao.markCrossResolverEnriched("t1", any()) }
        coVerify(exactly = 0) { dao.backfillResolverIds(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
    }

    @Test
    fun `runOnce persists new streaming ID but does NOT submit to Achordion when MBID is missing`() = runTest {
        val track = newTrack(recordingMbid = null)
        val dao = mockk<TrackDao>(relaxed = true)
        coEvery { dao.selectCrossResolverEnrichmentCandidates(any(), any()) } returns listOf(track)
        // After backfill, getById still returns the same track (no MBID since
        // mbidService is a relaxed mock and writes nothing).
        coEvery { dao.getById("t1") } returns track
        val achordion = mockk<AchordionClient>(relaxed = true)
        val service = newService(dao, achordion = achordion) { _, _ ->
            ResolvedSources(spotifyId = "SPOT123")
        }

        val result = service.runOnce()

        assertEquals(1, result.visited)
        assertEquals(1, result.enrichedAnyId)
        assertEquals(0, result.submittedToAchordion)
        coVerify(exactly = 1) {
            dao.backfillResolverIds(
                trackId = "t1",
                spotifyId = "SPOT123",
                spotifyUri = "spotify:track:SPOT123",
                appleMusicId = null,
                soundcloudId = null,
            )
        }
        coVerify(exactly = 0) { achordion.submitTrackLinks(any()) }
        coVerify(exactly = 1) { dao.markCrossResolverEnriched("t1", any()) }
    }

    @Test
    fun `runOnce with MBID present and resolver finds IDs submits to Achordion with all links`() = runTest {
        val initial = newTrack(recordingMbid = "mbid-abc")
        val afterBackfill = initial.copy(spotifyId = "SPOT", appleMusicId = "AM")
        val dao = mockk<TrackDao>(relaxed = true)
        coEvery { dao.selectCrossResolverEnrichmentCandidates(any(), any()) } returns listOf(initial)
        coEvery { dao.getById("t1") } returns afterBackfill
        val achordion = mockk<AchordionClient>(relaxed = true)
        coEvery { achordion.submitTrackLinks(any()) } returns SubmitResult.Ok
        val payload = slot<SubmitTrackLinksRequest>()
        val service = newService(dao, achordion = achordion) { _, _ ->
            ResolvedSources(spotifyId = "SPOT", appleMusicId = "AM")
        }

        val result = service.runOnce()

        assertEquals(1, result.visited)
        assertEquals(1, result.enrichedAnyId)
        assertEquals(1, result.submittedToAchordion)
        coVerify(exactly = 1) { achordion.submitTrackLinks(capture(payload)) }
        assertEquals("mbid-abc", payload.captured.mbid)
        assertEquals(2, payload.captured.links.size)
        assertTrue(payload.captured.links.any { it.host == "spotify.com" })
        assertTrue(payload.captured.links.any { it.host == "music.apple.com" })
        assertEquals("Song", payload.captured.trackName)
        assertEquals("Artist", payload.captured.artistName)
        assertEquals("Album", payload.captured.albumName)
        coVerify(exactly = 1) { dao.markCrossResolverEnriched("t1", any()) }
    }

    @Test
    fun `runOnce throttles between tracks with DELAY_BETWEEN_TRACKS_MS`() = runTest {
        val tracks = listOf(newTrack(id = "a"), newTrack(id = "b"), newTrack(id = "c"))
        val dao = mockk<TrackDao>(relaxed = true)
        coEvery { dao.selectCrossResolverEnrichmentCandidates(any(), any()) } returns tracks
        coEvery { dao.getById(any()) } answers { tracks.find { it.id == firstArg() } }
        val service = newService(dao) { _, _ -> ResolvedSources() }

        val start = testScheduler.currentTime
        service.runOnce()
        val elapsed = testScheduler.currentTime - start

        // Two delays for three tracks (none before first iter).
        val expectedMinDelay = 2 * CrossResolverEnrichmentService.DELAY_BETWEEN_TRACKS_MS
        assertTrue(
            "Expected at least $expectedMinDelay ms of virtual delay between 3 tracks, got $elapsed",
            elapsed >= expectedMinDelay,
        )
        coVerify(exactly = 1) { dao.markCrossResolverEnriched("a", any()) }
        coVerify(exactly = 1) { dao.markCrossResolverEnriched("b", any()) }
        coVerify(exactly = 1) { dao.markCrossResolverEnriched("c", any()) }
    }

    @Test
    fun `runOnce does not overwrite existing streaming IDs`() = runTest {
        // Track already has a Spotify ID; resolver returns a DIFFERENT spotify ID
        // — must NOT be persisted. Apple Music IS new and SHOULD be persisted.
        val track = newTrack(spotifyId = "EXISTING", recordingMbid = "mbid-abc")
        val dao = mockk<TrackDao>(relaxed = true)
        coEvery { dao.selectCrossResolverEnrichmentCandidates(any(), any()) } returns listOf(track)
        coEvery { dao.getById("t1") } returns track.copy(appleMusicId = "AM_NEW")
        val achordion = mockk<AchordionClient>(relaxed = true)
        coEvery { achordion.submitTrackLinks(any()) } returns SubmitResult.Ok
        val service = newService(dao, achordion = achordion) { _, _ ->
            ResolvedSources(spotifyId = "DIFFERENT_SPOT", appleMusicId = "AM_NEW")
        }

        service.runOnce()

        // Spotify is filtered out (existing) → null. AM is new → "AM_NEW".
        // No spotifyUri either (since spotify is filtered out).
        coVerify(exactly = 1) {
            dao.backfillResolverIds(
                trackId = "t1",
                spotifyId = null,
                spotifyUri = null,
                appleMusicId = "AM_NEW",
                soundcloudId = null,
            )
        }
    }
}
