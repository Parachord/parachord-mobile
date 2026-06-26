package com.parachord.android.data.db.dao

import com.parachord.android.data.db.TestDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPlaylistLinkDaoTest {
    @Test
    fun `upsertWithSnapshot stores and retrieves snapshotId`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot(
            localPlaylistId = "local-abc",
            providerId = "spotify",
            externalId = "xyz",
            snapshotId = "snap-1",
            syncedAt = 1L,
        )
        assertEquals("snap-1", dao.selectForLink("local-abc", "spotify")?.snapshotId)
    }

    @Test
    fun `setPendingAction and clearPendingAction round-trip`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("a", "spotify", "x", null, 1L)
        dao.setPendingAction("a", "spotify", "remote-deleted")
        assertEquals("remote-deleted", dao.selectForLink("a", "spotify")?.pendingAction)
        dao.clearPendingAction("a", "spotify")
        assertNull(dao.selectForLink("a", "spotify")?.pendingAction)
    }

    @Test
    fun `selectPendingForProvider returns only rows with pendingAction set`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("a", "spotify", "x", null, 1L)
        dao.upsertWithSnapshot("b", "spotify", "y", null, 2L)
        dao.upsertWithSnapshot("c", "applemusic", "z", null, 3L)
        dao.setPendingAction("b", "spotify", "remote-deleted")
        dao.setPendingAction("c", "applemusic", "remote-deleted")
        val pendingSpotify = dao.selectPendingForProvider("spotify")
        assertEquals(1, pendingSpotify.size)
        assertEquals("b", pendingSpotify.first().localPlaylistId)
        // Verify the provider filter actually works, not just "only one spotify row happens to be pending."
        val pendingApple = dao.selectPendingForProvider("applemusic")
        assertEquals(1, pendingApple.size)
        assertEquals("c", pendingApple.first().localPlaylistId)
    }

    @Test
    fun `clearStaleNwayFillMarkers nulls nway-fill rows and leaves others intact`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("a", "applemusic", "x", null, 1L)
        dao.upsertWithSnapshot("b", "applemusic", "y", null, 2L)
        dao.upsertWithSnapshot("c", "spotify", "z", null, 3L)
        // Stale leftover from the deleted N-way fill writer.
        dao.setPendingAction("a", "applemusic", "nway-fill")
        // A legitimate deferred action that must survive the sweep.
        dao.setPendingAction("b", "applemusic", "remote-deleted")
        // 'c' stays null.

        dao.clearStaleNwayFillMarkers()

        assertNull("nway-fill marker cleared", dao.selectForLink("a", "applemusic")?.pendingAction)
        assertEquals(
            "remote-deleted left intact",
            "remote-deleted",
            dao.selectForLink("b", "applemusic")?.pendingAction,
        )
        assertNull("untouched row stays null", dao.selectForLink("c", "spotify")?.pendingAction)

        // Idempotent — a second run is a no-op (no rows match 'nway-fill').
        dao.clearStaleNwayFillMarkers()
        assertEquals(
            "remote-deleted still intact after second run",
            "remote-deleted",
            dao.selectForLink("b", "applemusic")?.pendingAction,
        )
    }

    @Test
    fun `upsertWithSnapshot twice on same key updates snapshotId`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("local-abc", "spotify", "xyz", "snap-1", 1L)
        dao.upsertWithSnapshot("local-abc", "spotify", "xyz", "snap-2", 2L)
        val row = dao.selectForLink("local-abc", "spotify")
        assertNotNull(row)
        assertEquals("snap-2", row!!.snapshotId)
        assertEquals(2L, row.syncedAt)
        // Should be exactly one row — upsert, not insert-append.
        assertEquals(1, dao.selectAll().size)
    }
}
