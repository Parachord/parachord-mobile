package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.playback.QueuePersistenceStore
import com.parachord.shared.playback.settingsQueueStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Android's side of saving and restoring the queue across restarts: it feeds
 * the shared [QueuePersistenceStore] (which holds the rules, #594) from
 * [QueueManager.snapshot] and [PlaybackStateHolder.state]. The current track's
 * position is saved on a pause and every 15 s while it plays. Only with the
 * "Remember queue" setting on.
 */
class QueuePersistence constructor(
    private val settingsStore: SettingsStore,
    private val queueManager: QueueManager,
    private val stateHolder: PlaybackStateHolder,
) {
    companion object {
        private const val TAG = "QueuePersistence"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = QueuePersistenceStore(settingsQueueStorage(settingsStore), queueManager, scope)
    private var observeJob: Job? = null
    private var positionJob: Job? = null

    /**
     * Start saving the queue on every change, and the position while playing.
     * Called once from PlaybackController.connect().
     */
    fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                queueManager.snapshot,
                // Only the current track is saved. The full playback state
                // changes every 500 ms while a song plays (position), which
                // kept re-saving the whole queue about once a second.
                stateHolder.state.map { it.currentTrack }.distinctUntilChanged(),
                // Turning "Remember queue" on saves the queue as it is.
                settingsStore.persistQueue,
            ) { _, currentTrack, _ -> currentTrack }
                .collect { currentTrack -> store.saveSoon(currentTrack) }
        }
        positionJob?.cancel()
        positionJob = scope.launch {
            stateHolder.state.collect { state ->
                val due = store.positionSaveDue(state.isPlaying, currentTimeMillis())
                val track = state.currentTrack ?: return@collect
                if (due) store.savePosition(track.id, state.position, state.duration)
            }
        }
    }

    /**
     * Save [positionMs] as where [trackId] is now, at once: for a track
     * nothing plays (a restored or failed one) whose position the person
     * changed (Previous restarting it, a seek), which the observer above never
     * saves (it saves on a pause and while playing).
     */
    fun savePositionNow(trackId: String, positionMs: Long, durationMs: Long) {
        store.savePosition(trackId, positionMs, durationMs)
    }

    /** Where the restored track [trackId] should start, in ms; 0 starts it from the top. */
    suspend fun savedResumePositionMs(trackId: String): Long = store.resumePositionMs(trackId)

    /**
     * Restore the saved queue if the setting is on.
     *
     * @param canApply asked just before the saved queue is applied; false
     *   leaves the queue alone (see [com.parachord.shared.playback.QueueRestorePolicy]).
     * @return The saved current track (paused), or null if nothing was restored.
     */
    suspend fun restoreIfEnabled(canApply: () -> Boolean = { true }): TrackEntity? {
        val state = store.load() ?: return null
        // Checked after the read above, which suspends: a play can start in
        // the meantime (Android Auto starting the app), and putting the last
        // session's queue back over it would undo it.
        if (!canApply()) {
            Log.d(TAG, "Skipped restoring the queue: something is already playing")
            return null
        }
        val track = store.apply(state)
        Log.d(TAG, "Restored queue: current=${track?.title}, upNext=${state.upNext.size} tracks")
        return track
    }

    /** Clear any persisted queue data. */
    suspend fun clearPersistedQueue() {
        settingsStore.clearPersistedQueueState()
        Log.d(TAG, "Cleared persisted queue")
    }
}
