package com.parachord.android.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline

/**
 * Synthetic [Timeline] that exposes the Parachord queue (current track +
 * upNext) to Media3, including Android Auto's "Up Next" view.
 *
 * **Index convention:** index 0 is the current track. Indices 1..N are
 * `QueueSnapshot.upNext[0..N-1]`. Callers that translate Auto's
 * `mediaItemIndex` into a `QueueManager` queue index must subtract 1.
 *
 * **Why we need this:** the underlying [androidx.media3.exoplayer.ExoPlayer]
 * always holds a single-item timeline (either the silence loop during
 * external playback, or the currently-playing native track during ExoPlayer
 * playback). Without a synthetic timeline, Auto's queue view is empty.
 *
 * The wrapper layer ([com.parachord.android.playback.PlaybackService.ExternalPlaybackForwardingPlayer])
 * builds an instance per snapshot tick and reports it via
 * `getCurrentTimeline()`, hiding the delegate's single-item timeline from
 * external listeners.
 *
 * **Window/period contract:** every window is non-seekable, non-dynamic.
 * Window/period uids are positional (`<mediaId>#<index>`) so duplicate
 * mediaIds in the queue (e.g. user added the now-playing track to upNext)
 * resolve to distinct windows. [MediaItem.mediaId] itself stays as the bare
 * [com.parachord.shared.model.Track] id — Android Auto and
 * [PlaybackController.playFromQueue] both rely on the bare id for lookup;
 * only the Window.uid / Period.uid are positional. One period per window is
 * a deliberate simplification (no ad insertion, no multi-period content).
 * `defaultPositionProjectionUs` is ignored — it only matters for seekable /
 * live windows, which we don't expose.
 *
 * The constructor takes defensive copies of [items] and [durationsUs] so the
 * Timeline is immutable after construction even if callers retain and mutate
 * the original collection.
 */
class QueueTimeline(
    items: List<MediaItem>,
    durationsUs: LongArray,
) : Timeline() {

    private val items: List<MediaItem> = items.toList()
    private val durationsUs: LongArray = durationsUs.copyOf()

    init {
        require(this.items.size == this.durationsUs.size) {
            "items (${this.items.size}) and durationsUs (${this.durationsUs.size}) must be the same length"
        }
    }

    private fun uidAt(index: Int): String = "${items[index].mediaId}#$index"

    override fun getWindowCount(): Int = items.size

    override fun getPeriodCount(): Int = items.size

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        // Defensive bounds check. Media3 contractually shouldn't call
        // getWindow with an out-of-range index — but it does crash if
        // [ExternalPlaybackForwardingPlayer]'s snapshot is ever
        // inconsistent (timeline.size > 0 with currentMediaItemIndex =
        // INDEX_UNSET; controller-side PlayerInfo.getCurrentMediaItem
        // then calls timeline.getWindow(-1, ...)). The wrapper enforces
        // the consistency invariant — this is the second line of defense
        // so that any future regression there throws a clear,
        // QueueTimeline-attributed error instead of an opaque ArrayList
        // IndexOutOfBoundsException in a Media3 stack frame.
        if (windowIndex < 0 || windowIndex >= items.size) {
            throw IndexOutOfBoundsException(
                "QueueTimeline.getWindow: windowIndex=$windowIndex out of range [0, ${items.size}). " +
                    "Caller passed an invalid index — check that " +
                    "ExternalPlaybackForwardingPlayer.updateQueueSnapshot kept the " +
                    "current-track / items-list invariant (items empty iff currentTrack null)."
            )
        }
        val item = items[windowIndex]
        val durationUs = durationsUs[windowIndex]
        return window.set(
            /* uid = */ uidAt(windowIndex),
            /* mediaItem = */ item,
            /* manifest = */ null,
            /* presentationStartTimeMs = */ C.TIME_UNSET,
            /* windowStartTimeMs = */ C.TIME_UNSET,
            /* elapsedRealtimeEpochOffsetMs = */ C.TIME_UNSET,
            /* isSeekable = */ false,
            /* isDynamic = */ false,
            /* liveConfiguration = */ null,
            /* defaultPositionUs = */ 0L,
            /* durationUs = */ durationUs,
            /* firstPeriodIndex = */ windowIndex,
            /* lastPeriodIndex = */ windowIndex,
            /* positionInFirstPeriodUs = */ 0L,
        )
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        if (periodIndex < 0 || periodIndex >= items.size) {
            throw IndexOutOfBoundsException(
                "QueueTimeline.getPeriod: periodIndex=$periodIndex out of range [0, ${items.size})."
            )
        }
        val uid = if (setIds) uidAt(periodIndex) else null
        return period.set(
            /* id = */ uid,
            /* uid = */ uid,
            /* windowIndex = */ periodIndex,
            /* durationUs = */ durationsUs[periodIndex],
            /* positionInWindowUs = */ 0L,
        )
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        val s = uid as? String ?: return C.INDEX_UNSET
        // Parse the trailing "#index" suffix; reject malformed inputs.
        val hashIdx = s.lastIndexOf('#')
        if (hashIdx < 0) return C.INDEX_UNSET
        val idx = s.substring(hashIdx + 1).toIntOrNull() ?: return C.INDEX_UNSET
        if (idx !in items.indices) return C.INDEX_UNSET
        // Defense: confirm the prefix matches the mediaId at that index.
        val expectedPrefix = items[idx].mediaId
        if (s.substring(0, hashIdx) != expectedPrefix) return C.INDEX_UNSET
        return idx
    }

    override fun getUidOfPeriod(periodIndex: Int): Any = uidAt(periodIndex)
}
