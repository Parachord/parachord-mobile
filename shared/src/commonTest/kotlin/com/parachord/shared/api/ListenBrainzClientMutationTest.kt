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

    // ── getUserOwnedPlaylists ────────────────────────────────────────────────

    private val LB_PLAYLISTS_FIXTURE = """
        {
          "playlist_count": 2,
          "playlists": [
            {
              "playlist": {
                "identifier": "https://listenbrainz.org/playlist/11111111-1111-1111-1111-111111111111",
                "title": "My Playlist",
                "annotation": "Description One",
                "extension": {
                  "https://musicbrainz.org/doc/jspf#playlist": {
                    "last_modified_at": "2026-05-27T10:30:00.000000+00:00",
                    "public": true,
                    "creator": "testuser"
                  }
                }
              }
            },
            {
              "playlist": {
                "identifier": "https://listenbrainz.org/playlist/22222222-2222-2222-2222-222222222222",
                "title": "Another Playlist",
                "annotation": "",
                "extension": {
                  "https://musicbrainz.org/doc/jspf#playlist": {
                    "last_modified_at": "2026-05-26T08:15:00.000000+00:00",
                    "public": true,
                    "creator": "testuser"
                  }
                }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `getUserOwnedPlaylists parses playlist list with MBID extracted from identifier URL`() = runTest {
        var seenUrl: String? = null
        var seenAuth: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers["Authorization"]
            respond(LB_PLAYLISTS_FIXTURE, HttpStatusCode.OK, jsonHeaders)
        }
        val result = client(engine).getUserOwnedPlaylists("testuser")
        assertEquals(
            "https://api.listenbrainz.org/1/user/testuser/playlists?count=100&offset=0",
            seenUrl,
        )
        assertNull(seenAuth)
        assertEquals(2, result.size)
        assertEquals("11111111-1111-1111-1111-111111111111", result[0].mbid)
        assertEquals("My Playlist", result[0].title)
        assertEquals("Description One", result[0].annotation)
        assertEquals("2026-05-27T10:30:00.000000+00:00", result[0].lastModifiedAt)
        assertEquals("22222222-2222-2222-2222-222222222222", result[1].mbid)
        assertEquals("Another Playlist", result[1].title)
    }

    @Test
    fun `getUserOwnedPlaylists returns empty list on 404`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val result = client(engine).getUserOwnedPlaylists("missinguser")
        assertEquals(emptyList(), result)
    }

    @Test
    fun `getUserOwnedPlaylists returns empty list when no playlists present`() = runTest {
        val engine = MockEngine {
            respond(
                """{"playlist_count": 0, "playlists": []}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val result = client(engine).getUserOwnedPlaylists("emptyuser")
        assertEquals(emptyList(), result)
    }

    @Test
    fun `getUserOwnedPlaylists throws on 500`() = runTest {
        val engine = MockEngine { respond("oops", HttpStatusCode.InternalServerError) }
        assertFailsWith<Exception> {
            client(engine).getUserOwnedPlaylists("testuser")
        }
    }

    @Test
    fun `getUserOwnedPlaylists returns null lastModifiedAt when extension absent`() = runTest {
        // LB doesn't always include the extension block — verify we tolerate it
        // and produce null lastModifiedAt rather than crashing.
        val fixture = """
            {
              "playlist_count": 2,
              "playlists": [
                {
                  "playlist": {
                    "identifier": "https://listenbrainz.org/playlist/33333333-3333-3333-3333-333333333333",
                    "title": "Bare Playlist",
                    "annotation": ""
                  }
                },
                {
                  "playlist": {
                    "identifier": "https://listenbrainz.org/playlist/44444444-4444-4444-4444-444444444444",
                    "title": "Null Extension Playlist",
                    "annotation": "",
                    "extension": null
                  }
                }
              ]
            }
        """.trimIndent()
        val engine = MockEngine { respond(fixture, HttpStatusCode.OK, jsonHeaders) }
        val result = client(engine).getUserOwnedPlaylists("testuser")
        assertEquals(2, result.size)
        assertEquals("33333333-3333-3333-3333-333333333333", result[0].mbid)
        assertEquals("Bare Playlist", result[0].title)
        assertNull(result[0].lastModifiedAt)
        assertEquals("44444444-4444-4444-4444-444444444444", result[1].mbid)
        assertEquals("Null Extension Playlist", result[1].title)
        assertNull(result[1].lastModifiedAt)
    }

    @Test
    fun `getUserOwnedPlaylists propagates parse failures on malformed JSON`() = runTest {
        // Parse failures must throw, not swallow to empty list — otherwise the
        // sync provider can't distinguish "user has no playlists" from "LB
        // returned garbage" and would wipe the local mirror.
        val engine = MockEngine { respond("not json at all{", HttpStatusCode.OK, jsonHeaders) }
        assertFailsWith<Exception> {
            client(engine).getUserOwnedPlaylists("testuser")
        }
    }

    @Test
    fun `getUserOwnedPlaylists paginates through every page`() = runTest {
        // Regression for Parachord/parachord#846: the endpoint defaults to
        // count=25; without paging, the sync dedup only sees the newest page and
        // recreates everything else every cycle. The client MUST return the
        // COMPLETE list. Here playlist_count=250 with pageSize=100 → 3 pages.
        val total = 250
        val seenOffsets = mutableListOf<Int>()
        val engine = MockEngine { request ->
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            val count = request.url.parameters["count"]?.toInt() ?: 25
            seenOffsets += offset
            val pageItems = (offset until minOf(offset + count, total)).joinToString(",") { i ->
                """{"playlist":{"identifier":"https://listenbrainz.org/playlist/mbid-$i","title":"PL $i"}}"""
            }
            respond(
                """{"playlist_count":$total,"playlists":[$pageItems]}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val result = client(engine).getUserOwnedPlaylists("testuser")

        // All 250 fetched (not just the first 25/100).
        assertEquals(total, result.size)
        assertEquals("mbid-0", result.first().mbid)
        assertEquals("mbid-249", result.last().mbid)
        // Paged at offsets 0, 100, 200.
        assertEquals(listOf(0, 100, 200), seenOffsets)
    }

    @Test
    fun `getUserOwnedPlaylists throws on partial fetch with empty page before playlist_count`() = runTest {
        // Interop contract rule 4: a partial/truncated fetch is NOT a
        // confirmed-empty result. If LB reports playlist_count=250 but a page
        // comes back empty before we've walked the whole list, the client MUST
        // throw — returning the partial list would make the sync dedup treat the
        // un-fetched playlists as deleted-remotely and recreate them.
        val engine = MockEngine { request ->
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            val body = if (offset == 0) {
                // First page: 100 items, but claims 250 total.
                val items = (0 until 100).joinToString(",") { i ->
                    """{"playlist":{"identifier":"https://listenbrainz.org/playlist/mbid-$i","title":"PL $i"}}"""
                }
                """{"playlist_count":250,"playlists":[$items]}"""
            } else {
                // Second page: unexpectedly empty (LB truncated / transient).
                """{"playlist_count":250,"playlists":[]}"""
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        assertFailsWith<Exception> {
            client(engine).getUserOwnedPlaylists("testuser")
        }
    }

    @Test
    fun `getUserOwnedPlaylists returns empty on first-page 404 with no playlists`() = runTest {
        // A 404 on offset 0 is the one legitimate empty result: the user has no
        // playlists. Must return empty, not throw.
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val result = client(engine).getUserOwnedPlaylists("testuser")
        assertEquals(0, result.size)
    }

    @Test
    fun `getUserOwnedPlaylists stops after a single page when count covers all`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                """{"playlist_count":2,"playlists":[
                    {"playlist":{"identifier":"https://listenbrainz.org/playlist/a","title":"A"}},
                    {"playlist":{"identifier":"https://listenbrainz.org/playlist/b","title":"B"}}
                ]}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val result = client(engine).getUserOwnedPlaylists("testuser")

        assertEquals(2, result.size)
        assertEquals(1, requests) // no needless second page
    }
}
