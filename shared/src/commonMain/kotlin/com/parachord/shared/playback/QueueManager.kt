package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable snapshot of the queue state, exposed to UI via StateFlow.
 */
data class QueueSnapshot(
    val upNext: List<Track> = emptyList(),
    val playbackContext: PlaybackContext? = null,
    val shuffleEnabled: Boolean = false,
)

/**
 * Manages the playback queue, mirroring the desktop app's queue logic.
 *
 * Key design: the current track is NOT in the queue. The queue (`upNext`)
 * contains only upcoming tracks. A separate `playHistory` stack enables
 * back-navigation.
 *
 * Each history entry records whether it belongs to the current queue (the
 * repeat-all lap). Previous walks every entry; repeat all ([wrapToStart])
 * wraps only the lap: entries from before a cleared queue ([clearQueue]) and
 * spinoff pool tracks aren't in it.
 *
 * The spinoff pool plays outside this class, so the platforms report when a
 * pool track starts ([poolTrackStarted]); every other play goes through a
 * method here. From that, this class knows whether the current track is a pool
 * track ([currentIsPoolTrack]) and decides what goes into the lap: the seed (the
 * track playing when the first pool track starts) goes in, a pool track that
 * leaves for the queue ([skipNext], [playFromQueue], [wrapToStart],
 * [pushToHistory]) stays out, and a track reached with Previous is in the lap
 * only if its history entry was.
 *
 * All mutations emit a new [QueueSnapshot] via [snapshot].
 */
class QueueManager {

    private val upNext = mutableListOf<Track>()
    /** A played track, and whether it belongs to the current queue (the repeat-all lap). */
    private class Played(val track: Track, val inQueue: Boolean)

    private val playHistory = mutableListOf<Played>()
    private var originalOrder: List<Track>? = null
    private var _playbackContext: PlaybackContext? = null
    private var _shuffleEnabled = false
    private var _currentIsPoolTrack = false

    /**
     * The current track was pulled from the spinoff pool (see [poolTrackStarted]).
     * False after any play that goes through this class. Read for persistence.
     */
    val currentIsPoolTrack: Boolean get() = _currentIsPoolTrack

