package com.parachord.shared.ios

import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.api.Announcement
import com.parachord.shared.car.CarPlayRequest
import com.parachord.shared.home.ConcertArtistImages
import com.parachord.shared.home.HistoryMix
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeFeed
import com.parachord.shared.home.HomeQueues
import com.parachord.shared.home.HomeRecsLoad
import com.parachord.shared.home.HomeSources
import com.parachord.shared.home.HomeHalves
import com.parachord.shared.home.HomeState
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueues
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop
import com.parachord.shared.home.HistoryMixEntry
import com.parachord.shared.repository.HistoryMixesState
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosHomePageTest {

    private class FakeSecureStore : SecureTokenStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String): Boolean = m.containsKey(key)
        override fun observe(key: String): Flow<String?> = flowOf(m[key])
    }

    private class FakeSources : HomeSources {
        var now = 1_800_000_000_000L

        override val playlists = MutableStateFlow<List<Playlist>>(emptyList())
        override val collectionTracks = MutableStateFlow<List<Track>>(emptyList())
        override val hasCollection = MutableStateFlow(true)
        override val playingContext = MutableStateFlow<PlaybackContext?>(null)
        override val isPlaying = MutableStateFlow(false)
        override val announcements = MutableStateFlow<List<Announcement>>(emptyList())

        override val historyMixes = MutableStateFlow(HistoryMixesState())
        override suspend fun loadHistoryMixes(force: Boolean) = HomeRecsLoad(fetched = 1, failed = 0, sectionCount = 1)

        override val recommendationSections = MutableStateFlow<List<RecommendationPageSection>?>(null)
        override val aiAlbums = MutableStateFlow<List<AiAlbumSuggestion>?>(null)
        var recLoads = 0
        override suspend fun loadRecommendations(force: Boolean): HomeRecsLoad {
            recLoads++
            return HomeRecsLoad(fetched = 2, failed = 0, sectionCount = 3)
        }

        override val freshDrops = MutableStateFlow(HomeFeed<FreshDrop>())
        override val criticalDarlings = MutableStateFlow(HomeFeed<CriticsPickAlbum>())
        override val charts = MutableStateFlow(HomeFeed<ChartAlbum>())
        override suspend fun loadFeeds(force: Boolean) {}

        override val concerts = MutableStateFlow(HomeFeed<ConcertEvent>())
        var location: SettingsStore.ConcertLocation? = null
        override suspend fun concertLocation(): SettingsStore.ConcertLocation? = location
        override suspend fun loadConcerts() {}
        override val concertArtistImages = ConcertArtistImages { null }

        override val chatAvailable = MutableStateFlow<Boolean?>(null)
        override val plugins = MutableStateFlow(listOf(SPOTIFY))
        override val accounts = MutableStateFlow(mapOf("spotify" to true))
        override val playbackResolvers = MutableStateFlow(listOf("spotify"))
        override val folderAdded = MutableStateFlow(false)
        override val scrobblerAccounts = MutableStateFlow<Map<String, Boolean>>(emptyMap())

        override fun nowMs(): Long = now
        override fun hour(): Int = 9
    }

    private companion object {
        val SPOTIFY = PluginInfo(
            id = "spotify", name = "Spotify", version = "1.0.0",
            capabilities = mapOf("resolve" to true, "stream" to true), requiresAuth = true,
        )
        val TODAY = LocalDate(2026, 11, 20)
    }

    private fun settings() = SettingsStore(FakeSecureStore(), KvStore(MapSettings()))

    private fun homeTest(block: suspend TestScope.(CoroutineScope) -> Unit): TestResult = runTest {
        val job = Job()
        try {
            block(CoroutineScope(coroutineContext.minusKey(Job) + job))
        } finally {
            job.cancel()
        }
    }

    private fun queues(mixTracks: suspend (String) -> List<Track> = { emptyList() }) = HomeQueues(
        lovedTracks = { emptyList() },
        playlistTracks = { emptyList() },
        mixTracks = mixTracks,
        catalogQueues = CatalogQueues(MetadataService(emptyList(), { emptySet() })),
        collectionRadio = { null as CarPlayRequest? },
    )

    private fun session(scope: CoroutineScope, src: FakeSources, q: HomeQueues = queues()) = IosHomeSession(
        sources = src,
        settings = settings(),
        queues = q,
        parentScope = scope,
        lookupAlbumArt = { _, _ -> null },
        albumCardShown = {},
        dismissAnnouncement = {},
        ctaClicked = {},
        playlistArt = { it },
        today = { TODAY },
    )

    private fun mix(id: String) = HistoryMix(
        id = id, name = id, title = "Mix $id", url = "https://example.com/$id",
        resolverId = "listenbrainz", resolverName = "ListenBrainz",
    )

    private fun mixes(vararg m: HistoryMix) = HistoryMixesState(
        entries = listOf(HistoryMixEntry("listenbrainz", "ListenBrainz", m.toList())),
        flat = m.toList(),
    )

    @Test
    fun `a fresh drops failure keeps the other sections`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.playlists.value = listOf(Playlist(id = "p1", name = "Daily Brew", createdAt = 1, lastModified = 5))
        src.freshDrops.value = HomeFeed(loading = false, failed = true)
        src.charts.value = HomeFeed(loading = false, failed = true)
        val s = session(scope, src)
        s.enteredHome()
        s.onVisible()
        advanceUntilIdle()
        val page = s.watch().first()
        val charts = page.halves.single { it.kind == "charts" }
        assertEquals("failed", charts.body)
        assertEquals(HomeCopy.CHARTS_ERROR_TITLE, charts.errorTitle)
        assertEquals("POP_OF_THE_TOPS", charts.pageColorId)
        assertEquals(listOf("p1"), page.playlists.map { it.id })
        assertTrue(page.showPlaylists)
    }

    @Test
    fun `the playing mix is not a tile`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val m = mix("daily")
        src.historyMixes.value = mixes(m)
        val s = session(scope, src)
        advanceUntilIdle()
        assertTrue(s.watch().first().tiles.any { it.kind == "mix" })
        // What IosContainer.updateHomePlayback pushes into the sources' playing context.
        src.playingContext.value = PlaybackContext("mix", m.title, m.url)
        src.isPlaying.value = true
        advanceUntilIdle()
        assertTrue(s.watch().first().tiles.none { it.kind == "mix" })
    }

    @Test
    fun `two visits within ten minutes load recommendations once`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val s = session(scope, src)
        s.enteredHome()
        s.onVisible()
        advanceUntilIdle()
        src.now += 5 * 60_000L
        // A return from a pushed page, then a fresh entry: the one session sees both.
        s.onVisible()
        s.enteredHome()
        s.onVisible()
        advanceUntilIdle()
        assertEquals(1, src.recLoads)
    }

    @Test
    fun `a mix queue that fails returns the message and does not throw`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val m = mix("daily")
        src.historyMixes.value = mixes(m)
        val s = session(scope, src, queues(mixTracks = { throw IllegalStateException("offline") }))
        advanceUntilIdle()
        val key = s.watch().first().mixCards.single { !it.isLoves }.key
        val q = s.mixQueue(key)
        assertTrue(q.tracks.isEmpty())
        assertNull(q.context)
        assertEquals(HomeCopy.couldNotLoad(m.title), q.message)
        // A stale key plays nothing and says nothing.
        val stale = s.mixQueue("mix:gone")
        assertTrue(stale.tracks.isEmpty())
        assertNull(stale.message)
    }

    @Test
    fun `a concert with no tour artist has no open label`() {
        val event = ConcertEvent(id = "e1", name = "A Festival", venueName = "The Park", city = "Austin", date = "2026-12-04")
        val named = event.copy(id = "e2", artistName = "Hovvdy")
        val page = iosHomePage(HomeState(concerts = listOf(event, named)), emptyMap(), emptyMap(), TODAY)
        val none = page.concerts.single { it.id == "e1" }
        assertNull(none.tourArtist)
        assertNull(none.openLabel)
        assertEquals("A Festival, Dec 4, The Park, Austin", none.a11yLabel)
        val hovvdy = page.concerts.single { it.id == "e2" }
        assertEquals(HomeCopy.concertsOf("Hovvdy"), hovvdy.openLabel)
    }

    @Test
    fun `chat availability unknown maps to unknown`() {
        assertEquals("unknown", iosHomePage(HomeState(chatAvailable = null), emptyMap(), emptyMap(), TODAY).chatAvailable)
        assertEquals("yes", iosHomePage(HomeState(chatAvailable = true), emptyMap(), emptyMap(), TODAY).chatAvailable)
        assertEquals("no", iosHomePage(HomeState(chatAvailable = false), emptyMap(), emptyMap(), TODAY).chatAvailable)
        assertNotNull(iosHomePage(HomeState(), emptyMap(), emptyMap(), TODAY).askExample)
    }

    @Test
    fun `the halves draw Fresh Drops then Critical Darlings then Pop of the Tops`() {
        fun kinds(h: HomeHalves) =
            iosHomePage(HomeState(halves = h), emptyMap(), emptyMap(), TODAY).halves.map { it.kind }
        assertEquals(listOf("fresh", "critical"), kinds(HomeHalves(fresh = true, critical = true, charts = false)))
        assertEquals(listOf("fresh", "critical", "charts"), kinds(HomeHalves(fresh = true, critical = true, charts = true)))
        assertEquals(listOf("critical", "charts"), kinds(HomeHalves(fresh = false, critical = true, charts = true)))
    }
}
