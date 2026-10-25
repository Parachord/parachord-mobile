package com.parachord.android.sync

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.AppleMusicLibraryClient
import com.parachord.shared.api.AppleMusicSearchResponse
import com.parachord.shared.api.ItunesRateLimitedException
import com.parachord.shared.sync.AppleMusicSyncProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import com.parachord.shared.sync.TrackIdLookupUnreachableException
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Time-bound iTunes Search rate-limit breaker (incremental-materialization
 * Task 7d). One 429 pauses AM iTunes track-id hydration for 5 minutes — NOT
 * until app restart (the old session-permanent `iTunesSearchRateLimited` flag).
 *
 * Drives an injected `nowMs` clock so the cooldown is deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppleMusicItunesBreakerTest {

    private val library = mockk<AppleMusicLibraryClient>(relaxed = true)

    @Test
    fun `429 short-circuits subsequent searches within the 5-min window, resumes after`() = runTest {
        var now = 1_000_000L
        // catalogClient.search always 429s.
        val catalog = mockk<AppleMusicClient> {
            coEvery { search(any(), any(), any(), any(), any()) } throws ItunesRateLimitedException()
        }
        val provider = AppleMusicSyncProvider(
            api = library,
            catalogClient = catalog,
            nowMs = { now },
        )

        // A 429 (and the paused window after it) is "nobody answered", not "no
        // match": it must throw Unreachable so hydration doesn't record a miss.
        fun unreachable(title: String) = assertThrows(TrackIdLookupUnreachableException::class.java) {
            runBlocking { provider.searchForTrackId(title, "Artist", album = null, isrc = null) }
        }

        // First call: hits the 429 and arms the breaker.
        // No ISRC → goes straight to the iTunes fallback.
        unreachable("Song")
        coVerify(exactly = 1) { catalog.search(any(), any(), any(), any(), any()) }

        // Within the 5-min window: short-circuits WITHOUT another search call.
        now += 4L * 60L * 1000L // +4 min < 5-min cooldown
        unreachable("Song2")
        coVerify(exactly = 1) { catalog.search(any(), any(), any(), any(), any()) } // still 1

        // Past the window: the breaker self-clears, so it attempts again.
        now += 2L * 60L * 1000L // total +6 min > 5-min cooldown
        unreachable("Song3")
        coVerify(exactly = 2) { catalog.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a 5xx or network failure is unreachable, not a miss`() = runTest {
        val catalog = mockk<AppleMusicClient> {
            coEvery { search(any(), any(), any(), any(), any()) } throws IllegalStateException("iTunes Search failed: HTTP 503")
        }
        val provider = AppleMusicSyncProvider(api = library, catalogClient = catalog, nowMs = { 0L })
        assertThrows(TrackIdLookupUnreachableException::class.java) {
            runBlocking { provider.searchForTrackId("Song", "Artist", album = null, isrc = null) }
        }
        // The client is asked to surface 5xx as an error rather than an empty answer.
        coVerify { catalog.search(any(), any(), any(), any(), throwOnServerError = true) }
    }

    @Test
    fun `a clean (non-429) session never arms the breaker`() = runTest {
        var now = 1_000_000L
        // Returns empty (no match) — not a 429.
        val catalog = mockk<AppleMusicClient> {
            coEvery { search(any(), any(), any(), any(), any()) } returns AppleMusicSearchResponse()
        }
        val provider = AppleMusicSyncProvider(
            api = library,
            catalogClient = catalog,
            nowMs = { now },
        )

        assertNull(provider.searchForTrackId("Song", "Artist", album = null, isrc = null))
        now += 1L
        assertNull(provider.searchForTrackId("Song2", "Artist", album = null, isrc = null))
        // Both calls actually hit the catalog (breaker never armed).
        coVerify(exactly = 2) { catalog.search(any(), any(), any(), any(), any()) }
    }
}
