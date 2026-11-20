package com.parachord.shared.home

import com.parachord.shared.deeplink.ProtocolTrack
import com.parachord.shared.deeplink.ResolvedProtocolPlay
import com.parachord.shared.repository.RecommendationProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Desktop's history-mix rules (`home-model.js`): vectors from
 * `tests/renderer/home-model.test.js` ("history mix mosaics") and
 * `tests/resolver/history-mixes-loader.test.js` ("history mixes in the Home
 * model", "fix round 1").
 */
class HistoryMixesTest {

    private fun mix(id: String, imageUrl: String? = null, url: String = "https://x/$id", mosaic: List<String> = emptyList()) =
        HistoryMix(id = id, name = id, title = id, imageUrl = imageUrl, url = url, resolverId = "p", resolverName = "P", mosaic = mosaic)

    // ── normalizeHistoryMixes ────────────────────────────────────────

    @Test
    fun `normalizeHistoryMixes keeps openable playlists and names their provider`() {
        val out = normalizeHistoryMixes(
            """[
              {"type":"playlist","id":" pl.heavy ","name":" Heavy Rotation ","description":"Most played","imageUrl":"https://img/1.jpg","url":"https://x/pl.heavy"},
              {"id":"no-url","name":"No URL"},
              {"id":"","name":"No id","url":"https://x/1"},
              {"id":"no-name","url":"https://x/2"},
              {"type":"album","id":"alb","name":"Album","url":"https://x/3"},
              null
            ]""",
            "applemusic", "Apple Music",
        )
        assertEquals(
            listOf(
                HistoryMix(
                    id = "pl.heavy", name = "Heavy Rotation", title = "Heavy Rotation",
                    description = "Most played", imageUrl = "https://img/1.jpg", url = "https://x/pl.heavy",
                    resolverId = "applemusic", resolverName = "Apple Music",
                ),
            ),
            out,
        )
    }

    @Test
    fun `a non-array answer is no mixes`() {
        assertEquals(emptyList(), normalizeHistoryMixes("null", "x", "X"))
        assertEquals(emptyList(), normalizeHistoryMixes("{\"mixes\":[]}", "x", "X"))
        assertEquals(emptyList(), normalizeHistoryMixes("not json", "x", "X"))
        // A malformed entry is dropped; the rest stay.
        assertEquals(listOf("a"), normalizeHistoryMixes("""[null,"x",{"id":"a","name":"A","url":"u"}]""", "x", "X").map { it.id })
    }

    @Test
    fun `a provider with no name is named by its id`() {
        assertEquals("x", normalizeHistoryMixes("""[{"id":"a","name":"A","url":"u"}]""", "x", "").single().resolverName)
    }

    @Test
    fun `blank description and image are left out`() {
        val m = normalizeHistoryMixes("""[{"id":"a","name":"A","url":"u","description":"  ","imageUrl":""}]""", "x", "X").single()
        assertNull(m.description)
        assertNull(m.imageUrl)
    }

    // ── flattenHistoryMixes ──────────────────────────────────────────

    @Test
    fun `flattenHistoryMixes interleaves the providers in order and keeps an id once`() {
        val am = HistoryMixEntry("applemusic", "Apple Music", listOf(mix("a1"), mix("a2"), mix("a3")))
        val lb = HistoryMixEntry("listenbrainz", "ListenBrainz", listOf(mix("l1"), mix("a2")))
        assertEquals(listOf("a1", "l1", "a2", "a3"), flattenHistoryMixes(listOf(am, lb)).map { it.id })
        assertEquals(emptyList(), flattenHistoryMixes(emptyList()))
        assertEquals(emptyList(), flattenHistoryMixes(listOf(HistoryMixEntry("x", "x", emptyList()))))
    }

    @Test
    fun `Jump back in takes the first mix`() {
        val mixes = flattenHistoryMixes(
            listOf(
                HistoryMixEntry(
                    "applemusic", "Apple Music",
                    normalizeHistoryMixes("""[{"id":"pl.heavy","name":"Heavy Rotation","url":"https://x"}]""", "applemusic", "Apple Music"),
                ),
            ),
        )
        val tiles = jumpBackInTiles(emptyList(), emptyList(), mixes, ActiveContexts(), nowMs = 0L)
        val tile = tiles.single() as JumpTile.Mix
        assertEquals("pl.heavy", tile.mix.id)
        assertEquals("Heavy Rotation", tile.mix.title)
    }

    // ── historyMixCacheEntry / historyMixEntriesFor ──────────────────

    @Test
    fun `a cached entry counts only for the same account`() {
        val entry = HistoryMixCacheEntry("lb", "LB", listOf(mix("x")), fetchedAt = 1, account = "me")
        assertSame(entry, historyMixCacheEntry(entry, "me"))
        assertNull(historyMixCacheEntry(entry, "someone-else"))
        val noAccount = HistoryMixCacheEntry("lb", "LB", emptyList(), fetchedAt = 1, account = "")
        assertSame(noAccount, historyMixCacheEntry(noAccount, ""))
        assertSame(noAccount, historyMixCacheEntry(noAccount, null))
        assertNull(historyMixCacheEntry(null, ""))
    }

    @Test
    fun `entries follow provider priority and skip providers with no mixes`() {
        val byId = mapOf(
            "listenbrainz" to HistoryMixCacheEntry("listenbrainz", "listenbrainz", listOf(mix("l1")), 1, ""),
            "applemusic" to HistoryMixCacheEntry("applemusic", "Apple Music", listOf(mix("a1")), 1, ""),
            "amazonmusic" to HistoryMixCacheEntry("amazonmusic", "Amazon Music", emptyList(), 1, ""),
        )
        val providers = listOf(
            RecommendationProvider("applemusic", "Apple Music"),
            RecommendationProvider("amazonmusic", "Amazon Music"),
            RecommendationProvider("listenbrainz", ""),
        )
        assertEquals(
            listOf(
                HistoryMixEntry("applemusic", "Apple Music", listOf(mix("a1"))),
                HistoryMixEntry("listenbrainz", "listenbrainz", listOf(mix("l1"))),
            ),
            historyMixEntriesFor(providers) { byId[it.id] },
        )
    }

    // ── mosaics ──────────────────────────────────────────────────────

    private val jams = mix("lb-1", url = "https://listenbrainz.org/playlist/lb-1")
    private val replay = mix("am-1", imageUrl = "https://img/replay.jpg", url = "https://music.apple.com/x")

    @Test
    fun `a mix with no cover of its own needs a mosaic — once`() {
        assertEquals(listOf(jams), mixesNeedingMosaic(listOf(jams, replay), emptySet()))
        assertEquals(emptyList(), mixesNeedingMosaic(listOf(jams), setOf("lb-1")))
        assertEquals(emptyList(), mixesNeedingMosaic(listOf(jams.copy(mosaic = listOf("a"))), emptySet()))
        assertEquals(emptyList(), mixesNeedingMosaic(listOf(jams.copy(url = "")), emptySet()))
        assertEquals(emptyList(), mixesNeedingMosaic(emptyList(), emptySet()))
    }

    @Test
    fun `covers attach as each mix's mosaic — a mix without covers is unchanged`() {
        val mixes = listOf(jams, replay)
        val out = withMixMosaics(mixes, mapOf("lb-1" to listOf("a", "b", "c", "d", "e")))
        assertEquals(jams.copy(mosaic = listOf("a", "b", "c", "d")), out[0])
        assertSame(replay, out[1])
        assertSame(mixes, withMixMosaics(mixes, emptyMap()))
        assertSame(replay, withMixMosaics(listOf(replay), mapOf("am-1" to emptyList()))[0])
    }

    @Test
    fun `retry constants match desktop`() {
        assertEquals(3, HOME_MIX_COVER_ATTEMPTS)
        assertEquals(60_000L, HOME_MIX_COVER_RETRY_MS)
    }

    @Test
    fun `a mix's mosaic is its first four distinct track covers through the playlist lookup`() = runTest {
        val play = ResolvedProtocolPlay(
            displayName = "Weekly Jams",
            tracks = listOf("a", "a", null, "b", "c", "d", "e").mapIndexed { i, art -> ProtocolTrack("Artist", "T$i", artworkUrl = art) },
            providerId = "listenbrainz",
        )
        val asked = mutableListOf<String>()
        val covers = historyMixMosaicCovers(jams) { url -> asked += url; play }
        assertEquals(listOf("a", "b", "c", "d"), covers)
        assertEquals(listOf("https://listenbrainz.org/playlist/lb-1"), asked)
    }

    @Test
    fun `a playlist lookup that finds nothing throws so the mosaic is retried`() = runTest {
        assertFailsWith<IllegalStateException> { historyMixMosaicCovers(jams) { null } }
    }
}
