package com.parachord.android.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.AppleMusicLibraryIdRepair
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track
import com.parachord.shared.store.KvStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #265 — clearing Apple Music library ids saved where a catalog id belongs. */
class AppleMusicLibraryIdRepairTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
    private val db = ParachordDb(driver)
    private val trackDao = TrackDao(db, driver)
    private val playlistTrackDao = PlaylistTrackDao(db)

    private fun pt(position: Int, amId: String?) = PlaylistTrack(
        playlistId = "applemusic-p.goleKsZDVNL", position = position,
        trackTitle = "t$position", trackArtist = "A", trackAppleMusicId = amId,
    )

    @Test
    fun libraryIdsAreCleared_catalogIdsAndOtherColumnsKept() = runBlocking {
        playlistTrackDao.insertAll(listOf(pt(0, "i.3ZKYfNV54d"), pt(1, "l.oldStyle"), pt(2, "1440857781"), pt(3, null)))
        // The row id keeps the library id on purpose (it's the sync key); only appleMusicId is a catalog-id column.
        trackDao.insert(Track(id = "applemusic-i.abc", title = "T", artist = "A", appleMusicId = "i.abc", addedAt = 1L))
        trackDao.insert(Track(id = "spotify-xyz", title = "U", artist = "B", appleMusicId = "1440857781", addedAt = 1L))

        AppleMusicLibraryIdRepair.run(driver)

        assertEquals(
            listOf(null, null, "1440857781", null),
            playlistTrackDao.getByPlaylistIdSync("applemusic-p.goleKsZDVNL").sortedBy { it.position }.map { it.trackAppleMusicId },
        )
        val repaired = trackDao.getById("applemusic-i.abc")
        assertNull(repaired?.appleMusicId)
        assertEquals("T", repaired?.title)
        assertEquals("1440857781", trackDao.getById("spotify-xyz")?.appleMusicId)
    }

    @Test
    fun runOnceRunsOnlyTheFirstTime() = runBlocking {
        val kv = KvStore(MapSettings())
        assertTrue(AppleMusicLibraryIdRepair.runOnce(driver, kv))
        assertFalse(AppleMusicLibraryIdRepair.runOnce(driver, kv))
    }

    private fun snapshot(local: String, provider: String): String? =
        driver.executeQuery(null, "SELECT snapshotId FROM sync_playlist_link WHERE localPlaylistId = '$local' AND providerId = '$provider'", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else "<no row>")
        }, 0).value

    @Test
    fun playlistsPulledFromAppleMusicWithMissingIdsAreRefetched() = runBlocking {
        // Starred: pulled from Apple Music, its rows lost their ids in the repair.
        driver.execute(null, "INSERT INTO sync_playlist_source VALUES ('applemusic-p.starred','applemusic','p.starred','2026-01-01',NULL,1)", 0)
        driver.execute(null, "INSERT INTO sync_playlist_link VALUES ('applemusic-p.starred','applemusic','p.starred','2026-01-01',NULL,1)", 0)
        playlistTrackDao.insertAll(listOf(pt(0, null).copy(playlistId = "applemusic-p.starred")))
        // Complete: pulled from Apple Music, every row has its catalog id.
        driver.execute(null, "INSERT INTO sync_playlist_source VALUES ('applemusic-p.done','applemusic','p.done','2026-01-02',NULL,1)", 0)
        driver.execute(null, "INSERT INTO sync_playlist_link VALUES ('applemusic-p.done','applemusic','p.done','2026-01-02',NULL,1)", 0)
        playlistTrackDao.insertAll(listOf(pt(0, "1440857781").copy(playlistId = "applemusic-p.done")))
        // A Spotify playlist mirrored to Apple Music: not ours to re-pull.
        driver.execute(null, "INSERT INTO sync_playlist_source VALUES ('spotify-x','spotify','x','snap',NULL,1)", 0)
        driver.execute(null, "INSERT INTO sync_playlist_link VALUES ('spotify-x','applemusic','p.mirror','2026-01-03',NULL,1)", 0)
        playlistTrackDao.insertAll(listOf(pt(0, null).copy(playlistId = "spotify-x")))

        AppleMusicLibraryIdRepair.refetchPulledPlaylists(driver)

        assertNull(snapshot("applemusic-p.starred", "applemusic"))
        assertEquals("2026-01-02", snapshot("applemusic-p.done", "applemusic"))
        assertEquals("2026-01-03", snapshot("spotify-x", "applemusic"))
    }
}
