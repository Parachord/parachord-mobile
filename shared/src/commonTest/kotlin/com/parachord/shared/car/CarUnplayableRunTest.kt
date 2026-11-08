package com.parachord.shared.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarUnplayableRunTest {

    @Test
    fun `in the car the third failure in a row stops`() {
        val run = CarUnplayableRun()
        assertFalse(run.failed(carConnected = true))
        assertFalse(run.failed(carConnected = true))
        assertTrue(run.failed(carConnected = true))
    }

    @Test
    fun `a track that starts resets the run`() {
        val run = CarUnplayableRun()
        run.failed(carConnected = true)
        run.failed(carConnected = true)
        run.started()
        assertFalse(run.failed(carConnected = true))
        assertFalse(run.failed(carConnected = true))
        assertTrue(run.failed(carConnected = true))
    }

    @Test
    fun `without a car it never stops`() {
        val run = CarUnplayableRun()
        repeat(10) { assertFalse(run.failed(carConnected = false)) }
        assertEquals(10, run.count)
    }

    @Test
    fun `the run starts over after a stop`() {
        val run = CarUnplayableRun()
        repeat(2) { run.failed(carConnected = true) }
        assertTrue(run.failed(carConnected = true))
        assertEquals(0, run.count)
        assertFalse(run.failed(carConnected = true))
    }

    @Test
    fun `failures before the car connected count towards the run`() {
        val run = CarUnplayableRun()
        run.failed(carConnected = false)
        run.failed(carConnected = false)
        assertTrue(run.failed(carConnected = true))
    }

    @Test
    fun `the limit is three`() {
        assertEquals(3, CAR_UNPLAYABLE_RUN_LIMIT)
    }
}
