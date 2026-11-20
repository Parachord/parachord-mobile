package com.parachord.shared.ios

import com.parachord.shared.model.Track
import com.parachord.shared.recommendations.ListenerItem
import com.parachord.shared.recommendations.PageSource
import com.parachord.shared.recommendations.RecommendationCopy
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.WeeklyInfo
import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.repository.RecommendationsError
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosRecommendationsPageTest {

    private val today = LocalDate(2026, 11, 19)

    private fun item(
        name: String,
        type: RecommendationType = RecommendationType.SONG,
        source: String = "lastfm",
        artist: String? = "Artist",
        releaseDate: String? = null,
        weekly: WeeklyInfo? = null,
        url: String? = null,
    ) = RecommendationItem(
        type = type, id = "${type.wire}:$name", name = name, artist = artist,
        releaseDate = releaseDate, weekly = weekly, url = url,
        resolverId = source, sourceIds = listOf(source),
    )

    private fun row(
        key: String,
        type: RecommendationType?,
        items: List<RecommendationItem> = emptyList(),
        category: String? = null,
        title: String? = null,
        sources: List<PageSource> = emptyList(),
        listeners: List<ListenerItem> = emptyList(),
    ) = RecommendationRow(
        key = key, type = type, category = category, title = title,
        sources = sources, items = items, listeners = listeners,
    )

    private fun page(vararg sections: RecommendationPageSection, aiLoading: Boolean = false, sources: List<PageSource> = emptyList()) =
        RecommendationPageState(sections = sections.toList(), aiLoading = aiLoading, sources = sources)

    private fun render(p: IosRecPage): List<String> = p.blocks.map { b ->
        when (b.kind) {
            "row" -> "row:${b.rowKey}"
            "sectionTitle" -> "sectionTitle:${b.title}"
            else -> b.kind
        }
    }

    @Test
    fun `status follows pageStatus`() {
        val songs = RecommendationPageSection(RecommendationType.SONG, "Tracks", listOf(row("type:song", RecommendationType.SONG, listOf(item("a")))))
        assertEquals("loading", iosRecommendationsPage(RecommendationPageState(loading = true), null, emptySet(), today).status)
        assertEquals("notConfigured", iosRecommendationsPage(RecommendationPageState(error = RecommendationsError.NotConfigured), null, emptySet(), today).status)
        val failed = iosRecommendationsPage(RecommendationPageState(error = RecommendationsError.Message("x")), null, emptySet(), today)
        assertEquals("failed", failed.status)
        assertEquals("x", failed.statusText)
        assertEquals("empty", iosRecommendationsPage(RecommendationPageState(), null, emptySet(), today).status)
        val content = iosRecommendationsPage(page(songs), null, emptySet(), today)
        assertEquals("content", content.status)
        assertNull(content.statusText)
    }

    @Test
    fun `blocks come in Android's order`() {
        val songs = RecommendationPageSection(RecommendationType.SONG, "Tracks", listOf(row("type:song", RecommendationType.SONG, listOf(item("a")))))
        val albums = RecommendationPageSection(
            RecommendationType.ALBUM, "Albums",
            listOf(
                row("albums:new", RecommendationType.ALBUM, listOf(item("n", RecommendationType.ALBUM)), category = "new", title = "New for you"),
                row("albums:editorial", RecommendationType.ALBUM, listOf(item("e", RecommendationType.ALBUM)), category = "editorial", title = "Editorial"),
            ),
        )
        val p = iosRecommendationsPage(page(songs, albums, aiLoading = true), null, emptySet(), today)
        assertEquals(
            listOf("row:type:song", "sectionTitle:Albums", "row:albums:new", "personalSkeleton", "row:albums:editorial"),
            render(p),
        )
        val songRow = p.blocks.first()
        assertEquals("Tracks", songRow.title)
        assertFalse(songRow.subtitle)
        assertEquals("song", songRow.type)
        val newRow = p.blocks[2]
        assertEquals("New for you", newRow.title)
        assertTrue(newRow.subtitle)
    }

    @Test
    fun `an album item carries year and coming label and its source name`() {
        val sources = listOf(PageSource("lastfm", "Last.fm"))
        val albums = RecommendationPageSection(
            RecommendationType.ALBUM, "Albums",
            listOf(row("type:album", RecommendationType.ALBUM, listOf(item("Soon", RecommendationType.ALBUM, releaseDate = "2027-02-03")), sources = sources)),
        )
        val it = iosRecommendationsPage(page(albums), null, emptySet(), today).blocks.single().items.single()
        assertEquals("album", it.type)
        assertEquals("2027", it.year)
        assertEquals("Coming Feb 3, 2027", it.comingLabel)
        assertEquals("Last.fm", it.sourceName)
    }

    @Test
    fun `a weekly playlist item carries its weekly fields`() {
        val weekly = WeeklyInfo("mbid-1", "weekly-jam", "Weekly Jams", "This week")
        val linked = item("Mix", RecommendationType.PLAYLIST, source = "spotify", url = "https://open.spotify.com/playlist/x")
        val playlists = RecommendationPageSection(
            RecommendationType.PLAYLIST, "Playlists",
            listOf(row("type:playlist", RecommendationType.PLAYLIST, listOf(item("Jams", RecommendationType.PLAYLIST, source = "listenbrainz", weekly = weekly), linked))),
        )
        val items = iosRecommendationsPage(page(playlists), null, emptySet(), today).blocks.single().items
        val w = items[0]
        assertEquals("mbid-1", w.weeklyId)
        assertEquals("weekly-jam", w.weeklyContextType)
        assertEquals("Weekly Jams", w.weeklyLabel)
        assertEquals("This week", w.weeklyWeekLabel)
        assertFalse(w.opensView)
        assertNull(items[1].weeklyId)
        assertTrue(items[1].opensView)
    }

    @Test
    fun `a listener carries its badge and service name and the Achordion flag`() {
        val lb = ListenerItem("similar:listenbrainz:ann", "ann", "listenbrainz", 0.876, null, listOf("A", "B"), "listenbrainz", listOf("listenbrainz"))
        val lf = ListenerItem("similar:lastfm:bob", "bob", "lastfm", null, null, emptyList(), "lastfm", listOf("lastfm"))
        val listeners = RecommendationPageSection(null, "Listeners", listOf(row("friends:similar", null, listeners = listOf(lb, lf))))
        val block = iosRecommendationsPage(page(listeners), null, emptySet(), today).blocks.single()
        assertEquals("listener", block.type)
        val (a, b) = block.listeners
        assertEquals("88%", a.badge)
        assertEquals("ListenBrainz", a.serviceName)
        assertTrue(a.showsAchordion)
        assertEquals("A, B", a.topArtists)
        assertEquals("https://achordion.xyz/user/ann", a.achordionUrl)
        assertNull(b.badge)
        assertEquals("Last.fm", b.serviceName)
        assertFalse(b.showsAchordion)
        assertNull(b.achordionUrl)
    }

    @Test
    fun `the filter and the expanded set apply`() {
        val items = (1..7).map { item("s$it", source = if (it == 7) "listenbrainz" else "lastfm") }
        val sources = listOf(PageSource("lastfm", "Last.fm"), PageSource("listenbrainz", "ListenBrainz"))
        val songs = RecommendationPageSection(RecommendationType.SONG, "Tracks", listOf(row("type:song", RecommendationType.SONG, items, sources = sources)))
        val state = page(songs, sources = sources)

        val collapsed = iosRecommendationsPage(state, null, emptySet(), today).blocks.single()
        assertEquals(5, collapsed.items.size)
        assertEquals(RecommendationCopy.SEE_ALL, collapsed.toggleLabel)
        assertFalse(collapsed.expanded)

        val open = iosRecommendationsPage(state, null, setOf("type:song"), today).blocks.single()
        assertEquals(7, open.items.size)
        assertEquals(RecommendationCopy.SHOW_LESS, open.toggleLabel)
        assertTrue(open.expanded)

        val filtered = iosRecommendationsPage(state, "listenbrainz", emptySet(), today)
        assertEquals("listenbrainz", filtered.selectedSource)
        assertTrue(filtered.filteringOne)
        assertEquals(listOf("s7"), filtered.blocks.single().items.map { it.name })
        assertNull(filtered.blocks.single().toggleLabel)

        // A source no longer on the page falls back to All.
        assertNull(iosRecommendationsPage(state, "spotify", emptySet(), today).selectedSource)
    }

    @Test
    fun `sources carry their color id and the Shuffleupagus flag`() {
        val songs = RecommendationPageSection(RecommendationType.SONG, "Tracks", listOf(row("type:song", RecommendationType.SONG, listOf(item("a")))))
        val p = iosRecommendationsPage(page(songs, sources = listOf(PageSource("lastfm", "Last.fm"), PageSource("shuffleupagus", "Shuffleupagus"))), null, emptySet(), today)
        assertEquals(listOf("lastfm", "shuffleupagus"), p.sources.map { it.colorId })
        assertEquals(listOf(false, true), p.sources.map { it.isShuffleupagus })
    }

    @Test
    fun `bridge calls return a message instead of throwing`() = runTest {
        val result = listenerQueueResult("lastfm", "bob", "bob") { _, _ -> throw IllegalStateException("boom") }
        assertNull(result.queue)
        assertEquals(RecommendationCopy.listenerTracksFailed("bob"), result.message)

        val none = listenerQueueResult("lastfm", "bob", "bob") { _, _ -> emptyList() }
        assertEquals(RecommendationCopy.listenerNoTracks("bob"), none.message)

        val played = listenerQueueResult("lastfm", "bob", "bob") { _, _ -> listOf(Track(id = "t", title = "T", artist = "A")) }
        assertEquals(1, played.queue?.tracks?.size)
        assertNull(played.message)
    }

    @Test
    fun `a weekly queue says Playing and an empty one says Couldn't load`() = runTest {
        val weekly = WeeklyInfo("mbid-1", "weekly-jam", "Weekly Jams", "This week")
        val jams = item("Jams", RecommendationType.PLAYLIST, weekly = weekly)
        val empty = weeklyQueueResult(jams, "Jams") { emptyList() }
        assertNull(empty.queue)
        assertEquals(RecommendationCopy.couldntLoad("Jams"), empty.message)

        val failed = weeklyQueueResult(jams, "Jams") { throw IllegalStateException("offline") }
        assertEquals(RecommendationCopy.couldntLoad("Jams"), failed.message)

        val ok = weeklyQueueResult(jams, "Jams") {
            listOf(com.parachord.shared.api.LbPlaylistTrack(id = "1", title = "T", artist = "A"))
        }
        assertEquals(RecommendationCopy.playing("Jams"), ok.message)
        assertEquals("weekly-jam", ok.queue?.context?.type)

        // The card left the page: the message names what Swift drew.
        val gone = weeklyQueueResult(null, "Weekly Jams") { error("not asked") }
        assertNull(gone.queue)
        assertEquals(RecommendationCopy.couldntLoad("Weekly Jams"), gone.message)
    }
}
