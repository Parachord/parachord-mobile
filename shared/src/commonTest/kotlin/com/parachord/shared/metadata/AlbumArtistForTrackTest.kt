package com.parachord.shared.metadata

import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueues
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #839: desktop's compilation-album-link.test.js (parachord#1660). */
class AlbumArtistForTrackTest {

    private fun track(artist: String, album: String? = "Trainspotting", albumArtist: String? = null) =
        Track(id = "t", title = "T", artist = artist, album = album, albumArtist = albumArtist)

    @Test
    fun onlyATrackWhoseArtistIsNotTheAlbumsCarriesTheAlbumArtist() {
        assertEquals("Various Artists", trackAlbumArtist("Iggy Pop", "Various Artists"))
        assertEquals("DJ Koze", trackAlbumArtist("Mount Kimbie", "DJ Koze"))
        assertNull(trackAlbumArtist("Wild Pink", "Wild Pink"))
        assertNull(trackAlbumArtist("Wild Pink feat. Guest", "Wild Pink"))
        assertNull(trackAlbumArtist("A", "A & B"))
        assertNull(trackAlbumArtist("Iggy Pop", ""))
        assertNull(trackAlbumArtist(null, "Various Artists"))
    }

    @Test
    fun theTracksOwnAlbumGoesByTheAlbumArtistAndAnyOtherByTheTracks() {
        val t = track("Iggy Pop", albumArtist = "Various Artists")
        assertEquals("Various Artists", albumArtistForTrack(t, "Trainspotting"))
        assertEquals("Various Artists", albumArtistForTrack(t, " Trainspotting "))
        // The stream's album isn't the one the field describes.
        assertEquals("Iggy Pop", albumArtistForTrack(t, "Lust for Life"))
        assertEquals("Iggy Pop", albumArtistForTrack(t, null))
    }

    @Test
    fun withNoUsableAlbumArtistTheTracksOwnIsUsed() {
        assertEquals("Iggy Pop", albumArtistForTrack(track("Iggy Pop"), "Trainspotting"))
        for (none in listOf("", "  ", "Unknown", "unknown artist")) {
            assertEquals("Iggy Pop", albumArtistForTrack(track("Iggy Pop", albumArtist = none), "Trainspotting"))
        }
        assertEquals("Iggy Pop", albumArtistForTrack(track("Iggy Pop", album = null, albumArtist = "VA"), ""))
    }

    private val detail = AlbumDetail(
        title = "Trainspotting", artist = "Various Artists",
        tracks = listOf(
            TrackSearchResult(title = "Lust for Life", artist = "Iggy Pop", album = "Trainspotting"),
            TrackSearchResult(title = "Deep Blue Day", artist = "Brian Eno"),
        ),
    )

    @Test
    fun anAlbumQueuesTracksCarryItAndKeepTheirIds() {
        val tracks = CatalogQueues.albumQueue(detail, "Trainspotting", "Various Artists")!!.tracks
        assertEquals(listOf("Various Artists", "Various Artists"), tracks.map { it.albumArtist })
        assertEquals("catalog:lust for life|iggy pop", tracks[0].id)
        assertEquals("Various Artists", albumArtistForTrack(tracks[1], "Trainspotting"))

        val ordinary = AlbumDetail(
            title = "Dulling the Horns", artist = "Wild Pink",
            tracks = listOf(TrackSearchResult(title = "The Fences", artist = "Wild Pink")),
        )
        assertNull(CatalogQueues.albumQueue(ordinary, "Dulling the Horns", "Wild Pink")!!.tracks[0].albumArtist)
    }

    @Test
    fun theSavedQueueKeepsItAndAnOlderOneStillReads() {
        val json = Json { ignoreUnknownKeys = true }
        val t = track("Iggy Pop", albumArtist = "Various Artists")
        assertEquals(t, json.decodeFromString(Track.serializer(), json.encodeToString(Track.serializer(), t)))
        val older = """{"id":"t","title":"T","artist":"Iggy Pop","album":"Trainspotting"}"""
        assertNull(json.decodeFromString(Track.serializer(), older).albumArtist)
    }
}
