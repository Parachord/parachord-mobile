package com.parachord.android.data.db.dao

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.shared.db.ParachordDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistDaoJoinTest {
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
    fun `getByIdWithSpotifyLink returns link-table values when link exists`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "local-abc")
        val linkDao = SyncPlaylistLinkDao(db)
        linkDao.upsertWithSnapshot("local-abc", "spotify", "link-xyz", "link-snap", 1L)
        val dao = PlaylistDao(db)
        val view = dao.getByIdWithSpotifyLink("local-abc")
        assertEquals("link-xyz", view?.spotifyId)
        assertEquals("link-snap", view?.snapshotId)
    }

    @Test
    fun `getByIdWithSpotifyLink falls back to entity scalars when no link`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "local-abc", spotifyId = "legacy-xyz", snapshotId = "legacy-snap")
        val dao = PlaylistDao(db)
        val view = dao.getByIdWithSpotifyLink("local-abc")
        assertEquals("legacy-xyz", view?.spotifyId)
        assertEquals("legacy-snap", view?.snapshotId)
    }

    @Test
    fun `getByIdWithSpotifyLink — link-table values win over legacy scalars`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "local-abc", spotifyId = "legacy-xyz", snapshotId = "legacy-snap")
        val linkDao = SyncPlaylistLinkDao(db)
        linkDao.upsertWithSnapshot("local-abc", "spotify", "link-xyz", "link-snap", 1L)
        val dao = PlaylistDao(db)
        val view = dao.getByIdWithSpotifyLink("local-abc")
        assertEquals("link-xyz", view?.spotifyId)
        assertEquals("link-snap", view?.snapshotId)
        // Escape hatch: view.entity still returns legacy scalars for callers that need them.
        assertEquals("legacy-xyz", view?.entity?.spotifyId)
        assertEquals("legacy-snap", view?.entity?.snapshotId)
    }

    @Test
    fun `getByIdWithSpotifyLink returns null for unknown id`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = PlaylistDao(db)
        assertNull(dao.getByIdWithSpotifyLink("nope"))
    }

    @Test
    fun `other-provider links don't leak into spotifyId`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "local-abc", spotifyId = "legacy-xyz", snapshotId = "legacy-snap")
        val linkDao = SyncPlaylistLinkDao(db)
        // Apple Music link — should NOT affect the Spotify-derived fields.
        linkDao.upsertWithSnapshot("local-abc", "applemusic", "am-xyz", "am-snap", 1L)
        val dao = PlaylistDao(db)
        val view = dao.getByIdWithSpotifyLink("local-abc")
        // Falls back to legacy scalars — no spotify link exists.
        assertEquals("legacy-xyz", view?.spotifyId)
        assertEquals("legacy-snap", view?.snapshotId)
    }
}
