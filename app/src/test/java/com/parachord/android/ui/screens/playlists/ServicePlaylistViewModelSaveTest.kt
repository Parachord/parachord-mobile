package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackState
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.playlist.ImportResult
import com.parachord.android.playlist.PlaylistImportManager
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Save imports once: a second tap opens the saved playlist, and only a failure re-enables Save. */
class ServicePlaylistViewModelSaveTest {
    private val dispatcher = StandardTestDispatcher()
    private val importer = mockk<PlaylistImportManager>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(): ServicePlaylistViewModel {
        val library = mockk<LibraryRepository>(relaxed = true)
        every { library.getAllPlaylists() } returns MutableStateFlow(emptyList())
        val holder = mockk<PlaybackStateHolder>(relaxed = true)
        every { holder.state } returns MutableStateFlow(PlaybackState())
        val cache = mockk<TrackResolverCache>(relaxed = true)
        every { cache.trackResolvers } returns MutableStateFlow(emptyMap())
        val resolver = mockk<ProviderPlaylistResolver>()
        coEvery { resolver.resolve(any()) } returns null
        return ServicePlaylistViewModel(
            SavedStateHandle(mapOf("url" to "https://x/p", "name" to "Mix", "source" to "Plug")),
            resolver, importer, library, mockk(relaxed = true), holder, cache,
        )
    }

    @Test
    fun `a second save after success opens the saved playlist without importing again`() = runTest(dispatcher) {
        coEvery { importer.importFromUrl("https://x/p") } returns ImportResult("pl-1", "Mix", 3)
        val vm = vm()
        val events = mutableListOf<ServicePlaylistEvent>()
        val job = launch(dispatcher) { vm.events.collect { events += it } }
        advanceUntilIdle()
        vm.save(); advanceUntilIdle()
        vm.save(); advanceUntilIdle()
        coVerify(exactly = 1) { importer.importFromUrl(any()) }
        assertEquals(listOf(ServicePlaylistEvent.Saved("pl-1", "Mix")), events.take(1))
        assertEquals(2, events.size)
        assertTrue(vm.saving.value)
        job.cancel()
    }

    @Test
    fun `a failed save re-enables save and says it could not save`() = runTest(dispatcher) {
        coEvery { importer.importFromUrl(any()) } throws IllegalStateException("x")
        val vm = vm()
        val events = mutableListOf<ServicePlaylistEvent>()
        val job = launch(dispatcher) { vm.events.collect { events += it } }
        advanceUntilIdle()
        vm.save(); advanceUntilIdle()
        assertFalse(vm.saving.value)
        assertEquals(listOf<ServicePlaylistEvent>(ServicePlaylistEvent.Message("Couldn’t save Mix")), events)
        job.cancel()
    }
}
