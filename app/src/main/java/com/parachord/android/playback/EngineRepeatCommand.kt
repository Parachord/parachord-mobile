package com.parachord.android.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands

/**
 * ExoPlayer's own repeat mode, set by [PlaybackController] through a private
 * session command: `REPEAT_MODE_ONE` loops the silent keep-alive during
 * external playback, `REPEAT_MODE_OFF` lets a local file or stream end so the
 * controller can advance the queue.
 *
 * It can't use `MediaController.setRepeatMode`: the controller is connected to
 * the media session, whose player ([PlaybackService.ExternalPlaybackForwardingPlayer])
 * takes the session's repeat as Parachord's repeat ([PlaybackState.repeatMode],
 * the button in Now Playing, the lock screen and Android Auto). So the two are
 * kept apart: the session's repeat is Parachord's, this command is ExoPlayer's.
 *
 * Only Parachord's own controller is offered the command ([sessionCommandsFor]);
 * Media3 refuses a custom command a controller wasn't offered, so Android Auto
 * and the system UI can't send it.
 */
object EngineRepeatCommand {
    const val ACTION = "com.parachord.android.SET_ENGINE_REPEAT"
    const val EXTRA_MODE = "repeat_mode"

    val command = SessionCommand(ACTION, Bundle.EMPTY)

    fun args(repeatMode: Int): Bundle = Bundle().apply { putInt(EXTRA_MODE, repeatMode) }

    /**
     * Set [engine]'s repeat from the command's [args]. The engine is the
     * ExoPlayer itself, never the session's player. False (nothing changed)
     * for a missing or unknown mode.
     */
    fun apply(engine: Player, args: Bundle): Boolean {
        val mode = args.getInt(EXTRA_MODE, -1)
        if (mode != Player.REPEAT_MODE_OFF && mode != Player.REPEAT_MODE_ONE && mode != Player.REPEAT_MODE_ALL) {
            return false
        }
        engine.repeatMode = mode
        return true
    }

    /**
     * The session commands a connecting controller gets: Media3's defaults,
     * [extra] (the car's heart and Spinoff buttons), and this command only for
     * a controller from Parachord's own package.
     */
    fun sessionCommandsFor(
        controllerPackage: String,
        ownPackage: String,
        extra: List<SessionCommand>,
    ): SessionCommands {
        val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        extra.forEach { builder.add(it) }
        if (controllerPackage == ownPackage) builder.add(command)
        return builder.build()
    }
}
