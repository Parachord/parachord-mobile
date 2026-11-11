package com.parachord.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
