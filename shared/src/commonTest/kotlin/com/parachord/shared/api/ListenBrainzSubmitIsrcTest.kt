package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MockEngine tests for the `isrc` field on the ListenBrainz listen
 * submission (`POST /1/submit-listens`). Lets Achordion link a
 * mapper-less scrobble straight to the exact recording via the ISRC
 * instead of a fuzzy artist/title search.
 *
 * Rules: field name exactly `isrc` at `track_metadata.additional_info.isrc`,
 * normalized UPPERCASE, sent only when it matches the canonical ISRC shape
 * (`^[A-Z]{2}[A-Z0-9]{3}\d{7}$`); otherwise the field is omitted entirely
 * (never null / "" / malformed).
 */
class ListenBrainzSubmitIsrcTest {

    private fun client(engine: MockEngine) = ListenBrainzClient(
        HttpClient(engine) { install(ContentNegotiation) { json() } },
    )

    private suspend fun additionalInfo(request: HttpRequestData): JsonObject {
        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
        return body["payload"]!!.jsonArray[0].jsonObject["track_metadata"]!!
            .jsonObject["additional_info"]!!.jsonObject
    }

    private val okHeaders = headersOf("Content-Type", "application/json")

    @Test
    fun validIsrc_isUppercasedIntoAdditionalInfo_andVersionBumped() = runTest {
        var ai: JsonObject? = null
        val engine = MockEngine { request ->
            ai = additionalInfo(request)
            respond("""{"status":"ok"}""", HttpStatusCode.OK, okHeaders)
        }
        client(engine).submitListens(
            token = "tok", artist = "Spoon", title = "The Underdog",
            listenedAt = 1_700_000_000L,
            isrc = "usmex0800097", // lowercase input
        )
        assertEquals("USMEX0800097", ai!!["isrc"]!!.jsonPrimitive.content)
        // submission_client_version must be bumped off the old "1.0.0".
        assertEquals("1.1.0", ai!!["submission_client_version"]!!.jsonPrimitive.content)
    }

    @Test
    fun malformedIsrc_isOmitted() = runTest {
        var ai: JsonObject? = null
        val engine = MockEngine { request ->
            ai = additionalInfo(request)
            respond("""{"status":"ok"}""", HttpStatusCode.OK, okHeaders)
        }
        client(engine).submitListens(
            token = "tok", artist = "Spoon", title = "The Underdog",
            listenedAt = 1_700_000_000L,
            isrc = "NOT-AN-ISRC",
        )
        assertNull(ai!!["isrc"])
    }

    @Test
    fun blankOrNullIsrc_isOmitted() = runTest {
        var aiBlank: JsonObject? = null
        var aiNull: JsonObject? = null
        client(MockEngine { request -> aiBlank = additionalInfo(request); respond("""{"status":"ok"}""", HttpStatusCode.OK, okHeaders) })
            .submitListens(token = "tok", artist = "A", title = "B", listenedAt = 1L, isrc = "   ")
        client(MockEngine { request -> aiNull = additionalInfo(request); respond("""{"status":"ok"}""", HttpStatusCode.OK, okHeaders) })
            .submitListens(token = "tok", artist = "A", title = "B", listenedAt = 1L, isrc = null)
        assertNull(aiBlank!!["isrc"])
        assertNull(aiNull!!["isrc"])
    }
}
