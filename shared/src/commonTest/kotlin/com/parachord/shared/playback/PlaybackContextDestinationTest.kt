package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackContextDestinationTest {

    @Test
    fun `a service playlist opens its own view by link — not a saved playlist`() {
        assertEquals(
            ContextDestination.ServicePlaylist(url = "https://music.apple.com/pl", name = "Mix"),
            playbackContextDestination(PlaybackContext(SERVICE_PLAYLIST_CONTEXT, "Mix", "https://music.apple.com/pl")),
        )
        assertNull(playbackContextDestination(PlaybackContext(SERVICE_PLAYLIST_CONTEXT, "Mix", null)))
    }

    @Test
    fun `a mix opens its service playlist`() {
        assertEquals(
            ContextDestination.ServicePlaylist("https://music.apple.com/pl", "Daily"),
            playbackContextDestination(PlaybackContext("mix", "Daily", "https://music.apple.com/pl")),
        )
    }

    @Test
    fun `a mix with an old id opens nothing`() {
        assertNull(playbackContextDestination(PlaybackContext("mix", "Daily", "daily-jams")))
        assertNull(playbackContextDestination(PlaybackContext("mix", "Daily", null)))
    }

    @Test
    fun `album and artist and saved playlist contexts`() {
        assertEquals(ContextDestination.Album("Dummy", "Portishead"), playbackContextDestination(PlaybackContext("album", "Dummy", "Portishead")))
        assertEquals(ContextDestination.Album("Dummy", null), playbackContextDestination(PlaybackContext("album", "Dummy", " ")))
        assertEquals(ContextDestination.Artist("Tricky"), playbackContextDestination(PlaybackContext("artist", "Tricky")))
        assertEquals(ContextDestination.Playlist("local-1"), playbackContextDestination(PlaybackContext("playlist", "Mine", "local-1")))
        assertNull(playbackContextDestination(PlaybackContext("playlist", "Mine", null)))
    }

    @Test
    fun `other contexts open nothing here`() {
        assertNull(playbackContextDestination(PlaybackContext("spinoff", "Spinoff from X")))
        assertNull(playbackContextDestination(PlaybackContext(LISTEN_ALONG_CONTEXT, "Bob", "x")))
    }
}
