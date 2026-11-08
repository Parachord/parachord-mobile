package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity

/**
 * The external playback the idle timeout stopped: the paused track and where it
 * was paused (#550).
 *
 * Five minutes after a pause, `PlaybackController.startIdleTimeout` stops the
 * external player (Apple Music, Spotify, the embeds) and leaves external
 * playback. The track stays on screen and in the car, so play must start it
 * again; without this, play reached ExoPlayer, which only restarted its silent
 * keep-alive loop.
 */
data class IdleStoppedPlayback(val track: TrackEntity, val positionMs: Long)

/**
 * What play should start again: [stopped], when its track is still the current
 * one; null when nothing was stopped or another track has been played since.
 */
internal fun idleResumeTarget(stopped: IdleStoppedPlayback?, currentTrackId: String?): IdleStoppedPlayback? =
    stopped?.takeIf { currentTrackId != null && it.track.id == currentTrackId }
