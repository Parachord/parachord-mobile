package com.parachord.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoMediaButtonsTest {

    @Test
    fun `nothing playing shows no buttons`() {
        assertEquals(
            emptyList<AutoMediaButton>(),
            autoMediaButtons(loved = null, spinoffMode = false, spinoffAvailable = true),
        )
    }

    @Test
    fun `spinoff sits to the right of the heart`() {
        assertEquals(
            listOf(AutoMediaButton.LOVE, AutoMediaButton.SPINOFF),
            autoMediaButtons(loved = false, spinoffMode = false, spinoffAvailable = true),
        )
        assertEquals(
            listOf(AutoMediaButton.UNLOVE, AutoMediaButton.SPINOFF),
            autoMediaButtons(loved = true, spinoffMode = false, spinoffAvailable = true),
        )
    }

    @Test
    fun `an unchecked track still offers spinoff — as the phone's button does`() {
        assertEquals(
            listOf(AutoMediaButton.LOVE, AutoMediaButton.SPINOFF),
            autoMediaButtons(loved = false, spinoffMode = false, spinoffAvailable = null),
        )
    }

    @Test
    fun `no similar tracks hides the spinoff button`() {
        assertEquals(
            listOf(AutoMediaButton.LOVE),
            autoMediaButtons(loved = false, spinoffMode = false, spinoffAvailable = false),
        )
    }

    @Test
    fun `a running spinoff offers to end it whatever the availability`() {
        assertEquals(
            listOf(AutoMediaButton.LOVE, AutoMediaButton.END_SPINOFF),
            autoMediaButtons(loved = false, spinoffMode = true, spinoffAvailable = false),
        )
    }
}
