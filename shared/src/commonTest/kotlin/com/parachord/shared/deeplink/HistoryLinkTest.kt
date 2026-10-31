package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `parachord://friend` and `parachord://history` tab + period
 * contract to desktop's `friend-link.js` and its tests (parachord#1178, #470),
 * so a link copied on one platform opens the same view on another.
 */
class HistoryLinkTest {

    private fun target(tab: HistoryLinkTab?, period: String?) = HistoryLinkTarget(tab, period)

    @Test
    fun `a friend top tab takes a known period`() {
        assertEquals(target(HistoryLinkTab.TOP_ARTISTS, "1month"), parseFriendLink("top-artists", "1month"))
        assertEquals(target(HistoryLinkTab.TOP_ALBUMS, "overall"), parseFriendLink("top-albums", "overall"))
    }

    @Test
    fun `an unknown friend period is dropped so the view keeps its own`() {
        assertEquals(target(HistoryLinkTab.TOP_TRACKS, null), parseFriendLink("top-tracks", "2weeks"))
        assertEquals(target(HistoryLinkTab.TOP_TRACKS, null), parseFriendLink("top-tracks", null))
    }

    @Test
    fun `recent and a missing or unknown friend tab ignore the period`() {
        assertEquals(target(HistoryLinkTab.RECENT, null), parseFriendLink("recent", "1month"))
        assertEquals(target(null, null), parseFriendLink(null, "1month"))
        assertEquals(target(null, null), parseFriendLink("loved", "1month"))
    }

    @Test
    fun `a friend top tab link always carries its period`() {
        assertEquals(
            "parachord://friend/jane123/top-albums?period=1month",
            buildFriendLink("jane123", HistoryLinkTab.TOP_ALBUMS, "1month"),
        )
        assertEquals(
            "parachord://friend/jane123/top-tracks?period=7day",
            buildFriendLink("jane123", HistoryLinkTab.TOP_TRACKS, "7day"),
        )
    }

    @Test
    fun `a friend recent link carries no period`() {
        assertEquals("parachord://friend/jane123/recent", buildFriendLink("jane123", HistoryLinkTab.RECENT, "overall"))
    }

    @Test
    fun `the friend id is encoded`() {
        assertEquals(
            "parachord://friend/DJ%20Two%2FThree/recent",
            buildFriendLink("DJ Two/Three", HistoryLinkTab.RECENT, "7day"),
        )
    }

    @Test
    fun `every friend tab and period round-trips through the link`() {
        for (tab in HistoryLinkTab.entries) {
            for (period in HISTORY_PERIODS) {
                val rest = buildFriendLink("jane123", tab, period).removePrefix("parachord://friend/jane123/")
                val segment = rest.substringBefore('?')
                val query = rest.substringAfter("?period=", missingDelimiterValue = "").ifEmpty { null }
                assertEquals(
                    target(tab, if (tab == HistoryLinkTab.RECENT) null else period),
                    parseFriendLink(segment, query),
                    "$tab $period",
                )
            }
        }
    }

    @Test
    fun `a history link applies a known period on any tab`() {
        assertEquals(target(HistoryLinkTab.RECENT, "7day"), parseHistoryLink("recent", "7day"))
        assertEquals(target(HistoryLinkTab.TOP_ALBUMS, "3month"), parseHistoryLink("top-albums", "3month"))
        assertEquals(target(null, "overall"), parseHistoryLink(null, "overall"))
        assertEquals(target(HistoryLinkTab.TOP_ARTISTS, null), parseHistoryLink("top-artists", "bogus"))
        assertEquals(target(null, null), parseHistoryLink("loved", null))
    }
}
