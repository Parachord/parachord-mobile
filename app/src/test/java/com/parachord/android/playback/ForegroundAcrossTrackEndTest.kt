package com.parachord.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions behind keeping the foreground across a local track's end (#614). */
class ForegroundAcrossTrackEndTest {

    @Test fun `a local track's end keeps the foreground only in the background, local, with something next`() {
        assertTrue(shouldKeepForegroundAtLocalTrackEnd(appVisible = false, externalPlayback = false, somethingFollows = true))
        // A screen showing: a later start is allowed.
        assertFalse(shouldKeepForegroundAtLocalTrackEnd(appVisible = true, externalPlayback = false, somethingFollows = true))
        // External playback holds its own.
        assertFalse(shouldKeepForegroundAtLocalTrackEnd(appVisible = false, externalPlayback = true, somethingFollows = true))
        // The end of the queue: Media3 lets it go, as before.
        assertFalse(shouldKeepForegroundAtLocalTrackEnd(appVisible = false, externalPlayback = false, somethingFollows = false))
    }

    @Test fun `only the queue moving on in the background keeps a held foreground into a local play`() {
        assertTrue(localPlayKeepsHeldForeground(userRequested = false, appVisible = false))
        assertFalse(localPlayKeepsHeldForeground(userRequested = true, appVisible = false))
        assertFalse(localPlayKeepsHeldForeground(userRequested = false, appVisible = true))
        assertFalse(localPlayKeepsHeldForeground(userRequested = true, appVisible = true))
    }

    @Test fun `the silent keep-alive loop is an app resource, a track never is`() {
        assertTrue(isSilenceKeepAliveUri("android.resource://com.parachord.android.debug/2131820544"))
        assertFalse(isSilenceKeepAliveUri("content://media/external/audio/media/42"))
        assertFalse(isSilenceKeepAliveUri("https://example.com/stream.mp3"))
        assertFalse(isSilenceKeepAliveUri(null))
    }

    @Test fun `a held foreground idles only with nothing external and nothing starting`() {
        val step = FOREGROUND_HOLD_CHECK_MS
        assertEquals(step, nextForegroundHoldIdleMs(0, step, externalMode = false, transitionInProgress = false))
        assertEquals(0L, nextForegroundHoldIdleMs(25_000, step, externalMode = true, transitionInProgress = false))
        assertEquals(0L, nextForegroundHoldIdleMs(25_000, step, externalMode = false, transitionInProgress = true))
        // Six idle checks reach the limit.
        var idle = 0L
        repeat(6) { idle = nextForegroundHoldIdleMs(idle, step, externalMode = false, transitionInProgress = false) }
        assertTrue(idle >= FOREGROUND_HOLD_IDLE_LIMIT_MS)
    }

    @Test fun `session play and pause go to the controller only when both are external`() {
        assertTrue(sessionPlayPauseGoesToController(sessionExternalMode = true, controllerExternal = true))
        // External mode not yet left on the session's player: the loop case.
        assertFalse(sessionPlayPauseGoesToController(sessionExternalMode = true, controllerExternal = false))
        // External mode not yet on: the play starts the silent loop on ExoPlayer.
        assertFalse(sessionPlayPauseGoesToController(sessionExternalMode = false, controllerExternal = true))
        assertFalse(sessionPlayPauseGoesToController(sessionExternalMode = false, controllerExternal = false))
    }
}
