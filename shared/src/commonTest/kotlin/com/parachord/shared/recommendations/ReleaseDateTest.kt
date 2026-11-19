package com.parachord.shared.recommendations

import com.parachord.shared.api.MbArtistCredit
import com.parachord.shared.api.MbArtistRef
import com.parachord.shared.api.MbReleaseGroup
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Desktop's vectors for release dates (`tests/renderer/release-date.test.js`),
 * one test per desktop `test(...)`. Change a rule on desktop first.
 */
class ReleaseDateTest {

    @Test
    fun `dates are kept a week — announced dates move`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, RELEASE_DATE_TTL_MS)
    }

    @Test
    fun `the key ignores case — accents and punctuation`() {
        assertEquals(releaseDateKey("sigur ros", "agaetis byrjun"), releaseDateKey("Sigur Rós", "Ágætis byrjun!"))
        assertNotEquals(releaseDateKey("A", "B"), releaseDateKey("A", "C"))
    }

    @Test
    fun `the search asks MusicBrainz for the release group by title and artist — quotes escaped`() {
        assertEquals(
            "releasegroup:\"Forever \\\"Howlong\\\"\" AND artist:\"Black Country, New Road\"",
            releaseGroupQuery("Forever \"Howlong\"", "Black Country, New Road"),
        )
    }

    @Test
    fun `the search asks for the title without store decorations`() {
        fun q(title: String) = releaseGroupQuery(title, "X")
        assertEquals("releasegroup:\"Either/Or\" AND artist:\"X\"", q("Either/Or: Expanded Edition"))
        assertEquals("releasegroup:\"Yankee Hotel Foxtrot\" AND artist:\"X\"", q("Yankee Hotel Foxtrot (Expanded Edition)"))
        assertEquals("releasegroup:\"Diydui\" AND artist:\"X\"", q("Diydui - EP"))
        assertEquals("releasegroup:\"Sketches\" AND artist:\"X\"", q("Sketches [Explicit] (Deluxe)"))
        // A colon that is part of the title stays.
        assertEquals(
            "releasegroup:\"Hecklers Choice: Big Gums and Heavy Lifters\" AND artist:\"X\"",
            q("Hecklers Choice: Big Gums and Heavy Lifters"),
        )
        assertEquals("Either/Or", searchTitle("Either/Or: Expanded Edition"))
    }

    private fun rg(title: String, artist: String, date: String, type: String = "Album") = MbReleaseGroup(
        title = title,
        firstReleaseDate = date,
        primaryType = type,
        artistCredit = listOf(MbArtistCredit(name = artist, artist = MbArtistRef(id = "x", name = artist))),
    )

    @Test
    fun `picks the matching release group's first release date`() {
        val groups = listOf(
            rg("Some Other Album", "FIDLAR", "2019-01-25"),
            rg("Surviving the Dream", "FIDLAR", "2026-12-05"),
        )
        assertEquals("2026-12-05", pickReleaseGroupDate(groups, "SURVIVING THE DREAM", "FIDLAR"))
    }

    @Test
    fun `a title with an edition or explicit marker still matches the plain group`() {
        val groups = listOf(rg("Either/Or", "Elliott Smith", "1997-02-25"))
        assertEquals("1997-02-25", pickReleaseGroupDate(groups, "Either/Or (Expanded Edition)", "Elliott Smith"))
        assertEquals("1997-02-25", pickReleaseGroupDate(groups, "Either/Or [Explicit]", "Elliott Smith"))
        assertEquals("1997-02-25", pickReleaseGroupDate(groups, "Either/Or: Expanded Edition", "Elliott Smith"))
    }

    @Test
    fun `another artist's album of the same name never counts`() {
        val groups = listOf(rg("Love", "Someone Else", "2027-01-01"))
        assertNull(pickReleaseGroupDate(groups, "Love", "Michael Bublé"))
    }

    @Test
    fun `an album beats a single of the same name — no date or nothing matching is null`() {
        val groups = listOf(
            rg("Too", "FIDLAR", "2027-03-01", type = "Single"),
            rg("Too", "FIDLAR", "2015-09-04"),
        )
        assertEquals("2015-09-04", pickReleaseGroupDate(groups, "Too", "FIDLAR"))
        assertNull(pickReleaseGroupDate(listOf(rg("Too", "FIDLAR", "")), "Too", "FIDLAR"))
        assertNull(pickReleaseGroupDate(emptyList(), "Too", "FIDLAR"))
    }

    @Test
    fun `an accented artist still matches`() {
        val groups = listOf(rg("Ágætis byrjun", "Sigur Ros", "1999-06-12"))
        assertEquals("1999-06-12", pickReleaseGroupDate(groups, "Ágætis byrjun", "Sigur Rós"))
    }

    private val today = LocalDate(2026, 11, 12)

    @Test
    fun `a full date after today is Coming Mon D`() {
        assertEquals("Coming Nov 21", upcomingLabel("2026-11-21", today))
        assertEquals("Coming Feb 3, 2027", upcomingLabel("2027-02-03", today))
    }

    @Test
    fun `today or earlier is no badge`() {
        assertNull(upcomingLabel("2026-11-12", today))
        assertNull(upcomingLabel("1997-02-25", today))
    }

    @Test
    fun `a month-only date counts once the month is later — a year-only date once the year is`() {
        assertEquals("Coming Dec 2026", upcomingLabel("2026-12", today))
        assertNull(upcomingLabel("2026-11", today))
        assertEquals("Coming 2027", upcomingLabel("2027", today))
        assertNull(upcomingLabel("2026", today))
    }

    @Test
    fun `anything else is no badge`() {
        for (v in listOf(null, "", "soon", "2026-13-01", "2026-02-30")) assertNull(upcomingLabel(v, today))
    }

    private fun album(name: String, artist: String, date: String? = null) = RecommendationItem(
        type = RecommendationType.ALBUM, id = "a:$name", name = name, artist = artist, releaseDate = date,
    )

    @Test
    fun `a provider date wins — else the cache — else null`() = runTest {
        var now = 1_000L
        var stored: String? = null
        val rd = ReleaseDates({ stored }, { stored = it }, { _, _ -> "2027-01-01" }, { now })
        assertEquals("2020-05-05", rd.dateFor(album("X", "Y", "2020-05-05")))
        assertNull(rd.dateFor(album("X", "Y")))
        rd.requestLookups(listOf(album("X", "Y")))
        assertEquals("2027-01-01", rd.dateFor(album("X", "Y")))
        now += RELEASE_DATE_TTL_MS + 1
        assertNull(rd.dateFor(album("X", "Y")))
    }

    @Test
    fun `a failed lookup stores nothing and asks again later`() = runTest {
        var calls = 0
        var saved = 0
        val rd = ReleaseDates({ null }, { saved++ }, { _, _ -> calls++; error("offline") }, { 1L })
        rd.requestLookups(listOf(album("X", "Y")))
        rd.requestLookups(listOf(album("X", "Y")))
        assertEquals(2, calls)
        assertEquals(0, saved)
        assertNull(rd.dateFor(album("X", "Y")))
    }

    @Test
    fun `a lookup that found nothing is remembered and not asked again`() = runTest {
        var calls = 0
        val rd = ReleaseDates({ null }, { }, { _, _ -> calls++; null }, { 1L })
        rd.requestLookups(listOf(album("X", "Y"), album("X", "Y")))
        rd.requestLookups(listOf(album("X", "Y")))
        assertEquals(1, calls)
        assertNull(rd.dateFor(album("X", "Y")))
    }
}
