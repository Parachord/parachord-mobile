package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * MockEngine tests for [ListenBrainzClient.getPlayingNow]. Added as
 * part of the `parachord://listen-along` transient-friend fallback
 * (issue #121, Phase 3 Task 7).
 *
 * The endpoint shape mirrors `/listens` — the same `payload.listens[]`
 * envelope — so we reuse the [ListensWire] decoder. These tests exercise
 * the boundary cases where reuse can mask bugs:
 *  - empty payload → null (user not currently scrobbling)
 *  - blank artist OR title → null (ListenBrainz sometimes emits
 *    placeholders; we treat those as "not playing")
 *  - HTTP 4xx/5xx → null (calm UX — listen-along caller surfaces a
 *    "not currently listening" toast rather than an error)
 *  - happy path → populated [LbListen]
 */
class ListenBrainzPlayingNowTest {

    private fun client(engine: MockEngine) = ListenBrainzClient(HttpClient(engine))

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    @Test
    fun happyPath_returnsLbListenWithMetadata() = runTest {
        val body = """
            {
              "payload": {
                "listens": [
                  {
                    "listened_at": 1700000000,
                    "track_metadata": {
                      "artist_name": "Slowdive",
                      "track_name": "Sugar For The Pill",
                      "release_name": "Slowdive (2017)"
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }

        val listen = client(engine).getPlayingNow("rob")
        assertNotNull(listen)
        assertEquals("Slowdive", listen.artistName)
        assertEquals("Sugar For The Pill", listen.trackName)
        assertEquals("Slowdive (2017)", listen.releaseName)
        assertEquals(1700000000L, listen.listenedAt)
    }

    @Test
    fun emptyPayload_returnsNull() = runTest {
        // User reachable but not currently playing anything.
        val body = """{"payload":{"listens":[]}}"""
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        assertNull(client(engine).getPlayingNow("rob"))
    }

    @Test
    fun missingTrackMetadata_returnsNull() = runTest {
        val body = """
            {
              "payload": {
                "listens": [
                  { "listened_at": 1700000000 }
                ]
              }
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        assertNull(client(engine).getPlayingNow("rob"))
    }

    @Test
    fun blankArtist_returnsNull() = runTest {
        val body = """
            {
              "payload": {
                "listens": [
                  {
                    "track_metadata": {
                      "artist_name": "",
                      "track_name": "Sugar For The Pill"
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        assertNull(client(engine).getPlayingNow("rob"))
    }

    @Test
    fun blankTitle_returnsNull() = runTest {
        val body = """
            {
              "payload": {
                "listens": [
                  {
                    "track_metadata": {
                      "artist_name": "Slowdive",
                      "track_name": ""
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        assertNull(client(engine).getPlayingNow("rob"))
    }

    @Test
    fun http401_returnsNullCalmly() = runTest {
        // Auth not configured, or token expired. Listen-along caller
        // converts this into a calm "not currently listening" toast.
        val engine = MockEngine { _ ->
            respond("Unauthorized", HttpStatusCode.Unauthorized)
        }
        assertNull(client(engine).getPlayingNow("rob"))
    }

    @Test
    fun http500_returnsNullCalmly() = runTest {
        val engine = MockEngine { _ ->
            respond("Internal Server Error", HttpStatusCode.InternalServerError)
        }
        assertNull(client(engine).getPlayingNow("rob"))
    }

    @Test
    fun urlIncludesUsername() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { req ->
            seenUrl = req.url.toString()
            respond("""{"payload":{"listens":[]}}""", HttpStatusCode.OK, jsonHeaders)
        }
        client(engine).getPlayingNow("MrMonkey")
        assertEquals(
            "https://api.listenbrainz.org/1/user/MrMonkey/playing-now",
            seenUrl,
        )
    }
}
