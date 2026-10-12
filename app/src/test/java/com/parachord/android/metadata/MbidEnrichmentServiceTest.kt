package com.parachord.android.metadata

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MbidLookupOutcome
import com.parachord.shared.api.MbidLookupSource
import com.parachord.shared.api.MbidMapperResult
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.metadata.RecordingMbidLookup
import com.parachord.shared.metadata.TrackEnrichmentRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [MbidEnrichmentService] — the mapper → acr-lookup → ISRC
 * recording-MBID chain and, since #394, its THREE-outcome caching: a hit and a
 * mapper miss are trusted for 90 days, an acr-lookup miss for 6 hours, and an
 * unreachable (nothing answered) is never cached at all.
 *
 * The service is exercised with a mocked [ListenBrainzClient] (so we can count
 * lookups), a relaxed-mock [TrackDao] (the row backfill isn't under test),
 * in-memory cache lambdas instead of disk I/O, and an injectable clock.
 */
class MbidEnrichmentServiceTest {

    private val outageStartMs = 1_789_948_800_000L // 2026-09-21T00:00Z — MbidEnrichmentService.OUTAGE_START_MS
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun mapperResult(
        recordingMbid: String?,
        source: String = MbidLookupSource.MAPPER,
    ) = MbidMapperResult(
        artistMbid = "11111111-1111-1111-1111-111111111111",
        artistCreditName = "Slowdive",
        recordingName = "Sugar For The Pill",
        recordingMbid = recordingMbid,
        releaseName = "Slowdive",
        releaseMbid = "22222222-2222-2222-2222-222222222222",
        source = source,
    )

    private fun hit(recordingMbid: String) = MbidLookupOutcome.Hit(mapperResult(recordingMbid))
    private val mapperMiss = MbidLookupOutcome.Miss(MbidLookupSource.MAPPER)
    private val acrMiss = MbidLookupOutcome.Miss(MbidLookupSource.ACR_LOOKUP)
    private val unreachable = MbidLookupOutcome.Unreachable(mapOf("mapper" to "Failed to fetch", "acr-lookup" to "HTTP 503"))

    private class Harness(
        val service: MbidEnrichmentService,
        val client: ListenBrainzClient,
        val cacheJson: () -> String?,
    )

    private fun harness(
        client: ListenBrainzClient = mockk(),
        musicBrainz: MusicBrainzClient? = null,
        seededCache: String? = null,
        now: () -> Long = { outageStartMs + 2 * day },
    ): Harness {
        var cache: String? = seededCache
        val service = MbidEnrichmentService(
            listenBrainzClient = client,
            trackDao = mockk<TrackDao>(relaxed = true),
            cacheRead = { cache },
            cacheWrite = { cache = it },
            musicBrainzClient = musicBrainz,
            nowMs = now,
        )
        return Harness(service, client) { cache }
    }

    private fun req(artist: String, title: String) = TrackEnrichmentRequest("track-$artist-$title", artist, title)

    // ── getRecordingMbid: the chain ─────────────────────────────────────────

    @Test
    fun getRecordingMbid_returnsMappersRecordingMbid() = runTest {
        val expected = "33333333-3333-3333-3333-333333333333"
        val h = harness()
        coEvery { h.client.resolveRecordingMbid("Slowdive", "Sugar For The Pill", null) } returns hit(expected)

        assertEquals(expected, h.service.getRecordingMbid("Slowdive", "Sugar For The Pill"))
    }

    @Test
    fun getRecordingMbid_returnsNull_whenMapperHasNoMatch() = runTest {
        val h = harness()
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns mapperMiss

        assertNull(h.service.getRecordingMbid("Unknown", "Nothing"))
    }

    @Test
    fun getRecordingMbid_fallsBackToIsrc_whenNothingAnswered() = runTest {
        // The outage case: mapper AND labs unreachable → the ISRC fallback resolves
        // the recording MBID via MusicBrainz /isrc/, a different service.
        val viaIsrc = "55555555-5555-5555-5555-555555555555"
        val mb = mockk<MusicBrainzClient>()
        coEvery { mb.lookupRecordingMbidByIsrc("USSM12203074") } returns viaIsrc
        val h = harness(musicBrainz = mb)
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns unreachable

        assertEquals(viaIsrc, h.service.getRecordingMbid("Harry Styles", "As It Was", isrc = "USSM12203074"))
        coVerify(exactly = 1) { mb.lookupRecordingMbidByIsrc("USSM12203074") }
    }

    @Test
    fun getRecordingMbid_fallsBackToIsrc_onAGenuineMissToo() = runTest {
        // A service answering "no match" for the artist/title pair doesn't mean
        // MusicBrainz doesn't know the ISRC — the fallback still fires.
        val viaIsrc = "56565656-5656-5656-5656-565656565656"
        val mb = mockk<MusicBrainzClient>()
        coEvery { mb.lookupRecordingMbidByIsrc("USSM12203074") } returns viaIsrc
        val h = harness(musicBrainz = mb)
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns mapperMiss

        assertEquals(viaIsrc, h.service.getRecordingMbid("Harry Styles", "As It Was", isrc = "USSM12203074"))
    }

    @Test
    fun getRecordingMbid_skipsIsrcFallback_whenMapperMatches() = runTest {
        // Mapper hit → the ISRC fallback must NOT fire (mapper is primary/richer).
        val expected = "66666666-6666-6666-6666-666666666666"
        val mb = mockk<MusicBrainzClient>(relaxed = true)
        val h = harness(musicBrainz = mb)
        coEvery { h.client.resolveRecordingMbid("Harry Styles", "As It Was", null) } returns hit(expected)

        assertEquals(expected, h.service.getRecordingMbid("Harry Styles", "As It Was", isrc = "USSM12203074"))
        coVerify(exactly = 0) { mb.lookupRecordingMbidByIsrc(any()) }
    }

    @Test
    fun getRecordingMbid_doesNotCacheIsrcResult_soMapperCanUpgradeOnRecovery() = runTest {
        // Load-bearing invariant: the ISRC-only result (no canonical names /
        // artist+release MBIDs) must NOT be cached, or it would block the richer
        // mapper entry for 90 days once the mapper recovers. Two outage-era calls
        // with the same ISRC must BOTH hit the MusicBrainz /isrc/ lookup.
        val viaIsrc = "77777777-7777-7777-7777-777777777777"
        val mb = mockk<MusicBrainzClient>()
        coEvery { mb.lookupRecordingMbidByIsrc("USSM12203074") } returns viaIsrc
        val h = harness(musicBrainz = mb)
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns unreachable

        h.service.getRecordingMbid("Harry Styles", "As It Was", isrc = "USSM12203074")
        h.service.getRecordingMbid("Harry Styles", "As It Was", isrc = "USSM12203074")

        coVerify(exactly = 2) { mb.lookupRecordingMbidByIsrc("USSM12203074") }
    }

    @Test
    fun getRecordingMbid_returnsNull_whenMapperAndIsrcBothMiss() = runTest {
        val mb = mockk<MusicBrainzClient>()
        coEvery { mb.lookupRecordingMbidByIsrc(any()) } returns null
        val h = harness(musicBrainz = mb)
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns mapperMiss

        assertNull(h.service.getRecordingMbid("Unknown", "Nothing", isrc = "XXXXX0000000"))
    }

    @Test
    fun getRecordingMbid_cacheHit_avoidsSecondLookup() = runTest {
        val expected = "44444444-4444-4444-4444-444444444444"
        val h = harness()
        coEvery { h.client.resolveRecordingMbid("Slowdive", "Sugar For The Pill", null) } returns hit(expected)

        // First call: misses the cache, hits the chain, caches the result. Second
        // call (same artist/title): served from the cache, no second round-trip.
        assertEquals(expected, h.service.getRecordingMbid("Slowdive", "Sugar For The Pill"))
        assertEquals(expected, h.service.getRecordingMbid("Slowdive", "Sugar For The Pill"))
        coVerify(exactly = 1) { h.client.resolveRecordingMbid("Slowdive", "Sugar For The Pill", null) }
    }

    // ── lookupRecordingMbid: three outcomes (#394) ──────────────────────────

    @Test
    fun lookupRecordingMbid_isUnreachable_whenNothingAnswered_andNoIsrc() = runTest {
        val h = harness()
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns unreachable

        assertEquals(RecordingMbidLookup.Unreachable, h.service.lookupRecordingMbid("Radiohead", "Creep"))
    }

    @Test
    fun lookupRecordingMbid_isNotFound_whenAServiceAnsweredNoMatch() = runTest {
        val h = harness()
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns acrMiss

        assertEquals(RecordingMbidLookup.NotFound, h.service.lookupRecordingMbid("Nobody", "Nothing"))
    }

    @Test
    fun lookupRecordingMbid_staysUnreachable_whenIsrcFallbackAlsoFindsNothing() = runTest {
        // The status is decided by the mapper/acr chain; the ISRC fallback can only
        // UPGRADE it to Found. Nothing answered about the pair, so it is NOT a miss.
        val mb = mockk<MusicBrainzClient>()
        coEvery { mb.lookupRecordingMbidByIsrc(any()) } returns null
        val h = harness(musicBrainz = mb)
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns unreachable

        assertEquals(RecordingMbidLookup.Unreachable, h.service.lookupRecordingMbid("Radiohead", "Creep", isrc = "GBAYE9200066"))
    }

    @Test
    fun lookupRecordingMbid_passesAlbumThrough() = runTest {
        val h = harness()
        coEvery { h.client.resolveRecordingMbid("Radiohead", "Creep", "Pablo Honey") } returns hit("70595637-9310-45f2-a266-58f8de4874a7")

        val r = h.service.lookupRecordingMbid("Radiohead", "Creep", album = "Pablo Honey")

        assertEquals(RecordingMbidLookup.Found("70595637-9310-45f2-a266-58f8de4874a7"), r)
    }

    @Test
    fun unreachable_isNeverCached_soTheNextCallAsksAgain() = runTest {
        val h = harness()
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns unreachable

        assertNull(h.service.getRecordingMbid("Radiohead", "Creep"))
        assertNull(h.service.getRecordingMbid("Radiohead", "Creep"))

        coVerify(exactly = 2) { h.client.resolveRecordingMbid("Radiohead", "Creep", null) }
    }

    // ── enrichTrack: per-source miss TTLs, unreachable not cached ───────────

    @Test
    fun enrich_cachesAnAcrLookupMissForSixHoursOnly() = runTest {
        var now = outageStartMs + 2 * day
        val h = harness(now = { now })
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns acrMiss

        h.service.enrichTrack(req("Radiohead", "Creep - Remastered"))
        now += 5 * hour
        h.service.enrichTrack(req("Radiohead", "Creep - Remastered"))
        coVerify(exactly = 1) { h.client.resolveRecordingMbid(any(), any(), any()) }

        // Past 6 h the stricter matcher's miss is no longer trusted: ask again —
        // the mapper may be back and may match what acr-lookup couldn't.
        now += 2 * hour
        h.service.enrichTrack(req("Radiohead", "Creep - Remastered"))
        coVerify(exactly = 2) { h.client.resolveRecordingMbid(any(), any(), any()) }
    }

    @Test
    fun enrich_cachesAMapperMissForNinetyDays() = runTest {
        var now = outageStartMs + 2 * day
        val h = harness(now = { now })
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns mapperMiss

        h.service.enrichTrack(req("Nobody", "Nothing"))
        now += 7 * hour // well past the acr-lookup TTL; a mapper miss keeps the long one
        h.service.enrichTrack(req("Nobody", "Nothing"))

        coVerify(exactly = 1) { h.client.resolveRecordingMbid(any(), any(), any()) }
    }

    @Test
    fun enrich_neverCachesUnreachable() = runTest {
        val h = harness()
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns unreachable

        h.service.enrichTrack(req("Radiohead", "Creep"))
        h.service.enrichTrack(req("Radiohead", "Creep"))

        coVerify(exactly = 2) { h.client.resolveRecordingMbid(any(), any(), any()) }
    }

    // ── disk cache: one-shot purge of outage-era misses ─────────────────────

    @Test
    fun load_purgesSourcelessMissesRecordedSinceTheOutage_butKeepsEverythingElse() = runTest {
        // Four pre-existing entries written by the pre-#394 code (no `source`),
        // except the last which the new code wrote. `now` is 2 days into the outage.
        val sinceOutage = outageStartMs + 1 * day
        val beforeOutage = outageStartMs - 1 * day
        val seeded = """
            {
              "outage artist|song": {"cachedAt": $sinceOutage},
              "old artist|song":    {"cachedAt": $beforeOutage},
              "hit artist|song":    {"recordingMbid": "88888888-8888-8888-8888-888888888888", "cachedAt": $sinceOutage},
              "sourced artist|song":{"source": "mapper", "cachedAt": $sinceOutage}
            }
        """.trimIndent()
        val h = harness(seededCache = seeded)
        coEvery { h.client.resolveRecordingMbid(any(), any(), any()) } returns mapperMiss

        // The source-less miss recorded during the outage is an artifact of a
        // failed request, not a verdict: it is dropped and the pair is asked again.
        assertNull(h.service.getRecordingMbid("outage artist", "song"))
        coVerify(exactly = 1) { h.client.resolveRecordingMbid("outage artist", "song", null) }

        // A source-less miss from BEFORE the outage is a real pre-outage verdict: kept.
        assertNull(h.service.getRecordingMbid("old artist", "song"))
        coVerify(exactly = 0) { h.client.resolveRecordingMbid("old artist", "song", any()) }

        // A hit is never an artifact: kept, served from cache.
        assertEquals("88888888-8888-8888-8888-888888888888", h.service.getRecordingMbid("hit artist", "song"))
        coVerify(exactly = 0) { h.client.resolveRecordingMbid("hit artist", "song", any()) }

        // A miss the NEW code wrote carries its source: kept (the purge is one-shot).
        // (That new misses DO carry a source is proven behaviourally by
        // enrich_cachesAnAcrLookupMissForSixHoursOnly — the 6 h TTL only applies
        // when the entry was stamped "acr-lookup".)
        assertNull(h.service.getRecordingMbid("sourced artist", "song"))
        coVerify(exactly = 0) { h.client.resolveRecordingMbid("sourced artist", "song", any()) }
    }
}
