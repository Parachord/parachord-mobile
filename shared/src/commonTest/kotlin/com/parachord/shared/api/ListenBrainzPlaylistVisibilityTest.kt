package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** #527: desktop's `playlistStatus` and `setPlaylistPublic` (parachord#1265). */
class ListenBrainzPlaylistVisibilityTest {

    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private val jspf = "https://musicbrainz.org/doc/jspf#playlist"

    private fun client(engine: MockEngine) = ListenBrainzClient(
        HttpClient(engine) { install(ContentNegotiation) { json() } },
    )

    private fun playlist(extension: String) = """{"playlist":{"title":"Mix","extension":{"$jspf":$extension}}}"""

    private suspend fun status(body: String, code: HttpStatusCode = HttpStatusCode.OK) =
        client(MockEngine { respond(body, code, jsonHeaders) }).playlistStatus("abc", "secret")

    @Test
    fun aDefinite404IsGone() = runTest {
        assertEquals(LbPlaylistStatus(exists = false, isPublic = null), status("{}", HttpStatusCode.NotFound))
    }

    @Test
    fun aPrivatePlaylistExistsAndSaysSo() = runTest {
        assertEquals(LbPlaylistStatus(exists = true, isPublic = false), status(playlist("""{"public":false}""")))
    }

    @Test
    fun aPublicPlaylistExistsAndSaysSo() = runTest {
        assertEquals(LbPlaylistStatus(exists = true, isPublic = true), status(playlist("""{"public":true}""")))
    }

    @Test
    fun noPublicFlagIsUnknown_notPrivate() = runTest {
        assertEquals(LbPlaylistStatus(exists = true, isPublic = null), status(playlist("{}")))
        assertEquals(LbPlaylistStatus(exists = true, isPublic = null), status(playlist("""{"public":"false"}""")))
    }

    @Test
    fun anotherStatusSaysNothing() = runTest {
        assertEquals(LbPlaylistStatus(null, null), status("{}", HttpStatusCode.Forbidden))
    }

    @Test
    fun aBodyItCannotReadStillMeansItExists() = runTest {
        assertEquals(LbPlaylistStatus(exists = true, isPublic = null), status("not json"))
    }

    @Test
    fun aFailedRequestSaysNothing() = runTest {
        val answer = client(MockEngine { throw IllegalStateException("offline") }).playlistStatus("abc", "secret")
        assertEquals(LbPlaylistStatus(null, null), answer)
    }

    @Test
    fun theStatusAsksWithTheOwnersToken() = runTest {
        var auth: String? = null
        client(
            MockEngine {
                auth = it.headers["Authorization"]
                respond(playlist("{}"), HttpStatusCode.OK, jsonHeaders)
            },
        ).playlistStatus("abc", "secret")
        assertEquals("Token secret", auth)
    }

    @Test
    fun goingPublicReSendsTheCollaborators_andNothingElse() = runTest {
        var edit: String? = null
        var editUrl: String? = null
        var editAuth: String? = null
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                edit = request.body.toByteArray().decodeToString()
                editUrl = request.url.encodedPath
                editAuth = request.headers["Authorization"]
                respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                respond(playlist("""{"public":false,"collaborators":["a","b"]}"""), HttpStatusCode.OK, jsonHeaders)
            }
        }

        client(engine).setPlaylistPublic("abc", true, "secret")

        assertEquals("/1/playlist/edit/abc", editUrl)
        assertEquals("Token secret", editAuth)
        val sent = Json.parseToJsonElement(edit!!).jsonObject.getValue("playlist").jsonObject
        assertEquals(setOf("extension"), sent.keys, "a field left out stays as it is on ListenBrainz")
        assertEquals(
            Json.parseToJsonElement("""{"public":true,"collaborators":["a","b"]}"""),
            sent.getValue("extension").jsonObject.getValue(jspf),
        )
    }

    @Test
    fun noCollaboratorsSendsAnEmptyList() = runTest {
        var edit: String? = null
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                edit = request.body.toByteArray().decodeToString()
                respond("{}", HttpStatusCode.OK, jsonHeaders)
            } else {
                respond(playlist("""{"public":false}"""), HttpStatusCode.OK, jsonHeaders)
            }
        }

        client(engine).setPlaylistPublic("abc", true, "secret")

        assertEquals(
            Json.parseToJsonElement("""{"public":true,"collaborators":[]}"""),
            Json.parseToJsonElement(edit!!).jsonObject.getValue("playlist").jsonObject
                .getValue("extension").jsonObject.getValue(jspf),
        )
    }

    /** Sending an edit without knowing the collaborators would remove them. */
    @Test
    fun nothingIsEditedWhenTheCollaboratorsCannotBeRead() = runTest {
        for (read in listOf(HttpStatusCode.Forbidden to "{}", HttpStatusCode.OK to "not json")) {
            var edits = 0
            val engine = MockEngine { request ->
                if (request.method == HttpMethod.Post) edits++
                respond(read.second, read.first, jsonHeaders)
            }
            assertFailsWith<Exception> { client(engine).setPlaylistPublic("abc", true, "secret") }
            assertEquals(0, edits)
        }
    }

    @Test
    fun aRefusedEditThrows() = runTest {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                respond("{}", HttpStatusCode.Forbidden, jsonHeaders)
            } else {
                respond(playlist("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val error = assertFailsWith<ListenBrainzHttpException> { client(engine).setPlaylistPublic("abc", true, "secret") }
        assertEquals(403, error.status)
        assertFalse(error.message.isNullOrEmpty())
    }
}
