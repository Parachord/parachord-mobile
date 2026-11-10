package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The media session's play on a track that ExoPlayer doesn't hold (a queue
 * restored at launch, a track no source could play). Media3 handles a session
 * play with `Util.handlePlayButtonAction`, which calls `prepare()` first when
 * the player is idle. ExoPlayer holds nothing for a restored track, so a
 * forwarded prepare ended at once (an empty timeline is `STATE_ENDED`), and
 * the controller's end-of-track listener advanced the queue: a restored track
 * with tracks queued after it skipped to the next one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForwardingPlayerLoadOnPlayTest {

    private fun wrapper(needsLoad: Boolean): Triple<PlaybackService.ExternalPlaybackForwardingPlayer, androidx.media3.exoplayer.ExoPlayer, PlaybackController> {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.playbackState } returns Player.STATE_IDLE
        every { delegate.isCommandAvailable(any()) } returns true
        val controller = mockk<PlaybackController>(relaxed = true)
        every { controller.needsLoadOnPlay } returns needsLoad
        every { controller.wasRecentlyUserPaused() } returns false
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, PlaybackStateHolder())
        return Triple(wrapper, delegate, controller)
    }

    @Test fun `a session play on a track waiting for a load doesn't prepare ExoPlayer`() {
        val (wrapper, delegate, controller) = wrapper(needsLoad = true)

        Util.handlePlayButtonAction(wrapper)

        verify(exactly = 0) { delegate.prepare() }
        verify(exactly = 0) { delegate.play() }
        verify(exactly = 1) { controller.togglePlayPause() }
    }

    @Test fun `a session play on an idle player with nothing waiting prepares and plays it`() {
        val (wrapper, delegate, controller) = wrapper(needsLoad = false)

        Util.handlePlayButtonAction(wrapper)

        verify(exactly = 1) { delegate.prepare() }
        verify(exactly = 1) { delegate.play() }
        verify(exactly = 0) { controller.togglePlayPause() }
    }
}
