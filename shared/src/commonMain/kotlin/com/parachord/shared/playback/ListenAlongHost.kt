package com.parachord.shared.playback

import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log

private const val TAG = "ListenAlong"

/** A user's playback action, as each app's controller / coordinator reports it to its listener. */
enum class UserPlaybackAction {
    /** Next or Previous (any button: Now Playing, the mini player, the media session, the widget, the car). */
    SKIP,

    /** The user started something else: a track, an album, a playlist, a queue item, a play link. */
    PLAY_SOMETHING_ELSE,

    /** The user paused. */
    PAUSE,

    /** The user pressed play on a paused (or stopped) track. */
    RESUME,

    /** A spinoff started (the in-app button once its pool exists, or a radio link). */
    SPINOFF_STARTED,
}

/** The user's queue as Listen Along found it ([tracks]: the current track, then Up Next). */
data class ListenAlongSuspendedQueue(val tracks: List<Track>, val context: PlaybackContext?)

/**
 * What [ListenAlongHost] needs from an app's playback layer. Implemented by Android's
 * `MainViewModel` (over `PlaybackController`) and iOS's `ListenAlongController` (over
 * `QueuePlaybackCoordinator`). Every method is called on the main thread.
 */
interface ListenAlongPlatform {
    /** Play [track] alone, in [context] (a Listen Along context). */
    fun play(track: Track, context: PlaybackContext)

    /** The user's queue right now: the current track, then Up Next, and the context. */
    fun currentQueue(): ListenAlongSuspendedQueue

    /** End a running spinoff (Listen Along and a spinoff never run together). */
    fun exitSpinoff()

    /**
     * Give the user's queue back from its first track. [startPlayback] false: shown paused,
     * nothing loaded, Play starts it.
     */
    fun restoreQueue(queue: ListenAlongSuspendedQueue, startPlayback: Boolean)

    /** Nothing was queued before Listen Along: drop the queue (the current track stays). */
    fun clearQueue()

    /** Stop and take the current track off screen, as at the end of the queue. */
    fun stopAndClear()

    fun showMessage(text: String)

    /** The track the platform held on screen for the session may be played again (as any stopped track). */
    fun releaseHold()

    /** The session is over: stop polling, release the wake lock, clear the friend. */
    fun ended()

    /** [ListenAlongHost.friendIsAhead] or [ListenAlongHost.suspendedQueue] may have changed. */
    fun stateChanged()

    /** The current track is playing (not paused, not stopped). */
    fun isPlaying(): Boolean

    /** A play is under way (resolving, starting): it counts as playing. */
    fun isPlayStarting(): Boolean

    fun currentTrackId(): String?
}

/**
 * Listen Along's host logic for both apps: holds the shared [ListenAlongSession], tells it
 * what the platform's playback hooks report, and does what it answers. The user's queue is
 * suspended while it runs and comes back, or is dropped, when the session ends. Polling, the
 * UI and (Android) the wake lock stay with the platform. Main-thread only, like the session.
 *
 * Rules decided here, once: whether Listen Along's OWN song is playing (the user's queue
 * playing before the first song isn't); a track end or an unplayable track the session
 * takes while it waits for a poll is held, and Play does nothing until the next track plays
 * or the session ends ([isHolding]); a skip that ends the session with nothing suspended stops
 * and clears (no advance, no repeat); and how the end restores, clears or stops.
 */
class ListenAlongHost(private val platform: ListenAlongPlatform) {
    private var session: ListenAlongSession? = null

    /** The id of the track the session last played ([listenAlongTrack]). */
    private var playingId: String? = null
    private var contextId: String? = null
    private var suspension: ListenAlongSuspendedQueue? = null

    /** True while the host itself starts a play, so the platform's report of it isn't read as the user's. */
    private var driving = false

    /** Our song has ended and nothing of ours has played since (the session waits for a poll). */
    private var atSongEnd = false

    /** Our track ended or couldn't play and the session started nothing: Play does nothing. */
    private var holding = false

    val isActive: Boolean get() = session != null

    /** The friend is on a song we haven't played: Now Playing's Next catches up. */
    val friendIsAhead: Boolean get() = session?.friendIsAhead == true

    /** The user's queue, shown dimmed as "YOUR QUEUE" while listening along. */
    val suspendedQueue: List<Track> get() = suspension?.tracks ?: emptyList()

    /** The session holds its track on screen (waiting for a poll): Play does nothing. */
    val isHolding: Boolean get() = holding

    /**
     * Start following [friendName], playing [current] when known. A running spinoff ends
     * first; the user's queue is suspended the first time. Starting with another friend
     * replaces the session (the old one isn't told), keeps the suspension and keeps the old
     * friend's song as ours (it's held over, its end is consumed). [contextId] goes on the
     * Listen Along context (iOS: the friend's identity, for the banner's link).
     */
    fun start(friendName: String, current: FriendTrack?, contextId: String? = null) {
        platform.exitSpinoff()
        if (suspension == null) {
            val queue = platform.currentQueue()
            suspension = if (queue.context?.type == LISTEN_ALONG_CONTEXT) {
                ListenAlongSuspendedQueue(emptyList(), null)
            } else {
                queue
            }
        }
        this.contextId = contextId
        val s = ListenAlongSession(friendName)
        session = s
        Log.d(TAG, "start '$friendName' (current: ${current?.key ?: "none"})")
        apply(s, s.start(current))
    }

    /** A poll answered. */
    fun onPoll(result: ListenAlongPoll) {
        val s = session ?: return
        apply(s, s.onPoll(result, ourSongIsPlaying()))
    }

