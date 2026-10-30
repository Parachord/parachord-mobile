package com.parachord.android.playback.handlers

import android.app.Application
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import android.content.Intent
import android.view.KeyEvent
import com.parachord.shared.api.SpDevice
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Spotify can accept a play and then never start it: its process was only
 * bound by Android Auto as a media browser, and `/me/player` answers 204
 * ("nothing playing") for good. The car sat on 0:01 with no audio (#452). The
 * handler must confirm the start (with a screen showing, after sending the play
 * once more), pause the device, and then tell the controller so it can fall
 * back, instead of proceeding optimistically.
 *
 * Also covers [SpotifyPlaybackHandler.prewarm]: it finds the device ahead of
 * the play, so the play goes straight to it, and wakes Spotify only with a
 * PAUSE key.
 *
 * Real delays (runBlocking): the confirmation polls on wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SpotifyPlaybackHandlerStartConfirmTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var playerStatus = HttpStatusCode.NoContent
    private var playCalls = 0
    private val pausedDevices = mutableListOf<String?>()
    private var devicesJson = """{"devices":[{"id":"phone","name":"J9a","type":"Smartphone","is_active":false,"is_restricted":false}]}"""
    private lateinit var settingsStore: SettingsStore

    private val track = Track(
        id = "t1", title = "Adrenaline Nightshift", artist = "Japandroids",
        resolver = "spotify", spotifyUri = "spotify:track:4uvx2HRZqGFmSkBc5JapW2",
    )

    private fun handler(): SpotifyPlaybackHandler {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/me/player/devices") -> respond(devicesJson, HttpStatusCode.OK, json)
                path.endsWith("/me/player/pause") && req.method == HttpMethod.Put -> {
                    pausedDevices += req.url.parameters["device_id"]
                    respond("", HttpStatusCode.NoContent)
                }
                path.endsWith("/me/player/play") && req.method == HttpMethod.Put -> {
                    playCalls++
                    respond("", HttpStatusCode.NoContent)
                }
                path.endsWith("/me/player") && req.method == HttpMethod.Get ->
                    if (playerStatus == HttpStatusCode.NoContent) respond("", HttpStatusCode.NoContent)
                    else respond("""{"is_playing":true,"progress_ms":1200}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val tokens = object : AuthTokenProvider {
            override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = AuthCredential.BearerToken("token")
            override suspend fun invalidate(realm: AuthRealm) {}
        }
        return SpotifyPlaybackHandler(
            spotifyClient = SpotifyClient(http, tokens),
            settingsStore = settingsStore,
            oAuthManager = mockk<OAuthManager>(relaxed = true),
            context = RuntimeEnvironment.getApplication(),
        ).also { it.isAppVisible = { false } }
    }

    @Before
    fun setUp() {
        settingsStore = mockk(relaxed = true)
        coEvery { settingsStore.getSpotifyAccessToken() } returns "token"
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null
        coEvery { settingsStore.getResolverVolumeOffsets() } returns emptyMap()
    }

    @Test fun `in the background an accepted play that never starts throws without a re-send`() = runBlocking {
        val h = handler()
        try {
            h.play(track)
            fail("expected SpotifyPlaybackNotStartedException")
        } catch (_: SpotifyPlaybackNotStartedException) {
        }
        assertEquals("one confirm window, no re-send", 1, playCalls)
        assertEquals("the device is paused so a late start doesn't play over the fallback", listOf<String?>("phone"), pausedDevices)
    }

    @Test fun `with a screen showing the play is sent once more before giving up`() = runBlocking {
        val h = handler().also { it.isAppVisible = { true } }
        try {
            h.play(track)
            fail("expected SpotifyPlaybackNotStartedException")
        } catch (_: SpotifyPlaybackNotStartedException) {
        }
        assertEquals(2, playCalls)
        assertEquals(listOf<String?>("phone"), pausedDevices)
    }

    @Test fun `after a prewarm the play goes straight to the device without waking Spotify`() = runBlocking {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns SpotifyPlaybackHandler.LOCAL_DEVICE_ID
        playerStatus = HttpStatusCode.OK
        val h = handler()
        h.runPrewarm()
        assertTrue("the device was already registered, so no wake", mediaKeys().isEmpty())
        h.play(track)
        assertEquals(1, playCalls)
        assertTrue("the warm path sends no PLAY key", mediaKeys().isEmpty())
    }

    @Test fun `a prewarm wakes Spotify with PAUSE and never PLAY`() = runBlocking {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns SpotifyPlaybackHandler.LOCAL_DEVICE_ID
        devicesJson = """{"devices":[]}"""
        val h = handler()
        withTimeoutOrNull(1_500) { h.runPrewarm() }
        assertEquals(setOf(KeyEvent.KEYCODE_MEDIA_PAUSE), mediaKeys().toSet())
        assertEquals("nothing is played", 0, playCalls)
    }

    @Test fun `a prewarm doesn't wake Spotify for a remote preferred device`() = runBlocking {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns "living-room-tv"
        handler().runPrewarm()
        assertTrue(mediaKeys().isEmpty())
    }

    @Test fun `the prewarm target is the preferred remote device or this phone`() {
        val h = handler()
        val phone = SpDevice(id = "phone", name = "J9a", type = "Smartphone")
        val tv = SpDevice(id = "tv", name = "Living Room", type = "TV")
        assertEquals(tv, h.prewarmTarget(listOf(phone, tv), "tv"))
        assertNull(h.prewarmTarget(listOf(phone), "tv"))
        assertEquals(phone, h.prewarmTarget(listOf(tv, phone), SpotifyPlaybackHandler.LOCAL_DEVICE_ID))
        assertEquals(phone, h.prewarmTarget(listOf(tv, phone), null))
        assertNull(h.prewarmTarget(listOf(tv), SpotifyPlaybackHandler.LOCAL_DEVICE_ID))
    }

    @Suppress("DEPRECATION")
    private fun mediaKeys(): List<Int> =
        shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
            .filter { it.action == Intent.ACTION_MEDIA_BUTTON }
            .mapNotNull { (it.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent)?.keyCode }

    @Test fun `a play that starts returns normally`() = runBlocking {
        playerStatus = HttpStatusCode.OK
        handler().play(track)
        assertEquals(1, playCalls)
    }
}
