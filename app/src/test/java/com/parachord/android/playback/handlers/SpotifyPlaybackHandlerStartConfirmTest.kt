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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Spotify can accept a play and then never start it: its process was only
 * bound by Android Auto as a media browser, and `/me/player` answers 204
 * ("nothing playing") for good. The car sat on 0:01 with no audio (#452). The
 * handler must confirm the start, send the play once more, and then tell the
 * controller so it can fall back, instead of proceeding optimistically.
 *
 * Real delays (runBlocking): the confirmation polls on wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SpotifyPlaybackHandlerStartConfirmTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var playerStatus = HttpStatusCode.NoContent
    private var playCalls = 0
    private lateinit var settingsStore: SettingsStore

    private val track = Track(
        id = "t1", title = "Adrenaline Nightshift", artist = "Japandroids",
        resolver = "spotify", spotifyUri = "spotify:track:4uvx2HRZqGFmSkBc5JapW2",
    )

    private fun handler(): SpotifyPlaybackHandler {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/me/player/devices") -> respond(
                    """{"devices":[{"id":"phone","name":"J9a","type":"Smartphone","is_active":false,"is_restricted":false}]}""",
                    HttpStatusCode.OK, json,
                )
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

    @Test fun `an accepted play that never starts throws after one re-send`() = runBlocking {
        val h = handler()
        try {
            h.play(track)
            fail("expected SpotifyPlaybackNotStartedException")
        } catch (_: SpotifyPlaybackNotStartedException) {
        }
        assertEquals("the play is sent once more before giving up", 2, playCalls)
    }

    @Test fun `a play that starts returns normally`() = runBlocking {
        playerStatus = HttpStatusCode.OK
        handler().play(track)
        assertEquals(1, playCalls)
    }
}
