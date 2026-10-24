package com.parachord.android.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LibraryRepository.removeFromCollection] — removing a track handed over by a
 * screen whose row isn't the library row (weekly playlist, now-playing). The
 * old weekly-playlist path deleted the ephemeral row's id: it matched nothing
 * AND skipped [SyncEngine.onTrackRemoved], so no tombstone was written.
 */
class RemoveFromCollectionTest {

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
    )

    private val libraryRow = Track(
        id = "spotify-abc123", title = "Clover", artist = "Tundra 212", addedAt = 1L,
    )

    @Test
    fun `ephemeral track is matched to the library row by title and artist`() = runBlocking {
        trackDao.insert(libraryRow)
        // Weekly-playlist rows carry their own id; title/artist case differs too.
        val ephemeral = Track(id = "weekly-xyz-3", title = "clover", artist = "TUNDRA 212")

        assertTrue(repo.removeFromCollection(ephemeral))

        assertNull(trackDao.getById(libraryRow.id))
        // Sync removal + tombstones key on the LIBRARY row's id, not the ephemeral one.
        coVerify(exactly = 1) { syncEngine.onTrackRemoved(match { it.id == libraryRow.id }) }
    }

    @Test
    fun `library row passed directly is removed by id`() = runBlocking {
        trackDao.insert(libraryRow)
        trackDao.insert(libraryRow.copy(id = "local-dup", addedAt = 0L))

        assertTrue(repo.removeFromCollection(libraryRow))

        assertNull(trackDao.getById(libraryRow.id))
        // The id match wins over the (older) title+artist duplicate.
        assertEquals("local-dup", trackDao.getById("local-dup")?.id)
        coVerify(exactly = 1) { syncEngine.onTrackRemoved(match { it.id == libraryRow.id }) }
    }

    @Test
    fun `no library match is a no-op`() = runBlocking {
        assertFalse(repo.removeFromCollection(Track(id = "weekly-1", title = "Nope", artist = "Nobody")))
        coVerify(exactly = 0) { syncEngine.onTrackRemoved(any()) }
    }
}
