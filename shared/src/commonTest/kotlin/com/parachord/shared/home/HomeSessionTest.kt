package com.parachord.shared.home

import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.api.Announcement
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSessionTest {

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
        var hourOfDay = 9

        override val playlists = MutableStateFlow<List<Playlist>>(emptyList())
        override val collectionTracks = MutableStateFlow<List<Track>>(emptyList())
        override val hasCollection = MutableStateFlow(false)
        override val playingContext = MutableStateFlow<PlaybackContext?>(null)
        override val isPlaying = MutableStateFlow(false)
        override val announcements = MutableStateFlow<List<Announcement>>(emptyList())

        override val historyMixes = MutableStateFlow(HistoryMixesState())
        var mixLoads = 0
        var mixResult = HomeRecsLoad(fetched = 1, failed = 0, sectionCount = 2)
        override suspend fun loadHistoryMixes(force: Boolean): HomeRecsLoad { mixLoads++; return mixResult }

        override val recommendationSections = MutableStateFlow<List<RecommendationPageSection>?>(null)
        override val aiAlbums = MutableStateFlow<List<AiAlbumSuggestion>?>(null)
        var recLoads = 0
        var recResult = HomeRecsLoad(fetched = 2, failed = 0, sectionCount = 3)
        override suspend fun loadRecommendations(force: Boolean): HomeRecsLoad { recLoads++; return recResult }

        override val freshDrops = MutableStateFlow(HomeFeed<FreshDrop>())
        override val criticalDarlings = MutableStateFlow(HomeFeed<CriticsPickAlbum>())
        override val charts = MutableStateFlow(HomeFeed<ChartAlbum>())
        var feedLoads = mutableListOf<Boolean>()
        override suspend fun loadFeeds(force: Boolean) { feedLoads += force }

        override val concerts = MutableStateFlow(HomeFeed<ConcertEvent>())
        var location: SettingsStore.ConcertLocation? = null
        override suspend fun concertLocation(): SettingsStore.ConcertLocation? = location
        var concertLoads = 0
        override suspend fun loadConcerts() { concertLoads++ }
        val photoLookups = mutableListOf<String>()
        var photo: (String) -> String? = { null }
        override val concertArtistImages = ConcertArtistImages { name -> photoLookups += name; photo(name) }

        override val chatAvailable = MutableStateFlow<Boolean?>(true)
        override val plugins = MutableStateFlow(listOf(SPOTIFY))
        override val accounts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        override val playbackResolvers = MutableStateFlow<List<String>>(emptyList())
        override val folderAdded = MutableStateFlow(false)
        override val scrobblerUsernames = MutableStateFlow<List<String?>>(emptyList())

        override fun nowMs(): Long = now
        override fun hour(): Int = hourOfDay
    }

    private companion object {
        /** A service that needs an account and plays here (step 1's tile). */
        val SPOTIFY = PluginInfo(
            id = "spotify", name = "Spotify", version = "1.0.0",
            capabilities = mapOf("resolve" to true, "stream" to true), requiresAuth = true,
        )
    }

    private fun settings(): SettingsStore = SettingsStore(FakeSecureStore(), KvStore(MapSettings()))

    /**
     * The session runs on the test's dispatcher but not as a background
     * coroutine: `advanceUntilIdle` doesn't wait for background work. Its job
     * is cancelled when the test ends.
     */
    private fun homeTest(block: suspend TestScope.(CoroutineScope) -> Unit): TestResult = runTest {
        val job = Job()
        try {
            block(CoroutineScope(coroutineContext.minusKey(Job) + job))
        } finally {
            job.cancel()
        }
    }

    private fun session(scope: CoroutineScope, sources: HomeSources, settings: SettingsStore = settings()): HomeSession =
        HomeSession(sources, settings, scope)

    private fun love(id: String, artist: String, addedAt: Long, album: String? = "Album $id") =
        Track(id = id, title = "Song $id", artist = artist, album = album, addedAt = addedAt)

    private fun mix(id: String, resolver: String) = HistoryMix(
        id = id, name = id, title = "Mix $id", url = "https://example.com/$id",
        resolverId = resolver, resolverName = resolver.replaceFirstChar { it.uppercase() },
    )

    @Test
    fun `a user with nothing gets the getting-started layout until they skip it`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val store = settings()
        val s = session(scope, src, store)
        advanceUntilIdle()
        assertNotNull(s.state.value.gettingStarted)
        assertTrue(s.state.value.tiles.isEmpty())
        assertTrue(s.state.value.ready)
        assertEquals(true, store.getHomeGettingStarted()?.active)

        s.skipSetup()
        advanceUntilIdle()
        assertNull(s.state.value.gettingStarted)
        assertTrue(s.state.value.setup!!.items.any { it.id == "getting-started" })
        assertEquals(true, store.getHomeGettingStarted()?.skipped)

        s.resumeSetup()
        advanceUntilIdle()
        assertNotNull(s.state.value.gettingStarted)
    }

    @Test
    fun `an existing user with a collection and a playlist never sees it`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.hasCollection.value = true
        src.collectionTracks.value = listOf(love("1", "Portishead", 10))
        src.playlists.value = listOf(Playlist(id = "p1", name = "Daily Brew", createdAt = 1))
        val store = settings()
        val s = session(scope, src, store)
        advanceUntilIdle()
        assertNull(s.state.value.gettingStarted)
        assertEquals(false, store.getHomeGettingStarted()?.active)
    }

    @Test
    fun `playback while it shows is saved as played but a track that isn't playing is not`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val store = settings()
        session(scope, src, store)
        advanceUntilIdle()
        // A failed play, or a restored queue left paused: a track, nothing playing.
        assertEquals(false, store.getHomeGettingStarted()?.played)
        src.isPlaying.value = true
        advanceUntilIdle()
        assertEquals(true, store.getHomeGettingStarted()?.played)
    }

    @Test
    fun `nothing is decided until the plugins load`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.plugins.value = emptyList()
        src.accounts.value = mapOf("spotify" to true)
        src.playbackResolvers.value = listOf("spotify")
        val store = settings()
        val s = session(scope, src, store)
        // Within the timeout: advanceUntilIdle would run the clock past it.
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(s.state.value.ready)
        assertNull(s.state.value.gettingStarted)
        assertNull(store.getHomeGettingStarted())

        src.plugins.value = listOf(SPOTIFY)
        runCurrent()
        advanceUntilIdle()
        assertTrue(s.state.value.ready)
        assertNull(s.state.value.gettingStarted)
        assertEquals(false, store.getHomeGettingStarted()?.active)
    }

    @Test
    fun `a dismissed setup item is saved and leaves the pill`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val store = settings()
        val s = session(scope, src, store)
        advanceUntilIdle()
        s.skipSetup()
        advanceUntilIdle()
        s.dismissSetupItem("getting-started")
        advanceUntilIdle()
        assertNull(s.state.value.setup)
        assertEquals(setOf("getting-started"), store.getHomeSetupDismissed())
    }

    @Test
    fun `loves playing leave the loves tile out of Jump back in`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.hasCollection.value = true
        src.collectionTracks.value = listOf(love("1", "Portishead", 10), love("2", "Air", 20))
        val s = session(scope, src)
        advanceUntilIdle()
        assertTrue(s.state.value.tiles.any { it is JumpTile.Loves })

        src.playingContext.value = PlaybackContext(type = "loves", name = "Recent Loves")
        advanceUntilIdle()
        assertFalse(s.state.value.tiles.any { it is JumpTile.Loves })
    }

    @Test
    fun `plugins that never load give the normal page after the timeout and save nothing`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.plugins.value = emptyList()
        val store = settings()
        val s = session(scope, src, store)
        advanceTimeBy(HomeSession.GETTING_STARTED_DECIDE_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(s.state.value.ready)

        advanceTimeBy(2)
        runCurrent()
        advanceUntilIdle()
        assertTrue(s.state.value.ready)
        assertNull(s.state.value.gettingStarted)
        assertNull(store.getHomeGettingStarted())
    }

    @Test
    fun `a saved state decides at once without waiting for the plugins`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.plugins.value = emptyList()
        val store = settings()
        store.setHomeGettingStarted(GettingStartedState(active = true))
        val s = session(scope, src, store)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertTrue(s.state.value.ready)
        assertNotNull(s.state.value.gettingStarted)
    }

    @Test
    fun `a mix playing gives Jump back in the next mix`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.hasCollection.value = true
        src.historyMixes.value = HistoryMixesState(flat = listOf(mix("a", "listenbrainz"), mix("b", "listenbrainz")))
        val s = session(scope, src)
        advanceUntilIdle()
        assertEquals("a", (s.state.value.tiles.single { it is JumpTile.Mix } as JumpTile.Mix).mix.id)

        src.playingContext.value = PlaybackContext(type = "mix", name = "Mix a", id = "a")
        advanceUntilIdle()
        assertEquals("b", (s.state.value.tiles.single { it is JumpTile.Mix } as JumpTile.Mix).mix.id)
    }

    @Test
    fun `recommendations and mixes load once within ten minutes`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        src.now += 5 * 60_000L
        s.onVisible()
        advanceUntilIdle()
        assertEquals(1, src.recLoads)
        assertEquals(1, src.mixLoads)

        src.now += 6 * 60_000L
        s.onVisible()
        advanceUntilIdle()
        assertEquals(2, src.recLoads)
        assertEquals(2, src.mixLoads)
    }

    @Test
    fun `mixes with nothing to show don't count for the throttle`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.mixResult = HomeRecsLoad(fetched = 1, failed = 0, sectionCount = 0)
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        // No mixes to show, so the next visit asks again.
        src.now += 60_000L
        s.onVisible()
        advanceUntilIdle()
        assertEquals(2, src.mixLoads)
    }

    @Test
    fun `a mixes load where every provider failed is asked again next visit`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.mixResult = HomeRecsLoad(fetched = 2, failed = 2, sectionCount = 4)
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        src.now += 60_000L
        s.onVisible()
        advanceUntilIdle()
        assertEquals(2, src.mixLoads)
    }

    @Test
    fun `a recommendations load where every provider failed is asked again next visit`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.recResult = HomeRecsLoad(fetched = 2, failed = 2, sectionCount = 3)
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        src.now += 60_000L
        s.onVisible()
        advanceUntilIdle()
        assertEquals(2, src.recLoads)
    }

    @Test
    fun `a load that throws keeps the other loads and the session running`(): TestResult = homeTest { scope ->
        val fake = FakeSources()
        fake.location = SettingsStore.ConcertLocation(latitude = null, longitude = null, city = "Austin", radiusMiles = 50)
        val src = object : HomeSources by fake {
            override suspend fun loadRecommendations(force: Boolean): HomeRecsLoad = error("offline")
            override suspend fun loadHistoryMixes(force: Boolean): HomeRecsLoad = error("offline")
        }
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        assertTrue(s.state.value.ready)
        assertEquals(listOf(false), fake.feedLoads)
        assertEquals(1, fake.concertLoads)
    }

    @Test
    fun `Fresh Drops failed and empty with charts failed shows the charts error half and the rest`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.hasCollection.value = true
        src.collectionTracks.value = listOf(love("1", "Portishead", 10))
        src.playlists.value = listOf(Playlist(id = "p1", name = "Daily Brew", createdAt = 1))
        src.freshDrops.value = HomeFeed(failed = true)
        src.charts.value = HomeFeed(failed = true)
        src.criticalDarlings.value = HomeFeed(
            items = (1..5).map { CriticsPickAlbum(id = "c$it", title = "T$it", artist = "A$it") },
        )
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        val st = s.state.value
        assertTrue(st.halves.charts)
        assertFalse(st.halves.fresh)
        assertTrue(st.halves.critical)
        assertEquals(HOME_HALF_ROW_CARDS, st.critical.items.size)
        assertTrue(st.tiles.isNotEmpty())
        assertEquals(1, st.playlists.size)
        assertTrue(st.sections.critical)
    }

    @Test
    fun `retry charts forces the feeds`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val s = session(scope, src)
        advanceUntilIdle()
        s.retryCharts()
        advanceUntilIdle()
        assertEquals(listOf(true), src.feedLoads)
    }

    @Test
    fun `Recent Loves leads the mixes under All and a source shows only its mixes`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.hasCollection.value = true
        src.collectionTracks.value = listOf(love("1", "Portishead", 10))
        val mixes = listOf(mix("a", "listenbrainz"), mix("b", "applemusic"), mix("c", "listenbrainz"))
        src.historyMixes.value = HistoryMixesState(
            entries = listOf(HistoryMixEntry("listenbrainz", "ListenBrainz", mixes)),
            flat = mixes,
        )
        val s = session(scope, src)
        advanceUntilIdle()
        val all = s.state.value
        assertTrue(all.mixRow[0] is HomeMixCard.Loves)
        assertEquals(4, all.mixRow.size)
        assertEquals(2, all.mixSources.size)
        assertEquals("All", all.mixSource)

        s.setMixSource("listenbrainz")
        advanceUntilIdle()
        val lb = s.state.value
        assertFalse(lb.mixRow.any { it is HomeMixCard.Loves })
        assertEquals(listOf("a", "c"), lb.mixRow.map { (it as HomeMixCard.Mix).mix.id })

        s.setMixSource("All")
        advanceUntilIdle()
        assertTrue(s.state.value.mixRow[0] is HomeMixCard.Loves)
    }

    @Test
    fun `a picked source that has gone leaves the row on All`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.historyMixes.value = HistoryMixesState(flat = listOf(mix("a", "listenbrainz"), mix("b", "applemusic")))
        val s = session(scope, src)
        advanceUntilIdle()
        s.setMixSource("spotify")
        advanceUntilIdle()
        assertEquals("All", s.state.value.mixSource)
        assertEquals(2, s.state.value.mixRow.size)
    }

    @Test
    fun `no concert location shows no concerts and loads none`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.concerts.value = HomeFeed(items = listOf(ConcertEvent(id = "e1", name = "Show", city = "Austin", date = "2030-01-01")))
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        assertTrue(s.state.value.concerts.isEmpty())
        assertFalse(s.state.value.sections.concerts)
        assertFalse(s.state.value.concertsLoading)
        assertEquals(0, src.concertLoads)
    }

    @Test
    fun `a concert location loads and shows nearby upcoming shows`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.location = SettingsStore.ConcertLocation(latitude = null, longitude = null, city = "Austin, TX", radiusMiles = 50)
        src.concerts.value = HomeFeed(
            items = listOf(
                ConcertEvent(id = "e1", name = "Show", city = "Austin", date = "2030-01-01"),
                ConcertEvent(id = "e2", name = "Far", city = "Boston", date = "2030-01-01"),
            ),
        )
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        assertEquals(listOf("e1"), s.state.value.concerts.map { it.id })
        assertTrue(s.state.value.sections.concerts)
        assertEquals(1, src.concertLoads)
    }

    @Test
    fun `concert artist photos are looked up for the shown cards only`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.location = SettingsStore.ConcertLocation(latitude = null, longitude = null, city = "Austin, TX", radiusMiles = 50)
        src.photo = { name -> if (name == "Artist 2") null else "https://img/$name" }
        src.concerts.value = HomeFeed(
            items = (1..6).map { i ->
                ConcertEvent(id = "e$i", name = "Show $i", artistName = "Artist $i", city = "Austin", date = "2030-01-0$i")
            } + ConcertEvent(id = "far", name = "Far", artistName = "Far Artist", city = "Boston", date = "2030-01-01"),
        )
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        assertEquals(HOME_CONCERT_CARDS, s.state.value.concerts.size)
        assertEquals((1..HOME_CONCERT_CARDS).map { "Artist $it" }, src.photoLookups.sorted())
        assertEquals("https://img/Artist 1", s.state.value.concertArtistImages["artist 1"])
        assertNull(s.state.value.concertArtistImages["artist 2"])
        // A return visit asks nothing again.
        s.onVisible()
        advanceUntilIdle()
        assertEquals(HOME_CONCERT_CARDS, src.photoLookups.size)
    }

    @Test
    fun `the Ask example keeps its phrasing for the visit and reseeds on request`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.now = 4_000L // seed 0
        val s = session(scope, src)
        advanceUntilIdle()
        assertEquals("Make me a playlist for right now", s.state.value.askExample)

        src.collectionTracks.value = listOf(love("1", "Portishead", 10))
        advanceUntilIdle()
        assertEquals("A rainy Sunday playlist built around Portishead", s.state.value.askExample)

        // Built from artists: a collection change doesn't move it this visit.
        src.collectionTracks.value = listOf(love("2", "Air", 20), love("1", "Portishead", 10))
        advanceUntilIdle()
        assertEquals("A rainy Sunday playlist built around Portishead", s.state.value.askExample)

        src.now = 4_001L // seed 1
        s.reseedAskExample()
        advanceUntilIdle()
        assertEquals("A late night mix with Air and Portishead", s.state.value.askExample)
    }

    @Test
    fun `the header carries the greeting and the banner the first announcement`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        src.hourOfDay = 20
        src.announcements.value = listOf(Announcement(id = "a1", title = "Hello"), Announcement(id = "a2", title = "Later"))
        val s = session(scope, src)
        advanceUntilIdle()
        assertEquals(HomeCopy.GREETING_EVENING, s.state.value.greeting)
        assertEquals("a1", s.state.value.banner?.id)
        assertTrue(s.state.value.sections.banner)
    }

    @Test
    fun `the greeting follows the hour from one visit to the next`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val s = session(scope, src)
        advanceUntilIdle()
        s.onVisible()
        advanceUntilIdle()
        assertEquals(HomeCopy.GREETING_MORNING, s.state.value.greeting)
        src.hourOfDay = 14
        s.onVisible()
        advanceUntilIdle()
        assertEquals(HomeCopy.GREETING_AFTERNOON, s.state.value.greeting)
    }

    @Test
    fun `a dismiss that lands before the saved set is read keeps both`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val store = settings()
        store.setHomeSetupDismissed(setOf("old"))
        val s = session(scope, src, store)
        s.dismissSetupItem("getting-started")
        advanceUntilIdle()
        assertEquals(setOf("old", "getting-started"), store.getHomeSetupDismissed())
    }

    @Test
    fun `a banner view is reported once per id`(): TestResult = homeTest { scope ->
        val viewed = mutableListOf<String>()
        val src = object : HomeSources by FakeSources() {
            override suspend fun bannerViewed(id: String) { viewed += id }
        }
        val s = session(scope, src)
        advanceUntilIdle()
        s.bannerShown("a1")
        s.bannerShown("a1")
        s.bannerShown("a2")
        advanceUntilIdle()
        assertEquals(listOf("a1", "a2"), viewed)
    }

    @Test
    fun `playlists are freshest first with their badge and subtitle`(): TestResult = homeTest { scope ->
        val src = FakeSources()
        val day = 86_400_000L
        src.playlists.value = listOf(
            Playlist(id = "old", name = "Old", createdAt = src.now - 400 * day),
            Playlist(id = "new", name = "New", createdAt = src.now - 2 * day),
        )
        val s = session(scope, src)
        advanceUntilIdle()
        assertEquals(listOf("new", "old"), s.state.value.playlists.map { it.playlist.id })
        assertEquals(playlistSubtitle(src.playlists.value[1], src.now), s.state.value.playlists[0].subtitle)
    }
}
