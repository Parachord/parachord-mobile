package com.parachord.android.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.LocalLibraryMigration
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.SyncPlaylistLinkDao
import com.parachord.shared.db.dao.SyncPlaylistSourceDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.Track
import com.parachord.shared.repository.LibraryRepository
import com.parachord.shared.sync.SyncEngine
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #705: a scanned local file joins the local library, not the collection, so
 * it isn't a love; only the heart makes it one, and removing the heart keeps
 * the file.
 */
class LocalLibraryNotLovesTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        ParachordDb.Schema.create(it)
    }
    private val db = ParachordDb(driver)
    private val trackDao = TrackDao(db, driver)
    private val syncEngine = mockk<SyncEngine>(relaxed = true)

    private val repo = LibraryRepository(
        trackDao = trackDao,
        albumDao = AlbumDao(db),
        artistDao = ArtistDao(db),
        playlistDao = PlaylistDao(db),
        playlistTrackDao = PlaylistTrackDao(db),
        syncEngine = syncEngine,
        syncPlaylistLinkDao = SyncPlaylistLinkDao(db),
        syncPlaylistSourceDao = SyncPlaylistSourceDao(db),
        mbidEnrichTrack = { _, _, _ -> },
        mbidEnrichBatch = {},
        tombstones = mockk(relaxed = true),
        pushLovedTrack = { pushed += it.id },
    )
    private val pushed = mutableListOf<String>()

    private val file = Track(
        id = "local-42", title = "Clover", artist = "Tundra 212",
        resolver = "localfiles", sourceUrl = "content://media/external/audio/media/42", addedAt = 5L,
    )

    private fun inCollection(t: Track) = runBlocking {
        trackDao.existsByTitleAndArtist(t.title, t.artist).first()
    }

    @Test
    fun `a scanned file is listed but not loved`() = runBlocking {
        repo.addScannedLocalTracks(listOf(file))

        assertEquals(1, trackDao.getAll().first().size)
        assertFalse(trackDao.getById(file.id)!!.inCollection)
        assertFalse(inCollection(file))
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun `the heart loves a scanned file and dates it now`() = runBlocking {
        repo.addScannedLocalTracks(listOf(file))
        repo.addToCollection(file.copy(inCollection = false))

        val row = trackDao.getById(file.id)!!
        assertTrue(row.inCollection)
        assertTrue(row.addedAt > 5L)
        assertTrue(inCollection(file))
        assertEquals(listOf(file.id), pushed)
    }

    @Test
    fun `a rescan keeps a hearted file loved`() = runBlocking {
        repo.addScannedLocalTracks(listOf(file))
        repo.addToCollection(file)
        repo.addScannedLocalTracks(listOf(file))

        assertTrue(trackDao.getById(file.id)!!.inCollection)
    }

    @Test
    fun `removing the heart keeps the file in the library`() = runBlocking {
        repo.addScannedLocalTracks(listOf(file))
        repo.addToCollection(file)

        assertTrue(repo.removeFromCollection(file))

        val row = trackDao.getById(file.id)!!
        assertFalse(row.inCollection)
        assertFalse(inCollection(file))
    }

    @Test
    fun `removing a heart from an unloved file does nothing`() = runBlocking {
        repo.addScannedLocalTracks(listOf(file))
        assertFalse(repo.removeFromCollection(file))
        assertEquals(1, trackDao.getAll().first().size)
    }

    @Test
    fun `the one-time cleanup unloves scanned and imported files only`() = runBlocking {
        trackDao.insert(file)
        trackDao.insert(file.copy(id = "fileimport-abc", title = "Imported"))
        trackDao.insert(Track(id = "spotify-1", title = "Saved", artist = "A", resolver = "spotify"))
        assertTrue(trackDao.getById(file.id)!!.inCollection)

        driver.execute(null, LocalLibraryMigration.MARK_SCANNED_LOCAL_FILES_LIBRARY_ONLY, 0)

        assertFalse(trackDao.getById(file.id)!!.inCollection)
        assertFalse(trackDao.getById("fileimport-abc")!!.inCollection)
        assertTrue(trackDao.getById("spotify-1")!!.inCollection)
    }
}
