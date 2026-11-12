package com.parachord.shared.playback

import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/** Where the saved queue and the resume position live ("Remember queue"). */
interface QueueStorage {
    /** The "Remember queue" setting. */
    suspend fun enabled(): Boolean
    suspend fun readQueue(): String?
    suspend fun writeQueue(json: String)
    suspend fun readPosition(): String?
    suspend fun writePosition(json: String)
}

/** [QueueStorage] on the app's settings (`persisted_queue_state`, `persisted_queue_position`). */
fun settingsQueueStorage(settings: SettingsStore): QueueStorage = object : QueueStorage {
    override suspend fun enabled() = settings.isPersistQueueEnabled()
    override suspend fun readQueue() = settings.getPersistedQueueState()
    override suspend fun writeQueue(json: String) = settings.setPersistedQueueState(json)
    override suspend fun readPosition() = settings.getPersistedPlaybackPosition()
    override suspend fun writePosition(json: String) = settings.setPersistedPlaybackPosition(json)
}

/**
 * Saves the queue so the next launch can put it back, and restores it (#594).
 * One implementation for both apps: Android's `QueuePersistence` and iOS's
 * `QueuePlaybackCoordinator` only tell it when the queue changed and apply
 * what it reads.
 *
 * - **Saving:** [saveSoon] after every change to the queue or the current
 *   track. It takes the queue as it is at the call, and writes the latest one
 *   [SAVE_DEBOUNCE_MS] after the last call, so a burst of changes is one
 *   write. Only with "Remember queue" on, and never an empty queue over the
 *   last session's until this run has had one ([QueueRestorePolicy.shouldSave]).
 * - **Restoring:** [load] reads and decodes the saved queue; the platform
 *   checks [QueueRestorePolicy.shouldApply] on its own thread and calls [apply].
 * - **The position** ([PlaybackResumePosition]) is saved apart from the queue:
 *   [positionSaveDue] on each playback tick, [savePosition] to write it.
 *
 * Call [saveSoon], [apply] and [positionSaveDue] from the thread that changes
 * the [QueueManager] (iOS: the main thread).
 */
