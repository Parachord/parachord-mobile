package com.parachord.android.data.db.dao

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.model.PlaylistTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolver-ID and artwork backfills were keyed by (playlistId, position) only.
 * A resolve can wait in a queue while the playlist is reordered or a sync
 * rewrites it, so the IDs and cover meant for one song landed on whatever song
 * now sat at that position — and sync then read the wrong Spotify ID off it.
 */
class PlaylistTrackBackfillRowGuardTest {
    private val dao = PlaylistTrackDao(TestDatabaseFactory.create())

    private suspend fun seed() = dao.insertAll(
        listOf(
            // "Creep" was at 0 when the resolve was queued; a reorder put "Karma Police" there.
            PlaylistTrack(playlistId = "pl", position = 0, trackTitle = "Karma Police", trackArtist = "Radiohead"),
            PlaylistTrack(playlistId = "pl", position = 1, trackTitle = "Creep", trackArtist = "Radiohead"),
        ),
    )

    private suspend fun row(position: Int) = dao.getByPlaylistIdSync("pl").single { it.position == position }

    @Test
    fun `a backfill for a song that moved doesn't land on the song now at that position`() = runBlocking {
        seed()
        dao.backfillResolverIds("pl", 0, "Creep", "Radiohead", "creepId", "spotify:track:creepId", null, null, null)
        dao.updateTrackArtwork("pl", 0, "Creep", "Radiohead", "https://art/creep.jpg")

        assertNull(row(0).trackSpotifyId)
        assertNull(row(0).trackArtworkUrl)
    }

    @Test
    fun `a backfill for the song still at that position is written`() = runBlocking {
        seed()
        dao.backfillResolverIds("pl", 1, "Creep", "Radiohead", "creepId", "spotify:track:creepId", null, null, null)
        dao.updateTrackArtwork("pl", 1, "Creep", "Radiohead", "https://art/creep.jpg")

        assertEquals("creepId", row(1).trackSpotifyId)
        assertEquals("https://art/creep.jpg", row(1).trackArtworkUrl)
    }
}
