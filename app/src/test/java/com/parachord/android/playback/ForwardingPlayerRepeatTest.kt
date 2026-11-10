package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.parachord.shared.model.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The media session's repeat (lock screen, Android Auto) is Parachord's repeat,
 * kept in [PlaybackState.repeatMode] and applied by [PlaybackController]. The
 * wrapped ExoPlayer's own repeat mode belongs to the silent keep-alive loop
 * during external playback (always `REPEAT_MODE_ONE`), so the session must
 * never read it, write it, or hear about its changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForwardingPlayerRepeatTest {

    private class Fixture(
        val wrapper: PlaybackService.ExternalPlaybackForwardingPlayer,
        val delegate: androidx.media3.exoplayer.ExoPlayer,
        val controller: PlaybackController,
        val stateHolder: PlaybackStateHolder,
    )

    private fun fixture(externalMode: Boolean = false): Fixture {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        // The silence loop's repeat, throughout.
        every { delegate.repeatMode } returns Player.REPEAT_MODE_ONE
        every { delegate.isCommandAvailable(any()) } returns false
        every { delegate.availableCommands } returns Player.Commands.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.externalMode = externalMode
        return Fixture(wrapper, delegate, controller, stateHolder)
    }

    @Test fun `getRepeatMode reports Parachord's mode`() {
        for (external in listOf(false, true)) {
            val f = fixture(externalMode = external)

            f.stateHolder.update { copy(repeatMode = RepeatMode.ONE) }
            assertEquals(Player.REPEAT_MODE_ONE, f.wrapper.repeatMode)

            f.stateHolder.update { copy(repeatMode = RepeatMode.ALL) }
            assertEquals(Player.REPEAT_MODE_ALL, f.wrapper.repeatMode)

            f.stateHolder.update { copy(repeatMode = RepeatMode.OFF) }
            assertEquals(Player.REPEAT_MODE_OFF, f.wrapper.repeatMode)
        }
    }

    @Test fun `setRepeatMode goes to the controller`() {
        val f = fixture()

        f.wrapper.repeatMode = Player.REPEAT_MODE_ALL
        verify { f.controller.setRepeatMode(RepeatMode.ALL) }

        f.wrapper.repeatMode = Player.REPEAT_MODE_ONE
        verify { f.controller.setRepeatMode(RepeatMode.ONE) }

        f.wrapper.repeatMode = Player.REPEAT_MODE_OFF
        verify { f.controller.setRepeatMode(RepeatMode.OFF) }
    }

    @Test fun `setRepeatMode does not touch the delegate`() {
        for (external in listOf(false, true)) {
            val f = fixture(externalMode = external)

            f.wrapper.repeatMode = Player.REPEAT_MODE_ALL
            f.wrapper.repeatMode = Player.REPEAT_MODE_OFF

            verify(exactly = 0) { f.delegate.repeatMode = any() }
        }
    }

    @Test fun `the repeat command is available`() {
        for (external in listOf(false, true)) {
            val f = fixture(externalMode = external)

            assertTrue(f.wrapper.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE))
            assertTrue(f.wrapper.availableCommands.contains(Player.COMMAND_SET_REPEAT_MODE))
        }
    }

    @Test fun `a change in Parachord's repeat reaches the session's listeners`() {
        val f = fixture()
        val listener = mockk<Player.Listener>(relaxed = true)
        f.wrapper.addListener(listener)

        f.stateHolder.update { copy(repeatMode = RepeatMode.ALL) }
        f.wrapper.publishRepeatMode()

        verify(exactly = 1) { listener.onRepeatModeChanged(Player.REPEAT_MODE_ALL) }
    }

    @Test fun `the delegate's own repeat events are not forwarded`() {
        val f = fixture(externalMode = true)
        val installed = slot<Player.Listener>()
        every { f.delegate.addListener(capture(installed)) } returns Unit
        val listener = mockk<Player.Listener>(relaxed = true)
        f.wrapper.addListener(listener)

        // The silence loop's repeat being set by the controller.
        installed.captured.onRepeatModeChanged(Player.REPEAT_MODE_ONE)

        verify(exactly = 0) { listener.onRepeatModeChanged(any()) }
    }

    @Test fun `Next wraps on the last track under repeat all`() {
        val f = fixture(externalMode = true)
        every { f.delegate.currentTimeline } returns Timeline.EMPTY
        f.wrapper.updateQueueSnapshot(Track(id = "last", title = "last", artist = "A"), emptyList())

        f.stateHolder.update { copy(repeatMode = RepeatMode.OFF) }
        assertFalse(f.wrapper.hasNextMediaItem())
        assertEquals(C.INDEX_UNSET, f.wrapper.nextMediaItemIndex)

        f.stateHolder.update { copy(repeatMode = RepeatMode.ALL) }
        assertTrue(f.wrapper.hasNextMediaItem())
        assertEquals(0, f.wrapper.nextMediaItemIndex)
        assertTrue(f.wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test fun `an empty queue has no Next under repeat all`() {
        val f = fixture(externalMode = true)
        every { f.delegate.currentTimeline } returns Timeline.EMPTY
        f.wrapper.updateQueueSnapshot(null, emptyList())
        f.stateHolder.update { copy(repeatMode = RepeatMode.ALL) }

        assertFalse(f.wrapper.hasNextMediaItem())
        assertEquals(C.INDEX_UNSET, f.wrapper.nextMediaItemIndex)
    }
}
