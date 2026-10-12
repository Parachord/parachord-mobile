package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ListenBrainzClient.resolveRecordingMbid]: the mapper first, the labs
 * `acr-lookup` dataset only when the mapper can't be reached, and
 * "unreachable" (never "miss") when neither answers. Port of desktop's
 * `tests/resolver/mbid-lookup.test.js` (parachord#991 / parachord-mobile#394),
 * routed by host so the mapper and labs can be scripted independently.
 */
class ListenBrainzMbidLookupTest {

    private val mbid = "70595637-9310-45f2-a266-58f8de4874a7"
    private val artistMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
    private val releaseMbid = "07aab296-9a40-42a9-8d1f-6b06aa032e8a"

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private val mapperHit = """
        {"recording_mbid":"$mbid","artist_credit_mbids":["$artistMbid"],"release_mbid":"$releaseMbid",
         "recording_name":"Creep","artist_credit_name":"Radiohead","release_name":"Pablo Honey","confidence":0.95}
    """.trimIndent()

    // Real acr-lookup row shape (labs.api.listenbrainz.org/acr-lookup/json).
    private val acrRow = """
        {"index":0,"artist_credit_arg":"radiohead","recording_arg":"creep",
         "artist_credit_name":"Radiohead","release_name":"Pablo Honey","recording_name":"Creep",
         "artist_credit_id":66,"artist_mbids":["$artistMbid"],"release_mbid":"$releaseMbid","recording_mbid":"$mbid"}
    """.trimIndent()

    private class Scripted(
        val mapper: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>,
        val labs: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>,
    )

    private val notCalled: suspend (HttpRequestData) -> Pair<HttpStatusCode, String> =
        { throw IllegalStateException("should not be called") }

    /** A network/TLS-style failure: the request never gets a response. */
    private val connectionError: suspend (HttpRequestData) -> Pair<HttpStatusCode, String> =
        { throw IllegalStateException("Failed to fetch") }

    /**
     * A scripted engine plus our OWN request log. `MockEngine.requestHistory`
     * only records a request whose handler RETURNED a response — a simulated
     * TLS failure (handler throws) leaves no trace there, which is exactly the
     * call these tests must prove happened. So every request is logged before
     * it is dispatched.
     */
    private class Rig(val engine: MockEngine, val seen: MutableList<HttpRequestData>) {
        val hosts get() = seen.map { it.url.host }
    }

    private fun engine(s: Scripted): Rig {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            val (status, body) = when (request.url.host) {
                "mapper.listenbrainz.org" -> s.mapper(request)
                "labs.api.listenbrainz.org" -> s.labs(request)
                else -> error("unexpected host ${request.url.host}")
            }
            respond(body, status, jsonHeaders)
        }
        return Rig(engine, seen)
    }

    private fun client(rig: Rig) = ListenBrainzClient(HttpClient(rig.engine))

    @Test
    fun mapperHit_labsIsNeverAsked() = runTest {
        val engine = engine(Scripted(mapper = { HttpStatusCode.OK to mapperHit }, labs = notCalled))

        val r = client(engine).resolveRecordingMbid("Radiohead", "Creep", "Pablo Honey")

        assertIs<MbidLookupOutcome.Hit>(r)
        assertEquals(MbidLookupSource.MAPPER, r.result.source)
        assertEquals(mbid, r.result.recordingMbid)
        assertEquals(artistMbid, r.result.artistMbid)
        assertEquals(0.95, r.result.confidence)
        assertEquals(listOf("mapper.listenbrainz.org"), engine.hosts)
    }

    @Test
    fun mapperAnswersNoMatch_isAMapperMiss_labsNotConsulted() = runTest {
        val engine = engine(Scripted(mapper = { HttpStatusCode.OK to "{}" }, labs = notCalled))

        val r = client(engine).resolveRecordingMbid("Nobody", "Nothing")

        assertEquals(MbidLookupOutcome.Miss(MbidLookupSource.MAPPER), r)
        assertEquals(listOf("mapper.listenbrainz.org"), engine.hosts)
    }

    @Test
    fun mapperTlsFailure_theSept2026Outage_fallsThroughToAcrLookup() = runTest {
        val engine = engine(Scripted(mapper = connectionError, labs = { HttpStatusCode.OK to "[$acrRow]" }))

        val r = client(engine).resolveRecordingMbid("radiohead", "creep")

        assertIs<MbidLookupOutcome.Hit>(r)
        assertEquals(MbidLookupSource.ACR_LOOKUP, r.result.source)
        assertEquals(mbid, r.result.recordingMbid)
        // acr's `artist_mbids` is the mapper's `artist_credit_mbids`.
        assertEquals(artistMbid, r.result.artistMbid)
        assertEquals(releaseMbid, r.result.releaseMbid)
        assertEquals("Radiohead", r.result.artistCreditName)
        assertEquals(ListenBrainzClient.ACR_CONFIDENCE, r.result.confidence)
        assertEquals(listOf("mapper.listenbrainz.org", "labs.api.listenbrainz.org"), engine.hosts)
    }

    @Test
    fun mapper502_alsoFallsThroughToAcrLookup() = runTest {
        val engine = engine(Scripted(
            mapper = { HttpStatusCode.BadGateway to "" },
            labs = { HttpStatusCode.OK to "[$acrRow]" },
        ))

        val r = client(engine).resolveRecordingMbid("Radiohead", "Creep")

        assertIs<MbidLookupOutcome.Hit>(r)
        assertEquals(MbidLookupSource.ACR_LOOKUP, r.result.source)
    }

    @Test
    fun mapperDown_acrHasNoRow_isAnAcrMiss() = runTest {
        val engine = engine(Scripted(mapper = connectionError, labs = { HttpStatusCode.OK to "[]" }))

        val r = client(engine).resolveRecordingMbid("Nobody", "Nothing")

        assertEquals(MbidLookupOutcome.Miss(MbidLookupSource.ACR_LOOKUP), r)
    }

    @Test
    fun neitherAnswers_isUnreachable_notAMiss() = runTest {
        val engine = engine(Scripted(mapper = connectionError, labs = { HttpStatusCode.ServiceUnavailable to "" }))

        val r = client(engine).resolveRecordingMbid("Radiohead", "Creep")

        assertIs<MbidLookupOutcome.Unreachable>(r)
        assertEquals("Failed to fetch", r.errors[MbidLookupSource.MAPPER])
        assertEquals("HTTP 503", r.errors[MbidLookupSource.ACR_LOOKUP])
    }

    @Test
    fun blankArtistOrTitle_isUnreachableWithoutAnyRequest() = runTest {
        val engine = engine(Scripted(mapper = { HttpStatusCode.OK to mapperHit }, labs = { HttpStatusCode.OK to "[$acrRow]" }))
        val c = client(engine)

        assertIs<MbidLookupOutcome.Unreachable>(c.resolveRecordingMbid("", "Creep"))
        assertIs<MbidLookupOutcome.Unreachable>(c.resolveRecordingMbid("Radiohead", "  "))
        assertTrue(engine.seen.isEmpty())
    }

    @Test
    fun acrRowWithoutAValidRecordingMbid_isNoMatch() = runTest {
        val bad = acrRow.replace(mbid, "not-a-uuid")
        val engine = engine(Scripted(mapper = connectionError, labs = { HttpStatusCode.OK to "[$bad]" }))

        assertEquals(MbidLookupOutcome.Miss(MbidLookupSource.ACR_LOOKUP), client(engine).resolveRecordingMbid("Radiohead", "Creep"))
    }

    @Test
    fun mapperResponseWithoutAValidRecordingMbid_isAMapperMiss() = runTest {
        val bad = mapperHit.replace(mbid, "not-a-uuid")
        val engine = engine(Scripted(mapper = { HttpStatusCode.OK to bad }, labs = notCalled))

        assertEquals(MbidLookupOutcome.Miss(MbidLookupSource.MAPPER), client(engine).resolveRecordingMbid("Radiohead", "Creep"))
    }

    @Test
    fun releaseName_isSentToBothServicesOnlyWhenAlbumGiven() = runTest {
        val engine = engine(Scripted(mapper = connectionError, labs = { HttpStatusCode.OK to "[]" }))
        val c = client(engine)

        c.resolveRecordingMbid("Radiohead", "Creep", "Pablo Honey")
        c.resolveRecordingMbid("Radiohead", "Creep")

        // Each call = one (throwing) mapper request + one labs request.
        assertEquals(4, engine.seen.size)
        val withAlbum = engine.seen.take(2)
        val withoutAlbum = engine.seen.drop(2)
        withAlbum.forEach { req ->
            assertEquals("Radiohead", req.url.parameters["artist_credit_name"])
            assertEquals("Creep", req.url.parameters["recording_name"])
            assertEquals("Pablo Honey", req.url.parameters["release_name"])
        }
        withoutAlbum.forEach { req -> assertNull(req.url.parameters["release_name"]) }
    }

    @Test
    fun mbidMapperLookupCompat_returnsHitResult_andCollapsesMissAndUnreachableToNull() = runTest {
        val hit = client(engine(Scripted(mapper = { HttpStatusCode.OK to mapperHit }, labs = notCalled)))
        assertEquals(mbid, hit.mbidMapperLookup("Radiohead", "Creep")?.recordingMbid)

        val miss = client(engine(Scripted(mapper = { HttpStatusCode.OK to "{}" }, labs = notCalled)))
        assertNull(miss.mbidMapperLookup("Nobody", "Nothing"))

        val down = client(engine(Scripted(mapper = connectionError, labs = { HttpStatusCode.ServiceUnavailable to "" })))
        assertNull(down.mbidMapperLookup("Radiohead", "Creep"))
    }
}
