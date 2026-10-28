package com.parachord.android.playback.handlers

import android.app.Application
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.api.SpDevicesResponse
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A play from Android Auto (or a locked phone) can't launch Spotify: Android
 * blocks activity starts from the background without an error. With Spotify
 * not running, the play used to wait ~20s for a device that never came (#446).
 * It must now skip the launch and tell the caller quickly.
 *
 * Real delays (runBlocking): the wake loop's deadlines are wall-clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SpotifyPlaybackHandlerBackgroundWakeTest {

    private lateinit var spotifyClient: SpotifyClient
    private lateinit var settingsStore: SettingsStore
    private lateinit var handler: SpotifyPlaybackHandler

    private val track = Track(
        id = "t1", title = "I Know!", artist = "Swapmeet", album = "Mount Zero",
        resolver = "spotify", spotifyUri = "spotify:track:abc",
    )

    @Before
    fun setUp() {
        spotifyClient = mockk(relaxed = true)
        settingsStore = mockk(relaxed = true)
        coEvery { settingsStore.getSpotifyAccessToken() } returns "token"
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null
        // Spotify isn't running: no Connect devices, ever.
        coEvery { spotifyClient.getDevices() } returns SpDevicesResponse(emptyList())
        handler = SpotifyPlaybackHandler(
            spotifyClient = spotifyClient,
            settingsStore = settingsStore,
            oAuthManager = mockk<OAuthManager>(relaxed = true),
            context = RuntimeEnvironment.getApplication(),
        )
    }

    @Test fun `in the background it throws quickly and never tries to launch Spotify`() = runBlocking {
        handler.isAppVisible = { false }
        val start = System.currentTimeMillis()
        try {
            handler.play(track)
            fail("expected SpotifyWakeBlockedException")
        } catch (_: SpotifyWakeBlockedException) {
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("took ${elapsed}ms", elapsed < 5_000)
        assertNull(
            "no activity launch from the background",
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity,
        )
        coVerify(exactly = 0) { spotifyClient.startPlayback(any(), any()) }
    }
}
