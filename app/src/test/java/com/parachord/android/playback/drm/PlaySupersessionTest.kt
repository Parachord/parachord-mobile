package com.parachord.android.playback.drm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Desktop's play token on Android: a newer play cuts a plugin DRM start at
 * whatever it waits on (the Pixel's Next waited 17.5 s behind two Amazon
 * attempts), and the cut is never a failure.
 */
class PlaySupersessionTest {

    @Test
    fun `a play with nothing newer runs to its answer`() = runTest {
        val s = PlaySupersession()
        val mine = s.next()
        assertEquals("manifest", s.untilSuperseded(mine) { "manifest" })
        assertFalse(s.isSuperseded(mine))
    }

    @Test
    fun `a newer play cuts the wait at once`() = runTest {
        val s = PlaySupersession()
        val mine = s.next()
        val neverAnswers = CompletableDeferred<String>()
        val waiting = async {
            runCatching { s.untilSuperseded(mine) { neverAnswers.await() } }
        }
        yield()
        s.next() // Next pressed
        val result = waiting.await()
        assertTrue(result.exceptionOrNull() is PlaySupersededException)
    }

    @Test
    fun `a play already superseded never starts its wait`() = runTest {
        val s = PlaySupersession()
        val mine = s.next()
        s.next()
        var ran = false
        try {
            s.untilSuperseded(mine) { ran = true }
            fail("expected superseded")
        } catch (_: PlaySupersededException) {
        }
        assertFalse(ran)
        try {
            s.check(mine)
            fail("expected superseded")
        } catch (_: PlaySupersededException) {
        }
    }

    @Test
    fun `two source switches and a next leave only the last play`() = runTest {
        val s = PlaySupersession()
        val first = s.next()
        val second = s.next()
        val next = s.next()
        assertTrue(s.isSuperseded(first))
        assertTrue(s.isSuperseded(second))
        assertFalse(s.isSuperseded(next))
    }
}
