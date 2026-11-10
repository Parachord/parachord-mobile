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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val playBodies = mutableListOf<String>()
    /** The track `/me/player` reports while playing: ours unless a test says otherwise. */
    private var playingItemJson = """{"id":"4uvx2HRZqGFmSkBc5JapW2"}"""
    private var trackJson: String? = null
    private val pausedDevices = mutableListOf<String?>()
    /** Status `/me/player/pause` answers; 0 = throw, as when Parachord's network is cut. */
    private var pauseStatus = HttpStatusCode.NoContent
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
                    if (pauseStatus.value == 0) throw java.io.IOException("Unable to resolve host \"api.spotify.com\"")
                    respond("", pauseStatus)
                }
                path.endsWith("/me/player/play") && req.method == HttpMethod.Put -> {
                    playCalls++
                    playBodies += (req.body as io.ktor.http.content.TextContent).text
                    respond("", HttpStatusCode.NoContent)
                }
                path.contains("/v1/tracks/") && req.method == HttpMethod.Get ->
                    trackJson?.let { respond(it, HttpStatusCode.OK, json) } ?: respond("", HttpStatusCode.NotFound)
                path.endsWith("/me/player") && req.method == HttpMethod.Get ->
                    if (playerStatus == HttpStatusCode.NoContent) respond("", HttpStatusCode.NoContent)
                    else respond("""{"is_playing":true,"progress_ms":1200,"item":$playingItemJson}""", HttpStatusCode.OK, json)
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

    @Test fun `the play and its re-send name the track's album and start at the track`() = runBlocking {
        // Spotify's Android app plays nothing for a bare `uris` play (Nov 2026).
        trackJson = """{"id":"4uvx2HRZqGFmSkBc5JapW2","album":{"id":"album42"}}"""
        val h = handler().also { it.isAppVisible = { true } }
        try {
            h.play(track)
            fail("expected SpotifyPlaybackNotStartedException")
        } catch (_: SpotifyPlaybackNotStartedException) {
        }
        assertEquals(2, playBodies.size)
        for (body in playBodies) {
            val sent = Json.parseToJsonElement(body).jsonObject
            assertEquals("spotify:album:album42", sent["context_uri"]!!.jsonPrimitive.content)
            assertEquals(track.spotifyUri, sent["offset"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
            assertNull(sent["uris"])
        }
    }

    @Test fun `when the album can't be looked up the play names the bare track`() = runBlocking {
        playerStatus = HttpStatusCode.OK
        val h = handler()
        h.play(track)
        val sent = Json.parseToJsonElement(playBodies.single()).jsonObject
        assertEquals(track.spotifyUri, sent["uris"]!!.jsonArray.single().jsonPrimitive.content)
        assertNull(sent["context_uri"])
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

    @Test fun `on the warm path a play Spotify ignores falls back instead of sitting at 0 00`() = runBlocking {
        // Spotify's phone device already active and playing its own song, a
        // Parachord screen showing: the warm path used to skip the check, and
        // the play that never started sat at 0:00 with no fallback.
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns SpotifyPlaybackHandler.LOCAL_DEVICE_ID
        devicesJson = """{"devices":[{"id":"phone","name":"J9a","type":"Smartphone","is_active":true,"is_restricted":false}]}"""
        playerStatus = HttpStatusCode.OK
        playingItemJson = """{"id":"someoneElsesSong"}"""
        val h = handler().also { it.isAppVisible = { true } }
        h.runPrewarm()
        try {
            h.play(track)
            fail("expected SpotifyPlaybackNotStartedException")
        } catch (_: SpotifyPlaybackNotStartedException) {
        }
        assertEquals("the play and one re-send, both on the warm path", 2, playCalls)
        assertEquals(listOf<String?>("phone"), pausedDevices)
    }

    @Test fun `on the warm path a play that starts returns normally`() = runBlocking {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns SpotifyPlaybackHandler.LOCAL_DEVICE_ID
        devicesJson = """{"devices":[{"id":"phone","name":"J9a","type":"Smartphone","is_active":true,"is_restricted":false}]}"""
        playerStatus = HttpStatusCode.OK
        val h = handler().also { it.isAppVisible = { true } }
        h.runPrewarm()
        h.play(track)
        assertEquals(1, playCalls)
    }

    @Test fun `a relinked track counts as started`() = runBlocking {
        playerStatus = HttpStatusCode.OK
        playingItemJson = """{"id":"marketVersion","linked_from":{"id":"4uvx2HRZqGFmSkBc5JapW2"}}"""
        handler().play(track)
        assertEquals(1, playCalls)
    }

    // ── When Parachord can't reach Spotify, pause it locally ──────────
    // A play names the track's album (Spotify's Android app plays nothing
    // without a context), so Spotify runs on into the album when Parachord
    // misses the end: its network cut in the background, Doze.

    private fun playOnThisPhone(): SpotifyPlaybackHandler = runBlocking {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns SpotifyPlaybackHandler.LOCAL_DEVICE_ID
        devicesJson = """{"devices":[{"id":"phone","name":"J9a","type":"Smartphone","is_active":true,"is_restricted":false}]}"""
        playerStatus = HttpStatusCode.OK
        val h = handler().also { it.isAppVisible = { true } }
        h.runPrewarm()
        h.play(track)
        h
    }

    @Test fun `a pause Spotify can't be reached for goes to this phone's Spotify as a media key`() = runBlocking {
        val h = playOnThisPhone()
        pauseStatus = HttpStatusCode(0, "network down")
        h.pause()
        assertEquals("a down and an up", listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE), mediaKeys())
    }

    @Test fun `a pause Spotify accepted sends no media key`() = runBlocking {
        val h = playOnThisPhone()
        h.pause()
        assertTrue(mediaKeys().isEmpty())
    }

    @Test fun `a pause Spotify refused sends no media key`() = runBlocking {
        // 403: Spotify answered (it's already paused); a media key adds nothing.
        val h = playOnThisPhone()
        pauseStatus = HttpStatusCode.Forbidden
        h.pause()
        assertTrue(mediaKeys().isEmpty())
    }

    @Test fun `a failed pause of a remote device sends no media key`() = runBlocking {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns "living-room-tv"
        devicesJson = """{"devices":[{"id":"living-room-tv","name":"Living Room","type":"TV","is_active":true,"is_restricted":false}]}"""
        playerStatus = HttpStatusCode.OK
        val h = handler().also { it.isAppVisible = { true } }
        h.play(track)
        pauseStatus = HttpStatusCode(0, "network down")
        h.pause()
        assertTrue("a media key would reach this phone's Spotify, not the TV", mediaKeys().isEmpty())
    }

    @Test fun `the end pause waits for Spotify to be unreachable and the track to have ended`() {
        val rule = SpotifyPlaybackHandler.Companion
        // No poll has answered for 5 s (a cut network hangs a poll for 30 s
        // rather than failing it); last seen at 90 s of a 92 s track 1 s ago:
        // within 1 s of the end, so it pauses before the album's next track.
        assertTrue(rule.shouldPauseAtTrackEnd(nowMs = 11_000, isPlaying = true, positionMs = 90_000, durationMs = 92_000,
            positionSeenAtMs = 10_000, lastPollSuccessAtMs = 6_000))
        // A poll answered 1 s ago: Parachord's own near-end advance handles it.
        assertFalse(rule.shouldPauseAtTrackEnd(13_000, true, 90_000, 92_000, 10_000, lastPollSuccessAtMs = 12_000))
        // Unreachable, but the track has more than a second left.
        assertFalse(rule.shouldPauseAtTrackEnd(10_500, true, 90_000, 92_000, 10_000, 5_000))
        // Paused, no length known, or never seen: nothing to do.
        assertFalse(rule.shouldPauseAtTrackEnd(13_000, false, 90_000, 92_000, 10_000, 8_000))
        assertFalse(rule.shouldPauseAtTrackEnd(13_000, true, 90_000, 0, 10_000, 8_000))
        assertFalse(rule.shouldPauseAtTrackEnd(13_000, true, 90_000, 92_000, 0, 8_000))
    }

    @Test fun `the stale-position watchdog needs a poll that answered`() {
        val rule = SpotifyPlaybackHandler.Companion
        // Position stuck 16 s while polls answer: a stall, the track is done.
        assertTrue(rule.staleWatchdogFires(nowMs = 26_000, isPlaying = true, positionMs = 72_000,
            positionSeenAtMs = 10_000, pollFailures = 0, lastPollSuccessAtMs = 25_500))
        // Stuck 16 s, no failure counted, but no poll has answered for 16 s
        // either (one is hanging on a cut network): not a stall.
        assertFalse(rule.staleWatchdogFires(26_000, true, 72_000, 10_000, pollFailures = 0, lastPollSuccessAtMs = 10_000))
        // Polls failing: not a stall.
        assertFalse(rule.staleWatchdogFires(26_000, true, 72_000, 10_000, pollFailures = 3, lastPollSuccessAtMs = 25_500))
        // Not stuck long enough.
        assertFalse(rule.staleWatchdogFires(20_000, true, 72_000, 10_000, 0, 19_500))
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
