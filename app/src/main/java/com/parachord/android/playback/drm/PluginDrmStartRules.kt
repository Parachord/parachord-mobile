package com.parachord.android.playback.drm

import com.parachord.shared.playback.drm.PluginDrmError
import com.parachord.shared.playback.drm.PluginDrmOutcome
import com.parachord.shared.playback.drm.pluginDrmOutcome

/**
 * When a plugin DRM play has started and what a licence refusal does
 * (PlaybackController's rules, pure for tests).
 */
object PluginDrmStartRules {
    /**
     * Started means a licence for the session came back AND the player plays.
     * Playing alone is the clear lead: ExoPlayer plays a stream's unencrypted
     * start before it needs a key, so a refused FLAC rung played 30 s on a
     * Pixel and only then raised ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED.
     */
    fun started(isPlaying: Boolean, licensed: Boolean): Boolean = isPlaying && licensed

    enum class RefusalAction {
        /** The session is still starting: its start wait ends with the refusal at once. */
        END_START_WAIT,

        /** A per-content refusal for the session on the player: drop the family, replay where it is. */
        REPLAY_WITHOUT_FAMILY,

        /** Any other refusal after the start: ExoPlayer's error comes, classified then ([playerErrorOutcome]). */
        LEAVE_TO_PLAYER_ERROR,

        /** Not the session playing this track (a stale or closed one). */
        IGNORE,
    }

    fun onRefusal(inStartWait: Boolean, onPlayerForTrack: Boolean, error: PluginDrmError): RefusalAction = when {
        inStartWait -> RefusalAction.END_START_WAIT
        !onPlayerForTrack -> RefusalAction.IGNORE
        pluginDrmOutcome(error) == PluginDrmOutcome.FAMILY_REFUSED -> RefusalAction.REPLAY_WITHOUT_FAMILY
        else -> RefusalAction.LEAVE_TO_PLAYER_ERROR
    }

    /**
     * A DRM player error for a session, from its recorded refusal: the
     * outcome to act on, or null for the ordinary player failure (no refusal
     * recorded, or one that only says the play didn't start).
     */
    fun playerErrorOutcome(recorded: PluginDrmError?): PluginDrmOutcome? {
        val outcome = recorded?.let(::pluginDrmOutcome) ?: return null
        return outcome.takeUnless { it == PluginDrmOutcome.START_FAILED || it == PluginDrmOutcome.SUPERSEDED }
    }
}
