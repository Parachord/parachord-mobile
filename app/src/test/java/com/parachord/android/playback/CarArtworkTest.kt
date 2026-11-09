package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.resolver.trackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The covers Android Auto gets (#551): a spinoff track comes from Last.fm with
 * no artwork of its own, and the car showed none while the phone showed the
 * cover from Apple Music or the track's resolved sources.
 */
class CarArtworkTest {

    private val spinoffTrack = TrackEntity(id = "t-1", title = "Motion Sickness", artist = "Phoebe Bridgers")
    private val sourceArt = mapOf(trackKey("Motion Sickness", "Phoebe Bridgers") to "https://art.example/source.jpg")

    @Test
    fun `a queued track with no cover gets its resolved sources' art`() {
        assertEquals("https://art.example/source.jpg", carArtworkFor(spinoffTrack, sourceArt))
    }

    @Test
    fun `a queued track's own cover wins`() {
        val own = spinoffTrack.copy(artworkUrl = "https://art.example/own.jpg")
        assertEquals("https://art.example/own.jpg", carArtworkFor(own, sourceArt))
    }

    @Test
    fun `a blank cover counts as none`() {
        assertEquals("https://art.example/source.jpg", carArtworkFor(spinoffTrack.copy(artworkUrl = " "), sourceArt))
    }

    @Test
    fun `no cover anywhere leaves the placeholder`() {
        assertNull(carArtworkFor(spinoffTrack, emptyMap()))
    }

    @Test
    fun `the playing spinoff track gets the streaming service's cover first, then its sources'`() {
        val streaming = PlaybackState(
            currentTrack = spinoffTrack,
            streamingMetadata = StreamingMetadata(artworkUrl = "https://art.example/apple.jpg"),
        )
        assertEquals("https://art.example/apple.jpg", streaming.effectiveArtwork(sourceArt))
        assertEquals("https://art.example/source.jpg", PlaybackState(currentTrack = spinoffTrack).effectiveArtwork(sourceArt))
    }
}
