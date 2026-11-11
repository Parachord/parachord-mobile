package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.playback.FriendTrack
import com.parachord.shared.playback.LISTEN_ALONG_CONTEXT
import com.parachord.shared.playback.ListenAlongEffect
import com.parachord.shared.playback.ListenAlongPoll
import com.parachord.shared.playback.ListenAlongSession
import com.parachord.shared.playback.listenAlongTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android's side of Listen Along: holds the shared [ListenAlongSession], tells
 * it what [PlaybackController] reports and does what it answers. The user's
 * queue is suspended while it runs ([suspendedQueue]) and comes back, or is
 * dropped, when the session ends. Polling, the wake lock and the friend shown
 * in the UI stay with the caller (`MainViewModel`); [onEnded] tells it the
 * session is over. Call everything on the main thread.
 */
class ListenAlongDriver(
    /** Play the friend's track ([PlaybackController.playTrack]). */
    private val play: (TrackEntity, PlaybackContext) -> Unit,
    /** Play the user's suspended queue from its first track ([PlaybackController.playQueue]). */
    private val restoreQueue: (List<TrackEntity>, PlaybackContext?) -> Unit,
    /** Nothing was queued before Listen Along ([PlaybackController.clearQueue]). */
    private val clearQueue: () -> Unit,
    /**
     * The session ended at the end of its song with nothing to go back to:
     * stop and take the track off screen, as at the end of the queue
     * ([PlaybackController.stopAndClear]).
     */
    private val stopAndClear: () -> Unit,
    private val showMessage: (String) -> Unit,
    /** The session is over: stop polling, release the wake lock. */
    private val onEnded: () -> Unit,
) {
    private data class Suspension(val tracks: List<TrackEntity>, val context: PlaybackContext?)

    private var session: ListenAlongSession? = null

    /** The id of the track the session last played ([listenAlongTrack]). */
    private var playingId: String? = null
    private var suspension: Suspension? = null

    /** True while the driver itself starts a play, so it isn't read as the user's. */
    private var driving = false

    /**
     * Our song has ended and nothing has played since (the session waits for
     * a poll), or the end is being handled right now ([onOwnTrackEnded]).
     */
    private var atSongEnd = false

    private val _friendIsAhead = MutableStateFlow(false)

    /** The friend is on a song we haven't played: Now Playing's Next catches up. */
    val friendIsAhead: StateFlow<Boolean> = _friendIsAhead.asStateFlow()

    private val _suspendedQueue = MutableStateFlow<List<TrackEntity>>(emptyList())

    /** The user's queue, shown dimmed as "YOUR QUEUE" while listening along. */
    val suspendedQueue: StateFlow<List<TrackEntity>> = _suspendedQueue.asStateFlow()

    val isActive: Boolean get() = session != null

    /**
     * Start following [friendName], playing [current] when known. The user's
     * queue ([queueTracks] in [queueContext]) is suspended the first time;
     * starting with another friend replaces the session (the old one isn't
     * told) and keeps the same suspension.
     */
    fun start(
        friendName: String,
        current: FriendTrack?,
        queueTracks: List<TrackEntity>,
        queueContext: PlaybackContext?,
    ) {
        if (suspension == null) {
            suspension = if (queueContext?.type == LISTEN_ALONG_CONTEXT) {
                Suspension(emptyList(), null)
            } else {
                Suspension(queueTracks, queueContext)
            }
            _suspendedQueue.value = suspension!!.tracks
        }
        val s = ListenAlongSession(friendName)
        session = s
        apply(s, s.start(current))
    }

    /**
     * A poll answered. [isPlaying], [isPlayStarting] and [currentTrackId] are
     * the controller's; the session hears whether OUR song plays
     * ([ourSongIsPlaying]), not the user's own queue.
     */
    fun onPoll(result: ListenAlongPoll, isPlaying: Boolean, isPlayStarting: Boolean, currentTrackId: String?) {
        val s = session ?: return
        apply(s, s.onPoll(result, ourSongIsPlaying(isPlaying, isPlayStarting, currentTrackId)))
    }

    /**
     * False until the session has played something (the user's queue can be
     * playing while we wait for the friend's first song); while a play starts
     * after ours, true (the only plays while a session runs are its own: any
     * other ends it); else whether our track is the one playing.
     */
    private fun ourSongIsPlaying(isPlaying: Boolean, isPlayStarting: Boolean, currentTrackId: String?): Boolean {
        val ours = playingId ?: return false
        if (isPlayStarting) return true
        return isPlaying && currentTrackId == ours
    }

    /** [PlaybackController.drivesTrack]: true when [trackId] is the session's track. */
    fun drivesTrack(trackId: String): Boolean = session != null && isOurTrack(trackId)

    /**
     * [PlaybackController.onOwnTrackEnded]: our song ended. True (handled)
     * whenever the session is following and [currentTrackId] is its track.
     */
    fun onOwnTrackEnded(currentTrackId: String?): Boolean {
        val s = session ?: return false
        if (!isOurTrack(currentTrackId)) return false
        atSongEnd = true
        apply(s, s.onSongEnded())
        return true
    }

    /** [PlaybackController.onTrackUnplayable]: true when [trackId] is our track. */
    fun onTrackUnplayable(trackId: String): Boolean {
        val s = session ?: return false
        if (!isOurTrack(trackId)) return false
        apply(s, s.onTrackUnplayable())
        return true
    }

    /**
     * [PlaybackController.onUserAction]. True when a play was started here (a
     * restored queue, or the held friend track on a resume), so the
     * controller doesn't also act on the skip or the resume.
     */
    fun onUserAction(action: UserPlaybackAction): Boolean {
        if (driving) return false
        val s = session ?: return false
        return when (action) {
            UserPlaybackAction.SKIP -> apply(s, s.onUserSkipped())
            UserPlaybackAction.PLAY_SOMETHING_ELSE -> { apply(s, s.onUserPlayedSomethingElse()); false }
            UserPlaybackAction.PAUSE -> { apply(s, s.onUserPaused()); false }
            UserPlaybackAction.RESUME -> apply(s, s.onUserResumed())
            UserPlaybackAction.SPINOFF_STARTED -> { endSilently(); false }
        }
    }

    /** Now Playing's Next while listening along: jump to the friend's song. */
    fun catchUp() {
        val s = session ?: return
        apply(s, s.onCatchUp())
    }

    /** The Listen Along toggle / stop button. */
    fun stop() {
        val s = session ?: return
        apply(s, s.onUserStopped())
    }

    /** Something else takes over (a `parachord://` link, a spinoff): end with no message and drop the suspended queue. */
    fun endSilently() {
        if (session == null) return
        end(restore = false)
    }

    private fun isOurTrack(trackId: String?): Boolean = trackId != null && trackId == playingId

    /** Applies [effects] in order; returns true when one of them started a play. */
    private fun apply(s: ListenAlongSession, effects: List<ListenAlongEffect>): Boolean {
        var started = false
        for (effect in effects) {
            when (effect) {
                is ListenAlongEffect.Play -> {
                    val track = listenAlongTrack(effect.track)
                    playingId = track.id
                    atSongEnd = false
                    driving = true
                    try {
                        play(track, PlaybackContext(type = LISTEN_ALONG_CONTEXT, name = s.friendName))
                    } finally {
                        driving = false
                    }
                    started = true
                }
                is ListenAlongEffect.Message -> showMessage(effect.text)
                is ListenAlongEffect.End -> if (end(effect.restoreQueue)) started = true
            }
        }
        _friendIsAhead.value = session?.friendIsAhead == true
        return started
    }

    /** Ends the session; with [restore] the suspended queue plays again. True when it did. */
    private fun end(restore: Boolean): Boolean {
        session = null
        playingId = null
        val endedAtSongEnd = atSongEnd
        atSongEnd = false
        _friendIsAhead.value = false
        val s = suspension
        suspension = null
        _suspendedQueue.value = emptyList()
        onEnded()
        if (!restore || s == null) return false
        if (s.tracks.isEmpty()) {
            // Nothing to go back to. At the end of our song this is the end of
            // the queue: the track comes off screen. Otherwise (the toggle, a
            // skip, the friend gone while paused) the track is left as it is.
            clearQueue()
            if (endedAtSongEnd) stopAndClear()
            return false
        }
        restoreQueue(s.tracks, s.context)
        return true
    }

}
