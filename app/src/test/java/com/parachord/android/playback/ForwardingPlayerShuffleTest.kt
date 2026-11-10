package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The media session's shuffle (lock screen, Android Auto, Bluetooth) is
 * Parachord's, kept in [PlaybackState.shuffleEnabled] and changed only through
 * [PlaybackController.setShuffle], which refuses it during a spinoff or Listen
 * Along. The wrapped ExoPlayer's shuffle is never read, set or forwarded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForwardingPlayerShuffleTest {

    private class Fixture(
        val wrapper: PlaybackService.ExternalPlaybackForwardingPlayer,
        val delegate: androidx.media3.exoplayer.ExoPlayer,
        val controller: PlaybackController,
        val stateHolder: PlaybackStateHolder,
    )

    private fun fixture(externalMode: Boolean = false, delegateShuffle: Boolean = false): Fixture {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.shuffleModeEnabled } returns delegateShuffle
        every { delegate.isCommandAvailable(any()) } returns false
        every { delegate.availableCommands } returns Player.Commands.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.externalMode = externalMode
        return Fixture(wrapper, delegate, controller, stateHolder)
    }

    @Test fun `getShuffleModeEnabled reports Parachord's shuffle, not the delegate's`() {
        for (external in listOf(false, true)) {
            val f = fixture(externalMode = external, delegateShuffle = true)

            f.stateHolder.update { copy(shuffleEnabled = false) }
            assertFalse(f.wrapper.shuffleModeEnabled)

            f.stateHolder.update { copy(shuffleEnabled = true) }
            assertTrue(f.wrapper.shuffleModeEnabled)
        }
    }

    @Test fun `setShuffleModeEnabled goes to the controller, never the delegate`() {
        for (external in listOf(false, true)) {
            val f = fixture(externalMode = external)
            every { f.controller.setShuffle(any()) } returns true

            f.wrapper.shuffleModeEnabled = true
            f.wrapper.shuffleModeEnabled = false

            verify { f.controller.setShuffle(true) }
            verify { f.controller.setShuffle(false) }
            verify(exactly = 0) { f.delegate.shuffleModeEnabled = any() }
        }
    }

    @Test fun `a refused shuffle re-publishes the current value`() {
        // A MediaController shows the requested value at once; with nothing
        // changed, only this event puts the car's shuffle button back.
        val f = fixture()
        every { f.controller.setShuffle(any()) } returns false
        val listener = mockk<Player.Listener>(relaxed = true)
        f.wrapper.addListener(listener)

        f.wrapper.shuffleModeEnabled = true

        verify(exactly = 1) { listener.onShuffleModeEnabledChanged(false) }
        verify(exactly = 0) { listener.onShuffleModeEnabledChanged(true) }
    }

    @Test fun `an accepted shuffle is left to the state collector`() {
        val f = fixture()
        every { f.controller.setShuffle(any()) } returns true
        val listener = mockk<Player.Listener>(relaxed = true)
        f.wrapper.addListener(listener)

        f.wrapper.shuffleModeEnabled = true

        verify(exactly = 0) { listener.onShuffleModeEnabledChanged(any()) }
    }

    @Test fun `a change in Parachord's shuffle reaches the session's listeners`() {
        val f = fixture()
        val listener = mockk<Player.Listener>(relaxed = true)
        f.wrapper.addListener(listener)

        f.stateHolder.update { copy(shuffleEnabled = true) }
        f.wrapper.publishShuffleMode()

        verify(exactly = 1) { listener.onShuffleModeEnabledChanged(true) }
    }

    @Test fun `the delegate's own shuffle events are not forwarded`() {
        val f = fixture(externalMode = true)
        val installed = slot<Player.Listener>()
        every { f.delegate.addListener(capture(installed)) } returns Unit
        val listener = mockk<Player.Listener>(relaxed = true)
        f.wrapper.addListener(listener)

        installed.captured.onShuffleModeEnabledChanged(true)

        verify(exactly = 0) { listener.onShuffleModeEnabledChanged(any()) }
    }
}
