package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MockEngine-based unit test for [SpotifyClient.removePlaylistTracks] —
 * the targeted DELETE used by incremental playlist materialization. It
 * must issue `DELETE /v1/playlists/{id}/tracks` with a JSON body of the
 * shape `{"tracks":[{"uri":"..."}]}` (mirrors the existing
 * DELETE-with-body precedent, e.g. [SpotifyClient.removeTracks]).
 *
 * Construction mirrors [SpotifyClientWriteGateTest] exactly.
 */
class SpotifyClientPlaylistRemoveTest {

    private val appConfig = AppConfig(
        userAgent = "Parachord/0.5.0-test (Android; https://parachord.app)",
        isDebug = false,
    )
    private val json = Json { ignoreUnknownKeys = true }

    private val stubAuthProvider = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? =
            if (realm == AuthRealm.Spotify) AuthCredential.BearerToken("test") else null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val stubTokenRefresher = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private fun buildClient(mock: MockEngine): SpotifyClient {
        val httpClient = HttpClient(mock) {
            installSharedPlugins(json, appConfig, stubAuthProvider, stubTokenRefresher)
        }
        return SpotifyClient(httpClient, stubAuthProvider)
    }

    @Test
    fun `removePlaylistTracks DELETEs the tracks-by-uri body`() = runTest {
        var capturedMethod: String? = null
        var capturedUrl: String? = null
        var capturedBody: String? = null
        val mock = MockEngine { req ->
            capturedMethod = req.method.value
            capturedUrl = req.url.toString()
            capturedBody = (req.body as TextContent).text
            respond(
                content = """{"snapshot_id":"snap1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        client.removePlaylistTracks("PL1", SpTracksUriRefRequest(listOf(SpUriRef("spotify:track:a"))))

        assertEquals("DELETE", capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/v1/playlists/PL1/tracks"))
        assertTrue(capturedBody!!.contains("spotify:track:a"))
        assertTrue(capturedBody!!.contains("\"tracks\""))
        assertTrue(capturedBody!!.contains("\"uri\""))
    }
}
