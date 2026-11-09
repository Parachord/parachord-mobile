package com.parachord.android.playback.handlers

import com.parachord.shared.api.SpDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isThisPhonesSpotifyDevice]: Spotify names this phone's Connect device after
 * the phone's device name. A Pixel 9a renamed "J9a" showed up as "J9a", which
 * the model/maker match never recognised as "This device" (#459 spike).
 */
class ThisPhonesSpotifyDeviceTest {

    private fun phone(name: String, type: String = "Smartphone") = SpDevice(id = "id", name = name, type = type)

    @Test
    fun `a renamed phone matches its device name`() {
        assertTrue(isThisPhonesSpotifyDevice(phone("J9a"), androidDeviceName = "J9a", model = "Pixel 9a", manufacturer = "Google"))
    }

    @Test
    fun `the device name match ignores case and spaces at the ends`() {
        assertTrue(isThisPhonesSpotifyDevice(phone(" j9a "), androidDeviceName = "J9a", model = "Pixel 9a", manufacturer = "Google"))
    }

    @Test
    fun `an unnamed phone still matches by model or maker`() {
        assertTrue(isThisPhonesSpotifyDevice(phone("Pixel 9a"), androidDeviceName = null, model = "Pixel 9a", manufacturer = "Google"))
        assertTrue(isThisPhonesSpotifyDevice(phone("Google Pixel"), androidDeviceName = "", model = "Pixel 9a", manufacturer = "Google"))
    }

    @Test
    fun `another phone is not this one`() {
        assertFalse(isThisPhonesSpotifyDevice(phone("Karen's iPhone"), androidDeviceName = "J9a", model = "Pixel 9a", manufacturer = "Google"))
    }

    @Test
    fun `a device name only matches whole`() {
        assertFalse(isThisPhonesSpotifyDevice(phone("J9a Kitchen"), androidDeviceName = "J9a", model = "Pixel 9a", manufacturer = "Google"))
    }

    @Test
    fun `a speaker or TV with the phone's name is not this phone`() {
        assertFalse(isThisPhonesSpotifyDevice(phone("J9a", type = "Speaker"), androidDeviceName = "J9a", model = "Pixel 9a", manufacturer = "Google"))
    }

    @Test
    fun `missing build values match nothing`() {
        assertFalse(isThisPhonesSpotifyDevice(phone("Pixel 9a"), androidDeviceName = null, model = null, manufacturer = null))
    }
}