class QueuePersistenceStore(
    private val storage: QueueStorage,
    private val queueManager: QueueManager,
    private val scope: CoroutineScope,
    private val debounceMs: Long = SAVE_DEBOUNCE_MS,
) {
    companion object {
        /** A save waits this long for more changes. */
        const val SAVE_DEBOUNCE_MS = 500L
        private const val TAG = "QueuePersistence"
    }

    /** A queue was restored, or a non-empty one saved, in this run. */
    @Volatile var hadQueueThisRun = false
        private set

    @Volatile private var pendingSave: Job? = null

    /** The queue as it is now, with [currentTrack] (the queue manager doesn't hold it). */
    fun snapshot(currentTrack: Track?): PersistedQueueState {
        val snap = queueManager.snapshot.value
        return PersistedQueueState(
            currentTrack = currentTrack,
            upNext = snap.upNext,
            playHistory = queueManager.history,
            playbackContext = snap.playbackContext,
            shuffleEnabled = snap.shuffleEnabled,
            originalOrder = queueManager.savedOriginalOrder,
            playHistoryInQueue = queueManager.historyInQueue,
            currentIsPoolTrack = queueManager.currentIsPoolTrack,
            upNextInLap = queueManager.upNextInLap,
            currentInLap = queueManager.currentInLap,
        )
    }

    /** Save the queue as it is now, after [SAVE_DEBOUNCE_MS] with no newer call. */
    fun saveSoon(currentTrack: Track?) {
        val state = snapshot(currentTrack)
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(debounceMs)
            write(state)
        }
    }

    /** Write [state] now. False when it wasn't written (setting off, an empty queue held back, an error). */
    @Throws(Exception::class)
    suspend fun write(state: PersistedQueueState): Boolean {
        try {
            if (!storage.enabled()) return false
            val isEmpty = state.currentTrack == null && state.upNext.isEmpty()
            if (!QueueRestorePolicy.shouldSave(isEmpty, hadQueueThisRun)) {
                Log.d(TAG, "Not saving an empty queue over the last session's (nothing queued yet this run)")
                return false
            }
            if (!isEmpty) hadQueueThisRun = true
            storage.writeQueue(QueuePersistenceCodec.encode(state))
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to save the queue", e)
            return false
        }
    }

    /** The saved queue, when "Remember queue" is on and one with a track was saved; else null. */
    @Throws(Exception::class)
    suspend fun load(): PersistedQueueState? {
        try {
            if (!storage.enabled()) return null
            val state = storage.readQueue()?.let(QueuePersistenceCodec::decode) ?: return null
            return state.takeUnless { it.currentTrack == null && it.upNext.isEmpty() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to read the saved queue", e)
            return null
        }
    }

    /**
     * Put [state] into the queue manager. Returns its current track, which the
     * platform shows paused, loaded in no engine ([LoadOnPlay.Why.RESTORED]).
     */
    fun apply(state: PersistedQueueState): Track? {
        queueManager.restoreState(
            restoredUpNext = state.upNext,
            restoredHistory = state.playHistory,
            restoredOriginalOrder = state.originalOrder,
            context = state.playbackContext,
            shuffle = state.shuffleEnabled,
            restoredHistoryInQueue = state.playHistoryInQueue,
            restoredCurrentIsPoolTrack = state.currentIsPoolTrack,
            restoredUpNextInLap = state.upNextInLap,
            restoredCurrentInLap = state.currentInLap,
        )
        hadQueueThisRun = true
        return state.currentTrack
    }

    /** Where the restored track [trackId] starts, in ms ([PlaybackResumePosition.resumeAtMs]). */
    /**
     * Whether resuming [trackId] at its saved position continues a listen that
     * was already scrobbled (#657). False when it starts from the top.
     */
    @Throws(Exception::class)
    suspend fun resumeContinuesScrobbled(trackId: String): Boolean =
        try {
            val saved = PlaybackResumePosition.decode(storage.readPosition())
            saved != null && saved.scrobbled && PlaybackResumePosition.resumeAtMs(saved, trackId) > 0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }

    @Throws(Exception::class)
    suspend fun resumePositionMs(trackId: String): Long =
        try {
            PlaybackResumePosition.resumeAtMs(PlaybackResumePosition.decode(storage.readPosition()), trackId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            0
        }

    private var positionWasPlaying = false
    private var positionSavedAtMs = 0L

    /**
     * Call on each playback tick: whether to save the position now (a pause,
     * and every [PlaybackResumePosition.SAVE_INTERVAL_MS] while playing).
     */
    fun positionSaveDue(isPlaying: Boolean, nowMs: Long): Boolean {
        val due = PlaybackResumePosition.shouldSave(positionWasPlaying, isPlaying, nowMs - positionSavedAtMs)
        positionWasPlaying = isPlaying
        if (due) positionSavedAtMs = nowMs
        return due
    }

    /**
     * Save where [trackId] is, when "Remember queue" is on. [scrobbled]: this
     * listen was already scrobbled, so a resume after a restart doesn't scrobble
     * it again (#657).
     */
    fun savePosition(trackId: String, positionMs: Long, durationMs: Long, scrobbled: Boolean) {
        val json = PlaybackResumePosition.encode(SavedPlaybackPosition(trackId, positionMs, durationMs, scrobbled))
        scope.launch {
            try {
                if (storage.enabled()) storage.writePosition(json)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to save the playback position", e)
            }
        }
    }
}

/**
 * Getting the next track ready before the current one ends (#594). One rule
 * for both apps.
 *
 * - When a track starts, the next [PRERESOLVE_AHEAD] queued tracks are resolved
 *   in the background, so their source icons show and they start quickly.
 * - In the last [PREPARE_WITHIN_MS] of a track, the next one's engine is
 *   readied: Android wakes Spotify for a Spotify track
 *   (`prewarmSpotifyFor`) and resolves an Apple Music one; iOS fetches the
 *   next Apple Music song.
 */
object NextTrackPreparation {
    const val PRERESOLVE_AHEAD = 3
    const val PREPARE_WITHIN_MS = 30_000L

    /** The queued tracks to resolve when a track starts. */
    fun tracksToPreresolve(upNext: List<Track>): List<Track> = upNext.take(PRERESOLVE_AHEAD)

    /** Whether the track is close enough to its end to ready the next one. */
    fun shouldPrepareNext(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && durationMs - positionMs in 1..PREPARE_WITHIN_MS
}
