package com.parachord.android.playback.handlers

import android.app.Application
import android.os.Build
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.api.SpDevice
import com.parachord.shared.api.SpotifyClient
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Tests for [SpotifyPlaybackHandler.chooseStableId]: when the user picks a
 * device from the picker dialog, we must persist [LOCAL_DEVICE_ID] for the
 * local phone (synthetic placeholder OR resolved real smartphone matching
 * Build.MODEL/MANUFACTURER) so the preference survives Spotify process
 * restarts. Remote devices (TVs, other phones, computers) keep their real IDs.
 *
 * Companion to the prefer-local-over-active-remote and sticky-default fixes —
 * this one addresses the explicit picker path the user keeps hitting in the
 * "picker keeps resetting" loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SpotifyPlaybackHandlerChooseStableIdTest {

    private lateinit var handler: SpotifyPlaybackHandler

    @Before
    fun setUp() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Pixel 9a")
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")

        handler = SpotifyPlaybackHandler(
            spotifyClient = mockk<SpotifyClient>(relaxed = true),
            settingsStore = mockk<SettingsStore>(relaxed = true),
            oAuthManager = mockk<OAuthManager>(relaxed = true),
            context = RuntimeEnvironment.getApplication(),
        )
    }

    @Test
    fun `synthetic placeholder maps to LOCAL_DEVICE_ID`() {
        val placeholder = SpDevice(
            id = SpotifyPlaybackHandler.LOCAL_DEVICE_ID,
            name = "Google Pixel 9a",
            isActive = false,
            type = "Smartphone",
        )
        assertEquals(SpotifyPlaybackHandler.LOCAL_DEVICE_ID, handler.chooseStableId(placeholder))
    }

    /**
     * Common case: Spotify already registered the phone before the picker
     * opened, so the dialog shows the real device entry (real opaque ID).
     * Tapping it must still persist LOCAL_DEVICE_ID — the real ID rotates on
     * every Spotify cold start.
     */
    @Test
    fun `resolved real smartphone matching Build_MODEL maps to LOCAL_DEVICE_ID`() {
        val phone = SpDevice(
            id = "real-volatile-id-abc123",
            name = "Pixel 9a",
            isActive = true,
            type = "Smartphone",
        )
        assertEquals(SpotifyPlaybackHandler.LOCAL_DEVICE_ID, handler.chooseStableId(phone))
    }

    @Test
    fun `resolved real smartphone matching Build_MANUFACTURER maps to LOCAL_DEVICE_ID`() {
        val phone = SpDevice(
            id = "real-volatile-id-xyz",
            name = "My Google Phone",
            isActive = true,
            type = "Smartphone",
        )
        assertEquals(SpotifyPlaybackHandler.LOCAL_DEVICE_ID, handler.chooseStableId(phone))
    }

    /**
     * Different smartphone (e.g., partner's iPhone showing up via family
     * Premium Connect). Not local — must keep its real ID so cross-device
     * casts work.
     */
    @Test
    fun `non-matching smartphone keeps real id`() {
        val otherPhone = SpDevice(
            id = "iphone-id",
            name = "Bob's iPhone",
            isActive = false,
            type = "Smartphone",
        )
        assertEquals("iphone-id", handler.chooseStableId(otherPhone))
    }

    @Test
    fun `TV keeps real id`() {
        val tv = SpDevice(
            id = "tv-id",
            name = "Bedroom TV",
            isActive = true,
            type = "TV",
        )
        assertEquals("tv-id", handler.chooseStableId(tv))
    }

    @Test
    fun `Computer keeps real id`() {
        val laptop = SpDevice(
            id = "macbook-id",
            name = "Jeff's MacBook Pro",
            isActive = false,
            type = "Computer",
        )
        assertEquals("macbook-id", handler.chooseStableId(laptop))
    }

    /**
     * Edge case: a non-Smartphone device named "Pixel 9a" (e.g., a Cast target
     * the user named after their phone). Type guard wins — we only treat
     * Smartphone-typed devices as candidates for the local-phone normalization.
     */
    @Test
    fun `non-smartphone with matching name keeps real id`() {
        val castDevice = SpDevice(
            id = "cast-id",
            name = "Pixel 9a Cast",
            isActive = false,
            type = "CastVideo",
        )
        assertEquals("cast-id", handler.chooseStableId(castDevice))
    }
}
