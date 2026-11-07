package com.parachord.shared.playback

import com.parachord.shared.api.LastFmClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #534 — port of desktop's `tests/renderer/spinoff-pool.test.js`
 * (parachord#1281). Runs on the JVM and the iOS simulator, since both
 * platforms build their pool here.
 */
class SpinoffPoolTest {

    /** A fake Last.fm: answers by method, records every call as "method:limit". */
    private class FakeLastFm(
        val similarTracks: List<Pair<String, String>>? = emptyList(),
        val similarArtists: List<String> = emptyList(),
        val topTracks: Map<String, List<String>?> = emptyMap(),
        val fail: Set<String> = emptySet(),
    ) : SpinoffLastFm {
        val calls = mutableListOf<String>()
        val methods get() = calls.map { it.substringBefore(':') }

        override suspend fun similarTracks(artist: String, title: String, limit: Int): List<SpinoffLfmTrack>? {
            calls += "track.getsimilar:$limit"
            if ("track.getsimilar" in fail) return null
            // null stands for Last.fm's error 6, which the source reports as empty.
            return similarTracks.orEmpty().take(limit).map { (a, t) -> SpinoffLfmTrack(t, a, 0.5) }
        }

        override suspend fun similarArtists(artist: String, limit: Int): List<SpinoffLfmArtist>? {
            calls += "artist.getsimilar:$limit"
            if ("artist.getsimilar" in fail) return null
            return similarArtists.take(limit).mapIndexed { i, n -> SpinoffLfmArtist(n, 1 - i / 10.0) }
        }

        override suspend fun topTracks(artist: String, limit: Int): List<SpinoffLfmTrack>? {
            calls += "artist.gettoptracks:$limit"
            val names = topTracks[artist] ?: return null
            return names.take(limit).map { SpinoffLfmTrack(it, artist, null) }
        }
    }

    private val rng = { 0.0 } // deterministic shuffles
    private val twenty = (0 until 20).map { "Artist $it" to "Song $it" }
    private val artists = listOf(
        "James Arthur", "Lewis Capaldi", "Myles Smith", "Shawn Mendes",
        "James Bay", "James TW", "Niall Horan", "Calum Scott", "Ninth",
    )
    private val tops: Map<String, List<String>?> = artists.associateWith { a -> listOf("$a A", "$a B", "$a C", "$a D") }

    private fun noRunsBySameArtist(tracks: List<SpinoffCandidate>) =
        tracks.zipWithNext().none { (a, b) -> a.artist == b.artist }

    private suspend fun pool(lf: SpinoffLastFm) = buildSpinoffPool(lf, "Ed Sheeran", "Shape of You", rng)

    @Test fun constantsMatchTheIssue() {
        assertEquals(5, MIN_SIMILAR_TRACKS)
        assertEquals(8, FALLBACK_ARTISTS)
        assertEquals(3, TOP_TRACKS_PER_ARTIST)
        assertEquals(20, SIMILAR_TRACKS_LIMIT)
    }

    // ── dealRoundRobin ──────────────────────────────────────────────────

    @Test fun dealNeverPutsTwoTracksByTheSameArtistNextToEachOther() {
        val groups = listOf("A", "B", "C").map { a ->
            SpinoffArtistGroup(a, (1..3).map { SpinoffCandidate("$a$it", a, 0, SPINOFF_SOURCE_SIMILAR_ARTIST) })
        }
        val random = kotlin.random.Random(7)
        for (r in listOf({ 0.0 }, { 0.99 }, { 0.5 }, { random.nextDouble() })) {
            val dealt = dealRoundRobin(groups, r)
            assertEquals(9, dealt.size)
            assertTrue(noRunsBySameArtist(dealt))
        }
    }

    @Test fun unevenGroupsStillDealEveryTrack() {
        val dealt = dealRoundRobin(
            listOf(
                SpinoffArtistGroup("A", listOf(SpinoffCandidate("a1", "A", 0, ""))),
                SpinoffArtistGroup("B", listOf(SpinoffCandidate("b1", "B", 0, ""), SpinoffCandidate("b2", "B", 0, ""))),
            ),
            rng,
        )
        assertEquals(listOf("a1", "b1", "b2"), dealt.map { it.title }.sorted())
    }

    // ── buildSpinoffPool ────────────────────────────────────────────────

    @Test fun enoughSimilarTracksIsOneRequestAndNoFallback() = runTest {
        val lf = FakeLastFm(similarTracks = twenty)
        val p = pool(lf)
        assertFalse(p.failed)
        assertFalse(p.usedFallback)
        assertEquals(20, p.tracks.size)
        assertEquals(listOf("track.getsimilar:20"), lf.calls)
    }

    @Test fun noSimilarTracksBuildsThePoolFromEightSimilarArtistsThreeTracksEach() = runTest {
        val lf = FakeLastFm(similarTracks = emptyList(), similarArtists = artists, topTracks = tops)
        val p = pool(lf)
        assertTrue(p.usedFallback)
        assertEquals(24, p.tracks.size)
        assertEquals(8, p.tracks.map { it.artist }.toSet().size)
        assertFalse(p.tracks.any { it.artist == "Ninth" })
        assertTrue(noRunsBySameArtist(p.tracks))
        assertEquals(8, lf.methods.count { it == "artist.gettoptracks" })
        assertTrue(lf.calls.filter { it.startsWith("artist.gettoptracks") }.all { it.endsWith(":3") })
        assertTrue("artist.getsimilar:8" in lf.calls)
    }

    @Test fun aFewSimilarTracksComeFirstThenTheTopUp() = runTest {
        val lf = FakeLastFm(
            similarTracks = listOf("Rag n Bone Man" to "Human", "Passenger" to "Let Her Go"),
            similarArtists = artists, topTracks = tops,
        )
        val p = pool(lf)
        assertTrue(p.usedFallback)
        assertEquals(listOf("Human", "Let Her Go"), p.tracks.take(2).map { it.title }.sorted())
        assertEquals(26, p.tracks.size)
    }

    @Test fun twoSimilarTracksFallBack() = runTest {
        val lf = FakeLastFm(similarTracks = twenty.take(2), similarArtists = artists, topTracks = tops)
        assertTrue(pool(lf).usedFallback)
    }

    @Test fun exactlyTheThresholdDoesNotFallBack() = runTest {
        val lf = FakeLastFm(similarTracks = twenty.take(5), similarArtists = artists, topTracks = tops)
        val p = pool(lf)
        assertFalse(p.usedFallback)
        assertEquals(listOf("track.getsimilar"), lf.methods)
    }

    @Test fun aTrackLastFmDoesNotKnowCountsAsEmptyNotAsAFailure() = runTest {
        val lf = FakeLastFm(similarTracks = null, similarArtists = artists, topTracks = tops)
        val p = pool(lf)
        assertFalse(p.failed)
        assertEquals(24, p.tracks.size)
    }

    @Test fun aFailedSimilarTracksRequestFailsThePoolWithoutAskingAnythingElse() = runTest {
        val lf = FakeLastFm(fail = setOf("track.getsimilar"), similarArtists = artists, topTracks = tops)
        assertEquals(SpinoffPoolResult(emptyList(), failed = true, usedFallback = false), pool(lf))
        assertEquals(listOf("track.getsimilar"), lf.methods)
    }

    @Test fun aFailedSimilarArtistsRequestKeepsTheSimilarTracksItHad() = runTest {
        val lf = FakeLastFm(similarTracks = listOf("Passenger" to "Let Her Go"), fail = setOf("artist.getsimilar"))
        val p = pool(lf)
        assertFalse(p.failed)
        assertEquals(listOf("Let Her Go"), p.tracks.map { it.title })
    }

    @Test fun oneArtistsTopTracksFailingLeavesTheOthers() = runTest {
        val lf = FakeLastFm(similarTracks = emptyList(), similarArtists = artists, topTracks = tops + ("James Arthur" to null))
        val p = pool(lf)
        assertEquals(21, p.tracks.size)
        assertFalse(p.tracks.any { it.artist == "James Arthur" })
    }

    @Test fun leavesOutTheSeedTrackAndDuplicatesHoweverTheyAreSpelled() = runTest {
        val lf = FakeLastFm(
            similarTracks = listOf("ed sheeran" to "Shape Of You", "Passenger" to "Let Her Go"),
            similarArtists = listOf("Passenger", "Ed Sheeran"),
            topTracks = mapOf("Passenger" to listOf("Let Her Go", "Holes"), "Ed Sheeran" to listOf("Shape of You!", "Perfect")),
        )
        val keys = pool(lf).tracks.map { "${it.artist}|${it.title}" }
        assertFalse("ed sheeran|Shape Of You" in keys)
        assertFalse("Ed Sheeran|Shape of You!" in keys)
        assertEquals(1, keys.count { it.endsWith("Let Her Go") })
        assertTrue("Passenger|Holes" in keys)
        assertTrue("Ed Sheeran|Perfect" in keys)
    }

    @Test fun fallbackTracksCarryTheSimilarArtistsMatchAndSource() = runTest {
        val lf = FakeLastFm(similarTracks = emptyList(), similarArtists = artists, topTracks = tops)
        val tracks = pool(lf).tracks
        assertTrue(tracks.all { it.source == SPINOFF_SOURCE_SIMILAR_ARTIST })
        assertEquals(100, tracks.first { it.artist == "James Arthur" }.match)
        assertEquals(90, tracks.first { it.artist == "Lewis Capaldi" }.match)
    }

    @Test fun blankNamesFailWithoutARequest() = runTest {
        val lf = FakeLastFm(similarTracks = twenty)
        assertTrue(buildSpinoffPool(lf, "Ed Sheeran", " ", rng).failed)
        assertTrue(buildSpinoffPool(lf, "", "Song", rng).failed)
        assertEquals(emptyList(), lf.calls)
    }

    // ── checkSpinoffAvailable ───────────────────────────────────────────

    @Test fun aSongWithSimilarTracksIsOneRequest() = runTest {
        val lf = FakeLastFm(similarTracks = twenty)
        assertTrue(checkSpinoffAvailable(lf, "Ed Sheeran", "Shape of You"))
        assertEquals(listOf("track.getsimilar:1"), lf.calls)
    }

    @Test fun noSimilarTracksButSimilarArtistsIsAvailable() = runTest {
        val lf = FakeLastFm(similarTracks = emptyList(), similarArtists = artists)
        assertTrue(checkSpinoffAvailable(lf, "Ed Sheeran", "Shape of You"))
        assertEquals(listOf("track.getsimilar:1", "artist.getsimilar:1"), lf.calls)
    }

    @Test fun neitherIsUnavailable() = runTest {
        assertFalse(checkSpinoffAvailable(FakeLastFm(), "Ed Sheeran", "Shape of You"))
    }

    @Test fun aFailedRequestIsUnavailableAndAsksNothingMore() = runTest {
        val lf = FakeLastFm(fail = setOf("track.getsimilar"), similarArtists = artists)
        assertFalse(checkSpinoffAvailable(lf, "Ed Sheeran", "Shape of You"))
        assertEquals(listOf("track.getsimilar"), lf.methods)
    }

    @Test fun noNamesIsUnavailableWithNoRequests() = runTest {
        val lf = FakeLastFm(similarTracks = twenty)
        assertFalse(checkSpinoffAvailable(lf, "A", ""))
        assertEquals(emptyList(), lf.calls)
    }

    // ── LastFmSpinoffSource: telling error 6 from a real failure ─────────

    private fun source(status: HttpStatusCode = HttpStatusCode.OK, body: (String) -> String): Pair<LastFmSpinoffSource, MutableList<String>> {
        val urls = mutableListOf<String>()
        val mock = MockEngine { request ->
            urls += request.url.toString()
            respond(
                content = body(request.url.parameters["method"].orEmpty()),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return LastFmSpinoffSource(LastFmClient(HttpClient(mock)), apiKey = "KEY") to urls
    }

    @Test fun sourceReadsTheLists() = runTest {
        val (src, urls) = source { method ->
            when (method) {
                "track.getsimilar" -> """{"similartracks":{"track":[{"name":"Holes","match":1.0,"artist":{"name":"Passenger"}}],"@attr":{}}}"""
                "artist.getsimilar" -> """{"similarartists":{"artist":[{"name":"James Arthur","match":"0.982313"}]}}"""
                else -> """{"toptracks":{"track":[{"name":"Say You Won't Let Go","artist":{"name":"James Arthur"}}]}}"""
            }
        }
        assertEquals(listOf(SpinoffLfmTrack("Holes", "Passenger", 1.0)), src.similarTracks("Ed Sheeran", "Perfect", 20))
        assertEquals(listOf(SpinoffLfmArtist("James Arthur", 0.982313)), src.similarArtists("Ed Sheeran", 8))
        assertEquals(listOf(SpinoffLfmTrack("Say You Won't Let Go", "James Arthur", null)), src.topTracks("James Arthur", 3))
        assertTrue(urls.all { "api_key=KEY" in it })
    }

    @Test fun sourceEmptyListIsEmpty() = runTest {
        val (src, _) = source { """{"similartracks":{"track":[],"@attr":{"artist":"Ed Sheeran","track":"Shape of You"}}}""" }
        assertEquals(emptyList(), src.similarTracks("Ed Sheeran", "Shape of You", 20))
    }

    @Test fun sourceErrorSixIsEmpty() = runTest {
        val (src, _) = source { """{"error":6,"message":"Track not found","links":[]}""" }
        assertEquals(emptyList(), src.similarTracks("Nobody", "Nothing", 20))
        assertEquals(emptyList(), src.topTracks("Nobody", 3))
        assertEquals(emptyList(), src.similarArtists("Nobody", 8))
    }

    @Test fun sourceOtherErrorIsAFailure() = runTest {
        val (src, _) = source(HttpStatusCode.Forbidden) { """{"message":"Invalid API key","error":10}""" }
        assertNull(src.similarTracks("Ed Sheeran", "Shape of You", 20))
    }

    @Test fun sourceNonJsonIsAFailure() = runTest {
        val (src, _) = source(HttpStatusCode.BadGateway) { "<html>Bad Gateway</html>" }
        assertNull(src.similarTracks("Ed Sheeran", "Shape of You", 20))
    }

    @Test fun sourceWithNoKeyAsksNothing() = runTest {
        val urls = mutableListOf<String>()
        val mock = MockEngine { r -> urls += r.url.toString(); respond("{}") }
        val src = LastFmSpinoffSource(LastFmClient(HttpClient(mock)), apiKey = "")
        assertNull(src.similarTracks("Ed Sheeran", "Shape of You", 20))
        assertEquals(emptyList(), urls)
    }
}
