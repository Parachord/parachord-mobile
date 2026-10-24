package com.parachord.shared.resolver

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * #402 / #279 — the identity tier in front of the name gate, ported from desktop
 * parachord#1056 (`sync-providers/confidence-scoring.js#calculateConfidence` and
 * app.js `findBestMatch`). The first block mirrors desktop's
 * `tests/resolver/confidence-scoring.test.js` case for case: it is the
 * cross-platform contract. Runs on the JVM and the iOS simulator.
 */
class IdentityTierScoringTest {

    private val title = "Everything in Its Right Place"
    private val artist = "Radiohead"

    // ── scoreConfidence: desktop's contract ─────────────────────────────

    @Test fun matchingIsrcScores1EvenWhenTheNamesWouldFail() {
        assertEquals(
            1.0,
            scoreConfidence(
                title, artist, "Track 1", "Various Artists",
                targetIsrc = "GBAYK0000001", matchedIsrc = "gbayk0000001 ",
            ),
        )
    }

    @Test fun theTargetIsrcMayComeFromAResolverSource() {
        // Desktop walks the original track's `sources` for an ISRC. On mobile the
        // caller does that walk with pickTrackIsrc and passes the result.
        val picked = pickTrackIsrc(
            topLevelIsrc = null,
            sources = listOf(ResolvedSource(url = "u", sourceType = "spotify", resolver = "spotify", isrc = "GBAYK0000001")),
        )
        assertEquals(1.0, scoreConfidence(title, artist, "Y", "X", targetIsrc = picked, matchedIsrc = "GBAYK0000001"))
    }

    @Test fun matchingRecordingMbidScores1RegardlessOfCase() {
        assertEquals(
            1.0,
            scoreConfidence(
                title, artist, "Y", "X",
                targetMbid = "2C6CF4F6-1A4F-4B8B-9B67-4C6C6A2F1C11",
                matchedMbid = "2c6cf4f6-1a4f-4b8b-9b67-4c6c6a2f1c11",
            ),
        )
    }

    @Test fun aDifferentIsrcFallsThroughToTheNameGate() {
        assertEquals(
            0.95,
            scoreConfidence(title, artist, title, artist, targetIsrc = "GBAYK0000001", matchedIsrc = "USRC17607839"),
        )
    }

    @Test fun aOneSidedIsrcFallsThroughToTheNameGate() {
        assertEquals(0.5, scoreConfidence(title, artist, "Y", "X", targetIsrc = "GBAYK0000001"))
    }

    @Test fun aMalformedIsrcIsTreatedAsAbsent() {
        assertEquals(
            0.5,
            scoreConfidence(title, artist, "Y", "X", targetIsrc = "not-an-isrc", matchedIsrc = "not-an-isrc"),
        )
    }

    @Test fun aDifferentMbidFallsThroughToTheNameGate() {
        assertEquals(0.5, scoreConfidence(title, artist, "Y", "X", targetMbid = "abc", matchedMbid = "xyz"))
    }

    // ── scoreConfidence: edges beyond desktop's cases ───────────────────

    @Test fun mbidsNeedNoParticularShape() {
        // Desktop only lower-cases MBIDs, no shape check — so neither do we.
        assertEquals(1.0, scoreConfidence(title, artist, "Y", "X", targetMbid = "abc", matchedMbid = "ABC"))
    }

    @Test fun emptyIdsAreAbsentNotEqual() {
        assertEquals(0.5, scoreConfidence(title, artist, "Y", "X", targetMbid = "", matchedMbid = ""))
        assertEquals(0.5, scoreConfidence(title, artist, "Y", "X", targetIsrc = "", matchedIsrc = ""))
    }

    @Test fun withoutIdsScoringIsUnchanged() {
        assertEquals(0.95, scoreConfidence(title, artist, title, artist))
        assertEquals(0.5, scoreConfidence(title, artist, title, "Someone Else"))
    }

    // ── selectBestMatch: desktop findBestMatch tiers 1 and 2 ────────────

    private data class Hit(val title: String, val artist: String, val isrc: String? = null, val mbid: String? = null)

    private fun pick(results: List<Hit>, targetIsrc: String? = null, targetMbid: String? = null) =
        selectBestMatch(
            results, title, artist, { it.title }, { it.artist },
            targetMbid = targetMbid, targetIsrc = targetIsrc,
            mbidOf = { it.mbid }, isrcOf = { it.isrc },
        )

