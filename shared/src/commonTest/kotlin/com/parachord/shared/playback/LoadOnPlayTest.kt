package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules for a track on screen that nothing holds, which Play starts
 * through the whole pipeline: a queue restored at launch (#567), a paused
 * track the idle timeout stopped (#550), and a track no source could play
 * (offline, every source refused), where Play did nothing even once the
 * network was back.
 */
class LoadOnPlayTest {

    private val myth = Track(id = "t-myth", title = "Myth", artist = "Beach House")
    private val stopped = LoadOnPlay(myth, positionMs = 47_000, skipReselect = true, why = LoadOnPlay.Why.IDLE_STOPPED)

    // loadOnPlayTarget

    @Test
    fun `the waiting track is loaded when it is still current`() {
        assertEquals(stopped, loadOnPlayTarget(stopped, currentTrackId = "t-myth"))
    }

    @Test
    fun `nothing is loaded after another track has been played`() {
        assertNull(loadOnPlayTarget(stopped, currentTrackId = "t-other"))
    }

    @Test
    fun `nothing is loaded when no track is current`() {
        assertNull(loadOnPlayTarget(stopped, currentTrackId = null))
    }

    @Test
    fun `nothing is loaded when no track is waiting`() {
        assertNull(loadOnPlayTarget(null, currentTrackId = "t-myth"))
    }

    // idleTimeoutLoad

    @Test
    fun `a failed slot for the current track survives the idle timeout`() {
        val failed = failedLoad(myth, skipReselect = false, failedAtMs = 0, durationMs = 0)
        assertEquals(failed, idleTimeoutLoad(failed, myth, positionMs = 42_000))
    }

    @Test
    fun `a stale slot is replaced by an idle stopped one`() {
        val other = Track(id = "t-other", title = "Other", artist = "Someone")
        val stale = failedLoad(other, skipReselect = false, failedAtMs = 0, durationMs = 0)
        val idle = idleTimeoutLoad(stale, myth, positionMs = 42_000)
        assertEquals(LoadOnPlay(myth, 42_000, skipReselect = true, why = LoadOnPlay.Why.IDLE_STOPPED), idle)
        assertEquals(idle, idleTimeoutLoad(null, myth, positionMs = 42_000))
    }

    @Test
    fun `no current track leaves nothing for Play`() {
        val failed = failedLoad(myth, skipReselect = false, failedAtMs = 0, durationMs = 0)
        assertNull(idleTimeoutLoad(failed, null, positionMs = 0))
    }

    // failedLoad

    @Test
    fun `a play that never started is retried where it was meant to start`() {
        val load = failedLoad(myth, skipReselect = false, failedAtMs = 0, durationMs = 0, aimedAtMs = 95_000)
        assertEquals(LoadOnPlay(myth, 95_000, skipReselect = false, why = LoadOnPlay.Why.FAILED), load)
    }

    @Test
    fun `a stream that failed mid-song is retried where it stopped`() {
        val load = failedLoad(myth, skipReselect = false, failedAtMs = 132_000, durationMs = 240_000)
        assertEquals(132_000, load.positionMs)
    }

    @Test
    fun `a stream that failed in its first seconds starts over`() {
        assertEquals(0, failedLoad(myth, skipReselect = false, failedAtMs = 3_000, durationMs = 240_000).positionMs)
    }

    @Test
    fun `a stream that failed in its last seconds starts over`() {
        assertEquals(0, failedLoad(myth, skipReselect = false, failedAtMs = 236_000, durationMs = 240_000).positionMs)
    }

    @Test
    fun `a manual source pick is kept for the retry`() {
        assertTrue(failedLoad(myth, skipReselect = true, failedAtMs = 0, durationMs = 0).skipReselect)
    }

    @Test
    fun `a failed load keeps the picked resolver`() {
        val load = failedLoad(myth, skipReselect = true, failedAtMs = 0, durationMs = 0, pickedResolver = "spotify")
        assertEquals("spotify", load.pickedResolver)
        assertNull(failedLoad(myth, skipReselect = true, failedAtMs = 0, durationMs = 0).pickedResolver)
    }

    // positionForPrevious and restartedFromTop

    @Test
    fun `the previous position is the slot's while its track is current`() {
        val slot = LoadOnPlay(myth, 120_000, skipReselect = false, why = LoadOnPlay.Why.RESTORED)
        assertEquals(120_000, positionForPrevious(0, slot, currentTrackId = "t-myth"))
    }

    @Test
    fun `the previous position is the player's for another track`() {
        val slot = LoadOnPlay(myth, 120_000, skipReselect = false, why = LoadOnPlay.Why.RESTORED)
        assertEquals(4_000, positionForPrevious(4_000, slot, currentTrackId = "t-other"))
        assertEquals(4_000, positionForPrevious(4_000, null, currentTrackId = "t-myth"))
    }

    @Test
    fun `restarting a slot keeps everything but the position`() {
        val slot = LoadOnPlay(myth, 120_000, skipReselect = true, why = LoadOnPlay.Why.FAILED, pickedResolver = "spotify")
        assertEquals(slot.copy(positionMs = 0), slot.restartedFromTop())
    }

    /** #617: Play after the queue ended (the track was cleared) does nothing. */
    @Test
    fun playWithNoTrackOnScreenStartsNothing() {
        assertTrue(playStartsNothing(hasCurrentTrack = false, isPlaying = false, isStarting = false))
        assertFalse(playStartsNothing(hasCurrentTrack = true, isPlaying = false, isStarting = false))
        // A play still starting, or one playing, is left to the toggle (a pause).
        assertFalse(playStartsNothing(hasCurrentTrack = false, isPlaying = false, isStarting = true))
        assertFalse(playStartsNothing(hasCurrentTrack = false, isPlaying = true, isStarting = false))
    }
}
