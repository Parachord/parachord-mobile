package com.parachord.shared.playback

import com.parachord.shared.metadata.AlbumDetail
import com.parachord.shared.metadata.TrackSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #588: album and top-songs queues, one rule for both apps. */
class CatalogQueuesTest {

    private fun r(title: String, album: String? = null, art: String? = null) = TrackSearchResult(
        title = title, artist = "Swapmeet", album = album, duration = 180_000, artworkUrl = art,
        spotifyId = "sp-$title", mbid = "mb-$title",
    )

    @Test
    fun anAlbumQueuesEveryTrackMetadataOnlyWithItsContext() {
        val detail = AlbumDetail(
            title = "Mount Zero", artist = "Swapmeet", artworkUrl = "cover",
            tracks = listOf(r("Kismet Kill"), r("Mount Zero", art = "own")),
        )
        val q = CatalogQueues.albumQueue(detail, "Mount Zero", "Swapmeet")!!
        assertEquals(listOf("Kismet Kill", "Mount Zero"), q.tracks.map { it.title })
        assertEquals(listOf("Mount Zero", "Mount Zero"), q.tracks.map { it.album })
        assertEquals(listOf("cover", "own"), q.tracks.map { it.artworkUrl })
        val first = q.tracks.first()
        assertNull(first.resolver)
        assertNull(first.sourceUrl)
        assertEquals("sp-Kismet Kill", first.spotifyId)
        assertEquals("mb-Kismet Kill", first.recordingMbid)
        assertEquals(180_000L, first.duration)
        // The album page's form: name = title, id = artist.
        assertEquals(PlaybackContext(type = "album", name = "Mount Zero", id = "Swapmeet"), q.context)
    }

    @Test
    fun anArtistsTopSongsKeepTheirOwnAlbums() {
        val q = CatalogQueues.artistQueue("Swapmeet", listOf(r("Kismet Kill", album = "Mount Zero")))!!
        assertEquals("Mount Zero", q.tracks.single().album)
        assertEquals(PlaybackContext(type = "artist", name = "Swapmeet", id = "Swapmeet"), q.context)
    }

    @Test
    fun nothingToQueueIsNull() {
        assertNull(CatalogQueues.albumQueue(AlbumDetail(title = "X", artist = "Y"), "X", "Y"))
        assertNull(CatalogQueues.artistQueue("Y", emptyList()))
    }

    @Test
    fun theSameSongHasTheSameIdWhereverItsQueuedFrom() {
        val a = CatalogQueues.catalogTrack(r("Kismet Kill"), album = "A", artworkUrl = null)
        val b = CatalogQueues.catalogTrack(r("KISMET KILL"), album = null, artworkUrl = "x")
        assertEquals(a.id, b.id)
    }
}
