package com.parachord.android.data.db

import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Playlist freshness (shared [com.parachord.shared.home.PlaylistFreshness]):
 * the DAO stamps `tracksChangedAt` / `lastChangeAdded` when a write changes a
 * playlist's tracks, and only then. In-memory database, fixed clock.
 */
class PlaylistTrackStampTest {

    private val now = 5_000_000L

    private fun setup(): Triple<ParachordDb, PlaylistDao, PlaylistTrackDao> {
        val db = TestDatabaseFactory.create()
        return Triple(db, PlaylistDao(db), PlaylistTrackDao(db, nowMs = { now }))
    }

    private fun track(i: Int, title: String, artist: String = "Artist", playlistId: String = "p") =
        PlaylistTrack(playlistId = playlistId, position = i, trackTitle = title, trackArtist = artist)

    private fun stampOf(db: ParachordDb, id: String = "p"): Pair<Long?, Long> =
        db.playlistQueries.getTrackStamp(id).executeAsOne().let { it.tracksChangedAt to it.lastChangeAdded }

    private suspend fun seedPlaylist(dao: PlaylistDao, id: String = "p") =
        dao.insert(Playlist(id = id, name = "Road trip"))

    @Test
    fun `insertAll into an empty playlist stamps now with its track count`() = runBlocking {
        val (db, playlistDao, dao) = setup()
        seedPlaylist(playlistDao)
        dao.insertAll(listOf(track(0, "One"), track(1, "Two"), track(2, "Three")))
        assertEquals(now to 3L, stampOf(db))
    }

    @Test
    fun `insertAll stamps each playlist it writes`() = runBlocking {
        val (db, playlistDao, dao) = setup()
        seedPlaylist(playlistDao, "p")
        seedPlaylist(playlistDao, "q")
        dao.insertAll(listOf(track(0, "One"), track(0, "A", playlistId = "q"), track(1, "B", playlistId = "q")))
        assertEquals(now to 1L, stampOf(db, "p"))
        assertEquals(now to 2L, stampOf(db, "q"))
    }

    @Test
    fun `replaceAll with the same tracks in the same order leaves the stamp`() = runBlocking {
        val (db, playlistDao, dao) = setup()
        seedPlaylist(playlistDao)
        dao.insertAll(listOf(track(0, "One"), track(1, "Two")))
        db.playlistQueries.stampTrackChange(1000L, 2L, "p")
        // Same names, different ids and art: not a change.
        dao.replaceAll("p", listOf(
            track(0, "One").copy(trackSpotifyId = "x", trackArtworkUrl = "https://a"),
            track(1, "Two").copy(trackAppleMusicId = "123"),
        ))
        assertEquals(1000L to 2L, stampOf(db))
    }

    @Test
    fun `replaceAll that adds one track stamps now with 1`() = runBlocking {
        val (db, playlistDao, dao) = setup()
        seedPlaylist(playlistDao)
        dao.insertAll(listOf(track(0, "One"), track(1, "Two")))
        db.playlistQueries.stampTrackChange(1000L, 2L, "p")
        dao.replaceAll("p", listOf(track(0, "One"), track(1, "Two"), track(2, "Three")))
        assertEquals(now to 1L, stampOf(db))
    }

    @Test
    fun `replaceAll that reorders stamps now with 0`() = runBlocking {
        val (db, playlistDao, dao) = setup()
        seedPlaylist(playlistDao)
        dao.insertAll(listOf(track(0, "One"), track(1, "Two")))
        db.playlistQueries.stampTrackChange(1000L, 2L, "p")
        dao.replaceAll("p", listOf(track(0, "Two"), track(1, "One")))
        assertEquals(now to 0L, stampOf(db))
    }

    @Test
    fun `deleteTrack stamps now with 0`() = runBlocking {
        val (db, playlistDao, dao) = setup()
        seedPlaylist(playlistDao)
        dao.insertAll(listOf(track(0, "One"), track(1, "Two")))
        db.playlistQueries.stampTrackChange(1000L, 2L, "p")
        dao.deleteTrack("p", 1)
        assertEquals(now to 0L, stampOf(db))
    }

    @Test
    fun `a cover or id backfill is not a change`() = runBlocking {
        val (db, playlistDao, dao) = setup()
        seedPlaylist(playlistDao)
        dao.insertAll(listOf(track(0, "One"), track(1, "Two")))
        db.playlistQueries.stampTrackChange(1000L, 2L, "p")
        dao.updateTrackArtwork("p", 0, "One", "Artist", "https://art")
        dao.backfillResolverIds("p", 1, "Two", "Artist", "sp", "spotify:track:sp", "1", null, null)
        assertEquals(1000L to 2L, stampOf(db))
    }

    @Test
    fun `deleteByPlaylistId never stamps`() = runBlocking {
        val (db, playlistDao, dao) = setup()
        seedPlaylist(playlistDao)
        dao.insertAll(listOf(track(0, "One")))
        db.playlistQueries.stampTrackChange(1000L, 1L, "p")
        dao.deleteByPlaylistId("p")
        assertEquals(1000L to 1L, stampOf(db))
    }

    @Test
    fun `a playlist save with no stamp keeps the stored one`() = runBlocking {
        val (db, playlistDao, _) = setup()
        seedPlaylist(playlistDao)
        db.playlistQueries.stampTrackChange(1000L, 2L, "p")
        playlistDao.insert(Playlist(id = "p", name = "Renamed", tracksChangedAt = null, lastChangeAdded = 0))
        assertEquals(1000L to 2L, stampOf(db))
        assertEquals("Renamed", playlistDao.getById("p")!!.name)
    }

    @Test
    fun `a playlist save with an older stamp keeps the stored newer one`() = runBlocking {
        val (db, playlistDao, _) = setup()
        seedPlaylist(playlistDao)
        db.playlistQueries.stampTrackChange(1000L, 2L, "p")
        playlistDao.insert(Playlist(id = "p", name = "Stale copy", tracksChangedAt = 500L, lastChangeAdded = 9))
        assertEquals(1000L to 2L, stampOf(db))
    }

    @Test
    fun `a playlist save with a later stamp writes it`() = runBlocking {
        val (db, playlistDao, _) = setup()
        seedPlaylist(playlistDao)
        db.playlistQueries.stampTrackChange(1000L, 2L, "p")
        playlistDao.insert(Playlist(id = "p", name = "Road trip", tracksChangedAt = 2000L, lastChangeAdded = 4))
        assertEquals(2000L to 4L, stampOf(db))
        val read = playlistDao.getById("p")!!
        assertEquals(2000L, read.tracksChangedAt)
        assertEquals(4, read.lastChangeAdded)
    }

    @Test
    fun `a new playlist row starts unstamped`() = runBlocking {
        val (db, playlistDao, _) = setup()
        seedPlaylist(playlistDao)
        assertEquals(null to 0L, stampOf(db))
    }
}
