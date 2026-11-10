package com.parachord.android.playback

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.playback.PlaybackResumePosition
import com.parachord.shared.playback.SavedPlaybackPosition
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Persists and restores queue state across app restarts.
 *
 * Observes [QueueManager.snapshot] + [PlaybackStateHolder.state] (for currentTrack),
 * debounces changes, and serializes to DataStore as JSON. The current track's
 * position is saved separately, on a pause and every 15 s while it plays.
 * Only active when the "Remember queue" setting is enabled.
 */
class QueuePersistence constructor(
    private val settingsStore: SettingsStore,
    private val queueManager: QueueManager,
    private val stateHolder: PlaybackStateHolder,
    private val json: Json,
) {
    companion object {
        private const val TAG = "QueuePersistence"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null
    private var positionJob: Job? = null

    /**
     * Start observing queue state and auto-saving when the persist setting is on.
     * Should be called once from PlaybackController.connect().
     */
    @OptIn(FlowPreview::class)
    fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                queueManager.snapshot,
                // Only the current track is saved. The full playback state
                // changes every 500 ms while a song plays (position), which
                // kept re-saving the whole queue about once a second.
                stateHolder.state.map { it.currentTrack }.distinctUntilChanged(),
                settingsStore.persistQueue,
            ) { snapshot, currentTrack, enabled ->
                Triple(snapshot, currentTrack, enabled)
            }
                .debounce(500)
                .collectLatest { (snapshot, currentTrack, enabled) ->
                    if (!enabled) return@collectLatest
                    save(currentTrack, snapshot)
                }
        }
        positionJob?.cancel()
        positionJob = scope.launch { savePositionWhileObserving() }
    }

    /**
     * Saves where the current track is on a pause and every 15 s while it
     * plays ([PlaybackResumePosition]), apart from the queue, so a queue
     * restored after Android ends the app resumes the track there (#567).
     */
    private suspend fun savePositionWhileObserving() {
        var wasPlaying = false
        var lastSavedAt = 0L
        combine(stateHolder.state, settingsStore.persistQueue) { state, enabled -> state to enabled }
            .collect { (state, enabled) ->
                val now = currentTimeMillis()
                val due = PlaybackResumePosition.shouldSave(wasPlaying, state.isPlaying, now - lastSavedAt)
                wasPlaying = state.isPlaying
                val track = state.currentTrack ?: return@collect
                if (!enabled || !due) return@collect
                lastSavedAt = now
                try {
                    settingsStore.setPersistedPlaybackPosition(
                        PlaybackResumePosition.encode(
                            SavedPlaybackPosition(track.id, state.position, state.duration),
                        ),
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to save the playback position", e)
                }
            }
    }

    /**
     * Where the restored track [trackId] should start, in ms; 0 starts it
     * from the top ([PlaybackResumePosition.resumeAtMs]).
     */
    suspend fun savedResumePositionMs(trackId: String): Long =
        PlaybackResumePosition.resumeAtMs(
            PlaybackResumePosition.decode(settingsStore.getPersistedPlaybackPosition()),
            trackId,
        )

    private suspend fun save(
        currentTrack: com.parachord.android.data.db.entity.TrackEntity?,
        snapshot: QueueSnapshot,
    ) {
        try {
            val state = PersistedQueueState(
                currentTrack = currentTrack?.toSerializable(),
                upNext = snapshot.upNext.map { it.toSerializable() },
                playHistory = queueManager.history.map { it.toSerializable() },
                playHistoryInQueue = queueManager.historyInQueue,
                currentIsPoolTrack = queueManager.currentIsPoolTrack,
                upNextInLap = queueManager.upNextInLap,
                currentInLap = queueManager.currentInLap,
                playbackContext = snapshot.playbackContext,
                shuffleEnabled = snapshot.shuffleEnabled,
                originalOrder = queueManager.savedOriginalOrder?.map { it.toSerializable() },
            )
            val jsonStr = json.encodeToString(PersistedQueueState.serializer(), state)
            settingsStore.setPersistedQueueState(jsonStr)
            Log.d(TAG, "Saved queue: current=${currentTrack?.title}, upNext=${snapshot.upNext.size} tracks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue state", e)
        }
    }

    /**
     * Restore persisted queue if the setting is enabled.
     *
     * @param canApply asked just before the saved queue is applied; false
     *   leaves the queue alone (see [com.parachord.shared.playback.QueueRestorePolicy]).
     * @return The saved current track (paused), or null if nothing was restored.
     */
    suspend fun restoreIfEnabled(
        canApply: () -> Boolean = { true },
    ): com.parachord.android.data.db.entity.TrackEntity? {
        if (!settingsStore.isPersistQueueEnabled()) return null

        val jsonStr = settingsStore.getPersistedQueueState() ?: return null
        return try {
            val state = json.decodeFromString(PersistedQueueState.serializer(), jsonStr)
            if (state.upNext.isEmpty() && state.currentTrack == null) return null
            // Checked after the reads above, which suspend: a play can start
            // in the meantime (Android Auto starting the app), and putting the
            // last session's queue back over it would undo it.
            if (!canApply()) {
                Log.d(TAG, "Skipped restoring the queue: something is already playing")
                return null
            }

            queueManager.restoreState(
                restoredUpNext = state.upNext.map { it.toTrackEntity() },
                restoredHistory = state.playHistory.map { it.toTrackEntity() },
                restoredOriginalOrder = state.originalOrder?.map { it.toTrackEntity() },
                context = state.playbackContext,
                shuffle = state.shuffleEnabled,
                restoredHistoryInQueue = state.playHistoryInQueue,
                restoredCurrentIsPoolTrack = state.currentIsPoolTrack,
                restoredUpNextInLap = state.upNextInLap,
                restoredCurrentInLap = state.currentInLap,
            )

            val track = state.currentTrack?.toTrackEntity()
            Log.d(TAG, "Restored queue: current=${track?.title}, upNext=${state.upNext.size} tracks")
            track
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore queue state", e)
            null
        }
    }

    /** Clear any persisted queue data. */
    suspend fun clearPersistedQueue() {
        settingsStore.clearPersistedQueueState()
        Log.d(TAG, "Cleared persisted queue")
    }
}