    private val nameMatch = Hit(title, artist)
    private val isrcHit = Hit("Track 1", "Various Artists", isrc = " gbayk0000001")
    private val mbidHit = Hit("Track 2", "Various Artists", mbid = "mbid-1")

    @Test fun anExactIsrcHitBeatsAnEarlierNameMatch() {
        assertSame(isrcHit, pick(listOf(nameMatch, isrcHit), targetIsrc = "GBAYK0000001"))
    }

    @Test fun anExactMbidHitBeatsAnIsrcHit() {
        assertSame(mbidHit, pick(listOf(isrcHit, mbidHit), targetIsrc = "GBAYK0000001", targetMbid = "mbid-1"))
    }

    @Test fun thePickerComparesMbidsExactly() {
        // Desktop's findBestMatch uses `===` (unlike calculateConfidence).
        assertSame(nameMatch, pick(listOf(mbidHit, nameMatch), targetMbid = "MBID-1"))
    }

    @Test fun noIdentityHitFallsBackToNameMatching() {
        assertSame(nameMatch, pick(listOf(isrcHit, nameMatch), targetIsrc = "USRC17607839"))
        assertNull(pick(listOf(isrcHit), targetIsrc = "USRC17607839"))
    }

    // ── end to end through the shared coordinator ───────────────────────

    /** One native resolver returning [source]; records the identity it was given. */
    private class IdentityRuntime(private val source: ResolvedSource) : ResolverRuntime {
        override val nativeResolverIds = setOf("localfiles")
        var seenIsrc: String? = null
        var seenMbid: String? = null

        override suspend fun resolveNative(
            resolverId: String, query: String, targetTitle: String?, targetArtist: String?, album: String?,
        ): ResolvedSource? = source

        override suspend fun resolveNative(
            resolverId: String, query: String, targetTitle: String?, targetArtist: String?, album: String?,
            targetIsrc: String?, targetMbid: String?,
        ): ResolvedSource? {
            seenIsrc = targetIsrc
            seenMbid = targetMbid
            return source
        }

        override suspend fun resolveAxe(
            resolverId: String, query: String, targetTitle: String?, targetArtist: String?, album: String?,
        ): ResolvedSource? = null
    }

    private fun coordinatorFor(runtime: ResolverRuntime) = ResolverCoordinator(
        runtime = runtime,
        scoring = ResolverScoring(
            getResolverOrder = { ResolverScoring.CANONICAL_RESOLVER_ORDER },
            getActiveResolvers = { emptyList() },
        ),
        getActive = { emptyList() },
        getDisabled = { emptySet() },
        getPlugins = { emptyList() },
        resolveSpotify = { null },
    )

    /** A rip tagged "Various Artists / Track 1": fails the name gate, carries the right ids. */
    private val rip = ResolvedSource(
        url = "file://rip.mp3", sourceType = "local", resolver = "localfiles", confidence = 0.9,
        matchedTitle = "Track 1", matchedArtist = "Various Artists",
        isrc = "GBAYK0000001", recordingMbid = "mbid-1",
    )

    @Test fun anIdentityMatchSurvivesTheFloorThatWouldDropItByName() = runTest {
        val runtime = IdentityRuntime(rip)
        val ranked = coordinatorFor(runtime).resolveRanked(
            "q", targetTitle = title, targetArtist = artist, targetIsrc = "GBAYK0000001",
        )
        assertEquals(listOf(1.0), ranked.map { it.confidence })
        assertEquals("GBAYK0000001", runtime.seenIsrc)
    }

    @Test fun withoutTheIdentityTheSameSourceIsDroppedByTheFloor() = runTest {
        val ranked = coordinatorFor(IdentityRuntime(rip)).resolveRanked("q", targetTitle = title, targetArtist = artist)
        assertEquals(emptyList(), ranked)
    }

    @Test fun anMbidMatchAlsoReachesTheRescore() = runTest {
        val runtime = IdentityRuntime(rip)
        val scored = coordinatorFor(runtime).resolveScored(
            "q", targetTitle = title, targetArtist = artist, targetMbid = "MBID-1",
        )
        assertEquals(listOf(1.0), scored.map { it.confidence })
        assertEquals("MBID-1", runtime.seenMbid)
    }
}
