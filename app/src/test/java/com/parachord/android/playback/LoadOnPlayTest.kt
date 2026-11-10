package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for a track on screen that nothing holds, which Play starts
 * through the whole pipeline: a queue restored at launch (#567), a paused
 * track the idle timeout stopped (#550: Android Auto's play did nothing after a
 * long Apple Music pause), and a track no source could play (offline, every
 * source refused), where Play did nothing even once the network was back.
 */
class LoadOnPlayTest {

    private val myth = TrackEntity(id = "t-myth", title = "Myth", artist = "Beach House")
    private val stopped = LoadOnPlay(myth, positionMs = 47_000, skipReselect = true, why = LoadOnPlay.Why.IDLE_STOPPED)

    // ── loadOnPlayTarget ─────────────────────────────────────────────

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

    // ── failedPlayAwaitsLoad ─────────────────────────────────────────

    @Test
    fun `a refused track that was tapped waits for Play`() {
        assertTrue(failedPlayAwaitsLoad(PlayFailure.EMBED_REFUSED, advancing = false, spinoffMode = false))
    }

    @Test
    fun `a refused track the queue moved onto is skipped, not held`() {
        assertFalse(failedPlayAwaitsLoad(PlayFailure.EMBED_REFUSED, advancing = true, spinoffMode = false))
    }

    @Test
    fun `a refused track in a spinoff is skipped, not held`() {
        assertFalse(failedPlayAwaitsLoad(PlayFailure.EMBED_REFUSED, advancing = false, spinoffMode = true))
    }

    @Test
    fun `failures the queue doesn't move on from wait for Play, advancing or not`() {
        val held = listOf(
            PlayFailure.NO_SOURCE_WITHOUT_SPOTIFY,
            PlayFailure.START_TIMED_OUT,
            PlayFailure.ERROR,
            PlayFailure.PLAYER_ERROR,
        )
        for (failure in held) {
            for (advancing in listOf(false, true)) {
                for (spinoff in listOf(false, true)) {
                    assertTrue(
                        "$failure advancing=$advancing spinoff=$spinoff",
                        failedPlayAwaitsLoad(failure, advancing, spinoff),
                    )
                }
            }
        }
    }

    // ── failedLoad ───────────────────────────────────────────────────

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
}
