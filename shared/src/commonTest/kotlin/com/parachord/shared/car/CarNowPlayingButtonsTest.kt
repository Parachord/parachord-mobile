package com.parachord.shared.car

import kotlin.test.Test
import kotlin.test.assertEquals

class CarNowPlayingButtonsTest {

    @Test
    fun `nothing playing shows no buttons`() {
        assertEquals(
            emptyList<CarNowPlayingButton>(),
            carNowPlayingButtons(loved = null, spinoffMode = false, spinoffAvailable = true),
        )
    }

    @Test
    fun `spinoff sits to the right of the heart`() {
        assertEquals(
            listOf(CarNowPlayingButton.LOVE, CarNowPlayingButton.SPINOFF),
            carNowPlayingButtons(loved = false, spinoffMode = false, spinoffAvailable = true),
        )
        assertEquals(
            listOf(CarNowPlayingButton.UNLOVE, CarNowPlayingButton.SPINOFF),
            carNowPlayingButtons(loved = true, spinoffMode = false, spinoffAvailable = true),
        )
    }

    @Test
    fun `an unchecked track still offers spinoff — as the phone's button does`() {
        assertEquals(
            listOf(CarNowPlayingButton.LOVE, CarNowPlayingButton.SPINOFF),
            carNowPlayingButtons(loved = false, spinoffMode = false, spinoffAvailable = null),
        )
    }

    @Test
    fun `no similar tracks hides the spinoff button`() {
        assertEquals(
            listOf(CarNowPlayingButton.LOVE),
            carNowPlayingButtons(loved = false, spinoffMode = false, spinoffAvailable = false),
        )
    }

    @Test
    fun `a running spinoff offers to end it whatever the availability`() {
        assertEquals(
            listOf(CarNowPlayingButton.LOVE, CarNowPlayingButton.END_SPINOFF),
            carNowPlayingButtons(loved = false, spinoffMode = true, spinoffAvailable = false),
        )
    }

    @Test
    fun `a spinoff still building its pool already shows as running — issue 551`() {
        assertEquals(
            listOf(CarNowPlayingButton.UNLOVE, CarNowPlayingButton.END_SPINOFF),
            carNowPlayingButtons(loved = true, spinoffMode = false, spinoffAvailable = true, spinoffLoading = true),
        )
    }
}
