package com.parachord.android.data.repository

import com.parachord.shared.repository.ConcertsRepository
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The concerts cache isn't keyed by location, so after a location or radius
 * change it held the OLD area's events and showed them for up to 30 minutes.
 * `clearLocalEvents` drops them in memory and on disk.
 */
class ConcertsLocationChangeCacheTest {
    private var disk: String? = """{"events":[],"fetchedAt":${System.currentTimeMillis()}}"""

    private fun repo() = ConcertsRepository(
        ticketmasterClient = mockk(relaxed = true),
        seatGeekClient = mockk(relaxed = true),
        settingsStore = mockk(relaxed = true),
        cacheRead = { disk },
        cacheWrite = { disk = it },
        ticketmasterApiKeyFallback = "",
        seatGeekClientIdFallback = "",
    )

    @Test
    fun `clearing drops the old area's events in memory and on disk`() = runBlocking {
        val r = repo()
        assertNotNull("precondition: a cached result exists", r.loadCachedEvents())

        r.clearLocalEvents()

        assertNull(r.loadCachedEvents())
        assertEquals("", disk)
        assertEquals(true, r.isCacheStale)
        // A fresh process reading the blanked file has no cache either.
        assertNull(repo().loadCachedEvents())
    }
}
