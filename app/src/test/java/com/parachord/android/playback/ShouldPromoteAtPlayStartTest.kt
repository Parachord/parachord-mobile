package com.parachord.android.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldPromoteAtPlayStart]: a play asked for while Parachord isn't on screen
 * takes the foreground before its lookup, inside the window Android gives a car,
 * Bluetooth or lock-screen request. Without a foreground service Android cuts
 * the app's network and nothing plays.
 */
class ShouldPromoteAtPlayStartTest {

    @Test
    fun `a requested play with no Parachord screen showing promotes`() {
        assertTrue(shouldPromoteAtPlayStart(userRequested = true, appVisible = false, externalPlayback = false))
    }

    @Test
    fun `the queue moving on by itself does not`() {
        assertFalse(shouldPromoteAtPlayStart(userRequested = false, appVisible = false, externalPlayback = false))
    }

    @Test
    fun `nothing changes with a Parachord screen showing`() {
        assertFalse(shouldPromoteAtPlayStart(userRequested = true, appVisible = true, externalPlayback = false))
    }

    @Test
    fun `external playback already holds the foreground`() {
        assertFalse(shouldPromoteAtPlayStart(userRequested = true, appVisible = false, externalPlayback = true))
    }
}
