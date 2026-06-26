package com.parachord.shared.sync

import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.installSharedPlugins
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the Spotify incremental add/remove primitives (Task 3 of the
 * incremental-materialization plan) at the wire level via a MockEngine-backed
 * real [SpotifyClient] (construction mirrors `SpotifyClientPlaylistRemoveTest`).
 *
 * The critical invariants:
 *  - `addPlaylistTracks` appends NON-destructively — every chunk goes out as a
 *    POST `/v1/playlists/{id}/tracks` and NEVER the PUT replace.
 *  - `removePlaylistTracksByNativeId` issues DELETE with the `{"tracks":[{"uri":…}]}` body.
 *  - Both return the snapshot id parsed from the last successful response.
 */
class SpotifyIncrementalPrimitivesTest {

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

    private class Captured(
        val method: String,
        val url: String,
        val body: String,
    )

    private fun buildProvider(
        captures: MutableList<Captured>,
        snapshotIds: List<String> = listOf("snap-final"),
    ): SpotifySyncProvider {
        var callIndex = 0
        val mock = MockEngine { req: HttpRequestData ->
            captures += Captured(
                method = req.method.value,
                url = req.url.toString(),
                body = (req.body as? TextContent)?.text ?: "",
            )
            val snap = snapshotIds.getOrElse(callIndex) { snapshotIds.last() }
            callIndex++
            respond(
                content = """{"snapshot_id":"$snap"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val httpClient = HttpClient(mock) {
            installSharedPlugins(json, appConfig, stubAuthProvider, stubTokenRefresher)
        }
        return SpotifySyncProvider(SpotifyClient(httpClient, stubAuthProvider))
    }

    // ── addPlaylistTracks ──────────────────────────────────────────────

    @Test
    fun `addPlaylistTracks POSTs the tracks and returns last snapshot`() = runTest {
        val captures = mutableListOf<Captured>()
        val provider = buildProvider(captures, snapshotIds = listOf("snap-1"))

        val snap = provider.addPlaylistTracks("PL1", listOf("spotify:track:a", "spotify:track:b"))

        assertEquals(1, captures.size)
        assertEquals("POST", captures[0].method)
        assertTrue(captures[0].url.endsWith("/v1/playlists/PL1/tracks"))
        assertTrue(captures[0].body.contains("spotify:track:a"))
        assertTrue(captures[0].body.contains("spotify:track:b"))
        // Append shape is the uris request, NOT a tracks-by-uri-ref.
        assertTrue(captures[0].body.contains("\"uris\""))
        assertEquals("snap-1", snap)
    }

    @Test
    fun `addPlaylistTracks NEVER issues a PUT replace - all chunks are POST`() = runTest {
        val captures = mutableListOf<Captured>()
        // 150 tracks → 2 chunks at PLAYLIST_TRACK_BATCH_SIZE (100). Both must be POST.
        val provider = buildProvider(captures, snapshotIds = listOf("snap-a", "snap-b"))

        val snap = provider.addPlaylistTracks("PL1", (1..150).map { "spotify:track:t$it" })

        assertEquals(2, captures.size)
        assertTrue(captures.none { it.method == "PUT" }, "no chunk may be a PUT")
        assertTrue(captures.all { it.method == "POST" })
        // Returns the LAST chunk's snapshot.
        assertEquals("snap-b", snap)
    }

    @Test
    fun `addPlaylistTracks with empty list issues no request and returns null`() = runTest {
        val captures = mutableListOf<Captured>()
        val provider = buildProvider(captures)

        val snap = provider.addPlaylistTracks("PL1", emptyList())

        assertTrue(captures.isEmpty())
        assertNull(snap)
    }

    // ── removePlaylistTracksByNativeId ─────────────────────────────────

    @Test
    fun `removePlaylistTracksByNativeId DELETEs the tracks-by-uri body and returns snapshot`() = runTest {
        val captures = mutableListOf<Captured>()
        val provider = buildProvider(captures, snapshotIds = listOf("snap-del"))

        val snap = provider.removePlaylistTracksByNativeId(
            "PL1",
            listOf("spotify:track:a", "spotify:track:b"),
        )

        assertEquals(1, captures.size)
        assertEquals("DELETE", captures[0].method)
        assertTrue(captures[0].url.endsWith("/v1/playlists/PL1/tracks"))
        assertTrue(captures[0].body.contains("\"tracks\""))
        assertTrue(captures[0].body.contains("\"uri\""))
        assertTrue(captures[0].body.contains("spotify:track:a"))
        assertEquals("snap-del", snap)
    }

    @Test
    fun `removePlaylistTracksByNativeId chunks over 100 into multiple DELETEs`() = runTest {
        val captures = mutableListOf<Captured>()
        val provider = buildProvider(captures, snapshotIds = listOf("snap-x", "snap-y"))

        val snap = provider.removePlaylistTracksByNativeId(
            "PL1",
            (1..150).map { "spotify:track:t$it" },
        )

        assertEquals(2, captures.size)
        assertTrue(captures.all { it.method == "DELETE" })
        assertEquals("snap-y", snap)
    }
}
