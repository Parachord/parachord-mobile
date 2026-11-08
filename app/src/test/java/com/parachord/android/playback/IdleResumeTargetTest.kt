package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [idleResumeTarget] decides whether play, after the idle timeout stopped a
 * paused external track, starts that track again where it paused (#550: Android
 * Auto's play silently did nothing after a long Apple Music pause).
 */
class IdleResumeTargetTest {

    private val myth = TrackEntity(id = "t-myth", title = "Myth", artist = "Beach House")
    private val stopped = IdleStoppedPlayback(myth, positionMs = 47_000)

    @Test
    fun `the stopped track is resumed when it is still current`() {
        assertEquals(stopped, idleResumeTarget(stopped, currentTrackId = "t-myth"))
    }

    @Test
    fun `nothing is resumed after another track has been played`() {
        assertNull(idleResumeTarget(stopped, currentTrackId = "t-other"))
    }

    @Test
    fun `nothing is resumed when no track is current`() {
        assertNull(idleResumeTarget(stopped, currentTrackId = null))
    }

    @Test
    fun `nothing is resumed when the timeout stopped nothing`() {
        assertNull(idleResumeTarget(null, currentTrackId = "t-myth"))
    }
}
