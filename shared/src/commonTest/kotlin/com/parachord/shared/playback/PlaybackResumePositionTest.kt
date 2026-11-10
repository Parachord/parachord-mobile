package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PlaybackResumePosition]: a queue restored after the app was ended while
 * paused resumes its track where it was, not at 0:00 (#567).
 */
class PlaybackResumePositionTest {

    private val saved = SavedPlaybackPosition(trackId = "t1", positionMs = 95_000, durationMs = 240_000)

    @Test fun `a pause saves the position`() {
        assertTrue(PlaybackResumePosition.shouldSave(wasPlaying = true, isPlaying = false, msSinceLastSave = 1_000))
    }

    @Test fun `playing saves every 15 seconds`() {
        assertFalse(PlaybackResumePosition.shouldSave(wasPlaying = true, isPlaying = true, msSinceLastSave = 14_999))
        assertTrue(PlaybackResumePosition.shouldSave(wasPlaying = true, isPlaying = true, msSinceLastSave = 15_000))
    }

    @Test fun `staying paused saves nothing`() {
        assertFalse(PlaybackResumePosition.shouldSave(wasPlaying = false, isPlaying = false, msSinceLastSave = 600_000))
    }

    @Test fun `the saved track resumes where it was`() {
        assertEquals(95_000L, PlaybackResumePosition.resumeAtMs(saved, "t1"))
    }

    @Test fun `another track starts from the top`() {
        assertEquals(0L, PlaybackResumePosition.resumeAtMs(saved, "t2"))
        assertEquals(0L, PlaybackResumePosition.resumeAtMs(saved, null))
        assertEquals(0L, PlaybackResumePosition.resumeAtMs(null, "t1"))
    }

    @Test fun `a track barely started starts over`() {
        assertEquals(0L, PlaybackResumePosition.resumeAtMs(saved.copy(positionMs = 4_999), "t1"))
        assertEquals(5_000L, PlaybackResumePosition.resumeAtMs(saved.copy(positionMs = 5_000), "t1"))
    }

    @Test fun `a track about to end starts over`() {
        assertEquals(0L, PlaybackResumePosition.resumeAtMs(saved.copy(positionMs = 231_000), "t1"))
        assertEquals(230_000L, PlaybackResumePosition.resumeAtMs(saved.copy(positionMs = 230_000), "t1"))
    }

    @Test fun `an unknown length still resumes`() {
        assertEquals(95_000L, PlaybackResumePosition.resumeAtMs(saved.copy(durationMs = 0), "t1"))
    }

    @Test fun `the position survives a round trip`() {
        assertEquals(saved, PlaybackResumePosition.decode(PlaybackResumePosition.encode(saved)))
    }

    @Test fun `a malformed or missing value is no position`() {
        assertNull(PlaybackResumePosition.decode("not json"))
        assertNull(PlaybackResumePosition.decode(null))
    }
}
