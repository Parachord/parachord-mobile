package com.parachord.android.playback

import android.app.Application
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Android Auto is told between two ExoPlayer tracks (#848). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionStateBetweenTracksTest {

    private fun state(local: Int, between: Boolean, error: ExoPlaybackException? = null): Int {
        val delegate = mockk<ExoPlayer>(relaxed = true)
        every { delegate.playbackState } returns local
        every { delegate.playerError } returns error
        val controller = mockk<PlaybackController>(relaxed = true)
        every { controller.isBetweenTracks(any<Boolean>()) } returns between
        return PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, PlaybackStateHolder()).playbackState
    }

    @Test fun `ended or idle between tracks is loading`() {
        assertEquals(Player.STATE_BUFFERING, state(Player.STATE_ENDED, between = true))
        assertEquals(Player.STATE_BUFFERING, state(Player.STATE_IDLE, between = true))
    }

    @Test fun `ended or idle with nothing to follow is what it is`() {
        assertEquals(Player.STATE_ENDED, state(Player.STATE_ENDED, between = false))
        assertEquals(Player.STATE_IDLE, state(Player.STATE_IDLE, between = false))
    }

    @Test fun `playing and loading are never changed`() {
        assertEquals(Player.STATE_READY, state(Player.STATE_READY, between = true))
        assertEquals(Player.STATE_BUFFERING, state(Player.STATE_BUFFERING, between = true))
    }

    @Test fun `a failed player is never loading`() {
        val error = ExoPlaybackException.createForUnexpected(RuntimeException("x"))
        assertEquals(Player.STATE_IDLE, state(Player.STATE_IDLE, between = true, error = error))
    }

    @Test fun `a track is between tracks only while something is about to play`() {
        fun between(hasTrack: Boolean = true, waits: Boolean = false, starting: Boolean = false, follows: Boolean = false) =
            isBetweenTracks(hasTrack, waits, starting, follows)
        assertTrue(between(starting = true))
        assertTrue(between(follows = true))
        assertFalse("the end of the queue", between())
        assertFalse("nothing on screen", between(hasTrack = false, starting = true))
        assertFalse("stopped for Play", between(waits = true, follows = true))
    }
}
