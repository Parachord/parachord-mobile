package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/** #596: the playback messages both apps show come from one place. */
class PlaybackMessagesTest {

    @Test
    fun aRefusedTrackSaysWhichSourcePlaysItInstead() {
        assertEquals(
            "SoundCloud can't play this track here. Playing via Apple Music.",
            PlaybackMessages.playingViaInstead("SoundCloud", "applemusic"),
        )
        assertEquals("Open in Bandcamp", PlaybackMessages.openIn("Bandcamp"))
    }

    @Test
    fun theContextLineNamesWhereTheQueueCameFrom() {
        assertEquals("Playing from: Daily Brew", PlaybackMessages.contextLabel("playlist", "Daily Brew"))
        assertEquals("Listening along with KUTX", PlaybackMessages.contextLabel(LISTEN_ALONG_CONTEXT, "KUTX"))
        assertEquals("Radio: Slowdive", PlaybackMessages.contextLabel("spinoff", "Radio: Slowdive"))
    }

    @Test
    fun resolverNamesAreWhatPeopleSee() {
        assertEquals("Apple Music", resolverDisplayName("applemusic"))
        assertEquals("Local Files", resolverDisplayName("LocalFiles"))
        assertEquals("Direct", resolverDisplayName("direct"))
        // A plugin resolver the apps don't list: its id, capitalized.
        assertEquals("Tidal", resolverDisplayName("tidal"))
    }
}
