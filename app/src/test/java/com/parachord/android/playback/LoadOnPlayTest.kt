package com.parachord.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the controller stops what was playing before a track that can't play. The slot's rules are tested in shared. */
class LoadOnPlayTest {

    // ── failedTrackStop ──────────────────────────────────────────────

    @Test
    fun `a followed track that fails keeps external mode and the foreground`() {
        assertEquals(
            FailedTrackStop.KEEP_EXTERNAL_FOREGROUND,
            failedTrackStop(isExternal = true, autoSkip = false, followed = true),
        )
        assertEquals(
            FailedTrackStop.CLEAR_LOCAL_PLAYER,
            failedTrackStop(isExternal = false, autoSkip = false, followed = true),
        )
    }

    @Test
    fun `an external track before a skipped one keeps the foreground`() {
        // Screen off: dropping it here left the next track unable to take it back.
        assertEquals(FailedTrackStop.KEEP_EXTERNAL_FOREGROUND, failedTrackStop(isExternal = true, autoSkip = true))
    }

    @Test
    fun `an external track before one playback stops on leaves external mode`() {
        assertEquals(FailedTrackStop.LEAVE_EXTERNAL, failedTrackStop(isExternal = true, autoSkip = false))
    }

    @Test
    fun `a local track before a failed one is stopped and cleared either way`() {
        assertEquals(FailedTrackStop.CLEAR_LOCAL_PLAYER, failedTrackStop(isExternal = false, autoSkip = true))
        assertEquals(FailedTrackStop.CLEAR_LOCAL_PLAYER, failedTrackStop(isExternal = false, autoSkip = false))
    }

    // ── The auto-skip gap, the queue's "played" flag, the session's seek ──

    @Test
    fun `play is ignored only while the current track is the one being skipped`() {
        assertTrue(autoSkipPendingFor("n", "n"))
        assertFalse(autoSkipPendingFor("n", "b"))
        assertFalse(autoSkipPendingFor(null, "n"))
        assertFalse(autoSkipPendingFor(null, null))
    }

    @Test
    fun `a play marks the queue played only if it started and no new queue came meanwhile`() {
        assertTrue(playMarksQueuePlayed(completed = true, failure = null, queueAtStart = 3, queueNow = 3))
        assertFalse(playMarksQueuePlayed(completed = true, failure = null, queueAtStart = 3, queueNow = 4))
        assertFalse(playMarksQueuePlayed(completed = true, failure = PlayFailure.NO_SOURCE, queueAtStart = 3, queueNow = 3))
        assertFalse(playMarksQueuePlayed(completed = false, failure = null, queueAtStart = 3, queueNow = 3))
    }

    @Test
    fun `the play-start foreground is kept only for a skip with no external playback`() {
        assertTrue(keepsForegroundForAutoSkip(skipPendingForTrack = true, externalPlayback = false))
        assertFalse(keepsForegroundForAutoSkip(skipPendingForTrack = false, externalPlayback = false))
        assertFalse(keepsForegroundForAutoSkip(skipPendingForTrack = true, externalPlayback = true))
    }

    @Test
    fun `the session's seek follows the controller's state, not the session's external flag`() {
        // #607: external mode left on after a failed track, controller local.
        assertFalse(sessionSeekGoesToController(controllerExternal = false, loadPending = false))
        assertTrue(sessionSeekGoesToController(controllerExternal = true, loadPending = false))
        assertTrue(sessionSeekGoesToController(controllerExternal = false, loadPending = true))
    }

    @Test
    fun `a track the car stopped on waits for Play even while the queue moves on`() {
        // Skipped while advancing, unless the car stopped there.
        assertFalse(failedPlayAwaitsLoadAfterCarStop(PlayFailure.NO_SOURCE, advancing = true, spinoffMode = false, stoppedByCar = false))
        assertTrue(failedPlayAwaitsLoadAfterCarStop(PlayFailure.NO_SOURCE, advancing = true, spinoffMode = false, stoppedByCar = true))
        assertTrue(failedPlayAwaitsLoadAfterCarStop(PlayFailure.EMBED_REFUSED, advancing = false, spinoffMode = true, stoppedByCar = true))
        assertFalse(failedPlayAwaitsLoadAfterCarStop(PlayFailure.EMBED_REFUSED, advancing = false, spinoffMode = true, stoppedByCar = false))
        // Everything else is the shared rule.
        assertTrue(failedPlayAwaitsLoadAfterCarStop(PlayFailure.NO_SOURCE, advancing = false, spinoffMode = false, stoppedByCar = false))
    }
}
