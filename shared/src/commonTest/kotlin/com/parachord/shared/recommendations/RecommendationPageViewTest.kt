package com.parachord.shared.recommendations

import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.repository.RecommendationsError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationPageViewTest {

    private fun item(name: String, type: RecommendationType = RecommendationType.SONG, vararg sources: String) =
        RecommendationItem(type = type, id = "$type:$name", name = name, resolverId = sources.firstOrNull(), sourceIds = sources.toList())

    private fun row(
        key: String,
        type: RecommendationType?,
        items: List<RecommendationItem> = emptyList(),
        category: String? = null,
        pageable: List<PageableSection> = emptyList(),
        canLoadMore: Boolean = false,
        listeners: List<ListenerItem> = emptyList(),
    ) = RecommendationRow(key = key, type = type, category = category, items = items, pageable = pageable, canLoadMore = canLoadMore, listeners = listeners)

    private fun section(type: RecommendationType?, vararg rows: RecommendationRow) =
        RecommendationPageSection(type, type?.name ?: "Listeners", rows.toList())

    private fun pageable(key: String, resolver: String = "lastfm") =
        PageableSection(resolver, resolver, "id", "t", null, key, null)

    private fun listener(name: String, service: String) =
        ListenerItem("similar:$service:$name", name, service, 0.5, null, emptyList(), service, listOf(service))

    @Test
    fun `activeSourceFilter keeps a source on the page — falls back to All when it left`() {
        assertEquals("lastfm", activeSourceFilter("lastfm", listOf(PageSource("lastfm", "Last.fm"))))
        assertNull(activeSourceFilter("lastfm", listOf(PageSource("applemusic", "Apple Music"))))
        assertNull(activeSourceFilter(null, listOf(PageSource("lastfm", "Last.fm"))))
    }

    @Test
    fun `a song row previews 5 — See all when more`() {
        val r = row("type:song", RecommendationType.SONG, (1..7).map { item("s$it") })
        val collapsed = rowView(r, RecommendationPageState(), null, expanded = false)
        assertEquals(5, collapsed.items.size)
        assertTrue(collapsed.showToggle)
        assertEquals("See all", collapsed.toggleLabel)
        val open = rowView(r, RecommendationPageState(), null, expanded = true)
        assertEquals(7, open.items.size)
        assertEquals("Show less", open.toggleLabel)
        assertTrue(open.showToggle)
    }

    @Test
    fun `a card row with 3 items and no more pages has no toggle`() {
        val r = row("type:album", RecommendationType.ALBUM, (1..3).map { item("a$it", RecommendationType.ALBUM) })
        assertFalse(rowView(r, RecommendationPageState(), null, false).showToggle)
    }

    @Test
    fun `canLoadMore shows the toggle even under the preview`() {
        val r = row("type:album", RecommendationType.ALBUM, listOf(item("a", RecommendationType.ALBUM)), canLoadMore = true)
        assertTrue(rowView(r, RecommendationPageState(), null, false).showToggle)
    }

    @Test
    fun `the filter keeps only that source's items`() {
        val r = row(
            "type:song", RecommendationType.SONG,
            listOf(item("a", sources = arrayOf("lastfm")), item("b", sources = arrayOf("listenbrainz")), item("c", sources = arrayOf("lastfm", "listenbrainz"))),
        )
        val v = rowView(r, RecommendationPageState(), "listenbrainz", false)
        assertEquals(listOf("b", "c"), v.items.map { it.name })
    }

    @Test
    fun `loading tail only while a page for the row is in flight — and only if the last page brought its type`() {
        val r = row("type:album", RecommendationType.ALBUM, listOf(item("a", RecommendationType.ALBUM)), pageable = listOf(pageable("k")))
        fun state(types: List<RecommendationType>) = RecommendationPageState(
            loadingMore = setOf("k"),
            paging = mapOf("k" to ExpandedSection(items = emptyList(), lastPageTypes = types)),
        )
        assertFalse(rowView(r, state(listOf(RecommendationType.SONG)), null, expanded = true).loadingTail)
        assertTrue(rowView(r, state(listOf(RecommendationType.ALBUM)), null, expanded = true).loadingTail)
        assertFalse(rowView(r, state(listOf(RecommendationType.ALBUM)), null, expanded = false).loadingTail)
        assertFalse(rowView(r, RecommendationPageState(), null, expanded = true).loadingTail)
    }

    @Test
    fun `a category row's loading tail looks at the pages' categories`() {
        val r = row("albums:new", RecommendationType.ALBUM, listOf(item("a", RecommendationType.ALBUM)), category = "new", pageable = listOf(pageable("k")))
        fun state(categories: List<String>) = RecommendationPageState(
            loadingMore = setOf("k"),
            paging = mapOf("k" to ExpandedSection(items = emptyList(), lastPageCategories = categories)),
        )
        assertTrue(rowView(r, state(listOf("album:new")), null, true).loadingTail)
        assertFalse(rowView(r, state(listOf("album:editorial")), null, true).loadingTail)
    }

    @Test
    fun `Shuffleupagus tail — 3 cards on album and artist rows under All or its pill only`() {
        val state = RecommendationPageState(aiLoading = true)
        val album = row("type:album", RecommendationType.ALBUM, listOf(item("a", RecommendationType.ALBUM, "lastfm")))
        assertEquals(3, rowView(album, state, null, false).aiTail)
        assertEquals(0, rowView(album, state, "lastfm", false).aiTail)
        assertEquals(3, rowView(album, state, "shuffleupagus", false).aiTail)
        val songs = row("type:song", RecommendationType.SONG, listOf(item("s")))
        assertEquals(0, rowView(songs, state, null, false).aiTail)
        assertEquals(0, rowView(album, RecommendationPageState(), null, false).aiTail)
    }

    @Test
    fun `personal skeleton goes after New for you in a categorized Albums section`() {
        fun albums(vararg keys: String) = RecommendationPageState(
            aiLoading = true,
            sections = listOf(
                section(
                    RecommendationType.ALBUM,
                    *keys.map { k ->
                        row(k, RecommendationType.ALBUM, listOf(item("x$k", RecommendationType.ALBUM)), category = k.substringAfter(':'))
                    }.toTypedArray(),
                ),
            ),
        )
        assertEquals(1, sectionViews(albums("albums:new", "albums:editorial"), null).single().personalPendingAt)
        assertEquals(0, sectionViews(albums("albums:editorial", "albums:popular"), null).single().personalPendingAt)
        assertNull(sectionViews(albums("albums:new", "albums:personal"), null).single().personalPendingAt)
        val single = RecommendationPageState(
            aiLoading = true,
            sections = listOf(section(RecommendationType.ALBUM, row("type:album", RecommendationType.ALBUM, listOf(item("a", RecommendationType.ALBUM))))),
        )
        assertNull(sectionViews(single, null).single().personalPendingAt)
    }

    @Test
    fun `albums skeleton slot when Shuffleupagus thinks and nothing album-like is on the page`() {
        val songsOnly = RecommendationPageState(
            aiLoading = true,
            sections = listOf(section(RecommendationType.SONG, row("type:song", RecommendationType.SONG, listOf(item("s", sources = arrayOf("lastfm")))))),
        )
        assertTrue(albumsSkeletonSlot(songsOnly, null))
        assertFalse(albumsSkeletonSlot(songsOnly, "lastfm"))
        assertFalse(albumsSkeletonSlot(songsOnly.copy(aiLoading = false), null))
        val withAlbums = songsOnly.copy(
            sections = songsOnly.sections + section(RecommendationType.ALBUM, row("type:album", RecommendationType.ALBUM, listOf(item("a", RecommendationType.ALBUM)))),
        )
        assertFalse(albumsSkeletonSlot(withAlbums, null))
        // An artist row already carries the Shuffleupagus tail.
        val withArtists = songsOnly.copy(
            sections = songsOnly.sections + section(RecommendationType.ARTIST, row("type:artist", RecommendationType.ARTIST, listOf(item("x", RecommendationType.ARTIST)))),
        )
        assertFalse(albumsSkeletonSlot(withArtists, null))
    }

    @Test
    fun `sections — content first and Listeners last — empty sections dropped`() {
        val state = RecommendationPageState(
            sections = listOf(
                section(RecommendationType.SONG, row("type:song", RecommendationType.SONG, listOf(item("s", sources = arrayOf("lastfm"))))),
                section(null, row(LISTENERS_ROW_KEY, null, listeners = listOf(listener("bob", "listenbrainz")))),
                section(RecommendationType.ARTIST, row("type:artist", RecommendationType.ARTIST, listOf(item("x", RecommendationType.ARTIST, "listenbrainz")))),
            ),
        )
        assertEquals(listOf(RecommendationType.SONG, RecommendationType.ARTIST, null), sectionViews(state, null).map { it.section.type })
        // Under lastfm only songs have anything; artists and Listeners drop.
        assertEquals(listOf(RecommendationType.SONG), sectionViews(state, "lastfm").map { it.section.type })
        // A page in flight for the filtered source keeps its row.
        val loading = state.copy(
            sections = listOf(section(RecommendationType.ARTIST, row("type:artist", RecommendationType.ARTIST, listOf(item("x", RecommendationType.ARTIST, "listenbrainz")), pageable = listOf(pageable("k", "lastfm"))))),
            loadingMore = setOf("k"),
        )
        assertEquals(1, sectionViews(loading, "lastfm").size)
    }

    @Test
    fun `pageStatus order`() {
        val content = listOf(section(RecommendationType.SONG, row("type:song", RecommendationType.SONG, listOf(item("s")))))
        assertEquals(PageStatus.Loading, pageStatus(RecommendationPageState(loading = true), null))
        assertEquals(PageStatus.NotConfigured, pageStatus(RecommendationPageState(error = RecommendationsError.NotConfigured), null))
        assertEquals(PageStatus.Failed("x"), pageStatus(RecommendationPageState(error = RecommendationsError.Message("x")), null))
        assertEquals(PageStatus.Empty(false), pageStatus(RecommendationPageState(), null))
        assertEquals(PageStatus.Empty(true), pageStatus(RecommendationPageState(), "lastfm"))
        assertEquals(PageStatus.Content, pageStatus(RecommendationPageState(listenersPending = true), null))
        // With a source picked the Listeners skeleton doesn't show.
        assertEquals(PageStatus.Empty(true), pageStatus(RecommendationPageState(listenersPending = true), "lastfm"))
        // Shuffleupagus thinking: the Albums skeleton stands in.
        assertEquals(PageStatus.Content, pageStatus(RecommendationPageState(aiLoading = true), null))
        assertEquals(PageStatus.Content, pageStatus(RecommendationPageState(pendingProviders = setOf("lastfm")), null))
        assertEquals(PageStatus.Content, pageStatus(RecommendationPageState(sections = content), null))
        assertEquals(PageStatus.Content, pageStatus(RecommendationPageState(loading = true, sections = content), null))
    }

    @Test
    fun `releaseYear`() {
        assertEquals("2026", releaseYear("2026-11-21"))
        assertEquals("", releaseYear(null))
        assertEquals("20", releaseYear("20"))
    }

    @Test
    fun `shuffleupagusReason only for Shuffleupagus picks`() {
        val base = item("a", RecommendationType.ALBUM)
        assertEquals("peak", shuffleupagusReason(base.copy(resolverId = "shuffleupagus", reason = "peak")))
        assertNull(shuffleupagusReason(base.copy(resolverId = "lastfm", reason = "x")))
        assertEquals("y", shuffleupagusReason(base.copy(sourceIds = listOf("lastfm", "shuffleupagus"), reason = "y")))
        assertNull(shuffleupagusReason(base.copy(resolverId = "shuffleupagus", reason = " ")))
    }

    @Test
    fun `itemSourceName reads the row's sources`() {
        val r = RecommendationRow("type:song", RecommendationType.SONG, sources = listOf(PageSource("lastfm", "Last.fm", 1)))
        assertEquals("Last.fm", itemSourceName(item("s", sources = arrayOf("lastfm")), r))
        assertEquals("", itemSourceName(item("s", sources = arrayOf("other")), r))
    }

    @Test
    fun `similarityBadge rounds to a whole percent`() {
        assertEquals("88%", RecommendationCopy.similarityBadge(0.876))
        assertNull(RecommendationCopy.similarityBadge(null))
    }
}
