package com.parachord.shared.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of desktop tests/renderer/artist-bio.test.js, Wikidata part (parachord#1133, #429). */
class WikidataIdLookupTest {
    private val wildPink = "94fdf751-0644-4b78-9e72-dbdce539b5b7"
    private val json = headersOf("Content-Type", "application/json")
    private fun hit(id: String) = """{"query":{"search":[{"title":"$id"}]}}"""
    private val noHit = """{"query":{"search":[]}}"""

    private var wikidataRequests = 0
    private var mbRequests = 0

    /** [wikidata]: status + body, or null = unreachable. [mb]: the url-rel, or throws. */
    private fun kotlinx.coroutines.test.TestScope.lookup(
        wikidata: (() -> Pair<HttpStatusCode, String>)?,
        mb: () -> String?,
    ) = WikidataIdLookup(
        HttpClient(MockEngine { req ->
            assertEquals("www.wikidata.org", req.url.host)
            wikidataRequests++
            val (status, body) = wikidata?.invoke() ?: error("Failed to fetch")
            respond(body, status, json)
        }),
        musicBrainzWikidataUrl = { mbRequests++; mb() },
        // Supervisor scope on the test scheduler, so a propagated MusicBrainz
        // failure reaches the caller instead of failing the test outright.
        scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
    )

    @Test fun isMbidAcceptsOnlyAUuid() {
        assertTrue(WikidataIdLookup.isMbid(wildPink))
        assertTrue(WikidataIdLookup.isMbid(wildPink.uppercase()))
        assertFalse(WikidataIdLookup.isMbid("Wild Pink"))
        assertFalse(WikidataIdLookup.isMbid("$wildPink OR haswbstatement:P31"))
        assertFalse(WikidataIdLookup.isMbid(null))
    }

    @Test fun theSearchUrlFiltersOnP434AndNothingElse() {
        val url = WikidataIdLookup.wikidataSearchUrl(wildPink.uppercase())!!
        assertTrue(url.startsWith("https://www.wikidata.org/w/api.php?"))
        assertTrue(url.contains("srsearch=haswbstatement%3AP434%3D$wildPink"))
        assertTrue(url.contains("origin=*"))
        assertNull(WikidataIdLookup.wikidataSearchUrl("not an mbid"))
    }

    @Test fun readsOnlyAQIdFromMusicBrainzUrlRels() {
        assertEquals("Q25056425", WikidataIdLookup.wikidataIdFromMbRelation("https://www.wikidata.org/wiki/Q25056425"))
        assertNull(WikidataIdLookup.wikidataIdFromMbRelation("https://example.com/Wild_Pink"))
        assertNull(WikidataIdLookup.wikidataIdFromMbRelation(null))
    }

    @Test fun asksWikidataFirst_andNeverTouchesMusicBrainzOnAHit() = runTest {
        val l = lookup({ HttpStatusCode.OK to hit("Q25056425") }) { error("MusicBrainz is down") }
        assertEquals("Q25056425", l.lookup(wildPink))
        assertEquals(0, mbRequests)
    }

    @Test fun onlyAQIdCountsAsAHit() = runTest {
        val l = lookup({ HttpStatusCode.OK to hit("Wild Pink") }) { null }
        assertNull(l.lookup(wildPink))
        assertEquals(1, mbRequests, "a non-Q title falls back to MusicBrainz")
    }

    @Test fun fallsBackToMusicBrainzOnAMiss_aNon2xx_orUnreachableWikidata() = runTest {
        for (wikidata in listOf<(() -> Pair<HttpStatusCode, String>)?>(
            { HttpStatusCode.OK to noHit },
            { HttpStatusCode.ServiceUnavailable to "{}" },
            null,
        )) {
            val l = lookup(wikidata) { "https://www.wikidata.org/wiki/Q42" }
            assertEquals("Q42", l.lookup(wildPink))
        }
    }

    @Test fun nullWhenNeitherHasIt_andAMusicBrainzFailurePropagates() = runTest {
        assertNull(lookup({ HttpStatusCode.OK to noHit }) { null }.lookup(wildPink))
        assertFailsWith<IllegalStateException> {
            lookup({ HttpStatusCode.OK to noHit }) { error("Failed to fetch") }.lookup(wildPink)
        }
    }

    @Test fun noRequestAtAllWithoutAValidMbid() = runTest {
        val l = lookup({ HttpStatusCode.OK to hit("Q1") }) { "https://www.wikidata.org/wiki/Q1" }
        assertNull(l.lookup(null))
        assertNull(l.lookup("Wild Pink"))
        assertEquals(0, wikidataRequests + mbRequests)
    }

    @Test fun aFoundIdIsKept_concurrentLookupsShareARequest_aMissIsRetried() = runTest {
        var answer = noHit
        val l = lookup({ HttpStatusCode.OK to answer }) { null }
        assertNull(l.lookup(wildPink))
        assertEquals(1, wikidataRequests)

        answer = hit("Q25056425")
        val both = listOf(async { l.lookup(wildPink) }, async { l.lookup(wildPink.uppercase()) }).awaitAll()
        assertEquals(listOf("Q25056425", "Q25056425"), both)
        assertEquals(2, wikidataRequests)

        l.lookup(wildPink)
        assertEquals(2, wikidataRequests)
    }
}
