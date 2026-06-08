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
import io.mockk.coEvery
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

    private fun lbTrack(i: Int) = LbPlaylistTrack(
        id = "t$i", title = "Song $i", artist = "Artist $i",
    )

    private fun buildViewModel(): WeeklyPlaylistViewModel {
        weeklyRepo = mockk(relaxed = true)
        libraryRepo = mockk(relaxed = true)
        playlistDao = mockk(relaxed = true)
        playbackController = mockk(relaxed = true)
        playbackStateHolder = mockk(relaxed = true)
        resolverCache = mockk(relaxed = true)

        every { libraryRepo.getAllPlaylists() } returns MutableStateFlow(emptyList())
        every { playbackStateHolder.state } returns MutableStateFlow(PlaybackState())
        every { resolverCache.trackResolvers } returns MutableStateFlow(emptyMap())
        coEvery { weeklyRepo.loadWeeklyPlaylists(any()) } returns null
        coEvery { playlistDao.getById(any()) } returns null
        // A non-empty tracklist so the (old) bulk-resolve path would fire.
        coEvery { weeklyRepo.loadPlaylistTracks("pl1") } returns (1..50).map { lbTrack(it) }

        val handle = SavedStateHandle(
            mapOf("playlistId" to "pl1", "contextType" to "weekly-jam"),
        )
        return WeeklyPlaylistViewModel(
            savedStateHandle = handle,
            weeklyPlaylistsRepository = weeklyRepo,
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
}
