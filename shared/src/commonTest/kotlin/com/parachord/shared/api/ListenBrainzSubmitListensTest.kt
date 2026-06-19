package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression guard for the `duration_ms` rule in [ListenBrainzClient.submitListens].
 *
 * ListenBrainz rejects an ENTIRE listen with HTTP 400 when `additional_info.duration_ms`
 * is present and <= 0. Metadata-only tracks (Track.duration == 0) flow through scrobble
 * for BOTH Listen Along (#235/#246) and Spinoff (#231) — neither populates a duration on
 * the ephemeral Track — so the client MUST omit the field rather than send 0. This was the
 * Listen Along "scrobbles never reached LB" bug; Spinoff inherits the same client, so this
 * test protects both. See ListenBrainzClient.submitListens (`if (durationMs > 0)`).
 */
class ListenBrainzSubmitListensTest {

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private fun client(engine: MockEngine) = ListenBrainzClient(
        HttpClient(engine) { install(ContentNegotiation) { json() } },
    )

    private suspend fun bodyJson(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

    /** payload[0].track_metadata.additional_info — where duration_ms lives (or doesn't). */
    private fun additionalInfo(body: JsonObject): JsonObject =
        body["payload"]!!.jsonArray[0].jsonObject["track_metadata"]!!.jsonObject["additional_info"]!!.jsonObject

    @Test
    fun submitListens_omitsDurationMs_whenZero() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req -> captured = req; respond("{}", io.ktor.http.HttpStatusCode.OK, jsonHeaders) }
        val ok = client(engine).submitListens(
            token = "tok", artist = "Tundra 212", title = "Clover",
            durationMs = 0, listenedAt = 1_700_000_000L,
        )
        assertTrue(ok)
        assertNull(additionalInfo(bodyJson(captured!!))["duration_ms"],
            "duration_ms must be OMITTED when 0 (LB 400s otherwise) — the Listen Along/Spinoff case")
    }

    @Test
    fun submitListens_omitsDurationMs_whenNull() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req -> captured = req; respond("{}", io.ktor.http.HttpStatusCode.OK, jsonHeaders) }
        client(engine).submitListens(
            token = "tok", artist = "A", title = "T",
            durationMs = null, listenedAt = 1_700_000_000L,
        )
        assertNull(additionalInfo(bodyJson(captured!!))["duration_ms"])
    }

    @Test
    fun submitListens_includesDurationMs_whenPositive() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req -> captured = req; respond("{}", io.ktor.http.HttpStatusCode.OK, jsonHeaders) }
        client(engine).submitListens(
            token = "tok", artist = "A", title = "T",
            durationMs = 213_000L, listenedAt = 1_700_000_000L,
        )
        assertEquals(213_000L, additionalInfo(bodyJson(captured!!))["duration_ms"]!!.jsonPrimitive.long)
    }
}
