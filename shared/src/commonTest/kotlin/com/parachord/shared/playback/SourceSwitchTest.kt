package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSwitchTest {

    @Test
    fun `a manual switch that failed keeps the playback`() {
        for (failure in PlayFailure.entries) {
            assertTrue(failedSwitchKeepsPlayback(isManualSwitch = true, failure = failure, superseded = false), failure.name)
        }
    }

    @Test
    fun `a switch that started is nothing to give back`() {
        assertFalse(failedSwitchKeepsPlayback(isManualSwitch = true, failure = null, superseded = false))
    }

    @Test
    fun `a superseded switch is never a failure`() {
        assertFalse(failedSwitchKeepsPlayback(isManualSwitch = true, failure = PlayFailure.START_FAILED, superseded = true))
    }

    @Test
    fun `any other play follows the ordinary failure rules`() {
        assertFalse(failedSwitchKeepsPlayback(isManualSwitch = false, failure = PlayFailure.START_FAILED, superseded = false))
    }

    @Test
    fun `an engine left alone keeps playing`() {
        assertEquals(FailedSwitchRecovery.UNTOUCHED, failedSwitchRecovery(oldEngineReleased = false, oldEnginePaused = false, wasPlaying = true))
        assertEquals(FailedSwitchRecovery.UNTOUCHED, failedSwitchRecovery(oldEngineReleased = false, oldEnginePaused = false, wasPlaying = false))
    }

    @Test
    fun `an engine the switch paused resumes in place`() {
        assertEquals(FailedSwitchRecovery.RESUME_IN_PLACE, failedSwitchRecovery(oldEngineReleased = false, oldEnginePaused = true, wasPlaying = true))
    }

    @Test
    fun `an engine that let go replays a playing track and holds a paused one`() {
        assertEquals(FailedSwitchRecovery.REPLAY_AT_POSITION, failedSwitchRecovery(oldEngineReleased = true, oldEnginePaused = false, wasPlaying = true))
        assertEquals(FailedSwitchRecovery.HOLD_FOR_PLAY, failedSwitchRecovery(oldEngineReleased = true, oldEnginePaused = false, wasPlaying = false))
    }

    @Test
    fun `the message names the pick and the source it stays on`() {
        assertEquals(
            "Couldn’t play from Amazon Music. Still playing from Apple Music.",
            PlaybackMessages.couldNotSwitchTo("amazonmusic", "applemusic", playing = true),
        )
        assertEquals(
            "Couldn’t play from SoundCloud. Staying on Spotify.",
            PlaybackMessages.couldNotSwitchTo("soundcloud", "spotify", playing = false),
        )
    }

    @Test
    fun `with no other source known the message names only the pick`() {
        assertEquals("Couldn’t play from Amazon Music.", PlaybackMessages.couldNotSwitchTo("amazonmusic", null, playing = true))
        assertEquals("Couldn’t play from Amazon Music.", PlaybackMessages.couldNotSwitchTo("amazonmusic", "amazonmusic", playing = true))
    }
}
