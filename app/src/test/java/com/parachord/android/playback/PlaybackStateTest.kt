package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import org.junit.Assert.*
import org.junit.Test

class PlaybackStateTest {

    // -- effectiveTrack --

    @Test
    fun `effectiveTrack returns null when no current track`() {
        val state = PlaybackState()
        assertNull(state.effectiveTrack)
    }

    @Test
    fun `effectiveTrack returns current track when no streaming metadata`() {
        val track = TrackEntity(id = "1", title = "Song", artist = "Artist")
        val state = PlaybackState(currentTrack = track)
        assertEquals(track, state.effectiveTrack)
    }

    @Test
    fun `effectiveTrack overlays streaming metadata onto current track`() {
        val track = TrackEntity(id = "1", title = "Song", artist = "Artist", album = "Album")
        val meta = StreamingMetadata(
            title = "Real Song",
            artist = "Real Artist",
            album = "Real Album",
            artworkUrl = "http://art.jpg",
        )
        val state = PlaybackState(currentTrack = track, streamingMetadata = meta)
        val effective = state.effectiveTrack

        assertEquals("Real Song", effective?.title)
        assertEquals("Real Artist", effective?.artist)
        assertEquals("Real Album", effective?.album)
        assertEquals("http://art.jpg", effective?.artworkUrl)
        // id stays the same
        assertEquals("1", effective?.id)
    }

    @Test
    fun `effectiveTrack uses original values when streaming metadata fields are null`() {
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            album = "Album", artworkUrl = "http://original.jpg"
        )
        val meta = StreamingMetadata(title = "Real Song") // only title is set
        val state = PlaybackState(currentTrack = track, streamingMetadata = meta)
        val effective = state.effectiveTrack

        assertEquals("Real Song", effective?.title)
        assertEquals("Artist", effective?.artist) // fallback
        assertEquals("Album", effective?.album) // fallback
        assertEquals("http://original.jpg", effective?.artworkUrl) // fallback
    }

    // -- effectiveArtwork --

    private val resolved = mapOf("song|artist" to "http://resolved.jpg")

    @Test
    fun `effectiveArtwork is null with no current track`() {
        assertNull(PlaybackState().effectiveArtwork(resolved))
    }

    @Test
    fun `effectiveArtwork prefers the streaming service's art`() {
        val track = TrackEntity(id = "1", title = "Song", artist = "Artist", artworkUrl = "http://own.jpg")
        val state = PlaybackState(currentTrack = track, streamingMetadata = StreamingMetadata(artworkUrl = "http://stream.jpg"))
        assertEquals("http://stream.jpg", state.effectiveArtwork(resolved))
    }

    @Test
    fun `effectiveArtwork uses the track's own art before resolved art`() {
        val track = TrackEntity(id = "1", title = "Song", artist = "Artist", artworkUrl = "http://own.jpg")
        assertEquals("http://own.jpg", PlaybackState(currentTrack = track).effectiveArtwork(resolved))
    }

    @Test
    fun `effectiveArtwork falls back to the resolved sources' art`() {
        val track = TrackEntity(id = "1", title = "Song", artist = "Artist", artworkUrl = " ")
        assertEquals("http://resolved.jpg", PlaybackState(currentTrack = track).effectiveArtwork(resolved))
    }

    @Test
    fun `effectiveArtwork is null when nothing has art`() {
        val track = TrackEntity(id = "1", title = "Other", artist = "Artist")
        assertNull(PlaybackState(currentTrack = track).effectiveArtwork(resolved))
    }

    // -- RepeatMode --

    @Test
    fun `default state has repeat OFF`() {
        assertEquals(RepeatMode.OFF, PlaybackState().repeatMode)
    }
}
