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
 * **The repeat-all lap.** Every track this class holds (each history entry,
 * each Up Next entry, the current track) records whether it belongs to the
 * current queue, the lap that repeat all ([wrapToStart]) wraps. Out of the lap:
 * history from before a cleared queue ([clearQueue]) and spinoff pool tracks.
 * Previous and Next move tracks between history, the current track and Up Next
 * and keep their flag, so every track stays reachable and an out-of-lap track
 * never wraps.
 *
 * The spinoff pool plays outside this class, so the platforms report when a
 * pool track starts ([poolTrackStarted]); every other play goes through a
 * method here. The track playing when a pool track first starts (the seed)
 * goes to history with its own flag; a pool track is out of the lap.
 *
 * All mutations emit a new [QueueSnapshot] via [snapshot].
 */
class QueueManager {

    /** A track and whether it belongs to the current queue (the repeat-all lap). */
    private class Entry(val track: Track, val inLap: Boolean)

    private val upNext = mutableListOf<Entry>()
    private val playHistory = mutableListOf<Entry>()
    private var originalOrder: List<Track>? = null
    private var _playbackContext: PlaybackContext? = null
    private var _shuffleEnabled = false
    private var _currentIsPoolTrack = false
    private var _currentInLap = true

    /**
     * The current track was pulled from the spinoff pool (see [poolTrackStarted]).
     * False after any play that goes through this class. Read for persistence.
     */
    val currentIsPoolTrack: Boolean get() = _currentIsPoolTrack

    /** The current track belongs to the lap. Read for persistence. */
    val currentInLap: Boolean get() = _currentInLap

    private val _snapshot = MutableStateFlow(QueueSnapshot())
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    /** The track that's about to play came from [entry] (or from outside, in the lap). */
    private fun becomeCurrent(entry: Entry?) {
        _currentInLap = entry?.inLap ?: true
        _currentIsPoolTrack = false
    }

    /** Push the outgoing current track to history with its flag. */
    private fun pushCurrent(currentTrack: Track?) {
        currentTrack?.let { playHistory.add(Entry(it, _currentInLap)) }
        trimHistory()
    }

    /**
     * A play from outside the queue's order is replacing [outgoing] with
     * [next] (#605): [outgoing] goes to history, out of the lap, so Previous
     * reaches it but repeat all doesn't wrap it. Nothing when it's the same
     * track played again.
     */
    private fun leaveForNewPlay(outgoing: Track?, next: Track?) {
        if (outgoing == null || outgoing.id == next?.id) return
        playHistory.add(Entry(outgoing, inLap = false))
        trimHistory()
    }

    /** History is no longer this queue's: Previous still reaches it, repeat all doesn't wrap it. */
    private fun leaveLap() {
        for (i in playHistory.indices) {
            if (playHistory[i].inLap) playHistory[i] = Entry(playHistory[i].track, inLap = false)
        }
    }

    /**
     * Keep history at most [MAX_HISTORY] entries, dropping the oldest from
     * earlier queues first. It outlives each queue (#605) and is saved with
     * it, so it would otherwise grow without end. The current lap is never
     * trimmed, so repeat all always has it.
     */
    private fun trimHistory() {
        var excess = playHistory.size - MAX_HISTORY
        var i = 0
        while (excess > 0 && i < playHistory.size) {
            if (!playHistory[i].inLap) {
                playHistory.removeAt(i)
                excess--
            } else {
                i++
            }
        }
    }

