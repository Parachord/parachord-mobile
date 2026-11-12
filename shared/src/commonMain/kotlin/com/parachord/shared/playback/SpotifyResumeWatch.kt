package com.parachord.shared.playback

/**
 * A Spotify track the user paused in Parachord and then resumed somewhere
 * else: Spotify's own app or notification, a headset or the lock screen while
 * Spotify holds the media session, another Connect device (#666, desktop
 * Parachord/parachord#1357). Without this the app kept showing Play, its next
 * Play tap paused Spotify, and the queue never moved on at the track's end.
 *
 * While paused on a Spotify track the apps ask `GET /v1/me/player` every
 * [CHECK_INTERVAL_MS] and take the track back as playing when [resumedElsewhere]
 * says so. A port of desktop's `spotifyPlayingTrack` (`play-state.js`) and its
 * `EXTERNAL_RESUME_POLL_MS`.
 */
object SpotifyResumeWatch {
    /** How often a paused Spotify track is checked. Desktop's `EXTERNAL_RESUME_POLL_MS`. */
    const val CHECK_INTERVAL_MS = 5_000L

    /**
     * Spotify can still report `is_playing` for a moment after our own pause
     * reaches it, so nothing counts as a resume this soon after it. Desktop
     * gets the same effect from its first check landing 5 s after the pause.
     */
    const val GRACE_AFTER_PAUSE_MS = 5_000L

    /**
     * Spotify says it is playing our track: its item, or the track it was
     * relinked from (another market's copy). Spotify playing some other song
     * isn't a resume of ours.
     */
    fun playingOurTrack(
        isPlaying: Boolean,
        itemId: String?,
        linkedFromId: String?,
        ourTrackIds: Collection<String>,
    ): Boolean {
        if (!isPlaying || ourTrackIds.isEmpty()) return false
        return (itemId != null && itemId in ourTrackIds) ||
            (linkedFromId != null && linkedFromId in ourTrackIds)
    }

    /**
     * Whether a paused track should be watched at all: the user paused it
     * (not a hold, a stop or the end of the queue), no play of ours is
     * starting, and the track is still held by Spotify (not a `LoadOnPlay`
     * slot: a restored queue, a track the idle timeout stopped, a failed play,
     * which Play starts afresh).
     */
    fun watches(userPaused: Boolean, trackHeldBySlot: Boolean, playStarting: Boolean): Boolean =
        userPaused && !trackHeldBySlot && !playStarting

    /** The paused track was resumed outside Parachord, [msSincePause] after our pause. */
    fun resumedElsewhere(
        msSincePause: Long,
        isPlaying: Boolean,
        itemId: String?,
        linkedFromId: String?,
        ourTrackIds: Collection<String>,
    ): Boolean = msSincePause >= GRACE_AFTER_PAUSE_MS &&
        playingOurTrack(isPlaying, itemId, linkedFromId, ourTrackIds)
}
