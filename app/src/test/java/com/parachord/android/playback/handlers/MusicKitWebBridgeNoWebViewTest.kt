package com.parachord.android.playback.handlers

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * With no WebView (never created, or torn down after MusicKit didn't load
 * offline), there is no player to control. Each control call used to wait the
 * full 20 s for a MusicKit that couldn't load, so Play after an offline launch
 * spent 40 s (resume, then the router's stop) before its replay even started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicKitWebBridgeNoWebViewTest {

    private fun bridge() = MusicKitWebBridge(
        context = mockk(relaxed = true),
        settingsStore = mockk(relaxed = true),
        json = Json,
        catalogLimiter = mockk(relaxed = true),
    )

    @Test fun `control calls return at once`() = runTest {
        val bridge = bridge()
        bridge.resume()
        bridge.pause()
        bridge.stop()
        bridge.seekTo(8_000)
        assertEquals(0L, currentTime)
    }

    @Test fun `play fails at once`() = runTest {
        assertFalse(bridge().play("1109715066"))
        assertEquals(0L, currentTime)
    }
}
