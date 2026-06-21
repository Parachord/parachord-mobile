package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.SyncPlaylistLinkDao
import com.parachord.android.data.db.dao.SyncPlaylistSourceDao
import com.parachord.shared.db.ParachordDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end exercise of every Phase 1 schema addition. Represents a
 * realistic multi-provider state — a Spotify-imported playlist mirrored to
 * Apple Music with a pending-delete action on the AM side — and verifies
 * every read path surfaces the state correctly.
 *
 * Doesn't test propagation logic (that's Phase 3); this is strictly
 * "the schema + DAOs round-trip what we put in."
 */
class Phase1SchemaIntegrationTest {
    private fun ParachordDb.insertPlaylist(
        id: String,
        spotifyId: String? = null,
        snapshotId: String? = null,
        localOnly: Long = 0L,
    ) {
        playlistQueries.insert(
            id = id,
            name = "Test Playlist",
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
            localOnly = localOnly,
            writable = 1L,
        )
    }

    @Test
    fun `full multi-provider sync state round-trips through every new field`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val playlistDao = PlaylistDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val sourceDao = SyncPlaylistSourceDao(db)

        // Scenario: a Spotify-imported playlist (local id convention
        // `spotify-<externalId>`) mirrored to Apple Music. Apple Music's
        // mirror got deleted remotely and is pending re-push or unlink.
        // The local user has NOT flagged localOnly (it's synced).
        db.insertPlaylist(
            id = "spotify-abc",
            spotifyId = "abc",
            snapshotId = "snap-spotify-1",
            localOnly = 0L,
        )

        // syncedFrom row — this playlist was imported from Spotify.
        sourceDao.upsert(
            localPlaylistId = "spotify-abc",
            providerId = "spotify",
            externalId = "abc",
            snapshotId = "snap-spotify-1",
            ownerId = "user-123",
            syncedAt = 1000L,
        )

        // syncedTo rows — this playlist mirrors to both Spotify (round-trip)
        // and Apple Music. The Apple Music mirror is marked remote-deleted.
        linkDao.upsertWithSnapshot(
            localPlaylistId = "spotify-abc",
            providerId = "spotify",
            externalId = "abc",
            snapshotId = "snap-spotify-1",
            syncedAt = 1000L,
        )
        linkDao.upsertWithSnapshot(
            localPlaylistId = "spotify-abc",
            providerId = "applemusic",
            externalId = "am-xyz",
            snapshotId = "2026-04-20T12:00:00Z",
            syncedAt = 1000L,
        )
        linkDao.setPendingAction("spotify-abc", "applemusic", "remote-deleted")

        // -- Read everything back --

        // Playlist row with link-derived spotifyId/snapshotId (Decision 5).
        val view = playlistDao.getByIdWithSpotifyLink("spotify-abc")
        assertNotNull(view)
        assertEquals("abc", view!!.spotifyId)
        assertEquals("snap-spotify-1", view.snapshotId)
        assertEquals(false, view.entity.localOnly)

        // Legacy scalars are still there for UI/share readers.
        assertEquals("abc", view.entity.spotifyId)
        assertEquals("snap-spotify-1", view.entity.snapshotId)

        // syncedFrom representation.
        val source = sourceDao.selectForLocal("spotify-abc")
        assertNotNull(source)
        assertEquals("spotify", source!!.providerId)
        assertEquals("abc", source.externalId)
        assertEquals("user-123", source.ownerId)
        assertEquals("snap-spotify-1", source.snapshotId)

        // syncedTo representation — Apple Music mirror with pending action.
        val amLink = linkDao.selectForLink("spotify-abc", "applemusic")
        assertNotNull(amLink)
        assertEquals("am-xyz", amLink!!.externalId)
        assertEquals("2026-04-20T12:00:00Z", amLink.snapshotId)
        assertEquals("remote-deleted", amLink.pendingAction)

        // Spotify mirror — no pending action.
        val spotifyLink = linkDao.selectForLink("spotify-abc", "spotify")
        assertNotNull(spotifyLink)
        assertNull(spotifyLink!!.pendingAction)

        // Pending query filters correctly — only AM has a pending action.
        val pendingAm = linkDao.selectPendingForProvider("applemusic")
        assertEquals(1, pendingAm.size)
        assertEquals("spotify-abc", pendingAm.first().localPlaylistId)
        val pendingSpotify = linkDao.selectPendingForProvider("spotify")
        assertTrue(pendingSpotify.isEmpty())
    }

    @Test
    fun `localOnly true blocks no behavior at schema layer — just stored`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "local-private", spotifyId = null, localOnly = 1L)
        val view = PlaylistDao(db).getByIdWithSpotifyLink("local-private")
        assertNotNull(view)
        assertEquals(true, view!!.entity.localOnly)
        // No link, no source — purely local.
        assertNull(SyncPlaylistLinkDao(db).selectForLink("local-private", "spotify"))
        assertNull(SyncPlaylistSourceDao(db).selectForLocal("local-private"))
    }

    @Test
    fun `setLocalOnly flips the flag and persists`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "local-abc", localOnly = 0L)
        db.playlistQueries.setLocalOnly(1L, "local-abc")
        val view = PlaylistDao(db).getByIdWithSpotifyLink("local-abc")
        assertEquals(true, view!!.entity.localOnly)
    }
}
