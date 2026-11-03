package com.parachord.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Android Auto: a playlist opens its tracklist, and a tapped track plays from there. */
class AutoPlaylistBrowseTest {

    @Test
    fun aTrackIdRoundTrips() {
        assertEquals("spotify-37i9dQ" to 4, parsePlaylistTrackMediaId(playlistTrackMediaId("spotify-37i9dQ", 4)))
    }

    @Test
    fun aPlaylistIdWithColonsStillParses() {
        assertEquals("hosted:xspf:abc" to 0, parsePlaylistTrackMediaId(playlistTrackMediaId("hosted:xspf:abc", 0)))
    }

    @Test
    fun otherIdsAreNotPlaylistTracks() {
        assertNull(parsePlaylistTrackMediaId("playlist:local-1"))
        assertNull(parsePlaylistTrackMediaId("playlist-shuffle:local-1"))
        assertNull(parsePlaylistTrackMediaId("playlist-track:x:local-1"))
        assertNull(parsePlaylistTrackMediaId("playlist-track:3:"))
        assertNull(parsePlaylistTrackMediaId("loved-track:abc"))
    }

    @Test
    fun pagingReturnsTheAskedPage_orEverythingWhenNotPaged() {
        val items = (0 until 25).toList()
        assertEquals((10 until 20).toList(), pageOf(items, page = 1, pageSize = 10))
        assertEquals((20 until 25).toList(), pageOf(items, page = 2, pageSize = 10))
        assertEquals(emptyList<Int>(), pageOf(items, page = 3, pageSize = 10))
        assertEquals(items, pageOf(items, page = 0, pageSize = Int.MAX_VALUE))
    }
}
