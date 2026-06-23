package com.parachord.android.data.db.dao

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.shared.db.dao.TrackProviderIdCacheDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Negative-cache table (incremental-materialization Task 7a). Round-trip
 * upsert/select, the cooldown stale-candidate query (only null-resolvedId rows
 * past the cutoff, ordered fewest-failed first), and resolved-row exclusion.
 */
class TrackProviderIdCacheDaoTest {

    @Test
    fun `upsert then select round-trips an entry`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        dao.upsert(
            identityKey = "isrc-USRC12345678",
            providerId = "spotify",
            resolvedId = "spotify:track:abc",
            lastAttemptAt = 100L,
            attempts = 1L,
        )
        val entry = dao.select("isrc-USRC12345678", "spotify")
        assertNotNull(entry)
        assertEquals("spotify:track:abc", entry!!.resolvedId)
        assertEquals(100L, entry.lastAttemptAt)
        assertEquals(1L, entry.attempts)
    }

    @Test
    fun `upsert replaces on the composite primary key`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        dao.upsert("norm-a|b", "applemusic", resolvedId = null, lastAttemptAt = 10L, attempts = 1L)
        dao.upsert("norm-a|b", "applemusic", resolvedId = "9999", lastAttemptAt = 20L, attempts = 0L)
        val entry = dao.select("norm-a|b", "applemusic")
        assertNotNull(entry)
        assertEquals("9999", entry!!.resolvedId)
        assertEquals(20L, entry.lastAttemptAt)
        // Same key + provider on a different provider remains distinct.
        assertNull(dao.select("norm-a|b", "spotify"))
    }

    @Test
    fun `selectStale returns only null-resolvedId rows past the cutoff, ordered by attempts asc`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        // Stale, unresolved, 2 attempts.
        dao.upsert("k-stale-2", "spotify", resolvedId = null, lastAttemptAt = 100L, attempts = 2L)
        // Stale, unresolved, 0 attempts → should sort FIRST.
        dao.upsert("k-stale-0", "spotify", resolvedId = null, lastAttemptAt = 100L, attempts = 0L)
        // Resolved → excluded even though it's old.
        dao.upsert("k-resolved", "spotify", resolvedId = "found", lastAttemptAt = 50L, attempts = 0L)
        // Too recent (>= cutoff) → excluded.
        dao.upsert("k-recent", "spotify", resolvedId = null, lastAttemptAt = 5000L, attempts = 0L)

        val stale = dao.selectStale(cutoff = 1000L, limit = 10L)

        assertEquals(2, stale.size)
        // Ordered by attempts ASC → the 0-attempts row comes first.
        assertEquals("k-stale-0", stale[0].identityKey)
        assertEquals("k-stale-2", stale[1].identityKey)
    }

    @Test
    fun `selectStale honors the limit`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        dao.upsert("a", "spotify", null, 1L, 0L)
        dao.upsert("b", "spotify", null, 2L, 0L)
        dao.upsert("c", "spotify", null, 3L, 0L)
        val stale = dao.selectStale(cutoff = 1000L, limit = 2L)
        assertEquals(2, stale.size)
    }

    @Test
    fun `deleteForProvider nukes only that provider's rows`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        dao.upsert("k1", "spotify", "x", 1L, 0L)
        dao.upsert("k2", "applemusic", "y", 2L, 0L)
        dao.deleteForProvider("spotify")
        assertNull(dao.select("k1", "spotify"))
        assertNotNull(dao.select("k2", "applemusic"))
    }
}
