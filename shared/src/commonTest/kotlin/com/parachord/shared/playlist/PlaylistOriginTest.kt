package com.parachord.shared.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistOriginTest {
    @Test
    fun `the sync source wins over the id`() {
        assertEquals("listenbrainz", PlaylistOrigin.pullProvider("local-1", "listenbrainz"))
        assertEquals("spotify", PlaylistOrigin.pullProvider("spotify-37i9", null))
        assertNull(PlaylistOrigin.pullProvider("local-1", null))
        assertNull(PlaylistOrigin.pullProvider("spotify-import-link-abc", null))
    }

    @Test
    fun `every pulled service gets a source line — not only Spotify`() {
        assertEquals("Spotify", PlaylistOrigin.label("spotify-37i9", null, "spotify"))
        assertEquals("Apple Music", PlaylistOrigin.label("applemusic-p.1", null, "applemusic"))
        assertEquals("ListenBrainz", PlaylistOrigin.label("listenbrainz-x", null, "listenbrainz"))
    }

    @Test
    fun `imports and hosted playlists name where they came from`() {
        assertEquals("SoundCloud", PlaylistOrigin.label("soundcloud-import-link-abc", null, null))
        assertEquals(PlaylistOrigin.HOSTED_LABEL, PlaylistOrigin.label("hosted-xspf-1", "https://x.test/a.xspf", null))
        assertEquals(PlaylistOrigin.FILE_LABEL, PlaylistOrigin.label("imported-xspf-1", null, null))
    }

    @Test
    fun `a playlist made in Parachord has no source line`() {
        assertNull(PlaylistOrigin.label("local-1", null, null))
    }

    @Test
    fun `push mirrors leave out the pull source`() {
        val mirrors = mapOf("spotify" to "a", "applemusic" to "b")
        assertEquals(mapOf("applemusic" to "b"), PlaylistOrigin.pushMirrors(mirrors, "spotify"))
        assertEquals(mirrors, PlaylistOrigin.pushMirrors(mirrors, null))
    }
}
