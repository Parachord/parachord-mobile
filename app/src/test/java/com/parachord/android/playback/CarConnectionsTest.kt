package com.parachord.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Android Auto's connections to the media session, as the controller sees them. */
class CarConnectionsTest {

    @Test
    fun `only Android Auto counts and the last disconnect leaves the car`() {
        val cars = CarConnections()
        val other = Any()
        cars.connected("com.android.systemui", other)
        assertNull(cars.current)

        val browser = Any()
        val controller = Any()
        cars.connected(CarConnections.ANDROID_AUTO_PACKAGE, browser)
        cars.connected(CarConnections.ANDROID_AUTO_PACKAGE, controller)
        assertEquals(1, cars.current)
        cars.disconnected(browser)
        assertEquals(1, cars.current)
        cars.disconnected(controller)
        assertNull(cars.current)

        cars.connected(CarConnections.ANDROID_AUTO_PACKAGE, browser)
        assertEquals(2, cars.current)
    }

    @Test
    fun `a destroyed session leaves the car and the next connection is a new one`() {
        val cars = CarConnections()
        cars.connected(CarConnections.ANDROID_AUTO_PACKAGE, Any())
        cars.clear()
        assertNull(cars.current)
        cars.connected(CarConnections.ANDROID_AUTO_PACKAGE, Any())
        assertEquals(2, cars.current)
    }

    // ── stillInCar / confirmed ───────────────────────────────────────

    @Test
    fun `android auto's own state can only veto a tracked car`() {
        assertTrue(stillInCar(tracked = true, projectionState = null))
        assertFalse(stillInCar(tracked = true, projectionState = 0))
        assertTrue(stillInCar(tracked = true, projectionState = 1))
        assertTrue(stillInCar(tracked = true, projectionState = 2))
        for (state in listOf(null, 0, 1, 2)) assertFalse(stillInCar(tracked = false, projectionState = state))
    }

    @Test
    fun `confirmed keeps the tracked car unless the state says no car`() = runBlocking {
        val cars = CarConnections()
        cars.connected(CarConnections.ANDROID_AUTO_PACKAGE, Any())
        assertEquals(1, cars.confirmed { null })
        assertEquals(1, cars.confirmed { 2 })
        assertEquals(1, cars.confirmed { 1 })
        assertNull(cars.confirmed { 0 })
        // Cleared: the next Android Auto connect is a new connection.
        assertNull(cars.current)
        cars.connected(CarConnections.ANDROID_AUTO_PACKAGE, Any())
        assertEquals(2, cars.confirmed { null })
    }

    @Test
    fun `confirmed does not ask when no car is tracked`() = runBlocking {
        val cars = CarConnections()
        var asked = 0
        assertNull(cars.confirmed { asked++; 2 })
        assertEquals(0, asked)
    }
}

