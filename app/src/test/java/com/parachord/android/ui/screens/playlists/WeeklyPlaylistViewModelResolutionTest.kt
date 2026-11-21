package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.WeeklyPlaylistsRepository
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackState
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.api.LbPlaylistTrack
import com.parachord.shared.recommendations.PageSource
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.WeeklyInfo
import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.repository.RecommendationsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Visibility-scoped resolution for the weekly playlist screen (#177).
 *
 * Opening the screen must NOT bulk-submit the entire (50+ track) list to
 * the resolver cache — that fans out one Spotify + one iTunes catalog
 * search per track in a single burst on a fresh client, tripping
 * Spotify's account-wide abuse window. Resolution is driven per-visible
 * row instead (the LazyColumn composes only the viewport + overscan).
 */
class WeeklyPlaylistViewModelResolutionTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var weeklyRepo: WeeklyPlaylistsRepository
    private lateinit var libraryRepo: LibraryRepository
    private lateinit var playlistDao: PlaylistDao
    private lateinit var playbackController: PlaybackController
    private lateinit var playbackStateHolder: PlaybackStateHolder
    private lateinit var resolverCache: TrackResolverCache
    private lateinit var recommendationsRepo: RecommendationsRepository

    private fun lbTrack(i: Int) = LbPlaylistTrack(
        id = "t$i", title = "Song $i", artist = "Artist $i",
    )

    /** The page's weekly sections carrying this week's Weekly Jams `pl1`. */
    private fun weeklySections() = listOf(
        RecommendationPageSection(
            RecommendationType.PLAYLIST, "Playlists",
            listOf(
                RecommendationRow(
                    key = "type:playlist", type = RecommendationType.PLAYLIST,
                    sources = listOf(PageSource("listenbrainz", "ListenBrainz")),
                    items = listOf(
                        RecommendationItem(
                            type = RecommendationType.PLAYLIST, id = "pl1", name = "Weekly Jams",
                            weekly = WeeklyInfo("pl1", "weekly-jam", "Weekly Jams", "This Week", "2026-11-17"),
                            resolverId = "listenbrainz",
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun buildViewModel(
        page: RecommendationPageState = RecommendationPageState(),
        pageFlow: MutableStateFlow<RecommendationPageState> = MutableStateFlow(page),
    ): WeeklyPlaylistViewModel {
        weeklyRepo = mockk(relaxed = true)
        libraryRepo = mockk(relaxed = true)
        playlistDao = mockk(relaxed = true)
        playbackController = mockk(relaxed = true)
        playbackStateHolder = mockk(relaxed = true)
        resolverCache = mockk(relaxed = true)

        every { libraryRepo.getAllPlaylists() } returns MutableStateFlow(emptyList())
        every { playbackStateHolder.state } returns MutableStateFlow(PlaybackState())
        every { resolverCache.trackResolvers } returns MutableStateFlow(emptyMap())
        recommendationsRepo = mockk(relaxed = true)
        every { recommendationsRepo.page } returns pageFlow
        coEvery { playlistDao.getById(any()) } returns null
        // A non-empty tracklist so the (old) bulk-resolve path would fire.
        coEvery { weeklyRepo.loadPlaylistTracks("pl1") } returns (1..50).map { lbTrack(it) }

        val handle = SavedStateHandle(
            mapOf("playlistId" to "pl1", "contextType" to "weekly-jam"),
        )
        return WeeklyPlaylistViewModel(
            savedStateHandle = handle,
            weeklyPlaylistsRepository = weeklyRepo,
            recommendationsRepository = recommendationsRepo,
            libraryRepository = libraryRepo,
            playlistDao = playlistDao,
            playbackController = playbackController,
            playbackStateHolder = playbackStateHolder,
            trackResolverCache = resolverCache,
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading the playlist does not bulk-resolve the entire list`() = runTest(testDispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle() // let init/loadPlaylist finish

        // The whole-list submit is the anti-pattern this issue removes.
        verify(exactly = 0) { resolverCache.resolveInBackground(any(), any(), any()) }
    }

    @Test
    fun `resolveVisibleTrack submits exactly that one track`() = runTest(testDispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        val track = TrackEntity(id = "vis", title = "Visible", artist = "Someone")
        vm.resolveVisibleTrack(track)
        advanceUntilIdle()

        verify(exactly = 1) {
            resolverCache.resolveInBackground(listOf(track), backfillDb = false, priority = any())
        }
    }

    @Test
    fun `the header comes from the recommendation sections`() = runTest(testDispatcher) {
        val vm = buildViewModel(RecommendationPageState(weeklySections = weeklySections()))
        advanceUntilIdle()

        assertEquals("Weekly Jams", vm.title.value)
        assertEquals("This Week", vm.weekLabel.value)
        assertEquals("Weekly Jam", vm.kind.value)
        assertEquals("Nov 17, 2026", vm.dateLabel.value)
        assertEquals("Your favorite tracks from the past week, curated by ListenBrainz", vm.description.value)
    }

    @Test
    fun `a playlist the page does not carry shows the kind label alone`() = runTest(testDispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals("Weekly Jam", vm.title.value)
        assertEquals("", vm.weekLabel.value)
        assertEquals("", vm.dateLabel.value)
        assertEquals("", vm.description.value)
    }

    @Test
    fun `the header fills in once the page carries the playlist`() = runTest(testDispatcher) {
        val pageFlow = MutableStateFlow(RecommendationPageState())
        val vm = buildViewModel(pageFlow = pageFlow)
        advanceUntilIdle()
        assertEquals("Weekly Jam", vm.title.value)

        pageFlow.value = RecommendationPageState(weeklySections = weeklySections())
        advanceUntilIdle()
        assertEquals("Weekly Jams", vm.title.value)
        assertEquals("Nov 17, 2026", vm.dateLabel.value)
        assertEquals("Your favorite tracks from the past week, curated by ListenBrainz", vm.description.value)
    }

    @Test
    fun `save uses the per-week name`() = runTest(testDispatcher) {
        val vm = buildViewModel(RecommendationPageState(weeklySections = weeklySections()))
        advanceUntilIdle()
        vm.saveToLibrary()
        advanceUntilIdle()
        coVerify { libraryRepo.createPlaylistWithTracks(match { it.name == "Weekly Jams · Nov 17, 2026" }, any()) }
    }
}
