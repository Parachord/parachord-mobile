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
        assertEquals("Last.fm", resolverDisplayName("lastfm"))
        assertEquals("ListenBrainz", resolverDisplayName("listenbrainz"))
        assertEquals("Libre.fm", resolverDisplayName("librefm"))
        assertEquals("Couldn’t reach Last.fm", com.parachord.shared.ui.UiMessages.couldntReach("lastfm"))
        assertEquals("Couldn’t reach the service", com.parachord.shared.ui.UiMessages.couldntReach(null))
        assertEquals("Couldn’t reach the service", com.parachord.shared.ui.UiMessages.couldntReach(" "))
        // A plugin resolver the apps don't list: its id, capitalized.
        assertEquals("Tidal", resolverDisplayName("tidal"))
    }

    @Test
    fun `amazon music and plex have their names`() {
        kotlin.test.assertEquals("Amazon Music", resolverDisplayName("amazonmusic"))
        kotlin.test.assertEquals("Plex", resolverDisplayName("plex"))
    }

    @Test
    fun `a plugin the table doesn't know shows its manifest name`() {
        PluginDisplayNames.update(mapOf("tidalx" to "Tidal X"))
        try {
            kotlin.test.assertEquals("Tidal X", resolverDisplayName("tidalx"))
            kotlin.test.assertEquals("Spotify", resolverDisplayName("spotify"))
            kotlin.test.assertEquals("Unknownsvc", resolverDisplayName("unknownsvc"))
        } finally {
            PluginDisplayNames.update(emptyMap())
        }
    }
}
