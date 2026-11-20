package com.parachord.shared.recommendations

import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.ai.AiArtistSuggestion
import com.parachord.shared.repository.RecommendationPageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Pixel's saved answers ([DeviceRecommendationsFixture]): Apple Music's
 * titled sections, none with a category, and Last.fm's untitled songs. What
 * desktop's `buildRecommendationPage` makes of them, and what the page draws.
 */
class DeviceRecommendationsPageTest {

    private val ai = shuffleupagusSection(
        listOf(AiAlbumSuggestion("OK Computer", "Radiohead", "because")),
        listOf(AiArtistSuggestion("Björk", "because")),
    )

    private fun drawn(sections: List<RecommendationPageSection>, aiLoading: Boolean = false): Map<String, Int> {
        val state = RecommendationPageState(sections = sections, sources = pageSources(sections), aiLoading = aiLoading)
        return sectionViews(state, null).flatMap { v -> v.rows.map { it.key to rowView(it, state, null, false).items.size } }.toMap()
    }

    @Test
    fun `uncategorized Apple Music albums are one Albums row with cards`() {
        val page = buildRecommendationPage(providers = DeviceRecommendationsFixture.providers)
        val rows = drawn(page)
        assertEquals(listOf("type:song", "type:album", "type:artist", "playlists:personal", "playlists:editorial"), rows.keys.toList())
        assertEquals(CARD_PREVIEW, rows["type:album"])
    }

    @Test
    fun `Last_fm's untitled songs give the Artists row`() {
        val page = buildRecommendationPage(providers = DeviceRecommendationsFixture.providers)
        val artists = page.first { it.type == RecommendationType.ARTIST }.rows.single().items
        val songs = DeviceRecommendationsFixture.sections.getValue("lastfm").single().items
        assertEquals(songs.mapNotNull { it.artist }.distinctBy { it.lowercase() }, artists.map { it.name })
        assertTrue(artists.all { it.derived && it.sourceIds == listOf("lastfm") })
    }

    @Test
    fun `with Shuffleupagus the albums split into Based on your listening and More albums`() {
        val page = buildRecommendationPage(providers = DeviceRecommendationsFixture.providers, shuffleupagus = ai)
        val rows = drawn(page, aiLoading = true)
        assertEquals(
            listOf("type:song", "albums:personal", "albums:other", "type:artist", "playlists:personal", "playlists:editorial"),
            rows.keys.toList(),
        )
        assertEquals(1, rows["albums:personal"])
        assertEquals(CARD_PREVIEW, rows["albums:other"])
        val albums = sectionViews(RecommendationPageState(sections = page, aiLoading = true), null)
            .first { it.section.type == RecommendationType.ALBUM }
        // Its picks are already on the page: no "Based on your listening" skeleton.
        assertEquals(null, albums.personalPendingAt)
    }
}
