package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlLinkTest {

    @Test
    fun `pause toggles only while playing`() {
        assertTrue(controlLinkShouldToggle("pause", isPlaying = true))
        assertFalse(controlLinkShouldToggle("pause", isPlaying = false))
    }

    @Test
    fun `resume toggles only while paused`() {
        assertTrue(controlLinkShouldToggle("resume", isPlaying = false))
        assertFalse(controlLinkShouldToggle("resume", isPlaying = true))
    }

    @Test
    fun `play toggles only while paused`() {
        assertTrue(controlLinkShouldToggle("play", isPlaying = false))
        assertFalse(controlLinkShouldToggle("play", isPlaying = true))
    }

    @Test
    fun `any other action never toggles`() {
        assertFalse(controlLinkShouldToggle("skip", isPlaying = true))
        assertFalse(controlLinkShouldToggle("skip", isPlaying = false))
        assertFalse(controlLinkShouldToggle("", isPlaying = true))
    }
}
