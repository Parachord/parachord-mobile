package com.parachord.shared.recommendations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Desktop's `weeklyHomeEntries` vectors, as the weekly view's header reads them. */
class WeeklyPlaylistHeaderTest {

    private fun weekly(id: String, contextType: String = "weekly-jam", label: String = "Weekly Jams",
                       weekLabel: String = "This Week", date: String? = "2026-11-17", resolverId: String? = "listenbrainz") =
        RecommendationItem(
            type = RecommendationType.PLAYLIST, id = id, name = label,
            weekly = WeeklyInfo(id, contextType, label, weekLabel, date),
            resolverId = resolverId,
        )

    private fun page(vararg rows: RecommendationRow) =
        listOf(RecommendationPageSection(RecommendationType.PLAYLIST, "Playlists", rows.toList()))

    private fun row(items: List<RecommendationItem>, sources: List<PageSource> = listOf(PageSource("listenbrainz", "ListenBrainz"))) =
        RecommendationRow(key = "type:playlist", type = RecommendationType.PLAYLIST, sources = sources, items = items)

    @Test
    fun `a weekly jam names its own source as the curator`() {
        val header = weeklyPlaylistHeader(page(row(listOf(weekly("jam1")))), "jam1")!!
        assertEquals("Weekly Jams", header.title)
        assertEquals("This Week", header.weekLabel)
        assertEquals("Your favorite tracks from the past week, curated by ListenBrainz", header.blurb)
        assertEquals("2026-11-17", header.date)
        assertEquals("Nov 17, 2026", header.dateLabel)
        assertEquals("weekly-jam", header.contextType)
    }

    @Test
    fun `a weekly exploration uses its own description`() {
        val item = weekly("exp1", contextType = "weekly-exploration", label = "Weekly Exploration")
        assertEquals(
            "New discoveries based on your listening habits, curated by ListenBrainz",
            weeklyPlaylistHeader(page(row(listOf(item))), "exp1")!!.blurb,
        )
    }

    @Test
    fun `in a blended row the curator is the item source not the row first`() {
        val sources = listOf(PageSource("lastfm", "Last.fm"), PageSource("listenbrainz", "ListenBrainz"))
        val header = weeklyPlaylistHeader(page(row(listOf(weekly("jam1")), sources)), "jam1")!!
        assertEquals("Your favorite tracks from the past week, curated by ListenBrainz", header.blurb)
    }

    @Test
    fun `with no matching source the curator is the resolver id`() {
        val header = weeklyPlaylistHeader(page(row(listOf(weekly("jam1")), emptyList())), "jam1")!!
        assertEquals("Your favorite tracks from the past week, curated by listenbrainz", header.blurb)
    }

    @Test
    fun `with no resolver id the curator is your service`() {
        val header = weeklyPlaylistHeader(page(row(listOf(weekly("jam1", resolverId = null)), emptyList())), "jam1")!!
        assertEquals("Your favorite tracks from the past week, curated by your service", header.blurb)
    }

    @Test
    fun `a source with an empty name falls back to the resolver id`() {
        val header = weeklyPlaylistHeader(page(row(listOf(weekly("jam1")), listOf(PageSource("listenbrainz", "")))), "jam1")!!
        assertEquals("Your favorite tracks from the past week, curated by listenbrainz", header.blurb)
    }

    @Test
    fun `the first appearance of an id wins`() {
        val first = weekly("jam1", weekLabel = "This Week")
        val again = weekly("jam1", weekLabel = "Last Week")
        val headers = weeklyPlaylistHeaders(page(row(listOf(first)), row(listOf(again))))
        assertEquals(1, headers.size)
        assertEquals("This Week", headers.single().weekLabel)
    }

    @Test
    fun `playlists that are not weekly and unknown kinds are skipped`() {
        val plain = RecommendationItem(type = RecommendationType.PLAYLIST, id = "p1", name = "Mix")
        val odd = weekly("odd", contextType = "weekly-other")
        assertEquals(emptyList(), weeklyPlaylistHeaders(page(row(listOf(plain, odd)))))
    }

    @Test
    fun `an id the page does not carry has no header`() {
        assertNull(weeklyPlaylistHeader(page(row(listOf(weekly("jam1")))), "other"))
        assertNull(weeklyPlaylistHeader(emptyList(), "jam1"))
    }

    @Test
    fun `a missing date gives an empty date label`() {
        val header = weeklyPlaylistHeader(page(row(listOf(weekly("jam1", date = null)))), "jam1")!!
        assertNull(header.date)
        assertEquals("", header.dateLabel)
    }