    /**
     * False until the session has played something (the user's queue can be playing while
     * we wait for the friend's first song); while a play starts after ours, true (the only
     * plays while a session runs are its own: any other ends it); else whether our track is
     * the one playing.
     */
    private fun ourSongIsPlaying(): Boolean {
        val ours = playingId ?: return false
        if (platform.isPlayStarting()) return true
        return platform.isPlaying() && platform.currentTrackId() == ours
    }

    /** True when [trackId] is the session's track: its failure is the session's to handle. */
    fun drivesTrack(trackId: String): Boolean = session != null && isOurTrack(trackId)

    /**
     * The current track ENDED. True (handled: don't advance, replay or wrap) whenever the
     * session is following and it's our track. If nothing plays next the track is held.
     */
    fun onOwnTrackEnded(): Boolean {
        val s = session ?: return false
        if (!isOurTrack(platform.currentTrackId())) return false
        atSongEnd = true
        val started = apply(s, s.onSongEnded())
        if (!started && session != null) hold("our song ended")
        return true
    }

    /** [trackId] couldn't play. True (handled: no message, no skip) when it's our track. */
    fun onTrackUnplayable(trackId: String): Boolean {
        val s = session ?: return false
        if (!isOurTrack(trackId)) return false
        val started = apply(s, s.onTrackUnplayable())
        if (!started && session != null) hold("our track couldn't play")
        return true
    }

    /** A play of [trackId] is starting: our track is no longer at its end, nor held. */
    fun onTrackStarting(trackId: String) {
        if (!isOurTrack(trackId)) return
        atSongEnd = false
        holding = false
    }

    /**
     * A user's playback action, before the platform acts on it. True means the platform
     * mustn't act on it: a play was started here (a restored queue, the held friend track on
     * a resume), a skip ended the session with nothing to go back to (stopped and cleared),
     * or a resume came while the track is held (nothing plays).
     */
    fun onUserAction(action: UserPlaybackAction): Boolean {
        if (driving) return false
        val s = session ?: return false
        return when (action) {
            UserPlaybackAction.SKIP -> apply(s, s.onUserSkipped(), skip = true)
            UserPlaybackAction.PLAY_SOMETHING_ELSE -> { apply(s, s.onUserPlayedSomethingElse()); false }
            UserPlaybackAction.PAUSE -> { apply(s, s.onUserPaused()); false }
            UserPlaybackAction.RESUME -> if (holding) {
                Log.d(TAG, "play ignored: our track is held until the friend's next one")
                true
            } else {
                apply(s, s.onUserResumed())
            }
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

    /**
     * A `parachord://` link takes over before it plays. A radio link [silently] ends the
     * session and drops the suspended queue; a play link (track, album, playlist) is the user
     * playing something else: the message shows, the suspended queue is dropped.
     */
    fun takeOver(silently: Boolean) {
        val s = session ?: return
        if (silently) endSilently() else apply(s, s.onUserPlayedSomethingElse())
    }

    /** Something else takes over (a spinoff, a radio link): end with no message and drop the suspended queue. */
    fun endSilently() {
        if (session == null) return
        Log.d(TAG, "ended silently")
        end(restore = false, startPlayback = true, skip = false)
    }

    private fun hold(why: String) {
        Log.d(TAG, "$why; holding it until the friend's next track")
        holding = true
    }

    private fun isOurTrack(trackId: String?): Boolean = trackId != null && trackId == playingId

    /** Applies [effects] in order; true when the platform mustn't act on what caused them (see [end]). */
    private fun apply(s: ListenAlongSession, effects: List<ListenAlongEffect>, skip: Boolean = false): Boolean {
        var handled = false
        for (effect in effects) {
            when (effect) {
                is ListenAlongEffect.Play -> {
                    val track = listenAlongTrack(effect.track)
                    Log.d(TAG, "play '${track.title}'")
                    playingId = track.id
                    atSongEnd = false
                    holding = false
                    driving = true
                    try {
                        platform.play(track, PlaybackContext(type = LISTEN_ALONG_CONTEXT, name = s.friendName, id = contextId))
                    } finally {
                        driving = false
                    }
                    handled = true
                }
                is ListenAlongEffect.Message -> platform.showMessage(effect.text)
                is ListenAlongEffect.End -> if (end(effect.restoreQueue, effect.startPlayback, skip)) handled = true
            }
        }
        platform.stateChanged()
        return handled
    }

    /**
     * Ends the session; with [restore] the suspended queue comes back ([startPlayback]: playing,
     * else paused). True when the platform mustn't act on what ended it: the queue came back,
     * or a [skip] with nothing to go back to stopped and cleared the track.
     */
    private fun end(restore: Boolean, startPlayback: Boolean, skip: Boolean): Boolean {
        session = null
        playingId = null
        contextId = null
        val endedAtSongEnd = atSongEnd
        atSongEnd = false
        val wasHolding = holding
        holding = false
        val s = suspension
        suspension = null
        Log.d(TAG, "ended (restore: $restore, play: $startPlayback)")
        // The held track is let go: Play on it does what it does on any stopped track,
        // unless what follows replaces it.
        if (wasHolding) platform.releaseHold()
        platform.ended()
        platform.stateChanged()
        if (!restore || s == null) return false
        if (s.tracks.isEmpty()) {
            // Nothing to go back to. At the end of our song this is the end of the queue, and
            // a skip is Next on the last track: the track comes off screen. Otherwise (the
            // toggle, the friend gone while ours played or paused) it's left as it is.
            platform.clearQueue()
            if (endedAtSongEnd || skip) {
                platform.stopAndClear()
                return skip
            }
            return false
        }
        driving = true
        try {
            platform.restoreQueue(s, startPlayback)
        } finally {
            driving = false
        }
        return true
    }
}
