package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.handlers.AppleMusicPlaybackHandler
import com.parachord.android.playback.handlers.ExternalPlaybackHandler
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.android.playback.handlers.BandcampPlaybackHandler
import com.parachord.android.playback.handlers.SoundCloudPlaybackHandler
import com.parachord.android.playback.handlers.SpotifyPlaybackHandler
import com.parachord.android.plugin.PluginManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackRouterTest {

    private lateinit var spotifyHandler: SpotifyPlaybackHandler
    private lateinit var appleMusicHandler: AppleMusicPlaybackHandler
    private lateinit var soundCloudHandler: SoundCloudPlaybackHandler
    private lateinit var bandcampHandler: BandcampPlaybackHandler
    private lateinit var pluginManager: PluginManager
    private lateinit var router: PlaybackRouter

    @Before
    fun setup() {
        spotifyHandler = mockk(relaxed = true)
        appleMusicHandler = mockk(relaxed = true)
        soundCloudHandler = mockk(relaxed = true)
        bandcampHandler = mockk(relaxed = true)
        pluginManager = mockk(relaxed = true)
        // `plugins` is a StateFlow<List<Plugin>>; relaxed mockk can't infer the
        // generic type so the default iteration fails. Give it an empty list.
        every { pluginManager.plugins } returns MutableStateFlow(emptyList())

        // Default: no handler handles anything
        every { spotifyHandler.canHandle(any()) } returns false
        every { appleMusicHandler.canHandle(any()) } returns false
        every { soundCloudHandler.canHandle(any()) } returns false
        every { bandcampHandler.canHandle(any()) } returns false
        coEvery { soundCloudHandler.createMediaItem(any()) } returns null

        router = PlaybackRouter(spotifyHandler, appleMusicHandler, soundCloudHandler, bandcampHandler, pluginManager)
    }

    @Test
    fun `routes spotify track to external playback`() = runTest {
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            resolver = "spotify", spotifyUri = "spotify:track:abc123"
        )
        every { spotifyHandler.canHandle(track) } returns true

        val action = router.route(track)
        assertTrue(action is PlaybackAction.ExternalPlayback)
        assertEquals(spotifyHandler, (action as PlaybackAction.ExternalPlayback).handler)
    }

    @Test
    fun `routes apple music track to external playback`() = runTest {
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            resolver = "applemusic", appleMusicId = "12345"
        )
        every { appleMusicHandler.canHandle(track) } returns true

        val action = router.route(track)
        assertTrue(action is PlaybackAction.ExternalPlayback)
        assertEquals(appleMusicHandler, (action as PlaybackAction.ExternalPlayback).handler)
    }

    @Test
    fun `routes soundcloud track to its widget, as external playback`() = runTest {
        val widgetHandler = SoundCloudPlaybackHandler(mockk(relaxed = true))
        val widgetRouter = PlaybackRouter(spotifyHandler, appleMusicHandler, widgetHandler, bandcampHandler, pluginManager)
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            resolver = "soundcloud", soundcloudId = "293",
        )

        val action = widgetRouter.route(track)
        assertTrue(action is PlaybackAction.ExternalPlayback)
        assertEquals(widgetHandler, (action as PlaybackAction.ExternalPlayback).handler)
    }

    @Test
    fun `routes local file track to ExoPlayer`() = runTest {
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            sourceType = "local", sourceUrl = "content://media/1",
            resolver = "localfiles"
        )
        // No handler handles it → falls through to LocalFileHandler (internal)
        // LocalFileHandler.canHandle checks sourceType == "local" || resolver == "localfiles"

        val action = router.route(track)
        assertTrue(action is PlaybackAction.ExoPlayerItem)
    }

    @Test
    fun `routes direct stream to ExoPlayer`() = runTest {
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            sourceType = "stream", sourceUrl = "https://stream.example.com/song.mp3"
        )

        val action = router.route(track)
        assertTrue(action is PlaybackAction.ExoPlayerItem)
    }

    @Test
    fun `returns null when no handler matches`() = runTest {
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist"
            // No resolver, no sourceType, no sourceUrl
        )

        val action = router.route(track)
        assertNull(action)
    }

    @Test
    fun `sets activeExternalHandler when routing to external handler`() = runTest {
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            resolver = "spotify", spotifyUri = "spotify:track:abc"
        )
        every { spotifyHandler.canHandle(track) } returns true

        assertNull(router.activeExternalHandler)
        router.route(track)
        assertEquals(spotifyHandler, router.activeExternalHandler)
    }

    @Test
    fun `stops previous external handler when switching to new one`() = runTest {
        // First: route to Spotify
        val spotifyTrack = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            resolver = "spotify", spotifyUri = "spotify:track:abc"
        )
        every { spotifyHandler.canHandle(spotifyTrack) } returns true
        router.route(spotifyTrack)

        // Then: route to Apple Music
        val appleMusicTrack = TrackEntity(
            id = "2", title = "Song 2", artist = "Artist",
            resolver = "applemusic", appleMusicId = "12345"
        )
        every { appleMusicHandler.canHandle(appleMusicTrack) } returns true
        router.route(appleMusicTrack)

        coVerify { spotifyHandler.stop() }
        assertEquals(appleMusicHandler, router.activeExternalHandler)
    }

    @Test
    fun `stops external handler when switching to ExoPlayer`() = runTest {
        // First: route to Spotify
        val spotifyTrack = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            resolver = "spotify", spotifyUri = "spotify:track:abc"
        )
        every { spotifyHandler.canHandle(spotifyTrack) } returns true
        router.route(spotifyTrack)

        // Then: route to local file
        val localTrack = TrackEntity(
            id = "2", title = "Local", artist = "Artist",
            sourceType = "local", sourceUrl = "content://media/1",
            resolver = "localfiles"
        )
        router.route(localTrack)

        coVerify { spotifyHandler.stop() }
        assertNull(router.activeExternalHandler)
    }

    @Test
    fun `stopExternalPlayback clears active handler`() = runTest {
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            resolver = "spotify", spotifyUri = "spotify:track:abc"
        )
        every { spotifyHandler.canHandle(track) } returns true
        router.route(track)

        router.stopExternalPlayback()
        assertNull(router.activeExternalHandler)
        coVerify { spotifyHandler.stop() }
    }

    @Test
    fun `handler priority order is spotify then applemusic then soundcloud`() = runTest {
        // A track that both spotify and apple music can handle
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            resolver = "spotify", spotifyUri = "spotify:track:abc",
            appleMusicId = "12345"
        )
        every { spotifyHandler.canHandle(track) } returns true
        every { appleMusicHandler.canHandle(track) } returns true

        val action = router.route(track)
        // Spotify is first in handler order
        assertTrue(action is PlaybackAction.ExternalPlayback)
        assertEquals(spotifyHandler, (action as PlaybackAction.ExternalPlayback).handler)
    }

    @Test
    fun `routes bandcamp to its embed, not the browser, though the plugin says stream false`() = runTest {
        val track = TrackEntity(
            id = "1", title = "Song", artist = "Artist",
            resolver = "bandcamp", sourceUrl = "https://x.bandcamp.com/track/song",
            // A track re-routed here can still carry another service's ids.
            spotifyUri = "spotify:track:abc123",
        )
        every { bandcampHandler.canHandle(track) } returns true
        every { spotifyHandler.canHandle(track) } returns true
        every { pluginManager.plugins } returns MutableStateFlow(
            listOf(
                mockk(relaxed = true) {
                    every { id } returns "bandcamp"
                    every { capabilities } returns mapOf("stream" to false, "resolve" to true)
                },
            ),
        )

        val action = router.route(track)
        assertTrue(action is PlaybackAction.ExternalPlayback)
        assertEquals(bandcampHandler, (action as PlaybackAction.ExternalPlayback).handler)
    }
}
