package com.parachord.android.sync

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.AppleMusicLibraryClient
import com.parachord.shared.api.AppleMusicSearchResponse
import com.parachord.shared.api.ItunesRateLimitedException
import com.parachord.shared.sync.AppleMusicSyncProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
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
            coEvery { search(any(), any(), any(), any()) } throws ItunesRateLimitedException()
        }
        val provider = AppleMusicSyncProvider(
            api = library,
            catalogClient = catalog,
            nowMs = { now },
        )

        // First call: hits the 429, returns null, arms the breaker.
        // No ISRC → goes straight to the iTunes fallback.
        assertNull(provider.searchForTrackId("Song", "Artist", album = null, isrc = null))
        coVerify(exactly = 1) { catalog.search(any(), any(), any(), any()) }

        // Within the 5-min window: short-circuits WITHOUT another search call.
        now += 4L * 60L * 1000L // +4 min < 5-min cooldown
        assertNull(provider.searchForTrackId("Song2", "Artist", album = null, isrc = null))
        coVerify(exactly = 1) { catalog.search(any(), any(), any(), any()) } // still 1

        // Past the window: the breaker self-clears, so it attempts again.
        now += 2L * 60L * 1000L // total +6 min > 5-min cooldown
        assertNull(provider.searchForTrackId("Song3", "Artist", album = null, isrc = null))
        coVerify(exactly = 2) { catalog.search(any(), any(), any(), any()) }
    }

    @Test
    fun `a clean (non-429) session never arms the breaker`() = runTest {
        var now = 1_000_000L
        // Returns empty (no match) — not a 429.
        val catalog = mockk<AppleMusicClient> {
            coEvery { search(any(), any(), any(), any()) } returns AppleMusicSearchResponse()
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
        coVerify(exactly = 2) { catalog.search(any(), any(), any(), any()) }
    }
}
