package com.parachord.android.repository

import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.Announcement
import com.parachord.shared.repository.AnnouncementsRepository
import com.parachord.shared.repository.compareSemverOrNull
import com.parachord.shared.store.KvStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the contract of [AnnouncementsRepository] (#127).
 *
 * Covers:
 *  - [compareSemverOrNull] basic comparison cases
 *  - [filterForClient] surface / version / expiry / dismissal predicates
 *  - [AnnouncementsRepository.refreshIfStale] 6h gate
 *  - [AnnouncementsRepository.refreshNow] always-fetch
 *  - [AnnouncementsRepository.dismiss] persistence + state flow update + telemetry
 *  - [AnnouncementsRepository.trackView] once-per-session dedup
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncementsRepositoryTest {

    /** Build an in-memory KvStore stand-in with a backing map. */
    private fun fakeKv(
        initialLastFetchedMs: Long = 0L,
        initialDismissedCsv: String = "",
    ): KvStore {
        val storage = mutableMapOf<String, Any?>(
            AnnouncementsRepository.KEY_LAST_FETCHED_MS to initialLastFetchedMs,
            AnnouncementsRepository.KEY_DISMISSED_IDS to initialDismissedCsv,
        )
        val kv = mockk<KvStore>(relaxed = true)
        every { kv.getLong(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Long>()
            (storage[key] as? Long) ?: default
        }
        every { kv.getStringSetCsv(any()) } answers {
            val key = firstArg<String>()
            val raw = (storage[key] as? String) ?: ""
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        coEvery { kv.setLong(any(), any()) } answers {
            storage[firstArg()] = secondArg<Long>()
        }
        coEvery { kv.setStringSetCsv(any(), any()) } answers {
            storage[firstArg()] = secondArg<Set<String>>().joinToString(",")
        }
        return kv
    }

    private fun ann(
        id: String = "a1",
        title: String = "Hello",
        surfaces: List<String>? = null,
        minVersion: String? = null,
        maxVersion: String? = null,
        expiresAt: String? = null,
        severity: String? = "info",
    ) = Announcement(
        id = id,
        title = title,
        surfaces = surfaces,
        minVersion = minVersion,
        maxVersion = maxVersion,
        expiresAt = expiresAt,
        severity = severity,
    )

    @Test
    fun compareSemverOrNull_basicCases() {
        assertEquals(0, compareSemverOrNull("0.6.1", "0.6.1"))
        assertTrue((compareSemverOrNull("0.6.1", "0.7.0") ?: 0) < 0)
        assertTrue((compareSemverOrNull("0.7.0", "0.6.1") ?: 0) > 0)
        // Leading v
        assertEquals(0, compareSemverOrNull("v1.0.0", "1.0.0"))
        // Pre-release tags ignored
        assertEquals(0, compareSemverOrNull("1.2.3-alpha", "1.2.3"))
        // Missing components zero-padded
        assertTrue((compareSemverOrNull("1", "1.0.0") ?: 99) == 0)
        assertTrue((compareSemverOrNull("1.2", "1.2.0") ?: 99) == 0)
        // Unparseable → null (fail-open)
        assertNull(compareSemverOrNull("abc", "1.0.0"))
        assertNull(compareSemverOrNull("1.0.0", "xyz"))
    }

    @Test
    fun filterForClient_excludesNonParachordSurface() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val repo = AnnouncementsRepository(achordion, fakeKv(), appVersion = "0.6.1")
        val result = repo.filterForClient(listOf(ann(surfaces = listOf("achordion"))))
        assertTrue(result.isEmpty())
    }

    @Test
    fun filterForClient_includesWhenSurfacesNullOrContainsParachord() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val repo = AnnouncementsRepository(achordion, fakeKv(), appVersion = "0.6.1")
        val result = repo.filterForClient(
            listOf(
                ann(id = "a1", surfaces = null),
                ann(id = "a2", surfaces = listOf("parachord")),
                ann(id = "a3", surfaces = emptyList()),
                ann(id = "a4", surfaces = listOf("achordion", "parachord")),
            ),
        )
        assertEquals(listOf("a1", "a2", "a3", "a4"), result.map { it.id })
    }

    @Test
    fun filterForClient_excludesWhenAppBelowMinVersion() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val repo = AnnouncementsRepository(achordion, fakeKv(), appVersion = "0.6.1")
        val result = repo.filterForClient(listOf(ann(minVersion = "0.7.0")))
        assertTrue(result.isEmpty())
    }

    @Test
    fun filterForClient_includesAtOrAboveMinVersion() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val repo = AnnouncementsRepository(achordion, fakeKv(), appVersion = "0.7.0")
        val result = repo.filterForClient(
            listOf(
                ann(id = "a1", minVersion = "0.7.0"),
                ann(id = "a2", minVersion = "0.5.0"),
            ),
        )
        assertEquals(listOf("a1", "a2"), result.map { it.id })
    }

    @Test
    fun filterForClient_failsOpenOnUnparseableVersionBound() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val repo = AnnouncementsRepository(achordion, fakeKv(), appVersion = "0.6.1")
        val result = repo.filterForClient(
            listOf(
                ann(id = "a1", minVersion = "abc"),
                ann(id = "a2", maxVersion = "abc"),
            ),
        )
        // Unparseable bounds compare to null → no constraint enforced → both included.
        assertEquals(listOf("a1", "a2"), result.map { it.id })
    }

    @Test
    fun filterForClient_excludesExpiredAnnouncements() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val repo = AnnouncementsRepository(achordion, fakeKv(), appVersion = "0.6.1")
        val pastIso = java.time.Instant.now().minus(java.time.Duration.ofHours(1)).toString()
        val result = repo.filterForClient(listOf(ann(expiresAt = pastIso)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun filterForClient_includesValidFutureExpiry() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val repo = AnnouncementsRepository(achordion, fakeKv(), appVersion = "0.6.1")
        val futureIso = java.time.Instant.now().plus(java.time.Duration.ofHours(1)).toString()
        val result = repo.filterForClient(listOf(ann(expiresAt = futureIso)))
        assertEquals(1, result.size)
    }

    @Test
    fun filterForClient_excludesDismissedIds() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val repo = AnnouncementsRepository(
            achordion,
            fakeKv(initialDismissedCsv = "a1,a3"),
            appVersion = "0.6.1",
        )
        val result = repo.filterForClient(
            listOf(ann(id = "a1"), ann(id = "a2"), ann(id = "a3")),
        )
        assertEquals(listOf("a2"), result.map { it.id })
    }

    @Test
    fun refreshIfStale_skipsWhenWithinGate() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        // Last fetched 1h ago — within the 6h gate.
        val recent = System.currentTimeMillis() - 60L * 60 * 1000
        val repo = AnnouncementsRepository(
            achordion,
            fakeKv(initialLastFetchedMs = recent),
            appVersion = "0.6.1",
        )
        repo.refreshIfStale()
        coVerify(exactly = 0) { achordion.listAnnouncements() }
    }

    @Test
    fun refreshIfStale_fetchesWhenStale() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        coEvery { achordion.listAnnouncements() } returns emptyList()
        // Last fetched 7h ago — outside the 6h gate.
        val stale = System.currentTimeMillis() - 7L * 60 * 60 * 1000
        val repo = AnnouncementsRepository(
            achordion,
            fakeKv(initialLastFetchedMs = stale),
            appVersion = "0.6.1",
        )
        repo.refreshIfStale()
        coVerify(exactly = 1) { achordion.listAnnouncements() }
    }

    @Test
    fun refreshNow_alwaysFetches() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        coEvery { achordion.listAnnouncements() } returns emptyList()
        // Last fetched 1min ago — refreshNow should ignore the gate.
        val veryRecent = System.currentTimeMillis() - 60L * 1000
        val repo = AnnouncementsRepository(
            achordion,
            fakeKv(initialLastFetchedMs = veryRecent),
            appVersion = "0.6.1",
        )
        repo.refreshNow()
        coVerify(exactly = 1) { achordion.listAnnouncements() }
    }

    @Test
    fun dismiss_persistsAndRemovesFromVisibleAndFiresEvent() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        coEvery { achordion.listAnnouncements() } returns listOf(
            ann(id = "a1"), ann(id = "a2"),
        )
        val kv = fakeKv()
        val repo = AnnouncementsRepository(achordion, kv, appVersion = "0.6.1")
        repo.refreshNow()
        assertEquals(listOf("a1", "a2"), repo.visibleAnnouncements.value.map { it.id })

        val writtenSet = slot<Set<String>>()
        coEvery { kv.setStringSetCsv(AnnouncementsRepository.KEY_DISMISSED_IDS, capture(writtenSet)) } answers {
            // No-op — fakeKv() already wired persistence; this overlay just captures.
        }
        repo.dismiss("a1")
        advanceUntilIdle()

        // Visible list now excludes a1.
        assertEquals(listOf("a2"), repo.visibleAnnouncements.value.map { it.id })
        // Dismiss telemetry fired.
        coVerify(exactly = 1) { achordion.trackAnnouncementEvent("a1", "dismiss") }
        // KvStore write happened with a1 in the set.
        assertTrue(writtenSet.isCaptured)
        assertTrue("a1" in writtenSet.captured)
    }

    @Test
    fun trackView_firesOncePerSession() = runTest {
        val achordion = mockk<AchordionClient>(relaxed = true)
        val repo = AnnouncementsRepository(achordion, fakeKv(), appVersion = "0.6.1")
        repo.trackView("a1")
        repo.trackView("a1")
        repo.trackView("a1")
        advanceUntilIdle()
        coVerify(exactly = 1) { achordion.trackAnnouncementEvent("a1", "view") }
    }
}
