package com.parachord.android.ui.screens.library

import androidx.lifecycle.SavedStateHandle
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** A Collection link's songs sort (Home's Recent Loves) applies once per back-stack entry. */
class LibraryLinkSortTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a link's sort is taken once per handle`() {
        val handle = SavedStateHandle(mapOf("sort" to "RECENT"))
        assertEquals(TrackSort.RECENT, takeLinkTrackSort(handle))
        // Back to the entry, a restored tab, a recreated process: same handle, nothing again.
        assertNull(takeLinkTrackSort(handle))
        assertNull(takeLinkTrackSort(SavedStateHandle()))
        assertNull(takeLinkTrackSort(SavedStateHandle(mapOf("sort" to "NOT_A_SORT"))))
    }

    private fun vm(handle: SavedStateHandle, settings: SettingsStore): LibraryViewModel {
        val library = mockk<LibraryRepository>(relaxed = true)
        every { library.getAllTracks() } returns flowOf(emptyList())
        every { library.getAllArtists() } returns flowOf(emptyList())
        every { library.getAllAlbums() } returns flowOf(emptyList())
        every { library.getAllPlaylists() } returns flowOf(emptyList())
        val cache = mockk<TrackResolverCache>(relaxed = true)
        every { cache.trackResolvers } returns MutableStateFlow(emptyMap())
        every { cache.trackResolverConfidences } returns MutableStateFlow(emptyMap())
        every { cache.inFlightKeys } returns MutableStateFlow(emptySet())
        return LibraryViewModel(
            savedStateHandle = handle,
            repository = library,
            playbackController = mockk<PlaybackController>(relaxed = true),
            imageEnrichmentService = mockk<ImageEnrichmentService>(relaxed = true),
            metadataService = mockk<MetadataService>(relaxed = true),
            resolverManager = mockk<ResolverManager>(relaxed = true),
            resolverScoring = mockk<ResolverScoring>(relaxed = true),
            settingsStore = settings,
            trackResolverCache = cache,
        )
    }

    private fun settings(savedTracksSort: CompletableDeferred<String?>): SettingsStore {
        val s = mockk<SettingsStore>(relaxed = true)
        every { s.getResolverOrderFlow() } returns flowOf(emptyList())
        coEvery { s.getSortArtists() } returns null
        coEvery { s.getSortAlbums() } returns null
        coEvery { s.getSortFriends() } returns null
        coEvery { s.getSortTracks() } coAnswers { savedTracksSort.await() }
        return s
    }

    @Test
    fun `the link's sort wins over a saved sort that loads after it`() = runTest(dispatcher) {
        val saved = CompletableDeferred<String?>()
        val store = settings(saved)
        val model = vm(SavedStateHandle(mapOf("sort" to "RECENT")), store)
        saved.complete(TrackSort.ARTIST.name)
        advanceUntilIdle()
        assertEquals(TrackSort.RECENT, model.trackSort.value)
        coVerify { store.setSortTracks("RECENT") }
    }

    @Test
    fun `with no link the saved sort loads`() = runTest(dispatcher) {
        val saved = CompletableDeferred<String?>()
        val model = vm(SavedStateHandle(), settings(saved))
        saved.complete(TrackSort.ARTIST.name)
        advanceUntilIdle()
        assertEquals(TrackSort.ARTIST, model.trackSort.value)
    }

    @Test
    fun `a second view model on the same entry keeps the user's later choice`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("sort" to "RECENT"))
        val first = vm(handle, settings(CompletableDeferred<String?>().also { it.complete(null) }))
        advanceUntilIdle()
        first.setTrackSort(TrackSort.TITLE_ASC)
        // The process comes back: the saved sort is the user's, the link is spent.
        val again = vm(handle, settings(CompletableDeferred<String?>().also { it.complete(TrackSort.TITLE_ASC.name) }))
        advanceUntilIdle()
        assertEquals(TrackSort.TITLE_ASC, again.trackSort.value)
    }
}
