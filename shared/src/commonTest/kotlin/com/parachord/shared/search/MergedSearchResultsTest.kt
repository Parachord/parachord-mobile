package com.parachord.shared.search

import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.ArtistInfo
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Album
import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The merged search list the AAOS car's search shows: one list out of the
 * session's five, albums first, the same song once, caps per kind.
 */
class MergedSearchResultsTest {

    private fun track(title: String, artist: String) = Track(id = "t-$title", title = title, artist = artist)

    @Test
    fun `empty state merges to nothing`() {
        assertEquals(emptyList(), mergeSearchResults(SearchState(query = "x")))
    }

    @Test
    fun `round-robin puts an album first, then a song, then an artist`() {
        val state = SearchState(
            query = "q",
            localTracks = listOf(track("Song", "Band")),
            localAlbums = listOf(Album(id = "a", title = "Album", artist = "Band")),
            artists = listOf(ArtistInfo(name = "Band")),
        )
        val merged = mergeSearchResults(state)
        assertTrue(merged[0] is MergedSearchItem.LibraryAlbum)
        assertTrue(merged[1] is MergedSearchItem.Song)
        assertTrue(merged[2] is MergedSearchItem.Artist)
        assertEquals(3, merged.size)
    }

    @Test
    fun `the same song shows once, the library copy wins`() {
        val state = SearchState(
            query = "q",
            localTracks = listOf(track("Title", "Artist")),
            remoteTracks = listOf(TrackSearchResult(title = "Title", artist = "Artist")),
        )
        val merged = mergeSearchResults(state)
        assertEquals(1, merged.size)
        assertTrue(merged[0] is MergedSearchItem.Song)
    }

    @Test
    fun `a remote album the library also has is left out`() {
        val state = SearchState(
            query = "q",
            localAlbums = listOf(Album(id = "a", title = "The Album", artist = "The Band")),
            remoteAlbums = listOf(AlbumSearchResult(title = "the album", artist = "the band")),
        )
        val merged = mergeSearchResults(state)
        assertEquals(1, merged.size)
        assertTrue(merged[0] is MergedSearchItem.LibraryAlbum)
    }

    @Test
    fun `caps apply per kind`() {
        val state = SearchState(
            query = "q",
            localAlbums = (1..10).map { Album(id = "a$it", title = "Album $it", artist = "Band") },
            artists = (1..10).map { ArtistInfo(name = "Artist $it") },
            localTracks = (1..30).map { track("Song $it", "Band") },
        )
        val merged = mergeSearchResults(state)
        assertEquals(4, merged.count { it is MergedSearchItem.LibraryAlbum })
        assertEquals(4, merged.count { it is MergedSearchItem.Artist })
        assertEquals(14, merged.count { it is MergedSearchItem.Song })
    }
}
