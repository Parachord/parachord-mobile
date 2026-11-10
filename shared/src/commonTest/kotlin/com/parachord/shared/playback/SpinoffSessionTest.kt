package com.parachord.shared.playback

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpinoffSessionTest {

    @Test
    fun `refill is needed below three tracks`() {
        assertTrue(spinoffNeedsRefill(2))
        assertFalse(spinoffNeedsRefill(3))
        assertTrue(spinoffNeedsRefill(0))
    }

    @Test
    fun `the next three pool tracks are pre-resolved`() {
        assertEquals(listOf(1, 2, 3), spinoffTracksToPreresolve(listOf(1, 2, 3, 4, 5)))
        assertEquals(listOf(1, 2), spinoffTracksToPreresolve(listOf(1, 2)))
    }

    @Test
    fun `shuffle is refused during a spinoff or Listen Along`() {
        assertFalse(shuffleAllowed(true, null))
        assertFalse(shuffleAllowed(false, "listen-along"))
        assertTrue(shuffleAllowed(false, "playlist"))
        assertTrue(shuffleAllowed(false, null))
    }

    @Test
    fun `spinoff tracks share one id scheme`() {
        val t = spinoffTrack("Myth", "Beach House")
        assertEquals("spinoff:myth|beach house", t.id)
        assertEquals("Myth", t.title)
        assertEquals("Beach House", t.artist)
        assertEquals(0L, t.duration)
        assertNull(t.resolver)
        assertNull(t.spotifyUri)
        assertNull(t.spotifyId)
        assertNull(t.appleMusicId)
        assertNull(t.soundcloudId)
        assertNull(t.sourceUrl)
        assertNull(t.artworkUrl)
    }

    @Test
    fun `the first playable pool track is found`() = runTest {
        val calls = mutableListOf<Int>()
        val idx = firstPlayableIndex(listOf(0, 1, 2, 3, 4)) { calls += it; it == 2 }
        assertEquals(2, idx)
        assertEquals(listOf(0, 1, 2), calls)
    }

    @Test
    fun `a pool with nothing playable in the first five is refused`() = runTest {
        var calls = 0
        val idx = firstPlayableIndex((0 until 7).toList()) { calls++; false }
        assertNull(idx)
        assertEquals(5, calls)
    }

    @Test
    fun `a probe that throws counts as not playable`() = runTest {
        val idx = firstPlayableIndex(listOf(0, 1)) { if (it == 0) error("boom") else true }
        assertEquals(1, idx)
    }

    @Test
    fun `messages match desktop`() {
        assertEquals("No similar tracks found for \"Myth\"", PlaybackMessages.noSimilarTracks("Myth"))
        assertEquals("Spinning off of Myth - Beach House", PlaybackMessages.spinningOff("Myth", "Beach House"))
        assertEquals("Spinoff from Myth by Beach House", PlaybackMessages.spinoffFrom("Myth", "Beach House"))
        assertEquals("Failed to fetch similar tracks", PlaybackMessages.SPINOFF_FETCH_FAILED)
        assertEquals(
            "Couldn't find a playable track similar to \"Myth\"",
            PlaybackMessages.noPlayableSimilarTracks("Myth"),
        )
    }
}
