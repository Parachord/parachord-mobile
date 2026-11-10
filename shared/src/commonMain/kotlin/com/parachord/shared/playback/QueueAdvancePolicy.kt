package com.parachord.shared.playback

/** What moved the queue: a track finishing, the Next button, or a track that couldn't play being skipped. */
enum class AdvanceTrigger { TRACK_ENDED, NEXT_PRESSED, SKIPPED_UNPLAYABLE }

/** What the queue does next. */
enum class AdvanceAction { PLAY_NEXT, REPLAY_CURRENT, WRAP_TO_START, STOP_AND_CLEAR }

/**
 * The one rule both platforms follow when the queue advances. The spinoff pool
 * is handled by the platform before it asks; this only sees the queue.
 * Only a track that ended replays under repeat one (Next still moves on); repeat
 * all wraps at the end for an ended track or Next, never for an unplayable skip
 * (a queue of unplayable tracks would loop forever).
 */
fun advanceAction(
    trigger: AdvanceTrigger,
    repeatMode: RepeatMode,
    hasNext: Boolean,
    hasCurrent: Boolean,
): AdvanceAction = when {
    trigger == AdvanceTrigger.TRACK_ENDED && repeatMode == RepeatMode.ONE && hasCurrent -> AdvanceAction.REPLAY_CURRENT
    hasNext -> AdvanceAction.PLAY_NEXT
    repeatMode == RepeatMode.ALL && trigger != AdvanceTrigger.SKIPPED_UNPLAYABLE -> AdvanceAction.WRAP_TO_START
    else -> AdvanceAction.STOP_AND_CLEAR
}

/** What Previous does. */
enum class PreviousAction { RESTART_CURRENT, PLAY_PREVIOUS }

/** Past this position Previous restarts the current track instead of going back. */
const val PREVIOUS_RESTARTS_AFTER_MS = 3_000L

fun previousAction(positionMs: Long, hasPrevious: Boolean): PreviousAction =
    if (positionMs > PREVIOUS_RESTARTS_AFTER_MS || !hasPrevious) PreviousAction.RESTART_CURRENT
    else PreviousAction.PLAY_PREVIOUS

/** The repeat button's cycle: off, all, one, off. */
fun nextRepeatMode(mode: RepeatMode): RepeatMode = when (mode) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}