    @Test
    fun `Weekly Jams left off the page by the history-mix exclusion still have a header in the unexcluded build`() {
        val provider = ProviderRecommendations(
            resolverId = "listenbrainz", resolverName = "ListenBrainz", canExpand = false,
            sections = listOf(
                RecommendationSection(
                    items = listOf(weekly("jam1"), weekly("exp1", contextType = "weekly-exploration", label = "Weekly Exploration")),
                    id = "weekly",
                ),
            ),
        )
        val excluded = buildRecommendationPage(providers = listOf(provider), excludePlaylistIds = setOf("jam1"))
        assertNull(weeklyPlaylistHeader(excluded, "jam1"))
        val weeklySections = buildRecommendationPage(providers = listOf(provider))
        assertEquals(
            "Your favorite tracks from the past week, curated by ListenBrainz",
            weeklyPlaylistHeader(weeklySections, "jam1")!!.blurb,
        )
    }

    @Test
    fun `kind label and headline`() {
        assertEquals("Weekly Jam", weeklyKindLabel("weekly-jam"))
        assertEquals("Weekly Exploration", weeklyKindLabel("weekly-exploration"))
        assertEquals("Weekly Jam · Jun 15, 2026", weeklyHeadline("Weekly Jam", "Jun 15, 2026"))
        assertEquals("Weekly Jam", weeklyHeadline("Weekly Jam", ""))
    }

    @Test
    fun `weeklyDateLabel formats a bare ISO date`() {
        assertEquals("Jun 15, 2026", weeklyDateLabel("2026-06-15"))
        assertEquals("Jan 1, 2026", weeklyDateLabel("2026-01-01"))
        assertEquals("Dec 31, 2025", weeklyDateLabel("2025-12-31"))
    }

    @Test
    fun `weeklyDateLabel handles a date with a time suffix`() {
        assertEquals("Jun 11, 2026", weeklyDateLabel("2026-06-11T00:00:00+00:00"))
        assertEquals("Jun 11, 2026", weeklyDateLabel("2026-06-11 00:00:00"))
    }

    @Test
    fun `weeklyDateLabel returns empty for blank or unparseable input`() {
        assertEquals("", weeklyDateLabel(""))
        assertEquals("", weeklyDateLabel("not-a-date"))
    }

    private fun header(title: String = "Weekly Jams", weekLabel: String = "This Week", dateLabel: String = "Nov 17, 2026") =
        WeeklyHeader("jam1", "weekly-jam", title, weekLabel, "blurb", "2026-11-17", dateLabel)

    @Test
    fun `a saved weekly playlist is named by its label and date`() {
        assertEquals("Weekly Jams · Nov 17, 2026", weeklySavedName(header(), "weekly-jam", "2026-11-21"))
    }

    @Test
    fun `two weeks of one playlist save under different names`() {
        val thisWeek = weeklySavedName(header(dateLabel = "Nov 17, 2026"), "weekly-jam", "2026-11-21")
        val lastWeek = weeklySavedName(header(weekLabel = "Last Week", dateLabel = "Nov 10, 2026"), "weekly-jam", "2026-11-21")
        assertTrue(thisWeek != lastWeek)
    }

    @Test
    fun `with no date today names it and never the relative week label`() {
        assertEquals("Weekly Jams · Nov 21, 2026", weeklySavedName(header(dateLabel = ""), "weekly-jam", "2026-11-21"))
        assertEquals("Weekly Jams · Nov 21, 2026", weeklySavedName(header(weekLabel = " ", dateLabel = ""), "weekly-jam", "2026-11-21"))
    }

    @Test
    fun `an empty label falls back to the kind`() {
        assertEquals("Weekly Jam · Nov 17, 2026", weeklySavedName(header(title = "  "), "weekly-jam", "2026-11-21"))
    }

    @Test
    fun `with no header the kind and today name it`() {
        assertEquals("Weekly Jam · Nov 21, 2026", weeklySavedName(null, "weekly-jam", "2026-11-21"))
        assertEquals("Weekly Exploration · Nov 21, 2026", weeklySavedName(null, "weekly-exploration", "2026-11-21"))
    }

    @Test
    fun `with no header and no known kind the name is generic`() {
        assertEquals("Weekly playlist · Nov 21, 2026", weeklySavedName(null, null, "2026-11-21"))
        assertEquals("Weekly playlist · Nov 21, 2026", weeklySavedName(null, "playlist", "2026-11-21"))
    }

    @Test
    fun `the saved name is never the bare kind label or empty`() {
        for (name in listOf(
            weeklySavedName(null, "weekly-jam", "2026-11-21"),
            weeklySavedName(header(title = "", weekLabel = "", dateLabel = ""), "weekly-jam", "2026-11-21"),
        )) {
            assertTrue(name.isNotBlank() && name != "Weekly Jam" && name != "Weekly Exploration", name)
        }
    }

    @Test
    fun `todayIsoDate is an ISO date`() {
        assertTrue(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(todayIsoDate()), todayIsoDate())
    }
}
