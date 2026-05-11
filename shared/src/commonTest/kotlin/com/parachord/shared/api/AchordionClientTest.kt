package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AchordionClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildClient(engine: MockEngine, token: String = "test-token"): AchordionClient {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return AchordionClient(httpClient, token)
    }

    // ── fetchEntityLink ─────────────────────────────────────────────

    @Test
    fun fetchEntityLink_returnsCanonicalUrl_whenApiReturns200() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"url":"https://achordion.xyz/recording/slowdive-sugar","name":"Sugar For The Pill"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine)
        val result = client.fetchEntityLink(EntityType.Track, "abc-mbid")
        assertEquals("https://achordion.xyz/recording/slowdive-sugar", result?.url)
        assertEquals("Sugar For The Pill", result?.name)
    }

    @Test
    fun fetchEntityLink_returnsNull_on404() = runTest {
        val engine = MockEngine { _ ->
            respond(content = """{"error":"Not Found"}""", status = HttpStatusCode.NotFound)
        }
        val client = buildClient(engine)
        assertNull(client.fetchEntityLink(EntityType.Track, "abc-mbid"))
    }

    @Test
    fun fetchEntityLink_returnsNull_onMalformedResponse() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"unexpected":"shape"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine)
        // SerializationException on missing required `url` field → swallowed → null.
        assertNull(client.fetchEntityLink(EntityType.Track, "abc-mbid"))
    }

    @Test
    fun fetchEntityLink_sendsBearerTokenInAuthHeader() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond(
                """{"url":"https://achordion.xyz/recording/x"}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine, token = "secret-bearer")
        client.fetchEntityLink(EntityType.Track, "abc-mbid")
        assertEquals("Bearer secret-bearer", seenAuth)
    }

    @Test
    fun fetchEntityLink_encodesMbidAndTypeInQueryString() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { req ->
            seenUrl = req.url.toString()
            respond(
                """{"url":"https://achordion.xyz/release-group/x"}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine)
        client.fetchEntityLink(EntityType.ReleaseGroup, "554b417d-8885-41e6-86c7-ae935e62d571")
        val url = checkNotNull(seenUrl)
        assertEquals(true, url.startsWith("https://achordion.xyz/api/entity-link?"))
        assertEquals(true, url.contains("type=release-group"))
        assertEquals(true, url.contains("mbid=554b417d-8885-41e6-86c7-ae935e62d571"))
    }

    @Test
    fun fetchEntityLink_includeNamesAddsQueryParam() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { req ->
            seenUrl = req.url.toString()
            respond(
                """{"url":"https://achordion.xyz/x"}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine)
        client.fetchEntityLink(EntityType.Track, "abc", includeNames = true)
        assertEquals(true, seenUrl?.contains("include=names"))
    }

    @Test
    fun fetchEntityLink_emptyToken_returnsNull_doesNotHitNetwork() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls += 1
            respond("""{"url":"x"}""", HttpStatusCode.OK)
        }
        val client = buildClient(engine, token = "")
        assertNull(client.fetchEntityLink(EntityType.Track, "abc"))
        assertEquals(0, calls)
    }

    @Test
    fun fetchEntityLink_after401_subsequentCallsShortCircuit() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls += 1
            respond(content = "", status = HttpStatusCode.Unauthorized)
        }
        val client = buildClient(engine)
        assertNull(client.fetchEntityLink(EntityType.Track, "first"))
        assertNull(client.fetchEntityLink(EntityType.Track, "second"))
        assertNull(client.fetchEntityLink(EntityType.Track, "third"))
        assertEquals(1, calls)   // only the first call hits the network
    }

    // ── submitTrackLinks ────────────────────────────────────────────

    private val sampleRequest = SubmitTrackLinksRequest(
        mbid = "abc-mbid-123",
        links = listOf(
            TrackLink(url = "https://open.spotify.com/track/X", host = "spotify.com", label = "Spotify"),
        ),
        trackName = "Song",
        artistName = "Artist",
        albumName = "Album",
    )

    @Test
    fun submitTrackLinks_returnsOk_whenApiReturns200() = runTest {
        val engine = MockEngine { _ -> respond("", HttpStatusCode.OK) }
        val client = buildClient(engine)
        assertEquals(SubmitResult.Ok, client.submitTrackLinks(sampleRequest))
    }

    @Test
    fun submitTrackLinks_returnsNoLinks_whenLinksListIsEmpty() = runTest {
        val engine = MockEngine { _ -> error("should not be called") }
        val client = buildClient(engine)
        val result = client.submitTrackLinks(sampleRequest.copy(links = emptyList()))
        assertEquals(SubmitResult.NoLinks, result)
    }

    @Test
    fun submitTrackLinks_returnsNoMbid_whenMbidIsBlank() = runTest {
        val engine = MockEngine { _ -> error("should not be called") }
        val client = buildClient(engine)
        val result = client.submitTrackLinks(sampleRequest.copy(mbid = ""))
        assertEquals(SubmitResult.NoMbid, result)
    }

    @Test
    fun submitTrackLinks_returnsAlreadySubmitted_onSecondCallSameMbid() = runTest {
        val engine = MockEngine { _ -> respond("", HttpStatusCode.OK) }
        val client = buildClient(engine)
        assertEquals(SubmitResult.Ok, client.submitTrackLinks(sampleRequest))
        assertEquals(SubmitResult.AlreadySubmitted, client.submitTrackLinks(sampleRequest))
    }

    @Test
    fun submitTrackLinks_dedupKeyIsLowercaseMbid() = runTest {
        val engine = MockEngine { _ -> respond("", HttpStatusCode.OK) }
        val client = buildClient(engine)
        client.submitTrackLinks(sampleRequest.copy(mbid = "ABC-MBID-123"))
        val second = client.submitTrackLinks(sampleRequest.copy(mbid = "abc-mbid-123"))
        assertEquals(SubmitResult.AlreadySubmitted, second)
    }

    @Test
    fun submitTrackLinks_returnsAuthFailed_on401() = runTest {
        val engine = MockEngine { _ -> respond("", HttpStatusCode.Unauthorized) }
        val client = buildClient(engine)
        assertEquals(SubmitResult.AuthFailed, client.submitTrackLinks(sampleRequest))
    }

    @Test
    fun submitTrackLinks_authFailedShortCircuitsSubsequentCalls() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls += 1
            respond("", HttpStatusCode.Unauthorized)
        }
        val client = buildClient(engine)
        client.submitTrackLinks(sampleRequest)
        val again = client.submitTrackLinks(sampleRequest.copy(mbid = "different-mbid"))
        assertEquals(SubmitResult.AuthFailed, again)
        assertEquals(1, calls)
    }

    @Test
    fun submitTrackLinks_returnsHttpError_onOther4xx() = runTest {
        val engine = MockEngine { _ -> respond("", HttpStatusCode.UnprocessableEntity) }
        val client = buildClient(engine)
        val result = client.submitTrackLinks(sampleRequest)
        assertEquals(SubmitResult.HttpError(422), result)
    }

    @Test
    fun submitTrackLinks_sendsBearerTokenAndJsonBody() = runTest {
        var seenAuth: String? = null
        var seenContentType: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            seenContentType = req.body.contentType?.toString() ?: req.headers["Content-Type"]
            respond("", HttpStatusCode.OK)
        }
        val client = buildClient(engine, token = "tok-xyz")
        client.submitTrackLinks(sampleRequest)
        assertEquals("Bearer tok-xyz", seenAuth)
        assertEquals(true, seenContentType?.startsWith("application/json"))
    }

    @Test
    fun submitTrackLinks_emptyToken_returnsAuthFailed_doesNotHitNetwork() = runTest {
        var calls = 0
        val engine = MockEngine { _ -> calls += 1; respond("", HttpStatusCode.OK) }
        val client = buildClient(engine, token = "")
        assertEquals(SubmitResult.AuthFailed, client.submitTrackLinks(sampleRequest))
        assertEquals(0, calls)
    }
}
