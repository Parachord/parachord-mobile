package com.parachord.android.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.OrphanPlaylistTracks
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Removing a playlist removes its tracks, and the open-time cleanup drops track
 * rows a removed playlist left behind before that, and nothing else.
 */
class PlaylistDeleteTracksTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
    private val db = ParachordDb(driver)
    private val playlists = PlaylistDao(db)
    private val tracks = PlaylistTrackDao(db)

    private fun rows(id: String, n: Int) =
        (0 until n).map { PlaylistTrack(playlistId = id, position = it, trackTitle = "T$it", trackArtist = "A") }

    @Test
    fun `deleting a playlist deletes its tracks and leaves others`() = runBlocking {
        playlists.insert(Playlist(id = "a", name = "A"))
        playlists.insert(Playlist(id = "b", name = "B"))
        tracks.insertAll(rows("a", 3))
        tracks.insertAll(rows("b", 2))

        playlists.delete(Playlist(id = "a", name = "A"))

        assertEquals(0, tracks.getByPlaylistIdSync("a").size)
        assertEquals(2, tracks.getByPlaylistIdSync("b").size)
    }

    @Test
    fun `a playlist can take the id of a removed one`() = runBlocking {
        playlists.insert(Playlist(id = "spotify-x", name = "Old"))
        tracks.insertAll(rows("spotify-x", 3))
        playlists.delete(Playlist(id = "spotify-x", name = "Old"))

        playlists.insert(Playlist(id = "spotify-x", name = "New"))
        tracks.insertAll(rows("spotify-x", 2))

        assertEquals(2, tracks.getByPlaylistIdSync("spotify-x").size)
    }

    @Test
    fun `the open-time cleanup drops only rows with no playlist`() = runBlocking {
        playlists.insert(Playlist(id = "kept", name = "Kept"))
        tracks.insertAll(rows("kept", 2))
        tracks.insertAll(rows("gone", 4)) // left behind by an old delete

        driver.execute(null, OrphanPlaylistTracks.DELETE_ORPHANS, 0)

        assertEquals(2, tracks.getByPlaylistIdSync("kept").size)
        assertEquals(0, tracks.getByPlaylistIdSync("gone").size)
    }
}
