package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.deeplink.ProtocolTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit coverage for [PoolRefiller] — Mode C pool refill loop
 * (issue #121 Task 5).
 *
 * Drives the refiller with a fixed clock + controllable fetcher so we
 * can exercise rate-limit, stop-condition, dedup, and error-handling
 * paths without spinning up the full [PlaybackController].
 */
class PoolRefillerTest {

    /** Fake clock — caller advances time explicitly via [advance]. */
    private class FakeClock(initial: Long = 1_000L) {
        @Volatile var now: Long = initial
        fun advance(ms: Long) { now += ms }
        fun get(): Long = now
    }

    private fun track(
        id: String,
        artist: String = "A",
        title: String = "T",
        mbid: String? = null,
    ) = TrackEntity(id = id, title = title, artist = artist, recordingMbid = mbid)

    private fun ptrack(
        artist: String = "A",
        title: String = "T",
        mbid: String? = null,
    ) = ProtocolTrack(artist = artist, title = title, mbid = mbid)

    private fun buildRefiller(
        clock: FakeClock = FakeClock(),
        fetcher: (suspend (String) -> List<ProtocolTrack>)? = null,
        url: String? = "https://api.listenbrainz.org/refill",
    ): PoolRefiller {
        val r = PoolRefiller(clock = clock::get)
        r.fetcher = fetcher
        r.refillUrl = url
        return r
    }

    // ── canRefill / configuration gates ──────────────────────────────

    @Test
    fun canRefill_falseWhenFetcherNull() {
        val r = PoolRefiller(clock = FakeClock()::get)
        r.refillUrl = "https://x"
        r.fetcher = null
        assertFalse(r.canRefill())
    }

    @Test
    fun canRefill_falseWhenUrlNull() {
        val r = PoolRefiller(clock = FakeClock()::get)
        r.refillUrl = null
        r.fetcher = { emptyList() }
        assertFalse(r.canRefill())
    }

    // ── Rate limit ───────────────────────────────────────────────────

    @Test
    fun rateLimit_underWindowSkipsFetch() = runTest {
        val clock = FakeClock(initial = 10_000L)
        var fetched = 0
        val r = buildRefiller(clock = clock, fetcher = {
            fetched += 1
            listOf(ptrack(artist = "X", title = "Y"))
        })
        // First fetch fires (lastFetchTs == 0 ⇒ elapsed == 10_000 ≥ 5_000).
        assertNotNull(r.tryRefill(emptyList()))
        assertEquals(1, fetched)

        // Advance by 4999ms — still inside the window.
        clock.advance(4_999)
        assertFalse(r.canRefill())
        val r2 = r.tryRefill(emptyList())
        assertNull(r2)
        assertEquals("fetcher must NOT have run again", 1, fetched)
    }

    @Test
    fun rateLimit_atExactlyFiveSecondsAllowsFetch() = runTest {
        val clock = FakeClock(initial = 10_000L)
        var fetched = 0
        val r = buildRefiller(clock = clock, fetcher = {
            fetched += 1
            listOf(ptrack(artist = "X-$fetched", title = "Y"))
        })
        // First fetch.
        assertNotNull(r.tryRefill(emptyList()))
        assertEquals(1, fetched)

        // Advance by exactly 5000ms — boundary is `>=`, so this fires.
        clock.advance(5_000)
        assertTrue("at boundary canRefill should be true", r.canRefill())
        assertNotNull(r.tryRefill(emptyList()))
        assertEquals(2, fetched)
    }

    // ── Stop condition (3 consecutive empties) ───────────────────────

    @Test
    fun threeConsecutiveEmpty_stopsRefilling() = runTest {
        val clock = FakeClock(initial = 100_000L)
        var fetcherCalls = 0
        val r = buildRefiller(clock = clock, fetcher = {
            fetcherCalls += 1
            emptyList()
        })

        // First three attempts each call the fetcher and increment
        // emptyCount.
        for (i in 1..3) {
            assertNull(r.tryRefill(emptyList()))
            clock.advance(6_000)
        }
        assertEquals(3, fetcherCalls)
        assertEquals(3, r.emptyCountForTest)

        // Fourth attempt: stop condition hit. Even with the rate-limit
        // window cleared, canRefill() returns false and the fetcher
        // does NOT run again.
        assertFalse(r.canRefill())
        assertNull(r.tryRefill(emptyList()))
        assertEquals("fetcher must not have run a 4th time", 3, fetcherCalls)
    }

    @Test
    fun freshTracksResetEmptyCount() = runTest {
        val clock = FakeClock(initial = 100_000L)
        // Sequence: empty, empty, fresh, empty.
        // After step 3 empty count resets to 0.
        // After step 4 empty count == 1, NOT 3.
        val responses = mutableListOf<List<ProtocolTrack>>(
            emptyList(),
            emptyList(),
            listOf(ptrack(artist = "Slowdive", title = "Sugar")),
            emptyList(),
        )
        val r = buildRefiller(clock = clock, fetcher = { responses.removeAt(0) })

        assertNull(r.tryRefill(emptyList())); clock.advance(6_000)
        assertNull(r.tryRefill(emptyList())); clock.advance(6_000)
        assertEquals(2, r.emptyCountForTest)

        val fresh = r.tryRefill(emptyList())
        assertNotNull(fresh)
        assertEquals(1, fresh!!.size)
        assertEquals("counter reset on fresh", 0, r.emptyCountForTest)
        clock.advance(6_000)

        assertNull(r.tryRefill(emptyList()))
        assertEquals("re-incremented to 1, not 3", 1, r.emptyCountForTest)
    }

    // ── Error handling ───────────────────────────────────────────────

    @Test
    fun fetchThrows_countsAsEmpty() = runTest {
        val clock = FakeClock(initial = 100_000L)
        val r = buildRefiller(clock = clock, fetcher = {
            throw IOException("boom")
        })
        assertNull(r.tryRefill(emptyList()))
        assertEquals(1, r.emptyCountForTest)
    }

    @Test
    fun fetchThrows_threeConsecutiveErrorsHitStopCondition() = runTest {
        val clock = FakeClock(initial = 100_000L)
        val r = buildRefiller(clock = clock, fetcher = {
            throw IOException("boom")
        })
        for (i in 1..3) {
            assertNull(r.tryRefill(emptyList()))
            clock.advance(6_000)
        }
        assertEquals(3, r.emptyCountForTest)
        assertFalse(r.canRefill())
    }

    // ── Dedup ────────────────────────────────────────────────────────

    @Test
    fun dedupByMbid_caseInsensitive() = runTest {
        val clock = FakeClock(initial = 100_000L)
        val pool = listOf(
            track("e1", mbid = "ABC-123"),  // pool MBID upper, fetcher MBID lower
        )
        val r = buildRefiller(clock = clock, fetcher = {
            listOf(
                ptrack(artist = "Different", title = "Different", mbid = "abc-123"),
                ptrack(artist = "Other", title = "Other", mbid = "xyz-999"),
            )
        })
        val fresh = r.tryRefill(pool)
        assertNotNull(fresh)
        assertEquals("only the non-mbid-dup should pass", 1, fresh!!.size)
        assertEquals("Other", fresh[0].artist)
    }

    @Test
    fun dedupByArtistTitle_caseInsensitive() = runTest {
        val clock = FakeClock(initial = 100_000L)
        val pool = listOf(track("e1", artist = "Slowdive", title = "Sugar For The Pill"))
        val r = buildRefiller(clock = clock, fetcher = {
            listOf(
                ptrack(artist = "SLOWDIVE", title = "sugar for the pill"),
                ptrack(artist = "Beach House", title = "Space Song"),
            )
        })
        val fresh = r.tryRefill(pool)
        assertNotNull(fresh)
        assertEquals(1, fresh!!.size)
        assertEquals("Beach House", fresh[0].artist)
    }

    @Test
    fun dedup_allTracksDeduped_countsAsEmpty() = runTest {
        val clock = FakeClock(initial = 100_000L)
        val pool = listOf(
            track("e1", artist = "A", title = "T1"),
            track("e2", artist = "A", title = "T2"),
        )
        val r = buildRefiller(clock = clock, fetcher = {
            listOf(
                ptrack(artist = "A", title = "T1"),
                ptrack(artist = "a", title = "T2"),
            )
        })
        assertNull(r.tryRefill(pool))
        assertEquals(1, r.emptyCountForTest)
    }

    @Test
    fun fresh_appendsAndStampsStableIds() = runTest {
        val clock = FakeClock(initial = 555_000L)
        val r = buildRefiller(clock = clock, fetcher = {
            listOf(
                ptrack(artist = "A1", title = "T1"),
                ptrack(artist = "A2", title = "T2"),
                ptrack(artist = "A3", title = "T3"),
            )
        })
        val fresh = r.tryRefill(emptyList())
        assertNotNull(fresh)
        assertEquals(3, fresh!!.size)
        // IDs follow the protocol-radio-{ts}-{idx} convention.
        assertTrue(fresh[0].id.startsWith("protocol-radio-"))
        assertTrue(fresh[0].id.endsWith("-0"))
        assertTrue(fresh[1].id.endsWith("-1"))
        assertTrue(fresh[2].id.endsWith("-2"))
        // Field copy.
        assertEquals("A1", fresh[0].artist)
        assertEquals("T1", fresh[0].title)
    }

    // ── Reset ────────────────────────────────────────────────────────

    @Test
    fun resetCounters_clearsCountersButPreservesFetcherAndUrl() = runTest {
        val clock = FakeClock(initial = 100_000L)
        val r = buildRefiller(clock = clock, fetcher = { emptyList() })
        // Burn TWO empties to set both counters AND the timestamp.
        assertNull(r.tryRefill(emptyList())); clock.advance(6_000)
        assertNull(r.tryRefill(emptyList()))
        assertEquals(2, r.emptyCountForTest)

        r.resetCounters()
        assertEquals(0, r.emptyCountForTest)
        assertEquals(0L, r.lastFetchTsForTest)
        // Fetcher + URL preserved.
        assertNotNull(r.fetcher)
        assertNotNull(r.refillUrl)
        assertTrue(r.canRefill())
    }

    // ── Static dedup helper edge cases ───────────────────────────────

    @Test
    fun dedupHelper_emptyFreshReturnsEmpty() {
        val pool = listOf(track("e1"))
        assertTrue(PoolRefiller.dedupAgainstPool(emptyList(), pool).isEmpty())
    }

    @Test
    fun dedupHelper_emptyPoolReturnsAllFresh() {
        val fresh = listOf(ptrack(artist = "A", title = "T"))
        val out = PoolRefiller.dedupAgainstPool(fresh, emptyList())
        assertEquals(1, out.size)
    }

    @Test
    fun dedupHelper_mbidMissOnlyArtistTitleMatches_filters() {
        val pool = listOf(track("e1", artist = "A", title = "T"))
        // No mbid match (pool has none) — fall through to (artist|title).
        val fresh = listOf(ptrack(artist = "A", title = "T", mbid = "some-mbid"))
        assertTrue(PoolRefiller.dedupAgainstPool(fresh, pool).isEmpty())
    }
}