    /**
     * Set a new queue from a collection of tracks.
     *
     * Splits the track at [startIndex] out as the return value (to be played),
     * and puts everything after it into [upNext]. If shuffle is enabled,
     * shuffles upNext and saves original order.
     *
     * The earlier history stays, out of the new queue's lap: Previous still
     * reaches it, repeat all doesn't wrap it (#605). [outgoing], the track
     * the new queue replaces, goes on top of it.
     *
     * @return The track at [startIndex] to play immediately.
     */
    fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
        context: PlaybackContext? = null,
        shuffle: Boolean = false,
        outgoing: Track? = null,
    ): Track? {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return null

        val trackToPlay = tracks[startIndex]
        val remaining = tracks.subList(startIndex + 1, tracks.size).toMutableList()

        leaveLap()
        leaveForNewPlay(outgoing, trackToPlay)
        becomeCurrent(null)
        _playbackContext = context
        _shuffleEnabled = shuffle

        if (shuffle && remaining.size > 1) {
            originalOrder = remaining.toList()
            remaining.shuffle()
        } else {
            originalOrder = null
        }

        upNext.clear()
        remaining.forEach { upNext.add(Entry(it, inLap = true)) }
        emitSnapshot()
        return trackToPlay
    }

    /** Append tracks to the end of the queue. */
    fun addToQueue(tracks: List<Track>) {
        tracks.forEach { upNext.add(Entry(it, inLap = true)) }
        emitSnapshot()
    }

    /** Insert tracks at the front of the queue (play next). */
    fun insertNext(tracks: List<Track>) {
        upNext.addAll(0, tracks.map { Entry(it, inLap = true) })
        emitSnapshot()
    }

    /**
     * Advance to the next track.
     * Pushes [currentTrack] onto play history and pops the first item from upNext.
     *
     * @return The next track, or null if queue is empty.
     */
    fun skipNext(currentTrack: Track?): Track? {
        if (upNext.isEmpty()) return null
        pushCurrent(currentTrack)
        val next = upNext.removeAt(0)
        becomeCurrent(next)
        emitSnapshot()
        return next.track
    }

    /**
     * A spinoff pool track is starting; [current] is the track playing now
     * (null when nothing is, e.g. a radio link that cleared the queue first).
     * When the current track isn't a pool track (the spinoff's seed, a tapped
     * track, a track reached with Previous) it goes to history with its flag,
     * so Previous reaches it; later pool tracks push nothing. The pool track
     * itself is out of the lap.
     */
    fun poolTrackStarted(current: Track?) {
        if (!_currentIsPoolTrack) pushCurrent(current)
        _currentIsPoolTrack = true
        _currentInLap = false
        emitSnapshot()
    }

    /**
     * Go back to the previous track.
     * Pops from play history and pushes [currentTrack] to the front of upNext,
     * both keeping their flag.
     *
     * @return The previous track, or null if history is empty.
     */
    fun skipPrevious(currentTrack: Track?): Track? {
        if (playHistory.isEmpty()) return null
        currentTrack?.let { upNext.add(0, Entry(it, _currentInLap)) }
        val previous = playHistory.removeAt(playHistory.lastIndex)
        becomeCurrent(previous)
        emitSnapshot()
        return previous.track
    }

    /**
     * Put [track] on top of play history without touching Up Next. Used when the
     * queue runs out and playback stops on the last track, which is then cleared:
     * Previous must still go back to it, not to the track before it.
     */
    fun pushToHistory(track: Track) {
        pushCurrent(track)
        becomeCurrent(null)
        emitSnapshot()
    }

    /** True when there is a track in Up Next. */
    val hasNext: Boolean get() = upNext.isNotEmpty()

    /** True when there is a track in play history. */
    val hasPrevious: Boolean get() = playHistory.isNotEmpty()

    /**
     * Repeat-all wrap: gather the lap (history, [currentTrack] if any, and Up
     * Next, in that order, leaving out every track that isn't in the lap),
     * return the first to play and queue the rest. With shuffle on, the whole
     * set is shuffled first and [originalOrder] is the rest in played order.
     * History is cleared; context and shuffle flag are kept.
     *
     * @return The track to play, or null if there was nothing to wrap.
     */
    fun wrapToStart(currentTrack: Track?): Track? {
        val all = buildList {
            playHistory.forEach { if (it.inLap) add(it.track) }
            if (_currentInLap) currentTrack?.let { add(it) }
            upNext.forEach { if (it.inLap) add(it.track) }
        }
        if (all.isEmpty()) return null

        val ordered = if (_shuffleEnabled) all.shuffled() else all
        val first = ordered.first()
        val rest = ordered.drop(1)

        playHistory.clear()
        becomeCurrent(null)
        upNext.clear()
        rest.forEach { upNext.add(Entry(it, inLap = true)) }
        originalOrder = if (_shuffleEnabled && rest.size > 1) all.filter { it !== first }.toList() else null
        emitSnapshot()
        return first
    }

    /**
     * User tapped a track in the queue UI.
     * Pushes [currentTrack] to history, removes everything before [index] in upNext,
     * pops the item at [index] and returns it.
     */
    fun playFromQueue(index: Int, currentTrack: Track?): Track? {
        if (index !in upNext.indices) return null
        pushCurrent(currentTrack)
        val entry = upNext[index]
        val remaining = upNext.subList(index + 1, upNext.size).toList()
        upNext.clear()
        upNext.addAll(remaining)
        becomeCurrent(entry)
        emitSnapshot()
        return entry.track
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
     *
     * For a single-track play ([outgoing] replaced by [next]), the outgoing
     * track goes on top of history, out of the lap, so Previous reaches it
     * (#605). Without them (the Clear queue button) the current track stays.
     */
    fun clearQueue(outgoing: Track? = null, next: Track? = null) {
        upNext.clear()
        becomeCurrent(null)
        leaveLap()
        leaveForNewPlay(outgoing, next)
        originalOrder = null
        _playbackContext = null
        emitSnapshot()
    }

    /**
     * Toggle shuffle mode.
     * ON: save current upNext order, shuffle it (Fisher-Yates).
     * OFF: restore original order, removing items that were already played.
     * Each track keeps its lap flag.
     */
    fun toggleShuffle(): Boolean {
        _shuffleEnabled = !_shuffleEnabled
        if (_shuffleEnabled) {
            if (upNext.size > 1) {
                originalOrder = upNext.map { it.track }
                upNext.shuffle()
            }
        } else {
            val original = originalOrder
            if (original != null) {
                val pending = upNext.toMutableList()
                val restored = original.mapNotNull { track ->
                    val i = pending.indexOfFirst { it.track.id == track.id }
                    if (i >= 0) pending.removeAt(i) else null
                }
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

    /** Whether each [history] entry belongs to the lap, for persistence. */
    val historyInQueue: List<Boolean> get() = playHistory.map { it.inLap }

    /** Whether each Up Next entry belongs to the lap, for persistence. */
    val upNextInLap: List<Boolean> get() = upNext.map { it.inLap }

    /** Read-only access to original order for persistence. */
    val savedOriginalOrder: List<Track>? get() = originalOrder?.toList()

    /**
     * Restore full queue state from persistence.
     * Called once on startup when persist-queue setting is enabled.
     * [restoredHistoryInQueue] and [restoredUpNextInLap] pair with
     * [restoredHistory] and [restoredUpNext]; a missing list or entry (an older
     * saved queue) counts as in the lap. [restoredCurrentInLap] and
     * [restoredCurrentIsPoolTrack] describe the restored current track. A Listen Along
     * [context] isn't restored (no session runs after a restart).
     */
    fun restoreState(
        restoredUpNext: List<Track>,
        restoredHistory: List<Track>,
        restoredOriginalOrder: List<Track>?,
        context: PlaybackContext?,
        shuffle: Boolean,
        restoredHistoryInQueue: List<Boolean>? = null,
        restoredCurrentIsPoolTrack: Boolean = false,
        restoredUpNextInLap: List<Boolean>? = null,
        restoredCurrentInLap: Boolean = !restoredCurrentIsPoolTrack,
    ) {
        _currentIsPoolTrack = restoredCurrentIsPoolTrack
        _currentInLap = restoredCurrentInLap && !restoredCurrentIsPoolTrack
        upNext.clear()
        restoredUpNext.forEachIndexed { i, track ->
            upNext.add(Entry(track, restoredUpNextInLap?.getOrNull(i) ?: true))
        }
        playHistory.clear()
        restoredHistory.forEachIndexed { i, track ->
            playHistory.add(Entry(track, restoredHistoryInQueue?.getOrNull(i) ?: true))
        }
        originalOrder = restoredOriginalOrder
        // No Listen Along session survives a restart, so its context doesn't either:
        // left on, repeat would stay refused and Now Playing's Next disabled.
        _playbackContext = context?.takeUnless { it.type == LISTEN_ALONG_CONTEXT }
        _shuffleEnabled = shuffle
        emitSnapshot()
    }

    private fun emitSnapshot() {
        _snapshot.value = QueueSnapshot(
            upNext = upNext.map { it.track },
            playbackContext = _playbackContext,
            shuffleEnabled = _shuffleEnabled,
        )
    }

    companion object {
        /** The most history entries kept ([trimHistory]). */
        const val MAX_HISTORY = 200
    }

}
