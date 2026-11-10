package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a play that ended with nothing playing stops on its track for Play
 * to load again (Android's `LoadOnPlay` `FAILED`, iOS's `needsLoadOnPlay`).
 * Offline, an Apple Music start fails (`START_FAILED`): the queue used to skip
 * through every track on iOS, each failing the same way.
 */
class PlayFailureTest {

    @Test
    fun `a refused track that was tapped waits for Play`() {
        assertTrue(failedPlayAwaitsLoad(PlayFailure.EMBED_REFUSED, advancing = false, spinoffMode = false))
    }

    @Test
    fun `a refused track the queue moved onto is skipped and not held`() {
        assertFalse(failedPlayAwaitsLoad(PlayFailure.EMBED_REFUSED, advancing = true, spinoffMode = false))
    }

    @Test
    fun `a refused track in a spinoff is skipped and not held`() {
        assertFalse(failedPlayAwaitsLoad(PlayFailure.EMBED_REFUSED, advancing = false, spinoffMode = true))
    }

    @Test
    fun `a start that failed offline stops the queue on the track even when advancing`() {
        assertTrue(failedPlayAwaitsLoad(PlayFailure.START_FAILED, advancing = true, spinoffMode = false))
        assertTrue(failedPlayAwaitsLoad(PlayFailure.START_FAILED, advancing = false, spinoffMode = true))
    }

    @Test
    fun `failures the queue doesn't move on from wait for Play — advancing or not`() {
        val held = PlayFailure.entries - PlayFailure.EMBED_REFUSED
        for (failure in held) {
            for (advancing in listOf(false, true)) {
                for (spinoff in listOf(false, true)) {
                    assertTrue(
                        failedPlayAwaitsLoad(failure, advancing, spinoff),
                        "$failure advancing=$advancing spinoff=$spinoff",
                    )
                }
            }
        }
    }

    @Test
    fun `the message names the connection`() {
        assertEquals("Couldn't start playing. Check your connection and try again.", PLAY_START_FAILED_MESSAGE)
    }
}
