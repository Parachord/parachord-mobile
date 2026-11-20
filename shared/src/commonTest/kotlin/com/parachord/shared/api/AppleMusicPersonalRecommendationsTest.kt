package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The plugin host's `appleMusicPersonalRecommendations` call (`GET /v1/me/recommendations`). */
class AppleMusicPersonalRecommendationsTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Rig(val catalog: AppleMusicCatalog, val urls: MutableList<String>, val headers: MutableList<Pair<String?, String?>>)

    private fun rig(token: String = "DEV", status: HttpStatusCode = HttpStatusCode.OK): Rig {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Pair<String?, String?>>()
        val engine = MockEngine { req ->
            urls += req.url.toString()
            headers += req.headers[HttpHeaders.Authorization] to req.headers["Music-User-Token"]
            respond("""{"data":[{"id":"r1"}]}""", status, json)
        }
        return Rig(AppleMusicCatalog(HttpClient(engine), token, MusicKitCatalogLimiter()), urls, headers)
    }

    @Test
    fun sendsBothTokensAndTheClampedLimit() = runTest {
        val r = rig()
        r.catalog.personalRecommendations(99, "MUT")
        assertEquals(listOf("https://api.music.apple.com/v1/me/recommendations?limit=30"), r.urls)
        assertEquals("Bearer DEV" to "MUT", r.headers.single())
        r.catalog.personalRecommendations(0, "MUT")
        assertTrue(r.urls.last().endsWith("limit=1"))
    }

    @Test
    fun noUserTokenIsNotReady() = runTest {
        val r = rig()
        assertEquals(PersonalRecommendationsResult.NotReady("not-signed-in"), r.catalog.personalRecommendations(30, null))
        assertEquals(PersonalRecommendationsResult.NotReady("not-signed-in"), r.catalog.personalRecommendations(30, " "))
        assertTrue(r.urls.isEmpty())
    }

    @Test
    fun noDeveloperTokenIsNotReady() = runTest {
        val r = rig(token = "")
        assertEquals(PersonalRecommendationsResult.NotReady("no-developer-token"), r.catalog.personalRecommendations(30, "MUT"))
        assertTrue(r.urls.isEmpty())
    }

    @Test
    fun a401IsNotReadyTokenRefused() = runTest {
        assertEquals(
            PersonalRecommendationsResult.NotReady("token-refused"),
            rig(status = HttpStatusCode.Unauthorized).catalog.personalRecommendations(30, "MUT"),
        )
        assertEquals(
            PersonalRecommendationsResult.NotReady("token-refused"),
            rig(status = HttpStatusCode.Forbidden).catalog.personalRecommendations(30, "MUT"),
        )
    }

    @Test
    fun a500IsAFailure() = runTest {
        assertEquals(
            PersonalRecommendationsResult.Failed(500),
            rig(status = HttpStatusCode.InternalServerError).catalog.personalRecommendations(30, "MUT"),
        )
    }

    @Test
    fun a200HandsBackTheBody() = runTest {
        val result = rig().catalog.personalRecommendations(30, "MUT")
        assertEquals(PersonalRecommendationsResult.Ok("""{"data":[{"id":"r1"}]}"""), result)
    }

    @Test
    fun theEnvelopeTheHostsHandToJs() {
        assertEquals("""{"ok":{"a":1}}""", PersonalRecommendationsResult.Ok("""{"a":1}""").toJsEnvelope())
        assertEquals("""{"notReady":"not-signed-in"}""", PersonalRecommendationsResult.NotReady("not-signed-in").toJsEnvelope())
        assertEquals("""{"failed":500}""", PersonalRecommendationsResult.Failed(500).toJsEnvelope())
        // A 2xx body that isn't JSON would be a syntax error in the JS call, and the hook would never answer.
        assertEquals("""{"failed":0}""", PersonalRecommendationsResult.Ok("<html>oops</html>").toJsEnvelope())
        assertEquals("""{"failed":0}""", PersonalRecommendationsResult.Ok("").toJsEnvelope())
        assertEquals("""{"failed":0}""", PersonalRecommendationsResult.Ok("""{"a":oops}""").toJsEnvelope())
        assertEquals(
            """{"ok":{"data":[{"n":-1.5e3,"b":true,"x":null}]}}""",
            PersonalRecommendationsResult.Ok("""{"data":[{"n":-1.5e3,"b":true,"x":null}]}""").toJsEnvelope(),
        )
    }
}
