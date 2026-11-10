package com.parachord.shared.discovery

import com.parachord.shared.enrichment.CrossResolverEnrichmentService.ResolvedSources
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.ChartSong
import com.parachord.shared.model.filterChartAlbums
import com.parachord.shared.model.filterChartSongs
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.filterAndSortCriticsPicks
import com.parachord.shared.resolver.ResolvedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The list rules both platforms share: Pop of the Tops' and Critical Darlings'
 * search and sort, and which ids the cross-resolver enrichment keeps.
 */
class DiscoveryFiltersTest {

    @Test
    fun chart_search_matches_title_or_artist_ignoring_case() {
        val songs = listOf(
            ChartSong(id = "1", title = "Dead Fresh", artist = "Lil Baby"),
            ChartSong(id = "2", title = "Housewife", artist = "Tate McRae"),
        )
        assertEquals(listOf("2"), filterChartSongs(songs, "  mcrae ").map { it.id })
        assertEquals(listOf("1"), filterChartSongs(songs, "FRESH").map { it.id })
        assertEquals(songs, filterChartSongs(songs, " "))
        val albums = listOf(ChartAlbum(id = "a", title = "ICEMAN", artist = "Drake"))
        assertEquals(albums, filterChartAlbums(albums, "drake"))
        assertEquals(emptyList(), filterChartAlbums(albums, "cure"))
    }

    @Test
    fun critics_picks_keep_feed_order_or_sort_by_artist() {
        val picks = listOf(
            CriticsPickAlbum(id = "1", title = "Requiem For A Village", artist = "Steven Wilson"),
            CriticsPickAlbum(id = "2", title = "Doveland", artist = "Django Django"),
            CriticsPickAlbum(id = "3", title = "Back To Black", artist = "amy Winehouse"),
        )
        assertEquals(listOf("1", "2", "3"), filterAndSortCriticsPicks(picks, "", "recent").map { it.id })
        assertEquals(listOf("3", "2", "1"), filterAndSortCriticsPicks(picks, "", "artist").map { it.id })
        assertEquals(listOf("2"), filterAndSortCriticsPicks(picks, "django", "artist").map { it.id })
    }

    @Test
    fun enrichment_keeps_only_near_certain_ids_per_service() {
        val sources = listOf(
            ResolvedSource(url = "", sourceType = "stream", resolver = "spotify", spotifyId = "weak", confidence = 0.9),
            ResolvedSource(url = "", sourceType = "stream", resolver = "spotify", spotifyId = "strong", confidence = 0.95),
            ResolvedSource(url = "", sourceType = "stream", resolver = "applemusic", appleMusicId = "123", confidence = 1.0),
            ResolvedSource(url = "", sourceType = "stream", resolver = "soundcloud", soundcloudId = "sc", confidence = 0.6),
        )
        val kept = ResolvedSources.fromSources(sources)
        assertEquals("strong", kept.spotifyId)
        assertEquals("123", kept.appleMusicId)
        assertNull(kept.soundcloudId)
    }
}
