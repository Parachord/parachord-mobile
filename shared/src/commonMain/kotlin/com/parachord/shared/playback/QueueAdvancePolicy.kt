package com.parachord.shared.playback

/** What moved the queue: a track finishing, the Next button, or a track that couldn't play being skipped. */
enum class AdvanceTrigger { TRACK_ENDED, NEXT_PRESSED, SKIPPED_UNPLAYABLE }

/** What the queue does next. */
enum class AdvanceAction { PLAY_NEXT, REPLAY_CURRENT, WRAP_TO_START, STOP_AND_CLEAR }

/**
 * The one rule both platforms follow when the queue advances. The spinoff pool
 * is handled by the platform before it asks; this only sees the queue.
 * Only a track that ended replays under repeat one (Next still moves on). Under
 * repeat all the end of the queue starts again from the top, for an ended track,
 * Next, or an unplayable last track. An unplayable skip wraps only when
 * [playedSinceWrap]: some track actually started playing since the queue was set
 * or last wrapped. Otherwise it stops, so a queue where nothing plays can't loop
 * forever. Each platform keeps that one flag: false on a new or restored queue
 * and after a wrap, true when a track starts playing.
 */
fun advanceAction(
    trigger: AdvanceTrigger,
    repeatMode: RepeatMode,
    hasNext: Boolean,
    hasCurrent: Boolean,
    playedSinceWrap: Boolean,
): AdvanceAction = when {
    trigger == AdvanceTrigger.TRACK_ENDED && repeatMode == RepeatMode.ONE && hasCurrent -> AdvanceAction.REPLAY_CURRENT
    hasNext -> AdvanceAction.PLAY_NEXT
    repeatMode == RepeatMode.ALL && (trigger != AdvanceTrigger.SKIPPED_UNPLAYABLE || playedSinceWrap) -> AdvanceAction.WRAP_TO_START
    else -> AdvanceAction.STOP_AND_CLEAR
}

/** What Previous does. */
enum class PreviousAction { RESTART_CURRENT, PLAY_PREVIOUS }

/** Past this position Previous restarts the current track instead of going back. */
const val PREVIOUS_RESTARTS_AFTER_MS = 3_000L

/**
 * What Previous does at [positionMs] into the current track: restart it when more
 * than [PREVIOUS_RESTARTS_AFTER_MS] in, or when nothing is before it
 * ([hasPrevious] false); otherwise go back to the previous track.
 */
fun previousAction(positionMs: Long, hasPrevious: Boolean): PreviousAction =
    if (positionMs > PREVIOUS_RESTARTS_AFTER_MS || !hasPrevious) PreviousAction.RESTART_CURRENT
    else PreviousAction.PLAY_PREVIOUS

/** The repeat button's cycle: off, all, one, off. */
fun nextRepeatMode(mode: RepeatMode): RepeatMode = when (mode) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}