    private val _snapshot = MutableStateFlow(QueueSnapshot())
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    /**
     * Set a new queue from a collection of tracks.
     *
     * Splits the track at [startIndex] out as the return value (to be played),
     * and puts everything after it into [upNext]. If shuffle is enabled,
     * shuffles upNext and saves original order.
     *
     * @return The track at [startIndex] to play immediately.
     */
    fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
        context: PlaybackContext? = null,
        shuffle: Boolean = false,
    ): Track? {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return null

        val trackToPlay = tracks[startIndex]
        val remaining = tracks.subList(startIndex + 1, tracks.size).toMutableList()

        playHistory.clear()
        _currentIsPoolTrack = false
        _playbackContext = context
        _shuffleEnabled = shuffle

        if (shuffle && remaining.size > 1) {
            originalOrder = remaining.toList()
            remaining.shuffle()
        } else {
            originalOrder = null
        }

        upNext.clear()
        upNext.addAll(remaining)
        emitSnapshot()
        return trackToPlay
    }

    /** Append tracks to the end of the queue. */
    fun addToQueue(tracks: List<Track>) {
        upNext.addAll(tracks)
        emitSnapshot()
    }

    /** Insert tracks at the front of the queue (play next). */
    fun insertNext(tracks: List<Track>) {
        upNext.addAll(0, tracks)
        emitSnapshot()
    }

    /**
     * Advance to the next track.
     * Pushes [currentTrack] onto play history (out of the lap when it's a pool
     * track) and pops the first item from upNext.
     *
     * @return The next track, or null if queue is empty.
     */
    fun skipNext(currentTrack: Track?): Track? {
        if (upNext.isEmpty()) return null
        pushCurrent(currentTrack)
        val next = upNext.removeAt(0)
        emitSnapshot()
        return next
    }

    /**
     * A spinoff pool track is starting; [current] is the track playing now
     * (null when nothing is, e.g. a radio link that cleared the queue first).
     * The first pool track keeps [current] (the spinoff's seed, or a tapped
     * track) in history inside the lap; later ones push nothing, since pool
     * tracks never join the lap.
     */
    fun poolTrackStarted(current: Track?) {
        if (!_currentIsPoolTrack && current != null) playHistory.add(Played(current, inQueue = true))
        _currentIsPoolTrack = true
        emitSnapshot()
    }

    /** Push the outgoing current track (out of the lap when it's a pool track). */
    private fun pushCurrent(currentTrack: Track?) {
        currentTrack?.let { playHistory.add(Played(it, inQueue = !_currentIsPoolTrack)) }
        _currentIsPoolTrack = false
    }

    /**
     * Go back to the previous track.
     * Pops from play history and pushes [currentTrack] to the front of upNext,
     * unless it's a pool track: that would put it in the user's queue. The track
     * reached is in the lap only if its history entry was.
     *
     * @return The previous track, or null if history is empty.
     */
    fun skipPrevious(currentTrack: Track?): Track? {
        if (playHistory.isEmpty()) return null
        if (!_currentIsPoolTrack) currentTrack?.let { upNext.add(0, it) }
        val previous = playHistory.removeAt(playHistory.lastIndex)
        _currentIsPoolTrack = !previous.inQueue
        emitSnapshot()
        return previous.track
    }

    /**
     * Put [track] on top of play history without touching Up Next. Used when the
     * queue runs out and playback stops on the last track, which is then cleared:
     * Previous must still go back to it, not to the track before it. A pool
     * track goes in out of the lap.
     */
    fun pushToHistory(track: Track) {
        pushCurrent(track)
        emitSnapshot()
    }

    /** True when there is a track in Up Next. */
    val hasNext: Boolean get() = upNext.isNotEmpty()

    /** True when there is a track in play history. */
    val hasPrevious: Boolean get() = playHistory.isNotEmpty()

    /**
     * Repeat-all wrap: gather this queue's history, [currentTrack] (if any, and
     * not a pool track) and Up Next in that order, return the first to play and
     * queue the rest. History from before a cleared queue and spinoff pool tracks
     * aren't wrapped. With shuffle on, the whole set is shuffled first and
     * [originalOrder] is the rest in played order. History is cleared; context
     * and shuffle flag are kept.
     *
     * @return The track to play, or null if there was nothing to wrap.
     */
    fun wrapToStart(currentTrack: Track?): Track? {
        val all = buildList {
            playHistory.forEach { if (it.inQueue) add(it.track) }
            if (!_currentIsPoolTrack) currentTrack?.let { add(it) }
            addAll(upNext)
        }
        if (all.isEmpty()) return null

        val ordered = if (_shuffleEnabled) all.shuffled() else all
        val first = ordered.first()
        val rest = ordered.drop(1)

        playHistory.clear()
        _currentIsPoolTrack = false
        upNext.clear()
        upNext.addAll(rest)
        originalOrder = if (_shuffleEnabled && rest.size > 1) all.filter { it !== first }.toList() else null
        emitSnapshot()
        return first
    }

    /**
     * User tapped a track in the queue UI.
     * Pushes [currentTrack] to history (out of the lap when it's a pool track),
     * removes everything before [index] in upNext, pops the item at [index] and
     * returns it.
     */
    fun playFromQueue(index: Int, currentTrack: Track?): Track? {
        if (index !in upNext.indices) return null
        pushCurrent(currentTrack)
        val track = upNext[index]
        val remaining = upNext.subList(index + 1, upNext.size).toList()
        upNext.clear()
        upNext.addAll(remaining)
        emitSnapshot()
        return track
    }

    /** Drag-to-reorder: move a track from one position to another. */
    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in upNext.indices || toIndex !in upNext.indices) return
        val item = upNext.removeAt(fromIndex)
        upNext.add(toIndex, item)
        emitSnapshot()
    }

    /** Remove a single track from the queue. */
    fun removeFromQueue(index: Int) {
        if (index !in upNext.indices) return
        upNext.removeAt(index)
        emitSnapshot()
    }

    /**
     * Clear the queue. Resets shuffle state and context, but preserves play
     * history for Previous; that history is no longer this queue's, so repeat
     * all won't wrap into it. The current track counts as the new queue's.
     */
    fun clearQueue() {
        upNext.clear()
        _currentIsPoolTrack = false
        for (i in playHistory.indices) {
            if (playHistory[i].inQueue) playHistory[i] = Played(playHistory[i].track, inQueue = false)
        }
        originalOrder = null
        _playbackContext = null
        emitSnapshot()
    }

    /**
     * Toggle shuffle mode.
     * ON: save current upNext order, shuffle it (Fisher-Yates).
     * OFF: restore original order, removing items that were already played.
     */
    fun toggleShuffle(): Boolean {
        _shuffleEnabled = !_shuffleEnabled
        if (_shuffleEnabled) {
            if (upNext.size > 1) {
                originalOrder = upNext.toList()
                upNext.shuffle()
            }
        } else {
            val original = originalOrder
            if (original != null) {
                val currentIds = upNext.map { it.id }.toSet()
                val restored = original.filter { it.id in currentIds }
                upNext.clear()
                upNext.addAll(restored)
                originalOrder = null
            }
        }
        emitSnapshot()
        return _shuffleEnabled
    }

    val shuffleEnabled: Boolean get() = _shuffleEnabled
    val playbackContext: PlaybackContext? get() = _playbackContext

    /** Set the playback context without changing the queue contents. */
    fun setContext(context: PlaybackContext?) {
        _playbackContext = context
        emitSnapshot()
    }

    /** Read-only access to play history for persistence. */
    val history: List<Track> get() = playHistory.map { it.track }

    /** Whether each [history] entry belongs to the current queue, for persistence. */
    val historyInQueue: List<Boolean> get() = playHistory.map { it.inQueue }

    /** Read-only access to original order for persistence. */
    val savedOriginalOrder: List<Track>? get() = originalOrder?.toList()

    /**
     * Restore full queue state from persistence.
     * Called once on startup when persist-queue setting is enabled.
     * [restoredHistoryInQueue] pairs with [restoredHistory]; a missing list or
     * entry (an older saved queue) counts as in the queue.
     * [restoredCurrentIsPoolTrack]: the restored current track was a pool track.
     */
    fun restoreState(
        restoredUpNext: List<Track>,
        restoredHistory: List<Track>,
        restoredOriginalOrder: List<Track>?,
        context: PlaybackContext?,
        shuffle: Boolean,
        restoredHistoryInQueue: List<Boolean>? = null,
        restoredCurrentIsPoolTrack: Boolean = false,
    ) {
        _currentIsPoolTrack = restoredCurrentIsPoolTrack
        upNext.clear()
        upNext.addAll(restoredUpNext)
        playHistory.clear()
        restoredHistory.forEachIndexed { i, track ->
            playHistory.add(Played(track, restoredHistoryInQueue?.getOrNull(i) ?: true))
        }
        originalOrder = restoredOriginalOrder
        _playbackContext = context
        _shuffleEnabled = shuffle
        emitSnapshot()
    }

    private fun emitSnapshot() {
        _snapshot.value = QueueSnapshot(
            upNext = upNext.toList(),
            playbackContext = _playbackContext,
            shuffleEnabled = _shuffleEnabled,
        )
    }
}
