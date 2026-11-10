package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class QueueAdvancePolicyTest {

    @Test
    fun `a track that ended plays the next one`() {
        assertEquals(AdvanceAction.PLAY_NEXT, advanceAction(AdvanceTrigger.TRACK_ENDED, RepeatMode.OFF, hasNext = true, hasCurrent = true))
    }

    @Test
    fun `only a track that ended replays under repeat one`() {
        assertEquals(AdvanceAction.REPLAY_CURRENT, advanceAction(AdvanceTrigger.TRACK_ENDED, RepeatMode.ONE, true, true))
        assertEquals(AdvanceAction.PLAY_NEXT, advanceAction(AdvanceTrigger.NEXT_PRESSED, RepeatMode.ONE, true, true))
        assertEquals(AdvanceAction.PLAY_NEXT, advanceAction(AdvanceTrigger.SKIPPED_UNPLAYABLE, RepeatMode.ONE, true, true))
    }

    @Test
    fun `the end of the queue stops and clears with repeat off`() {
        assertEquals(AdvanceAction.STOP_AND_CLEAR, advanceAction(AdvanceTrigger.TRACK_ENDED, RepeatMode.OFF, false, true))
        assertEquals(AdvanceAction.STOP_AND_CLEAR, advanceAction(AdvanceTrigger.NEXT_PRESSED, RepeatMode.OFF, false, true))
    }

    @Test
    fun `repeat all wraps at the end — ended or Next`() {
        assertEquals(AdvanceAction.WRAP_TO_START, advanceAction(AdvanceTrigger.TRACK_ENDED, RepeatMode.ALL, false, true))
        assertEquals(AdvanceAction.WRAP_TO_START, advanceAction(AdvanceTrigger.NEXT_PRESSED, RepeatMode.ALL, false, true))
    }

    @Test
    fun `an unplayable skip at the end never wraps`() {
        assertEquals(AdvanceAction.STOP_AND_CLEAR, advanceAction(AdvanceTrigger.SKIPPED_UNPLAYABLE, RepeatMode.ALL, false, true))
    }

    @Test
    fun `repeat one with nothing current falls through`() {
        assertEquals(AdvanceAction.PLAY_NEXT, advanceAction(AdvanceTrigger.TRACK_ENDED, RepeatMode.ONE, true, false))
    }

    @Test
    fun `previous restarts after 3 seconds`() {
        assertEquals(PreviousAction.RESTART_CURRENT, previousAction(3_001, true))
        assertEquals(PreviousAction.PLAY_PREVIOUS, previousAction(3_000, true))
    }

    @Test
    fun `previous with nothing before restarts`() {
        assertEquals(PreviousAction.RESTART_CURRENT, previousAction(0, false))
    }

    @Test
    fun `repeat cycles off all one`() {
        assertEquals(RepeatMode.ALL, nextRepeatMode(RepeatMode.OFF))
        assertEquals(RepeatMode.ONE, nextRepeatMode(RepeatMode.ALL))
        assertEquals(RepeatMode.OFF, nextRepeatMode(RepeatMode.ONE))
    }
}
