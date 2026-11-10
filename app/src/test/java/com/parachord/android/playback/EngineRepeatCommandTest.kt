package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ExoPlayer's own repeat (the silent keep-alive's loop, or OFF so a local file
 * ends) travels from [PlaybackController] to the service as a private session
 * command, kept apart from the session's repeat, which is Parachord's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EngineRepeatCommandTest {

    @Test fun `the command sets the engine's repeat and leaves Parachord's alone`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.repeatMode } returns Player.REPEAT_MODE_OFF
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        stateHolder.update { copy(repeatMode = RepeatMode.ALL) }
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        // What the service's onCustomCommand does with the controller's command.
        assertTrue(EngineRepeatCommand.apply(delegate, EngineRepeatCommand.args(Player.REPEAT_MODE_ONE)))
        assertTrue(EngineRepeatCommand.apply(delegate, EngineRepeatCommand.args(Player.REPEAT_MODE_OFF)))

        verify { delegate.repeatMode = Player.REPEAT_MODE_ONE }
        verify { delegate.repeatMode = Player.REPEAT_MODE_OFF }
        assertEquals(RepeatMode.ALL, stateHolder.state.value.repeatMode)
        assertEquals(Player.REPEAT_MODE_ALL, wrapper.repeatMode)
        verify(exactly = 0) { controller.setRepeatMode(any()) }
    }

    @Test fun `a command without a valid mode changes nothing`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)

        assertFalse(EngineRepeatCommand.apply(delegate, android.os.Bundle.EMPTY))
        assertFalse(EngineRepeatCommand.apply(delegate, EngineRepeatCommand.args(7)))

        verify(exactly = 0) { delegate.repeatMode = any() }
    }

    @Test fun `only Parachord's own controller is offered the command`() {
        val love = SessionCommand("love", android.os.Bundle.EMPTY)

        val own = EngineRepeatCommand.sessionCommandsFor("com.parachord.android.debug", "com.parachord.android.debug", listOf(love))
        val auto = EngineRepeatCommand.sessionCommandsFor("com.google.android.projection.gearhead", "com.parachord.android.debug", listOf(love))
        val systemUi = EngineRepeatCommand.sessionCommandsFor("com.android.systemui", "com.parachord.android.debug", listOf(love))

        assertTrue(own.contains(EngineRepeatCommand.command))
        assertFalse(auto.contains(EngineRepeatCommand.command))
        assertFalse(systemUi.contains(EngineRepeatCommand.command))
        // Everyone keeps the car's buttons.
        assertTrue(own.contains(love))
        assertTrue(auto.contains(love))
        assertTrue(systemUi.contains(love))
    }
}
