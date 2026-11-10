package com.parachord.shared.playback

import com.parachord.shared.model.Friend
import com.parachord.shared.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals

/** [listenAlongPollOf]: a refreshed friend row becomes a poll answer. */
class ListenAlongPollMappingTest {
    private fun friend(
        name: String? = "Roads",
        artist: String? = "Portishead",
        album: String? = "Dummy",
        art: String? = "http://art/x.jpg",
        onAir: Boolean = true,
        hides: Boolean = false,
    ) = Friend(
        id = "f1", username = "u", service = "listenbrainz", displayName = "U", addedAt = 0,
        cachedTrackName = name, cachedTrackArtist = artist, cachedTrackAlbum = album,
        cachedTrackArtworkUrl = art,
        cachedTrackTimestamp = if (onAir) currentTimeMillis() / 1000 else 1L,
        hidesListening = hides,
    )

    @Test
    fun `on air with a track is listening`() {
        assertEquals(
            ListenAlongPoll.Listening(FriendTrack("Roads", "Portishead", "Dummy", "http://art/x.jpg")),
            listenAlongPollOf(friend()),
        )
    }

    @Test
    fun `not on air is not listening`() {
        assertEquals(ListenAlongPoll.NotListening, listenAlongPollOf(friend(onAir = false)))
    }

    @Test
    fun `a blank name or artist is not listening`() {
        assertEquals(ListenAlongPoll.NotListening, listenAlongPollOf(friend(name = " ")))
        assertEquals(ListenAlongPoll.NotListening, listenAlongPollOf(friend(artist = null)))
    }

    @Test
    fun `a hidden profile is not listening even with a stale cached track`() {
        assertEquals(ListenAlongPoll.NotListening, listenAlongPollOf(friend(hides = true)))
    }

    @Test
    fun `a missing row is not listening`() {
        assertEquals(ListenAlongPoll.NotListening, listenAlongPollOf(null))
    }
}
