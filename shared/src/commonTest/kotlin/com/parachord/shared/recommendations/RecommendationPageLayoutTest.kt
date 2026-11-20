package com.parachord.shared.recommendations

import com.parachord.shared.repository.RecommendationPageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationPageLayoutTest {

    private fun item(name: String, type: RecommendationType = RecommendationType.SONG, source: String = "lastfm") =
        RecommendationItem(type = type, id = "${type.wire}:$name", name = name, artist = "A", resolverId = source, sourceIds = listOf(source))

    private fun row(key: String, type: RecommendationType?, items: List<RecommendationItem>, category: String? = null, title: String? = null) =
        RecommendationRow(key = key, type = type, category = category, title = title, items = items)

    private fun render(blocks: List<RecommendationPageBlock>): List<String> = blocks.map {
        when (it) {
            is RecommendationPageBlock.SectionTitle -> "sectionTitle:${it.section.title}"
            is RecommendationPageBlock.Row -> "row:${it.row.key}"
            RecommendationPageBlock.PersonalSkeleton -> "personalSkeleton"
            RecommendationPageBlock.AlbumsSkeleton -> "albumsSkeleton"
            RecommendationPageBlock.PendingSkeleton -> "pendingSkeleton"
            RecommendationPageBlock.ListenersSkeleton -> "listenersSkeleton"
        }
    }

    private val songs = RecommendationPageSection(RecommendationType.SONG, "Tracks", listOf(row("type:song", RecommendationType.SONG, listOf(item("a")))))

    @Test
    fun `category rows get their section title and the personal skeleton goes after New for you`() {
        val albums = RecommendationPageSection(
            RecommendationType.ALBUM, "Albums",
            listOf(
                row("albums:new", RecommendationType.ALBUM, listOf(item("n", RecommendationType.ALBUM)), category = "new", title = "New for you"),
                row("albums:editorial", RecommendationType.ALBUM, listOf(item("e", RecommendationType.ALBUM)), category = "editorial", title = "Editorial"),
            ),
        )
        val blocks = recommendationPageLayout(RecommendationPageState(sections = listOf(songs, albums), aiLoading = true), null, emptySet())
        assertEquals(
            listOf("row:type:song", "sectionTitle:Albums", "row:albums:new", "personalSkeleton", "row:albums:editorial"),
            render(blocks),
        )
        val songRow = blocks[0] as RecommendationPageBlock.Row
        assertEquals("Tracks", songRow.title)
        assertFalse(songRow.subtitle)
        val newRow = blocks[2] as RecommendationPageBlock.Row
        assertEquals("New for you", newRow.title)
        assertTrue(newRow.subtitle)
    }

    @Test
    fun `the Albums slot pending and Listeners skeletons come in desktop's order`() {
        val artists = RecommendationPageSection(RecommendationType.ARTIST, "Artists", listOf(row("type:artist", RecommendationType.ARTIST, listOf(item("x", RecommendationType.ARTIST)))))
        // Shuffleupagus picking with no album section and no row its picks tail: the Albums slot goes before Artists.
        val state = RecommendationPageState(
            sections = listOf(songs, artists.copy(rows = listOf(row("artists:other", RecommendationType.ARTIST, listOf(item("x", RecommendationType.ARTIST)))))),
            aiLoading = true,
            pendingProviders = setOf("listenbrainz"),
            listenersPending = true,
        )
        assertEquals(
            listOf("row:type:song", "albumsSkeleton", "row:artists:other", "pendingSkeleton", "listenersSkeleton"),
            render(recommendationPageLayout(state, null, emptySet())),
        )
        // With a source picked, none of the All-only skeletons show.
        assertEquals(listOf("row:type:song", "row:artists:other"), render(recommendationPageLayout(state, "lastfm", emptySet())))
    }

    @Test
    fun `an open row carries its expanded view`() {
        val many = RecommendationPageSection(RecommendationType.SONG, "Tracks", listOf(row("type:song", RecommendationType.SONG, (1..7).map { item("s$it") })))
        val state = RecommendationPageState(sections = listOf(many))
        val closed = recommendationPageLayout(state, null, emptySet()).single() as RecommendationPageBlock.Row
        assertFalse(closed.expanded)
        assertEquals(5, closed.view.items.size)
        val open = recommendationPageLayout(state, null, setOf("type:song")).single() as RecommendationPageBlock.Row
        assertTrue(open.expanded)
        assertEquals(7, open.view.items.size)
    }
}
