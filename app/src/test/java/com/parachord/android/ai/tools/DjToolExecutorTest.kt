package com.parachord.android.ai.tools

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #520: an AI tool never plays, queues or saves a different song from the one
 * requested. The search can answer with only other songs (desktop played "Untitled
 * #4" by The Avett Brothers for "Happy Me" by Biohio, parachord#1241).
 */
class DjToolExecutorTest {

    private val playback = mockk<PlaybackController>(relaxed = true)
    private val metadata = mockk<MetadataService>()
    private val library = mockk<LibraryRepository>(relaxed = true)
    private val resolverManager = mockk<ResolverManager>()
    private val resolverScoring = mockk<ResolverScoring>()

    private val executor = DjToolExecutor(
        playback, mockk(relaxed = true), metadata, library, mockk(relaxed = true), resolverManager, resolverScoring,
    ).also {
        coEvery { resolverManager.resolveWithHints(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { resolverScoring.selectBest(any()) } returns null
        // Whatever is asked for, the search answers with the song it answered desktop with.
        coEvery { metadata.searchTracks(any(), any()) } returns listOf(AVETT)
    }

    private fun searchFinds(artist: String, title: String) {
        coEvery { metadata.searchTracks("$artist $title", any()) } returns
            listOf(AVETT, TrackSearchResult(title = title, artist = artist))
    }

    private fun track(artist: String, title: String) = mapOf("artist" to artist, "title" to title)

    @Test
    fun `play refuses a search that found only other songs`() = runTest {
        val result = executor.execute("play", track("Biohio", "Happy Me"))

        assertEquals(false, result["success"])
        assertEquals("Could not find \"Happy Me\" by Biohio", result["error"])
        coVerify(exactly = 0) { playback.playTrack(any<TrackEntity>()) }
    }

    @Test
    fun `play picks the requested song out of the results`() = runTest {
        searchFinds("Biohio", "Happy Me")

        val result = executor.execute("play", track("Biohio", "Happy Me"))

        assertEquals(true, result["success"])
        val played = slot<TrackEntity>()
        coVerify { playback.playTrack(capture(played)) }
        assertEquals("Happy Me", played.captured.title)
    }

    @Test
    fun `queue_add leaves out a missing track and says which`() = runTest {
        searchFinds("A", "First")
        searchFinds("C", "Third")

        val result = executor.execute(
            "queue_add",
            mapOf("tracks" to listOf(track("A", "First"), track("Biohio", "Happy Me"), track("C", "Third"))),
        )

        assertEquals(true, result["success"])
        assertEquals(2, result["queued"])
        assertEquals(true, result["nowPlaying"])
        assertEquals(listOf(track("Biohio", "Happy Me")), result["notFound"])
        val queued = slot<List<TrackEntity>>()
        verify { playback.addToQueue(capture(queued)) }
        assertEquals(listOf("Third"), queued.captured.map { it.title })
        val played = slot<TrackEntity>()
        coVerify { playback.playTrack(capture(played)) }
        assertEquals("First", played.captured.title)
    }

    @Test
    fun `queue_add starts nothing when the track to start with is missing`() = runTest {
        searchFinds("C", "Third")

        val result = executor.execute(
            "queue_add",
            mapOf("tracks" to listOf(track("Biohio", "Happy Me"), track("C", "Third"))),
        )

        assertEquals(true, result["success"])
        assertEquals(1, result["queued"])
        assertEquals(false, result["nowPlaying"])
        assertEquals(listOf(track("Biohio", "Happy Me")), result["notFound"])
        assertEquals("Could not find \"Happy Me\" by Biohio", result["error"])
        coVerify(exactly = 0) { playback.playTrack(any<TrackEntity>()) }
        verifyOrder {
            playback.clearQueue()
            playback.addToQueue(any())
        }
    }

    @Test
    fun `queue_add changes nothing when every track is missing`() = runTest {
        val result = executor.execute("queue_add", mapOf("tracks" to listOf(track("Biohio", "Happy Me"))))

        assertEquals(false, result["success"])
        assertEquals("Could not find any of the requested tracks", result["error"])
        assertEquals(listOf(track("Biohio", "Happy Me")), result["notFound"])
        verify(exactly = 0) { playback.clearQueue() }
        verify(exactly = 0) { playback.addToQueue(any()) }
    }

    @Test
    fun `create_playlist saves only the requested songs and says which it skipped`() = runTest {
        searchFinds("A", "First")

        val result = executor.execute(
            "create_playlist",
            mapOf("name" to "Mix", "tracks" to listOf(track("A", "First"), track("Biohio", "Happy Me"))),
        )

        assertEquals(true, result["success"])
        assertEquals(1, result["trackCount"])
        assertEquals(listOf(track("Biohio", "Happy Me")), result["notFound"])
        val saved = slot<List<TrackEntity>>()
        coVerify { library.createPlaylistWithTracks(any(), capture(saved)) }
        assertEquals(listOf("First"), saved.captured.map { it.title })
    }

    @Test
    fun `create_playlist makes no playlist when every track is missing`() = runTest {
        val result = executor.execute(
            "create_playlist",
            mapOf("name" to "Mix", "tracks" to listOf(track("Biohio", "Happy Me"))),
        )

        assertEquals(false, result["success"])
        assertTrue((result["error"] as String).isNotEmpty())
        coVerify(exactly = 0) { library.createPlaylistWithTracks(any(), any()) }
    }

    private companion object {
        val AVETT = TrackSearchResult(title = "Untitled #4", artist = "The Avett Brothers")
    }
}
