package com.parachord.shared.api

import com.parachord.shared.sync.ListenBrainzUnauthorizedException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MockEngine tests for [ListenBrainzClient] playlist mutation endpoints
 * added in Phase 2 of the ListenBrainz sync provider work (issue #156,
 * Task 6).
 *
 * Endpoints under test:
 *  - POST /1/playlist/create
 *  - POST /1/playlist/edit/{mbid}
 *  - POST /1/playlist/{mbid}/delete
 *
 * Each method must:
 *  - send `Authorization: Token <token>` header
 *  - throw [ListenBrainzUnauthorizedException] on HTTP 401
 *  - shape the body per the LB JSPF spec (create/edit only)
 */
class ListenBrainzClientMutationTest {

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private fun client(engine: MockEngine) = ListenBrainzClient(
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        },
    )

    private suspend fun bodyJson(request: HttpRequestData): JsonObject {
        val text = request.body.toByteArray().decodeToString()
        return Json.parseToJsonElement(text).jsonObject
    }

    // ── createPlaylist ───────────────────────────────────────────────────────

    @Test
    fun createPlaylist_sendsCorrectRequestAndParsesMbid() = runTest {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenMethod: HttpMethod? = null
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers["Authorization"]
            seenMethod = request.method
            seenBody = bodyJson(request)
            respond(
                """{"playlist_mbid":"12345678-1234-1234-1234-123456789012","status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val mbid = client(engine).createPlaylist(
            name = "Test Playlist",
            description = "A test",
            isPublic = true,
            token = "tok",
        )

        assertEquals("12345678-1234-1234-1234-123456789012", mbid)
        assertEquals("https://api.listenbrainz.org/1/playlist/create", seenUrl)
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("Token tok", seenAuth)

        val playlist = seenBody!!["playlist"]!!.jsonObject
        assertEquals("Test Playlist", playlist["title"]!!.jsonPrimitive.content)
        assertEquals("A test", playlist["annotation"]!!.jsonPrimitive.content)
        val ext = playlist["extension"]!!.jsonObject
        val jspfExt = ext["https://musicbrainz.org/doc/jspf#playlist"]!!.jsonObject
        assertTrue(jspfExt["public"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun createPlaylist_omitsDescriptionWhenNull() = runTest {
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenBody = bodyJson(request)
            respond(
                """{"playlist_mbid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        client(engine).createPlaylist(name = "No-Desc", description = null, token = "tok")
        val playlist = seenBody!!["playlist"]!!.jsonObject
        assertEquals("No-Desc", playlist["title"]!!.jsonPrimitive.content)
        assertFalse(playlist.containsKey("annotation"))
    }

    @Test
    fun createPlaylist_privateFlag() = runTest {
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenBody = bodyJson(request)
            respond(
                """{"playlist_mbid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        client(engine).createPlaylist(name = "Priv", isPublic = false, token = "tok")
        val ext = seenBody!!["playlist"]!!.jsonObject["extension"]!!.jsonObject
        val jspfExt = ext["https://musicbrainz.org/doc/jspf#playlist"]!!.jsonObject
        assertFalse(jspfExt["public"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun createPlaylist_throwsUnauthorizedOn401() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<ListenBrainzUnauthorizedException> {
            client(engine).createPlaylist("Test", token = "bad-token")
        }
    }

    // ── editPlaylist ─────────────────────────────────────────────────────────

    @Test
    fun editPlaylist_sendsBodyWithOnlyNonNullFields() = runTest {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers["Authorization"]
            seenBody = bodyJson(request)
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }

        client(engine).editPlaylist(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            name = "Renamed",
            description = null,
            token = "tok",
        )

        assertEquals(
            "https://api.listenbrainz.org/1/playlist/edit/abcd1234-1111-2222-3333-444455556666",
            seenUrl,
        )
        assertEquals("Token tok", seenAuth)
        val playlist = seenBody!!["playlist"]!!.jsonObject
        assertEquals("Renamed", playlist["title"]!!.jsonPrimitive.content)
        assertFalse(playlist.containsKey("annotation"))
    }

    @Test
    fun editPlaylist_includesDescriptionWhenProvided() = runTest {
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenBody = bodyJson(request)
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        client(engine).editPlaylist(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            name = null,
            description = "New annotation",
            token = "tok",
        )
        val playlist = seenBody!!["playlist"]!!.jsonObject
        assertFalse(playlist.containsKey("title"))
        assertEquals("New annotation", playlist["annotation"]!!.jsonPrimitive.content)
    }

    @Test
    fun editPlaylist_throwsUnauthorizedOn401() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<ListenBrainzUnauthorizedException> {
            client(engine).editPlaylist(
                playlistMbid = "abcd1234-1111-2222-3333-444455556666",
                name = "x",
                description = null,
                token = "bad",
            )
        }
    }

    // ── deletePlaylist ───────────────────────────────────────────────────────

    @Test
    fun deletePlaylist_postsToDeleteEndpoint() = runTest {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenMethod: HttpMethod? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers["Authorization"]
            seenMethod = request.method
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }

        client(engine).deletePlaylist(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            token = "tok",
        )

        assertEquals(
            "https://api.listenbrainz.org/1/playlist/abcd1234-1111-2222-3333-444455556666/delete",
            seenUrl,
        )
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("Token tok", seenAuth)
    }

    @Test
    fun deletePlaylist_throwsUnauthorizedOn401() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<ListenBrainzUnauthorizedException> {
            client(engine).deletePlaylist(
                playlistMbid = "abcd1234-1111-2222-3333-444455556666",
                token = "bad",
            )
        }
    }

    // ── addPlaylistItems ─────────────────────────────────────────────────────

    @Test
    fun `addPlaylistItems sends list of MB recording URIs in JSPF body`() = runTest {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenMethod: HttpMethod? = null
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers["Authorization"]
            seenMethod = request.method
            seenBody = bodyJson(request)
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }

        client(engine).addPlaylistItems(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            recordingMbids = listOf(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
            ),
            token = "tok",
        )

        assertEquals(
            "https://api.listenbrainz.org/1/playlist/abcd1234-1111-2222-3333-444455556666/item/add",
            seenUrl,
        )
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("Token tok", seenAuth)
        val tracks = seenBody!!["playlist"]!!.jsonObject["track"]!!.jsonArray
        assertEquals(2, tracks.size)
        assertEquals(
            "https://musicbrainz.org/recording/11111111-1111-1111-1111-111111111111",
            tracks[0].jsonObject["identifier"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "https://musicbrainz.org/recording/22222222-2222-2222-2222-222222222222",
            tracks[1].jsonObject["identifier"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `addPlaylistItems short-circuits when list is empty`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        client(engine).addPlaylistItems(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            recordingMbids = emptyList(),
            token = "tok",
        )
        assertEquals(0, callCount)
    }

    @Test
    fun `addPlaylistItems throws on 401`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<ListenBrainzUnauthorizedException> {
            client(engine).addPlaylistItems(
                playlistMbid = "abcd1234-1111-2222-3333-444455556666",
                recordingMbids = listOf("11111111-1111-1111-1111-111111111111"),
                token = "bad",
            )
        }
    }

    // ── deletePlaylistItems ──────────────────────────────────────────────────

    @Test
    fun `deletePlaylistItems sends index and count`() = runTest {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenMethod: HttpMethod? = null
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers["Authorization"]
            seenMethod = request.method
            seenBody = bodyJson(request)
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }

        client(engine).deletePlaylistItems(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            index = 0,
            count = 5,
            token = "tok",
        )

        assertEquals(
            "https://api.listenbrainz.org/1/playlist/abcd1234-1111-2222-3333-444455556666/item/delete",
            seenUrl,
        )
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("Token tok", seenAuth)
        assertEquals(0, seenBody!!["index"]!!.jsonPrimitive.int)
        assertEquals(5, seenBody!!["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `deletePlaylistItems short-circuits when count is zero`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        client(engine).deletePlaylistItems(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            index = 0,
            count = 0,
            token = "tok",
        )
        assertEquals(0, callCount)
    }

    @Test
    fun `deletePlaylistItems throws on 401`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<ListenBrainzUnauthorizedException> {
            client(engine).deletePlaylistItems(
                playlistMbid = "abcd1234-1111-2222-3333-444455556666",
                index = 0,
                count = 3,
                token = "bad",
            )
        }
    }

    // ── getPlaylistLastModified ──────────────────────────────────────────────

    @Test
    fun `getPlaylistLastModified extracts last_modified_at from JSPF extension`() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond(
                """
                {
                  "playlist": {
                    "title": "X",
                    "extension": {
                      "https://musicbrainz.org/doc/jspf#playlist": {
                        "last_modified_at": "2026-05-27T12:34:56.000000+00:00",
                        "public": true
                      }
                    }
                  }
                }
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val result = client(engine).getPlaylistLastModified(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
        )
        assertEquals(
            "https://api.listenbrainz.org/1/playlist/abcd1234-1111-2222-3333-444455556666",
            seenUrl,
        )
        assertEquals("2026-05-27T12:34:56.000000+00:00", result)
    }

    @Test
    fun `getPlaylistLastModified returns null on 404`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val result = client(engine).getPlaylistLastModified(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
        )
        assertNull(result)
    }

    @Test
    fun `getPlaylistLastModified returns null when field missing`() = runTest {
        val engine = MockEngine {
            respond(
                """
                {
                  "playlist": {
                    "title": "X",
                    "extension": {
                      "https://musicbrainz.org/doc/jspf#playlist": {
                        "public": true
                      }
                    }
                  }
                }
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val result = client(engine).getPlaylistLastModified(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
        )
        assertNull(result)
    }
}
