package com.parachord.android.playback

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.deeplink.AndroidProtocolPlayTeardown
import com.parachord.shared.model.Track
import com.parachord.shared.playback.LISTEN_ALONG_POLL_MS
import com.parachord.shared.playback.ListenAlongHost
import com.parachord.shared.playback.ListenAlongPlatform
import com.parachord.shared.playback.ListenAlongPoll
import com.parachord.shared.playback.ListenAlongSuspendedQueue
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.playback.listenAlongPollOf
import com.parachord.shared.repository.FriendsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android's Listen Along session (#611): the shared [ListenAlongHost] over
 * [PlaybackController], its 15 s poll loop and the wake lock that keeps
 * polling with the screen off.
 *
 * It lives as long as the app's process, like playback: the playback service
 * creates it when it starts, so a session keeps following the friend after
 * Back closes the app's screen, and one started with no phone screen (a link,
 * the car) runs too. Screens only observe it ([friend], [suspendedQueue],
 * [friendIsAhead], [messages]) and send it the user's choices. A session ends
 * only for the reasons the shared session gives, or with the process.
 *
 * Call it on the main thread, as [PlaybackController].
 */
class ListenAlongManager(
    context: Context,
    private val playbackController: PlaybackController,
    private val stateHolder: PlaybackStateHolder,
    private val friendsRepository: FriendsRepository,
    teardown: AndroidProtocolPlayTeardown,
) {
    companion object {
        private const val TAG = "ListenAlong"
        /** A safety limit on the wake lock; the session releases it when it ends. */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    private val _friend = MutableStateFlow<FriendEntity?>(null)
    /** The friend being followed, refreshed by each poll; null when no session runs. */
    val friend: StateFlow<FriendEntity?> = _friend.asStateFlow()

    private val _suspendedQueue = MutableStateFlow<List<TrackEntity>>(emptyList())
    /** Shown dimmed as "YOUR QUEUE" in the queue sheet while listening along. */
    val suspendedQueue: StateFlow<List<TrackEntity>> = _suspendedQueue.asStateFlow()

    private val _friendIsAhead = MutableStateFlow(false)
    /** True when the friend is on a song we haven't played (Now Playing's Next catches up). */
    val friendIsAhead: StateFlow<Boolean> = _friendIsAhead.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** The session's messages ("Listening along with X", …), for a screen to show. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Keeps the CPU awake so polling continues with the screen off. */
    private val wakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Parachord::ListenAlong")
            .apply { setReferenceCounted(false) }

    private val host: ListenAlongHost = ListenAlongHost(object : ListenAlongPlatform {
        override fun play(track: Track, context: PlaybackContext) =
            playbackController.playTrack(track, context = context)

        override fun currentQueue(): ListenAlongSuspendedQueue {
            val state = stateHolder.state.value
            return ListenAlongSuspendedQueue(listOfNotNull(state.currentTrack) + state.upNext, state.playbackContext)
        }

        override fun exitSpinoff() = playbackController.exitSpinoff()

        override fun restoreQueue(queue: ListenAlongSuspendedQueue, startPlayback: Boolean) {
            if (startPlayback) {
                playbackController.playQueue(queue.tracks, 0, queue.context)
            } else {
                playbackController.restoreQueuePaused(queue.tracks, queue.context)
            }
        }

        override fun clearQueue() = playbackController.clearQueue()
        override fun stopAndClear() = playbackController.stopAndClear()

        override fun showMessage(text: String) {
            Log.d(TAG, "Listen along: $text")
            _messages.tryEmit(text)
        }

        override fun releaseHold() = playbackController.releaseListenerHold()
        override fun ended() = onEnded()

        override fun stateChanged() {
            _friendIsAhead.value = host.friendIsAhead
            _suspendedQueue.value = host.suspendedQueue
        }

        override fun isPlaying() = stateHolder.state.value.isPlaying
        override fun isPlayStarting() = playbackController.isPlayStarting
        override fun currentTrackId() = stateHolder.state.value.currentTrack?.id
    })

    init {
        // The session drives playback through the controller's hooks.
        playbackController.onOwnTrackEnded = { host.onOwnTrackEnded() }
        playbackController.onTrackUnplayable = { track -> host.onTrackUnplayable(track.id) }
        playbackController.drivesTrack = { track -> host.drivesTrack(track.id) }
        playbackController.onUserAction = { action -> host.onUserAction(action) }
        playbackController.onTrackStarting = { track -> host.onTrackStarting(track.id) }
        // A `parachord://` play or radio link takes over, with or without a screen.
        teardown.setListenAlongStopper { silently -> takeOver(silently) }
    }

    /**
     * Start listening along with [friend]: play what they're playing now, then
     * poll every [LISTEN_ALONG_POLL_MS] and let the session decide. Switching
     * friends replaces the session and keeps the user's suspended queue; a
     * running spinoff ends first.
     */
    fun start(friend: FriendEntity) {
        pollJob?.cancel()
        pollJob = null
        _friend.value = friend
        if (!wakeLock.isHeld) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "Acquired WakeLock for listen-along polling")
        }

        val current = (listenAlongPollOf(friend) as? ListenAlongPoll.Listening)?.track
        host.start(friend.displayName, current)

        pollJob = scope.launch {
            while (isActive && host.isActive) {
                delay(LISTEN_ALONG_POLL_MS)
                // Saved and transient (deep-link) friends alike; a failed
                // request is ListenAlongPoll.Failed, not "not listening".
                val result = friendsRepository.pollListenAlong(friend)
                result.friend?.let { _friend.value = it }
                Log.d(TAG, "Listen along poll: ${result.poll} (playing=${stateHolder.state.value.isPlaying}, starting=${playbackController.isPlayStarting})")
                host.onPoll(result.poll)
            }
        }
    }

    /** The Listen Along toggle / stop button: the user's queue comes back. */
    fun stop() = host.stop()

    /**
     * A `parachord://` link takes over: a radio link [silently] (no message),
     * a play link as the user playing something else (the message shows).
     * Either way the suspended queue is dropped.
     */
    fun takeOver(silently: Boolean) = host.takeOver(silently)

    /** Catch up to the friend's song (Now Playing's Next while listening along). */
    fun catchUp() = host.catchUp()

    /** The session ended (any way): stop polling and release the wake lock. */
    private fun onEnded() {
        pollJob?.cancel()
        pollJob = null
        _friend.value = null
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "Released WakeLock for listen-along polling")
        }
    }
}
