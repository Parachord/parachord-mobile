package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueRestorePolicyTest {

    @Test fun `a fresh start restores the saved queue`() {
        assertTrue(QueueRestorePolicy.shouldApply(hasCurrentTrack = false, playRequested = false))
    }

    @Test fun `a play the car started first is kept`() {
        // Android Auto's tap reached the controller while the restore was still reading storage.
        assertFalse(QueueRestorePolicy.shouldApply(hasCurrentTrack = true, playRequested = true))
    }

    @Test fun `a play still resolving is kept`() {
        // Asked for, but the track hasn't been routed yet, so nothing is current.
        assertFalse(QueueRestorePolicy.shouldApply(hasCurrentTrack = false, playRequested = true))
    }

    @Test fun `a track already showing is kept`() {
        // The phone reconnects to a session that is still playing.
        assertFalse(QueueRestorePolicy.shouldApply(hasCurrentTrack = true, playRequested = false))
    }
}
