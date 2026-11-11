package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The media session's play and pause on the local player (lock screen,
 * headphones, the car) reach ExoPlayer without `togglePlayPause`, so the
 * forwarding player tells the controller ([PlaybackController.onSessionPlayPause])
 * and skips the resume when the listener started something else instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForwardingPlayerSessionPlayPauseTest {

    private fun wrapper(external: Boolean = false, needsLoad: Boolean = false, listenerTakesResume: Boolean = false):
        Triple<PlaybackService.ExternalPlaybackForwardingPlayer, androidx.media3.exoplayer.ExoPlayer, PlaybackController> {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.playbackState } returns Player.STATE_READY
        every { delegate.isCommandAvailable(any()) } returns true
        val controller = mockk<PlaybackController>(relaxed = true)
        every { controller.needsLoadOnPlay } returns needsLoad
        every { controller.wasRecentlyUserPaused() } returns false
        every { controller.onSessionPlayPause(true) } returns listenerTakesResume
        val stateHolder = PlaybackStateHolder()
        stateHolder.update { copy(isPlaying = true) }
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.externalMode = external
        return Triple(wrapper, delegate, controller)
    }

    @Test fun `a session play the listener takes doesn't resume ExoPlayer`() {
        val (wrapper, delegate, controller) = wrapper(listenerTakesResume = true)
        wrapper.play()
        verify(exactly = 1) { controller.onSessionPlayPause(true) }
        verify(exactly = 0) { delegate.play() }
    }

    @Test fun `a session play the listener leaves resumes ExoPlayer`() {
        val (wrapper, delegate, controller) = wrapper()
        wrapper.play()
        verify(exactly = 1) { controller.onSessionPlayPause(true) }
        verify(exactly = 1) { delegate.play() }
    }

    @Test fun `a session pause is told to the controller and pauses ExoPlayer`() {
        val (wrapper, delegate, controller) = wrapper()
        wrapper.pause()
        verify(exactly = 1) { controller.onSessionPlayPause(false) }
        verify(exactly = 1) { delegate.pause() }
    }

    @Test fun `a play waiting for a load and external mode go through togglePlayPause only`() {
        val (loadWrapper, _, loadController) = wrapper(needsLoad = true)
        loadWrapper.play()
        verify(exactly = 0) { loadController.onSessionPlayPause(any()) }

        val (extWrapper, _, extController) = wrapper(external = true)
        extWrapper.pause()
        extWrapper.play()
        verify(exactly = 0) { extController.onSessionPlayPause(any()) }
    }

    @Test fun `sessionPlayPauseAction reports a user's resume and pause only`() {
        assertEquals(UserPlaybackAction.RESUME, sessionPlayPauseAction(play = true, isPlaying = false, isPlayStarting = false))
        // The controller's own play coming back through the session.
        assertNull(sessionPlayPauseAction(play = true, isPlaying = false, isPlayStarting = true))
        assertNull(sessionPlayPauseAction(play = true, isPlaying = true, isPlayStarting = false))
        assertEquals(UserPlaybackAction.PAUSE, sessionPlayPauseAction(play = false, isPlaying = true, isPlayStarting = false))
        assertNull(sessionPlayPauseAction(play = false, isPlaying = false, isPlayStarting = false))
    }
}
