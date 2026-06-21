package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.SyncPlaylistLinkDao
import com.parachord.android.data.db.dao.SyncPlaylistSourceDao
import com.parachord.shared.db.ParachordDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch]:
 * mirrors desktop's `healImportedSyncedFromMismatch` (closes #146).
 *
 * Implementation lives in `shared/commonMain` so iOS inherits it. Test lives
 * here because `TestDatabaseFactory` (JDBC SQLite driver) is JVM-only and
 * commonTest can't pull in a JDBC dependency without breaking the iOS build.
 * Same approach as the sibling [MigrateSourceFromPlaylistsTest].
 */
class HealImportedSyncedFromTest {

    private fun ParachordDb.insertPlaylist(
        id: String,
        spotifyId: String? = null,
        snapshotId: String? = null,
        locallyModified: Long = 0L,
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
            locallyModified = locallyModified,
            ownerName = null,
            sourceUrl = null,
            sourceContentHash = null,
            localOnly = 0L,
            writable = 1L,
        )
    }

    @Test
    fun `healthy spotifyImported is no-op`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "spotify-X", spotifyId = "X")
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistDao = PlaylistDao(db)

        // Pre-seed the healthy source row.
        sourceDao.upsert(
            localPlaylistId = "spotify-X",
            providerId = "spotify",
            externalId = "X",
            snapshotId = "snap-1",
            ownerId = null,
            syncedAt = 100L,
        )

        com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch(
            playlistDao, sourceDao, linkDao,
        )

        val src = sourceDao.selectForLocal("spotify-X")
        assertNotNull(src)
        assertEquals("spotify", src!!.providerId)
        assertEquals("X", src.externalId)
        // Unchanged: snapshot preserved, no link created.
        assertEquals("snap-1", src.snapshotId)
        assertEquals(100L, src.syncedAt)
        assertTrue(linkDao.selectForLocal("spotify-X").isEmpty())
    }

    @Test
    fun `corrupted spotifyImported with applemusic source heals and demotes`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "spotify-X", spotifyId = "X")
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistDao = PlaylistDao(db)

        // Corrupt: spotify-prefix playlist with applemusic in sync_playlist_source.
        sourceDao.upsert(
            localPlaylistId = "spotify-X",
            providerId = "applemusic",
            externalId = "AM-Y",
            snapshotId = "am-snap",
            ownerId = null,
            syncedAt = 100L,
        )

        com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch(
            playlistDao, sourceDao, linkDao,
        )

        // Source row corrected to spotify.
        val src = sourceDao.selectForLocal("spotify-X")
        assertNotNull(src)
        assertEquals("spotify", src!!.providerId)
        assertEquals("X", src.externalId)
        assertNull(src.snapshotId) // null so next sync repopulates

        // Demoted applemusic mapping preserved as a link.
        val link = linkDao.selectForLink("spotify-X", "applemusic")
        assertNotNull(link)
        assertEquals("AM-Y", link!!.externalId)
        assertEquals("am-snap", link.snapshotId)
    }

    @Test
    fun `corrupted but link already exists for wrong provider does not overwrite link`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "spotify-X", spotifyId = "X")
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistDao = PlaylistDao(db)

        sourceDao.upsert(
            localPlaylistId = "spotify-X",
            providerId = "applemusic",
            externalId = "AM-Y",
            snapshotId = "am-snap-stale",
            ownerId = null,
            syncedAt = 100L,
        )
        // Pre-existing applemusic link with a different externalId and fresher snapshot.
        linkDao.upsertWithSnapshot(
            localPlaylistId = "spotify-X",
            providerId = "applemusic",
            externalId = "AM-PRESERVED",
            snapshotId = "am-snap-fresh",
            syncedAt = 200L,
        )

        com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch(
            playlistDao, sourceDao, linkDao,
        )

        // Source corrected.
        val src = sourceDao.selectForLocal("spotify-X")
        assertEquals("spotify", src!!.providerId)
        assertEquals("X", src.externalId)

        // Existing link preserved untouched.
        val link = linkDao.selectForLink("spotify-X", "applemusic")
        assertNotNull(link)
        assertEquals("AM-PRESERVED", link!!.externalId)
        assertEquals("am-snap-fresh", link.snapshotId)
        assertEquals(200L, link.syncedAt)
    }

    @Test
    fun `missing source spotifyImported creates source from prefix`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "spotify-X", spotifyId = "X")
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistDao = PlaylistDao(db)

        // No source row at all.
        assertNull(sourceDao.selectForLocal("spotify-X"))

        com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch(
            playlistDao, sourceDao, linkDao,
        )

        val src = sourceDao.selectForLocal("spotify-X")
        assertNotNull(src)
        assertEquals("spotify", src!!.providerId)
        assertEquals("X", src.externalId)
        assertNull(src.snapshotId)
        assertNull(src.ownerId)
        // No link should be created when there was no existing source to demote.
        assertTrue(linkDao.selectForLocal("spotify-X").isEmpty())
    }

    @Test
    fun `noPrefix localId skips`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "local-uuid-abc")
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistDao = PlaylistDao(db)

        com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch(
            playlistDao, sourceDao, linkDao,
        )

        // Still no source row, no error.
        assertNull(sourceDao.selectForLocal("local-uuid-abc"))
        assertTrue(linkDao.selectForLocal("local-uuid-abc").isEmpty())
    }

    @Test
    fun `mixed playlists only heals corrupted`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "spotify-HEALTHY", spotifyId = "HEALTHY")
        db.insertPlaylist(id = "applemusic-HEALTHY-AM", spotifyId = null)
        db.insertPlaylist(id = "spotify-CORRUPT", spotifyId = "CORRUPT")
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistDao = PlaylistDao(db)

        // Healthy spotify.
        sourceDao.upsert(
            localPlaylistId = "spotify-HEALTHY",
            providerId = "spotify",
            externalId = "HEALTHY",
            snapshotId = "sp-snap",
            ownerId = null,
            syncedAt = 100L,
        )
        // Healthy applemusic.
        sourceDao.upsert(
            localPlaylistId = "applemusic-HEALTHY-AM",
            providerId = "applemusic",
            externalId = "HEALTHY-AM",
            snapshotId = "am-snap",
            ownerId = null,
            syncedAt = 100L,
        )
        // Corrupt: spotify-prefix playlist with applemusic source.
        sourceDao.upsert(
            localPlaylistId = "spotify-CORRUPT",
            providerId = "applemusic",
            externalId = "WRONG-ID",
            snapshotId = "wrong-snap",
            ownerId = null,
            syncedAt = 100L,
        )

        com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch(
            playlistDao, sourceDao, linkDao,
        )

        // Healthy rows unchanged.
        val healthySp = sourceDao.selectForLocal("spotify-HEALTHY")!!
        assertEquals("spotify", healthySp.providerId)
        assertEquals("HEALTHY", healthySp.externalId)
        assertEquals("sp-snap", healthySp.snapshotId)
        assertEquals(100L, healthySp.syncedAt)

        val healthyAm = sourceDao.selectForLocal("applemusic-HEALTHY-AM")!!
        assertEquals("applemusic", healthyAm.providerId)
        assertEquals("HEALTHY-AM", healthyAm.externalId)
        assertEquals("am-snap", healthyAm.snapshotId)
        assertEquals(100L, healthyAm.syncedAt)

        // Corrupt row corrected.
        val healed = sourceDao.selectForLocal("spotify-CORRUPT")!!
        assertEquals("spotify", healed.providerId)
        assertEquals("CORRUPT", healed.externalId)
        assertNull(healed.snapshotId)

        // Demoted link only for the corrupt one.
        val link = linkDao.selectForLink("spotify-CORRUPT", "applemusic")
        assertNotNull(link)
        assertEquals("WRONG-ID", link!!.externalId)
        assertEquals("wrong-snap", link.snapshotId)

        // Healthy ones never got a phantom link.
        assertTrue(linkDao.selectForLocal("spotify-HEALTHY").isEmpty())
        assertTrue(linkDao.selectForLocal("applemusic-HEALTHY-AM").isEmpty())
    }

    @Test
    fun `heal restores listenbrainz source when playlist ID prefix is listenbrainz`() = runBlocking {
        val db = TestDatabaseFactory.create()
        // listenbrainz-<mbid> playlist row, but sync_playlist_source is wrongly
        // pointing at spotify (corrupt state — e.g. from a bug in a prior client).
        db.insertPlaylist(id = "listenbrainz-abc123")
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistDao = PlaylistDao(db)

        sourceDao.upsert(
            localPlaylistId = "listenbrainz-abc123",
            providerId = "spotify",
            externalId = "SP-WRONG",
            snapshotId = "sp-snap",
            ownerId = null,
            syncedAt = 100L,
        )

        com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch(
            playlistDao, sourceDao, linkDao,
        )

        // Source row corrected to listenbrainz with externalId derived from prefix.
        val src = sourceDao.selectForLocal("listenbrainz-abc123")
        assertNotNull(src)
        assertEquals("listenbrainz", src!!.providerId)
        assertEquals("abc123", src.externalId)
        assertNull(src.snapshotId) // null so next LB sync repopulates

        // Demoted spotify mapping preserved as a link.
        val link = linkDao.selectForLink("listenbrainz-abc123", "spotify")
        assertNotNull(link)
        assertEquals("SP-WRONG", link!!.externalId)
        assertEquals("sp-snap", link.snapshotId)
    }

    @Test
    fun `healthy listenbrainz playlist is no-op`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "listenbrainz-abc123")
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistDao = PlaylistDao(db)

        sourceDao.upsert(
            localPlaylistId = "listenbrainz-abc123",
            providerId = "listenbrainz",
            externalId = "abc123",
            snapshotId = "lb-snap",
            ownerId = null,
            syncedAt = 100L,
        )

        com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch(
            playlistDao, sourceDao, linkDao,
        )

        val src = sourceDao.selectForLocal("listenbrainz-abc123")
        assertNotNull(src)
        assertEquals("listenbrainz", src!!.providerId)
        assertEquals("abc123", src.externalId)
        assertEquals("lb-snap", src.snapshotId) // snapshot preserved
        assertEquals(100L, src.syncedAt)
        assertTrue(linkDao.selectForLocal("listenbrainz-abc123").isEmpty())
    }

    @Test
    fun `clears locallyModified flag on healed playlist`() = runBlocking {
        val db = TestDatabaseFactory.create()
        db.insertPlaylist(id = "spotify-X", spotifyId = "X", locallyModified = 1L)
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val playlistDao = PlaylistDao(db)

        sourceDao.upsert(
            localPlaylistId = "spotify-X",
            providerId = "applemusic",
            externalId = "AM-Y",
            snapshotId = null,
            ownerId = null,
            syncedAt = 100L,
        )

        // Sanity-check the seed.
        assertTrue(playlistDao.getAllSync().first { it.id == "spotify-X" }.locallyModified)

        com.parachord.shared.sync.SyncEngine.healImportedSyncedFromMismatch(
            playlistDao, sourceDao, linkDao,
        )

        // Flag cleared.
        val pl = playlistDao.getAllSync().first { it.id == "spotify-X" }
        assertFalse(pl.locallyModified)
    }
}
