package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyResumeWatchTest {
    private val ours = setOf("abc")

    @Test
    fun `our track playing counts`() {
        assertTrue(SpotifyResumeWatch.playingOurTrack(true, "abc", null, ours))
    }

    @Test
    fun `a relinked copy of our track counts`() {
        assertTrue(SpotifyResumeWatch.playingOurTrack(true, "other-market", "abc", ours))
    }

    @Test
    fun `another song playing is not a resume of ours`() {
        assertFalse(SpotifyResumeWatch.playingOurTrack(true, "xyz", null, ours))
        assertFalse(SpotifyResumeWatch.playingOurTrack(true, null, null, ours))
    }

    @Test
    fun `our track paused is not playing`() {
        assertFalse(SpotifyResumeWatch.playingOurTrack(false, "abc", null, ours))
    }

    @Test
    fun `no known track id matches nothing`() {
        assertFalse(SpotifyResumeWatch.playingOurTrack(true, "abc", null, emptySet()))
    }

    @Test
    fun `nothing counts within the grace after our own pause`() {
        assertFalse(SpotifyResumeWatch.resumedElsewhere(4_999, true, "abc", null, ours))
        assertTrue(SpotifyResumeWatch.resumedElsewhere(5_000, true, "abc", null, ours))
    }

    @Test
    fun `only a user pause with nothing starting is watched`() {
        assertTrue(SpotifyResumeWatch.watches(userPaused = true, trackHeldBySlot = false, playStarting = false))
        assertFalse(SpotifyResumeWatch.watches(userPaused = false, trackHeldBySlot = false, playStarting = false))
        assertFalse(SpotifyResumeWatch.watches(userPaused = true, trackHeldBySlot = true, playStarting = false))
        assertFalse(SpotifyResumeWatch.watches(userPaused = true, trackHeldBySlot = false, playStarting = true))
    }
}
