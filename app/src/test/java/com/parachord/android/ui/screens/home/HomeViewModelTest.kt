package com.parachord.android.ui.screens.home

import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackController
import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.api.Announcement
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.car.CarIds
import com.parachord.shared.car.CarPlayRequest
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.home.GettingStartedState
import com.parachord.shared.home.HomeFeed
import com.parachord.shared.home.HomeRecsLoad
import com.parachord.shared.home.HomeSources
import com.parachord.shared.metadata.ImageEnrichmentService
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.repository.AnnouncementsRepository
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop
import com.parachord.shared.repository.HistoryMixesState
import com.parachord.shared.repository.RecommendationsRepository
import com.parachord.shared.settings.SettingsStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Home's view model: a thin wrapper on the shared HomeSession, plus what Home's taps play. */
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeSources(tracks: List<Track> = emptyList()) : HomeSources {
        var recsLoads = 0
        var mixLoads = 0
        var feedLoads = 0
        override val playlists: Flow<List<Playlist>> = MutableStateFlow(emptyList())
        override val collectionTracks: Flow<List<Track>> = MutableStateFlow(tracks)
        override val hasCollection: Flow<Boolean> = MutableStateFlow(tracks.isNotEmpty())
        override val playingContext: Flow<PlaybackContext?> = MutableStateFlow(null)
        override val isPlaying: Flow<Boolean> = MutableStateFlow(false)
        override val announcements: Flow<List<Announcement>> = MutableStateFlow(emptyList())
        override val historyMixes: StateFlow<HistoryMixesState> = MutableStateFlow(HistoryMixesState())
        override suspend fun loadHistoryMixes(force: Boolean): HomeRecsLoad { mixLoads++; return HomeRecsLoad(1, 0, 1) }
        override val recommendationSections: Flow<List<RecommendationPageSection>?> = MutableStateFlow(null)
        override val aiAlbums: Flow<List<AiAlbumSuggestion>?> = MutableStateFlow(null)
        override suspend fun loadRecommendations(force: Boolean): HomeRecsLoad { recsLoads++; return HomeRecsLoad(1, 0, 1) }
        override val freshDrops: Flow<HomeFeed<FreshDrop>> = MutableStateFlow(HomeFeed())
        override val criticalDarlings: Flow<HomeFeed<CriticsPickAlbum>> = MutableStateFlow(HomeFeed())
        override val charts: Flow<HomeFeed<ChartAlbum>> = MutableStateFlow(HomeFeed())
        override suspend fun loadFeeds(force: Boolean) { feedLoads++ }
        override val concerts: Flow<HomeFeed<ConcertEvent>> = MutableStateFlow(HomeFeed())
        override suspend fun concertLocation(): SettingsStore.ConcertLocation? = null
        override suspend fun loadConcerts() {}
        override val concertArtistImages = com.parachord.shared.home.ConcertArtistImages { null }
        override val chatAvailable: Flow<Boolean?> = MutableStateFlow(true)
        override val plugins: Flow<List<PluginInfo>> = MutableStateFlow(listOf(PluginInfo("x", "X", "1")))
        override val accounts: Flow<Map<String, Boolean>> = MutableStateFlow(emptyMap())
        override val playbackResolvers: Flow<List<String>> = MutableStateFlow(emptyList())
        override val folderAdded: Flow<Boolean> = MutableStateFlow(false)
        override val scrobblerAccounts: Flow<Map<String, Boolean>> = MutableStateFlow(emptyMap())
        override fun nowMs(): Long = 1_000_000_000L
        override fun hour(): Int = 9
    }

    private val controller = mockk<PlaybackController>(relaxed = true).also {
        // playQueue's default `shuffle` reads the controller's queue manager, which a mock lacks.
        PlaybackController::class.java.getDeclaredField("queueManager").apply { isAccessible = true }
            .set(it, mockk<com.parachord.shared.playback.QueueManager>(relaxed = true))
    }
    private val carBrowse = mockk<CarBrowse>()

    private fun vm(sources: HomeSources): HomeViewModel {
        val settings = mockk<SettingsStore>(relaxed = true)
        coEvery { settings.getHomeGettingStarted() } returns GettingStartedState(active = false)
        coEvery { settings.getHomeSetupDismissed() } returns emptySet()
        every { settings.getResolverOrderFlow() } returns flowOf(emptyList())
        val library = mockk<LibraryRepository>(relaxed = true)
        every { library.getAllTracks() } returns flowOf(emptyList())
        every { library.getAllPlaylists() } returns flowOf(emptyList())
        return HomeViewModel(
            sources = sources,
            settingsStore = settings,
            repository = library,
            playbackController = controller,
            metadataService = mockk<MetadataService>(relaxed = true),
            carBrowse = carBrowse,
            providerPlaylistResolver = mockk<ProviderPlaylistResolver>(relaxed = true),
            announcementsRepository = mockk<AnnouncementsRepository>(relaxed = true),
            imageEnrichmentService = mockk<ImageEnrichmentService>(relaxed = true),
            recommendationsRepository = mockk<RecommendationsRepository>(relaxed = true),
        )
    }

    private fun love(id: String, addedAt: Long) =
        Track(id = id, title = "T$id", artist = "A", album = "Al", addedAt = addedAt, inCollection = true)

    @Test
    fun `playRecentLoves plays the loves newest first as Recent Loves`() = runTest(dispatcher) {
        val older = love("1", 10L)
        val newer = love("2", 20L)
        val vm = vm(FakeSources(listOf(older, newer)))
        advanceUntilIdle()
        vm.playRecentLoves()
        advanceUntilIdle()
        verify {
            controller.playQueue(listOf(newer, older), 0, PlaybackContext("loves", "Recent Loves"), any())
        }
    }

    @Test
    fun `playCollectionRadio plays the car's Collection Radio shuffled`() = runTest(dispatcher) {
        val tracks = listOf(love("1", 1L), love("2", 2L))
        coEvery { carBrowse.playRequest(CarIds.COLLECTION_RADIO) } returns CarPlayRequest(tracks, 0, true)
        val vm = vm(FakeSources(tracks))
        advanceUntilIdle()
        vm.playCollectionRadio()
        advanceUntilIdle()
        verify { controller.playQueue(tracks, 0, any(), shuffle = true) }
    }

    @Test
    fun `onVisible loads recommendations and mixes once within ten minutes`() = runTest(dispatcher) {
        val sources = FakeSources()
        val vm = vm(sources)
        advanceUntilIdle()
        vm.onVisible()
        advanceUntilIdle()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(1, sources.recsLoads)
        assertEquals(1, sources.mixLoads)
        assertEquals(2, sources.feedLoads)
    }
}
