package com.parachord.shared.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarUnplayableRunTest {

    @Test
    fun `in a car without the phone the third failure in a row stops`() {
        val run = CarUnplayableRun()
        assertFalse(run.failed(carWithoutPhone = true))
        assertFalse(run.failed(carWithoutPhone = true))
        assertTrue(run.failed(carWithoutPhone = true))
    }

    @Test
    fun `a track that starts resets the run`() {
        val run = CarUnplayableRun()
        run.failed(carWithoutPhone = true)
        run.failed(carWithoutPhone = true)
        run.started()
        assertFalse(run.failed(carWithoutPhone = true))
        assertFalse(run.failed(carWithoutPhone = true))
        assertTrue(run.failed(carWithoutPhone = true))
    }

    @Test
    fun `without a car — or with the phone app open — it never stops`() {
        val run = CarUnplayableRun()
        repeat(10) { assertFalse(run.failed(carWithoutPhone = false)) }
        assertEquals(10, run.count)
    }

    @Test
    fun `the run starts over after a stop`() {
        val run = CarUnplayableRun()
        repeat(2) { run.failed(carWithoutPhone = true) }
        assertTrue(run.failed(carWithoutPhone = true))
        assertEquals(0, run.count)
        assertFalse(run.failed(carWithoutPhone = true))
    }

    @Test
    fun `failures before the car connected don't count once reset`() {
        val run = CarUnplayableRun()
        run.failed(carWithoutPhone = false)
        run.failed(carWithoutPhone = false)
        run.reset() // the car connected
        assertFalse(run.failed(carWithoutPhone = true))
        assertFalse(run.failed(carWithoutPhone = true))
        assertTrue(run.failed(carWithoutPhone = true))
    }

    @Test
    fun `an explicit play starts a new run`() {
        val run = CarUnplayableRun()
        run.failed(carWithoutPhone = true)
        run.failed(carWithoutPhone = true)
        run.reset() // a tap
        assertFalse(run.failed(carWithoutPhone = true))
        assertEquals(1, run.count)
    }

    @Test
    fun `the limit is three`() {
        assertEquals(3, CAR_UNPLAYABLE_RUN_LIMIT)
    }
}
