package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistPullBannerTest {

    @Test
    fun `a ListenBrainz playlist with a Spotify copy isn't offered a pull from Spotify`() {
        // 2019 Greatest Hits: pulled from ListenBrainz, mirrored to Spotify.
        val id = "listenbrainz-85d8db11-06af-43ef-92dd-296f0263591c"
        assertFalse(PlaylistPullBanner.offersPull("listenbrainz", id, "spotify"))
        // With no source row, the id names ListenBrainz.
        assertFalse(PlaylistPullBanner.offersPull(null, id, "spotify"))
    }

    @Test
    fun `a playlist pulled from Spotify is offered a pull`() {
        assertTrue(PlaylistPullBanner.offersPull("spotify", "spotify-6RgB2r1l7WxuQ9mU4OUpP4", "spotify"))
        assertTrue(PlaylistPullBanner.offersPull(null, "spotify-6RgB2r1l7WxuQ9mU4OUpP4", "spotify"))
    }

    @Test
    fun `a local playlist mirrored to Spotify isn't offered a pull`() {
        assertFalse(PlaylistPullBanner.offersPull(null, "local-123", "spotify"))
    }

    @Test
    fun `an empty pull never replaces a playlist that has tracks`() {
        assertFalse(PlaylistPullBanner.acceptsPulledTracks(localCount = 84, remoteCount = 0))
        assertTrue(PlaylistPullBanner.acceptsPulledTracks(localCount = 84, remoteCount = 83))
        assertTrue(PlaylistPullBanner.acceptsPulledTracks(localCount = 0, remoteCount = 0))
    }
}
