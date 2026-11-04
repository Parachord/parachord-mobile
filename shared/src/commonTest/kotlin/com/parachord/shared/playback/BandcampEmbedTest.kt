package com.parachord.shared.playback

import com.parachord.shared.resolver.pluginBandcampTrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #513: the URL rules and state rules ported from desktop's bandcamp-embed-url.js and adapter. */
class BandcampEmbedTest {

    private val single =
        "https://bandcamp.com/EmbeddedPlayer/track=3635703524/size=large/bgcol=333333/linkcol=ffffff/tracklist=false/artwork=small/transparent=true/"

    private fun state(
        t: Double = 0.0,
        dur: Double = 0.0,
        paused: Boolean = false,
        ended: Boolean = false,
        errorCode: Int? = null,
    ) = """{"audio":true,"hasSrc":true,"paused":$paused,"ended":$ended,"t":$t,"dur":$dur,"errorCode":$errorCode}"""

    // ── URLs ──

    @Test
    fun embedUrlIsTheSingleTrackForm() {
        assertEquals(single, BandcampEmbed.embedUrl("3635703524"))
    }

    @Test
    fun embedUrlIsBuiltOnlyFromANumericId() {
        listOf(null, "", "0", "01", "12a", "1/album=2", "-1", "https://evil.example/").forEach {
            assertNull(BandcampEmbed.embedUrl(it), "id: $it")
        }
    }

    @Test
    fun onlyTrackPagesOnBandcampSubdomainsAreLookedUp() {
        assertTrue(BandcampEmbed.isTrackPage("https://swapmeetband.bandcamp.com/track/i-know"))
        assertTrue(BandcampEmbed.isTrackPage("https://swapmeetband.bandcamp.com/track/i-know/"))
        listOf(
            null,
            "http://swapmeetband.bandcamp.com/track/i-know",
            "https://swapmeetband.bandcamp.com/album/mount-zero",
            "https://music.example.com/track/i-know",
            "https://bandcamp.com.evil.example/track/i-know",
            "https://user:pw@swapmeetband.bandcamp.com/track/i-know",
            "https://swapmeetband.bandcamp.com/track/i-know/extra",
            "not a url",
        ).forEach { assertFalse(BandcampEmbed.isTrackPage(it), "url: $it") }
    }

    @Test
    fun theWebViewStaysOnBandcamp() {
        listOf("bandcamp.com", "x.bandcamp.com", "BANDCAMP.com", "t4.bcbits.com").forEach {
            assertTrue(BandcampEmbed.isAllowedHost(it), it)
        }
        listOf("evilbandcamp.com", "bandcamp.com.evil.example", "example.com", "").forEach {
            assertFalse(BandcampEmbed.isAllowedHost(it), it)
        }
    }

    @Test
    fun scriptsOnlyEverCarryACheckedNumber() {
        assertEquals("bcSeek(12.5)", BandcampEmbed.seekScript(12.5))
        assertEquals("bcSeek(0.0)", BandcampEmbed.seekScript(-3.0))
        assertEquals("bcSeek(0.0)", BandcampEmbed.seekScript(Double.NaN))
        assertEquals("bcVolume(1.0)", BandcampEmbed.volumeScript(7.0))
        assertEquals("bcVolume(0.0)", BandcampEmbed.volumeScript(-1.0))
    }

    // ── Finding the id ──

    @Test
    fun aCarriedIdNeedsNoLookup() = runTest {
        val url = BandcampEmbed.resolveEmbedUrl("3635703524", "https://music.example.com/x") { error("no fetch expected") }
        assertEquals(single, url)
    }

    @Test
    fun aMissingIdComesFromTheSearchApi_withItsDoubledUrl() = runTest {
        val fetched = mutableListOf<String>()
        val url = BandcampEmbed.resolveEmbedUrl(null, "https://swapmeetband.bandcamp.com/track/i-know") {
            fetched += it
            """{"results":[
                {"id":1,"url":"https://other.bandcamp.comhttps://other.bandcamp.com/track/i-know"},
                {"id":3635703524,"url":"https://swapmeetband.bandcamp.comhttps://swapmeetband.bandcamp.com/track/i-know"}
            ]}"""
        }
        assertEquals(single, url)
        assertEquals(1, fetched.size)
        assertTrue(fetched[0].startsWith("https://bandcamp.com/api/fuzzysearch/1/app_autocomplete?q=swapmeetband"), fetched[0])
    }

    @Test
    fun aSearchMissFallsBackToThePage() = runTest {
        val url = BandcampEmbed.resolveEmbedUrl(null, "https://swapmeetband.bandcamp.com/track/i-know") { fetched ->
            if ("api/fuzzysearch" in fetched) {
                """{"results":[]}"""
            } else {
                """<script data-tralbum="{&quot;current&quot;:{&quot;type&quot;:&quot;track&quot;,&quot;id&quot;:3635703524}}"></script>"""
            }
        }
        assertEquals(single, url)
    }

    @Test
    fun anAlbumPageTralbumIsNotATrack() = runTest {
        val url = BandcampEmbed.resolveEmbedUrl(null, "https://swapmeetband.bandcamp.com/track/i-know") { fetched ->
            if ("api/fuzzysearch" in fetched) null
            else """<div data-tralbum="{&quot;current&quot;:{&quot;type&quot;:&quot;album&quot;,&quot;id&quot;:9}}"></div>"""
        }
        assertNull(url)
    }

    @Test
    fun aCustomDomainPageWithNoIdIsNeverFetched() = runTest {
        assertNull(BandcampEmbed.resolveEmbedUrl(null, "https://music.example.com/track/x") { error("no fetch expected") })
    }

