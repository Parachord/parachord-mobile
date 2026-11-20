package com.parachord.shared.resolver

import com.parachord.shared.api.SpAlbumRef
import com.parachord.shared.api.SpArtistRef
import com.parachord.shared.api.SpSearchResponse
import com.parachord.shared.api.SpTrack
import com.parachord.shared.api.SpPaginated
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.plugin.PluginManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shuffleupagus's `search` is desktop's `searchResolvers(query)`: the person's services in their order. */
class ResolverSearchTest {

    private var order = listOf("spotify", "applemusic", "bandcamp", "soundcloud", "localfiles")
    private var active = emptyList<String>()
    private var disabled = emptySet<String>()
    private var plugins = listOf(plugin("bandcamp"), plugin("soundcloud"), plugin("lastfm", search = false))
    private val asked = mutableListOf<String>()

    private val natives = mutableMapOf<String, suspend (String) -> List<TrackSearchResult>?>(
        "spotify" to { q -> asked += "spotify"; rows("spotify", q, 2) },
        "applemusic" to { q -> asked += "applemusic"; rows("applemusic", q, 2) },
        "localfiles" to { q -> asked += "localfiles"; rows("localfiles", q, 1) },
    )
    private var pluginAnswers: suspend (String, String) -> List<TrackSearchResult> = { id, q -> rows(id, q, 2) }

    private fun search() = ResolverSearch(
        getOrder = { order },
        getActive = { active },
        getDisabled = { disabled },
        getPlugins = { plugins },
        nativeSearchers = natives,
        searchPlugin = { id, q -> asked += id; pluginAnswers(id, q) },
    )

    @Test
    fun `results follow the resolver order and keep each service's own order`() = runTest {
        val results = search().search("IDLES", limit = 50)

        assertEquals(
            listOf("spotify", "spotify", "applemusic", "applemusic", "bandcamp", "bandcamp", "soundcloud", "soundcloud", "localfiles"),
            results.map { it.provider },
        )
        assertEquals(listOf("spotify 0", "spotify 1"), results.take(2).map { it.title })
    }

    @Test
    fun `a reordered list puts the first service first`() = runTest {
        order = listOf("bandcamp", "spotify")

        val ids = search().resolverIds()

        assertEquals(listOf("bandcamp", "spotify", "applemusic", "localfiles", "soundcloud"), ids)
    }

    @Test
    fun `results are capped at ten by default`() = runTest {
        natives["spotify"] = { q -> rows("spotify", q, 20) }

        val results = search().search("IDLES")

        assertEquals(ResolverSearch.DEFAULT_LIMIT, results.size)
        assertTrue(results.all { it.provider == "spotify" })
    }

    @Test
    fun `results are tagged with the resolver they came from`() = runTest {
        pluginAnswers = { _, _ -> listOf(TrackSearchResult(title = "Danny Nedelko", artist = "IDLES", provider = "wrong")) }

        val results = search().search("IDLES", limit = 50)

        assertEquals("bandcamp", results.first { it.title == "Danny Nedelko" }.provider)
    }

    @Test
    fun `an active list leaves out the resolvers it doesn't name`() = runTest {
        active = listOf("spotify", "bandcamp")

        search().search("IDLES", limit = 50)

        assertEquals(setOf("spotify", "bandcamp"), asked.toSet())
    }

    @Test
    fun `a switched-off plugin and one that can't search aren't asked`() = runTest {
        disabled = setOf("soundcloud")

        search().search("IDLES", limit = 50)

        assertTrue("soundcloud" !in asked)
        assertTrue("lastfm" !in asked)
    }

    @Test
    fun `a native search that isn't connected is skipped`() = runTest {
        natives["applemusic"] = { null }

        val results = search().search("IDLES", limit = 50)

        assertTrue(results.none { it.provider == "applemusic" })
    }

    @Test
    fun `a resolver that throws is skipped and the others still answer`() = runTest {
        natives["spotify"] = { throw IllegalStateException("cooling down") }
        pluginAnswers = { id, q -> if (id == "bandcamp") throw IllegalStateException("timed out") else rows(id, q, 1) }

        val results = search().search("IDLES", limit = 50)

        assertEquals(listOf("applemusic", "applemusic", "soundcloud", "localfiles"), results.map { it.provider })
    }

    @Test
    fun `a blank query asks nobody`() = runTest {
        assertEquals(emptyList(), search().search("  "))
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `plugin results are read from the plugin track shape`() {
        val parsed = ResolverSearch.parsePluginResults(
            """[{"title":"Danny Nedelko","artist":"IDLES","album":"Joy as an Act of Resistance","duration":204,""" +
                """"albumArt":"https://f4.bcbits.com/img/a1_10.jpg","isrc":"gbcel1800123"},""" +
                """{"artist":"no title"},"junk",{"title":"Never Fight a Man With a Perm","appleMusicId":"1434901863"}]""",
        )

        assertEquals(2, parsed.size)
        assertEquals(204_000L, parsed[0].duration)
        assertEquals("https://f4.bcbits.com/img/a1_10.jpg", parsed[0].artworkUrl)
        assertEquals("GBCEL1800123", parsed[0].isrc)
        assertEquals("1434901863", parsed[1].appleMusicId)
        assertEquals(emptyList(), ResolverSearch.parsePluginResults("{}"))
        assertEquals(emptyList(), ResolverSearch.parsePluginResults(null))
    }

    @Test
    fun `spotify results keep playable tracks in spotify's order`() {
        val response = SpSearchResponse(
            tracks = SpPaginated(
                items = listOf(
                    SpTrack(id = "a", name = "Colossus", artists = listOf(SpArtistRef(name = "IDLES")), album = SpAlbumRef(name = "Joy")),
                    SpTrack(id = "b", name = "Gone", artists = listOf(SpArtistRef(name = "IDLES")), isPlayable = false),
                    SpTrack(id = "c", name = "Mother", artists = listOf(SpArtistRef(name = "IDLES"))),
                ),
            ),
        )

        val results = ResolverSearch.spotifyResults(response)

        assertEquals(listOf("Colossus", "Mother"), results.map { it.title })
        assertEquals("a", results[0].spotifyId)
        assertEquals("Joy", results[0].album)
        assertNull(results[1].album)
    }

    @Test
    fun `the search tool answers in desktop's shape`() {
        val results = (0 until 12).map { TrackSearchResult(title = "t$it", artist = "a", provider = "spotify") }

        val answer = searchToolResult("IDLES", results, limit = 10)

        @Suppress("UNCHECKED_CAST")
        val rows = answer["results"] as List<Map<String, Any?>>
        assertEquals(10, rows.size)
        assertEquals("spotify", rows[0]["source"])
        assertEquals(12, answer["total"])
        assertEquals("No results found for \"x\"", searchToolResult("x", emptyList(), 10)["message"])
    }

    private fun rows(id: String, query: String, n: Int) =
        (0 until n).map { TrackSearchResult(title = "$id $it", artist = query) }

    private fun plugin(id: String, search: Boolean = true) = PluginManager.PluginInfo(
        id = id, name = id, version = "1.0.0", capabilities = mapOf("resolve" to search, "search" to search),
    )
}
