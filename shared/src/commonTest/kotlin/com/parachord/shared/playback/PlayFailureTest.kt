package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a play that ended with nothing playing stops on its track for Play
 * to load again (the shared [LoadOnPlay] slot, `FAILED`, on both apps).
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
        val held = PlayFailure.entries - PlayFailure.EMBED_REFUSED - PlayFailure.NO_SOURCE
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
    fun `a track with no source is skipped while the queue advances`() {
        assertFalse(failedPlayAwaitsLoad(PlayFailure.NO_SOURCE, advancing = true, spinoffMode = false))
        assertFalse(failedPlayAwaitsLoad(PlayFailure.NO_SOURCE, advancing = false, spinoffMode = true))
        assertTrue(failedPlayAwaitsLoad(PlayFailure.NO_SOURCE, advancing = false, spinoffMode = false))
    }

    @Test
    fun `the message names the connection`() {
        assertEquals("Couldn't start playing. Check your connection and try again.", PLAY_START_FAILED_MESSAGE)
    }

    @Test
    fun `no match from resolvers that answered online is no source`() {
        assertEquals(PlayFailure.NO_SOURCE, noSourceFailure(online = true, resolversCouldNotAnswer = false))
    }

    @Test
    fun `resolvers that couldn't answer are a failed start and not no source`() {
        assertEquals(PlayFailure.START_FAILED, noSourceFailure(online = true, resolversCouldNotAnswer = true))
    }

    @Test
    fun `offline is a failed start`() {
        assertEquals(PlayFailure.START_FAILED, noSourceFailure(online = false, resolversCouldNotAnswer = false))
        assertEquals(PlayFailure.START_FAILED, noSourceFailure(online = false, resolversCouldNotAnswer = true))
    }

    // ── unplayableTrackOutcome (#616) ────────────────────────────────

    private val carMessage = com.parachord.shared.car.CarMessages.OPEN_PARACHORD_ON_PHONE

    @Test
    fun `a tapped track with no source stops with the no-source message`() {
        assertEquals(
            UnplayableTrackOutcome.Stop(PLAY_NO_SOURCE_MESSAGE),
            unplayableTrackOutcome(PlayFailure.NO_SOURCE, advancing = false, spinoffMode = false) { error("not asked") },
        )
    }

    @Test
    fun `no source while the queue advances or a spinoff runs is skipped`() {
        assertEquals(UnplayableTrackOutcome.Skip, unplayableTrackOutcome(PlayFailure.NO_SOURCE, advancing = true, spinoffMode = false) { false })
        assertEquals(UnplayableTrackOutcome.Skip, unplayableTrackOutcome(PlayFailure.NO_SOURCE, advancing = false, spinoffMode = true) { false })
    }

    @Test
    fun `a failed start stops on the track advancing or not — without asking the car`() {
        for (failure in listOf(PlayFailure.START_FAILED, PlayFailure.START_TIMED_OUT, PlayFailure.PLAYER_ERROR, PlayFailure.ERROR)) {
            for (advancing in listOf(false, true)) {
                assertEquals(
                    UnplayableTrackOutcome.Stop(PLAY_START_FAILED_MESSAGE),
                    unplayableTrackOutcome(failure, advancing, spinoffMode = false) { error("the car is asked only for a skip") },
                    "$failure advancing=$advancing",
                )
            }
        }
    }

    @Test
    fun `a refused embed that's tapped stops with its own message`() {
        assertEquals(
            UnplayableTrackOutcome.Stop("SoundCloud only lets Parachord play a preview of X"),
            unplayableTrackOutcome(PlayFailure.EMBED_REFUSED, advancing = false, spinoffMode = false,
                refusedMessage = "SoundCloud only lets Parachord play a preview of X") { error("not asked") },
        )
        assertEquals(UnplayableTrackOutcome.Skip, unplayableTrackOutcome(PlayFailure.EMBED_REFUSED, advancing = true, spinoffMode = false) { false })
    }

    @Test
    fun `the car turns a skip into a stop with its message`() {
        var asked = 0
        val outcome = unplayableTrackOutcome(PlayFailure.NO_SOURCE, advancing = true, spinoffMode = false) { asked++; true }
        assertEquals(UnplayableTrackOutcome.Stop(carMessage, byCar = true), outcome)
        assertEquals(1, asked)
        assertEquals(
            UnplayableTrackOutcome.Stop(carMessage, byCar = true),
            unplayableTrackOutcome(PlayFailure.EMBED_REFUSED, advancing = true, spinoffMode = false, refusedMessage = "refused") { true },
        )
    }
}