    @Test
    fun aFailedLookupIsAMiss_notACrash() = runTest {
        assertNull(BandcampEmbed.resolveEmbedUrl(null, "https://x.bandcamp.com/track/y") { throw IllegalStateException("down") })
    }

    @Test
    fun aPluginResultsIdIsReadAsNumberOrString() {
        assertEquals("123", pluginBandcampTrackId("""{"bandcampTrackId":123}"""))
        assertEquals("123", pluginBandcampTrackId("""{"bandcampTrackId":"123"}"""))
        assertNull(pluginBandcampTrackId("""{"bandcampTrackId":null}"""))
        assertNull(pluginBandcampTrackId("""{"bandcampTrackId":"1/album=2"}"""))
        assertNull(pluginBandcampTrackId("""{"bandcampUrl":"https://x.bandcamp.com/track/y"}"""))
        assertNull(pluginBandcampTrackId("not json"))
    }

    // ── What the page answers ──

    @Test
    fun junkFromThePageIsNotAState() {
        listOf(null, "", "not json", "[]", "null", "12").forEach { assertNull(BandcampEmbed.parseState(it), "raw: $it") }
    }

    @Test
    fun fieldsAreCoercedAndRangeChecked() {
        val junk = assertNotNull(
            BandcampEmbed.parseState("""{"paused":"no","ended":"yes","t":"5","dur":99999999999,"errorCode":"x"}"""),
        )
        assertTrue(junk.paused)
        assertFalse(junk.ended)
        assertEquals(0.0, junk.positionSeconds)
        assertEquals(0.0, junk.durationSeconds)
        assertNull(junk.errorCode)

        val real = assertNotNull(BandcampEmbed.parseState(state(t = 12.5, dur = 214.3)))
        assertFalse(real.paused)
        assertEquals(12.5, real.positionSeconds)
        assertEquals(214.3, real.durationSeconds)
    }

    // ── The tracker ──

    @Test
    fun startedMeansThePositionMoved() {
        val tracker = BandcampEmbedTracker(startedAtMs = 0)
        assertTrue(tracker.update(state(t = 0.0, dur = 214.3), 500))
        assertFalse(tracker.started)
        assertFalse(tracker.isPlaying)
        assertEquals(214.3, tracker.durationSeconds)
        tracker.update(state(t = 0.4, dur = 214.3), 1000)
        assertTrue(tracker.started)
        assertTrue(tracker.isPlaying)
        assertEquals(0.4, tracker.positionSeconds)
    }

    @Test
    fun aTrackThatNeverStartsIsRefusedAtTheLimit() {
        val tracker = BandcampEmbedTracker(startedAtMs = 1000)
        tracker.update(state(), 15_999)
        assertFalse(tracker.refused)
        tracker.update(null, 16_000)
        assertTrue(tracker.refused)
    }

    @Test
    fun anErrorBeforeStartingIsARefusal_afterStartingTheQueueMovesOn() {
        val before = BandcampEmbedTracker(startedAtMs = 0)
        before.update(state(errorCode = 4), 500)
        assertTrue(before.refused)
        assertFalse(before.finished)

        val after = BandcampEmbedTracker(startedAtMs = 0)
        after.update(state(t = 5.0), 500)
        after.update(state(t = 6.0, errorCode = 2), 1000)
        assertTrue(after.finished)
        assertFalse(after.refused)
        assertFalse(after.isPlaying)
    }

    @Test
    fun endingIsSeenEvenWhenAPollCameLate() {
        val tracker = BandcampEmbedTracker(startedAtMs = 0)
        tracker.update(state(t = 214.3, dur = 214.3, ended = true), 500)
        assertTrue(tracker.started)
        assertTrue(tracker.finished)
        assertFalse(tracker.isPlaying)
    }

    @Test
    fun endingWithoutEverPlayingIsARefusal() {
        val tracker = BandcampEmbedTracker(startedAtMs = 0)
        tracker.update(state(ended = true), 500)
        assertTrue(tracker.refused)
        assertFalse(tracker.finished)
    }

    @Test
    fun pauseAndResumeFollowThePage() {
        val tracker = BandcampEmbedTracker(startedAtMs = 0)
        tracker.update(state(t = 5.0), 500)
        tracker.update(state(t = 5.5, paused = true), 1000)
        assertFalse(tracker.isPlaying)
        tracker.update(state(t = 5.5), 1500)
        assertTrue(tracker.isPlaying)
    }

    @Test
    fun aTapShowsAtOnce_andAStalePollDoesNotUndoIt() {
        val tracker = BandcampEmbedTracker(startedAtMs = 0)
        tracker.update(state(t = 5.0, paused = true), 500)
        assertTrue(tracker.started)
        assertFalse(tracker.isPlaying)

        tracker.userSetPaused(paused = false, nowMs = 1000)
        assertTrue(tracker.isPlaying)
        // Asked before the play reached the page.
        tracker.update(state(t = 5.0, paused = true), 1200)
        assertTrue(tracker.isPlaying)
        // After the hold the page is right again, whatever it says.
        tracker.update(state(t = 5.0, paused = true), 2000)
        assertFalse(tracker.isPlaying)
    }

    @Test
    fun aPollThatGotNothingChangesNothingOnceStarted() {
        val tracker = BandcampEmbedTracker(startedAtMs = 0)
        tracker.update(state(t = 5.0), 500)
        assertFalse(tracker.update(null, 60_000))
        assertTrue(tracker.isPlaying)
        assertEquals(5.0, tracker.positionSeconds)
        assertFalse(tracker.refused)
    }
}
