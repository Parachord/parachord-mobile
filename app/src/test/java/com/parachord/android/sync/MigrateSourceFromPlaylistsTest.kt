package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.android.data.db.dao.SyncPlaylistSourceDao
import com.parachord.shared.db.ParachordDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MigrateSourceFromPlaylistsTest {
    /**
     * Inserts a playlist row with enough fields populated for the
     * migration to make a decision. SQLDelight-generated insert query
     * signature is positional and long — use named args.
     */
    private fun ParachordDb.insertPlaylist(
        id: String,
        spotifyId: String? = null,
        snapshotId: String? = null,
    ) {
        playlistQueries.insert(
            id = id,
            name = "Test",
            description = null,
            artworkUrl = null,
            trackCount = 0,
            createdAt = 1L,
            updatedAt = 1L,
            spotifyId = spotifyId,
            snapshotId = snapshotId,
            lastModified = 1L,
            locallyModified = 0L,
            ownerName = null,
            sourceUrl = null,
            sourceContentHash = null,
            localOnly = 0L,
            writable = 1L,
        )
    }

    @Test
    fun `backfills syncedFrom for spotify-imported playlists`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "spotify-abc", spotifyId = "abc", snapshotId = "snap-1")
        val sourceDao = SyncPlaylistSourceDao(db)

        SyncEngine.migrateSourceFromPlaylists(db, sourceDao)

        val src = sourceDao.selectForLocal("spotify-abc")
        assertNotNull(src)
        assertEquals("spotify", src!!.providerId)
        assertEquals("abc", src.externalId)
        assertEquals("snap-1", src.snapshotId)
    }

    @Test
    fun `is idempotent on second call`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "spotify-abc", spotifyId = "abc", snapshotId = "snap-1")
        val sourceDao = SyncPlaylistSourceDao(db)

        SyncEngine.migrateSourceFromPlaylists(db, sourceDao)
        SyncEngine.migrateSourceFromPlaylists(db, sourceDao)

        // Still exactly one row; same content.
        assertEquals(1, sourceDao.selectAll().size)
        assertEquals("snap-1", sourceDao.selectForLocal("spotify-abc")?.snapshotId)
    }

    @Test
    fun `ignores playlists whose id does not start with spotify-`() = runBlocking {
        val db = TestDatabaseFactory.create()
        // local-* playlist with a spotifyId — it's a PUSH target, not a pull source.
        // Must NOT get a syncedFrom row.
        db.insertPlaylist(id = "local-abc", spotifyId = "xyz", snapshotId = "snap-x")
        // hosted XSPF — also not a spotify pull source.
        db.insertPlaylist(id = "hosted-xspf-abc", spotifyId = null, snapshotId = null)
        val sourceDao = SyncPlaylistSourceDao(db)

        SyncEngine.migrateSourceFromPlaylists(db, sourceDao)

        assertNull(sourceDao.selectForLocal("local-abc"))
        assertNull(sourceDao.selectForLocal("hosted-xspf-abc"))
        assertEquals(0, sourceDao.selectAll().size)
    }

    @Test
    fun `skips spotify- playlist with null spotifyId (defensive)`() = runBlocking {
        val db = TestDatabaseFactory.create()
        // Can happen if a playlist was mid-migration from an older schema.
        // Don't create a broken sync_playlist_source row with empty externalId.
        db.insertPlaylist(id = "spotify-abc", spotifyId = null, snapshotId = null)
        val sourceDao = SyncPlaylistSourceDao(db)

        SyncEngine.migrateSourceFromPlaylists(db, sourceDao)

        assertNull(sourceDao.selectForLocal("spotify-abc"))
    }

    @Test
    fun `mixed DB — only spotify-prefixed imports get source rows`() = runBlocking {
        val db = TestDatabaseFactory.create()
        // Real-world mix: one Spotify import, one hosted XSPF, one local-* push mirror.
        db.insertPlaylist(id = "spotify-abc", spotifyId = "abc", snapshotId = "snap-1")
        db.insertPlaylist(id = "hosted-xspf-def", spotifyId = null, snapshotId = null)
        db.insertPlaylist(id = "local-ghi", spotifyId = "ghi", snapshotId = "snap-ghi")
        val sourceDao = SyncPlaylistSourceDao(db)

        SyncEngine.migrateSourceFromPlaylists(db, sourceDao)

        // Exactly one source row; it belongs to the spotify-prefixed playlist.
        val all = sourceDao.selectAll()
        assertEquals(1, all.size)
        assertEquals("spotify-abc", all.first().localPlaylistId)
        assertNull(sourceDao.selectForLocal("hosted-xspf-def"))
        assertNull(sourceDao.selectForLocal("local-ghi"))
    }
}
