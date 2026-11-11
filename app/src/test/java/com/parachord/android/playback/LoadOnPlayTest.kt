package com.parachord.android.playback

import org.junit.Assert.assertEquals
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
}
