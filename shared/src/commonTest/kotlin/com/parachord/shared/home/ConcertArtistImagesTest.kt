package com.parachord.shared.home

import com.parachord.shared.repository.ConcertEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConcertArtistImagesTest {

    @Test
    fun `looks up in batches of five and merges as they arrive`() = runTest {
        var running = 0
        var maxRunning = 0
        val asked = mutableListOf<String>()
        val images = ConcertArtistImages { name ->
            asked += name
            running++
            maxRunning = maxOf(maxRunning, running)
            delay(10)
            running--
            "https://img/$name"
        }
        images.load((1..12).map { "A$it" })
        assertEquals(12, asked.size)
        assertEquals(ConcertArtistImages.BATCH_SIZE, maxRunning)
        assertEquals("https://img/A7", images.images.value["a7"])
    }

    @Test
    fun `an artist is asked once by trimmed lower-cased name — found or not`() = runTest {
        val asked = mutableListOf<String>()
        val images = ConcertArtistImages { name ->
            asked += name
            if (name == "Nobody") null else "https://img/$name"
        }
        images.load(listOf("Wilco", " wilco ", "WILCO", "Nobody"))
        images.load(listOf("Wilco", "nobody", "  "))
        assertEquals(listOf("Wilco", "Nobody"), asked)
        assertEquals(mapOf("wilco" to "https://img/Wilco"), images.images.value)
    }

    @Test
    fun `a lookup that throws leaves the artist out and isn't retried`() = runTest {
        var calls = 0
        val images = ConcertArtistImages { name ->
            calls++
            if (name == "Bad") throw IllegalStateException("offline") else "https://img/$name"
        }
        images.load(listOf("Bad", "Good"))
        images.load(listOf("Bad"))
        assertEquals(2, calls)
        assertNull(images.images.value["bad"])
        assertEquals("https://img/Good", images.images.value["good"])
    }

    @Test
    fun `a non-http answer is left out`() = runTest {
        val images = ConcertArtistImages { "content://x" }
        images.load(listOf("A"))
        assertTrue(images.images.value.isEmpty())
    }

    @Test
    fun `two callers share one cache`() = runTest {
        val asked = mutableListOf<String>()
        val shared = ConcertArtistImages { name -> asked += name; "https://img/$name" }
        // Home's shown cards, then the Concerts page's whole list.
        shared.load(listOf("A", "B"))
        shared.load(listOf("a", "B", "C"))
        assertEquals(listOf("A", "B", "C"), asked)
        assertEquals(3, shared.images.value.size)
    }

    @Test
    fun `a concurrent load doesn't ask for an artist already in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        val images = ConcertArtistImages { name -> asked += name; gate.await(); "https://img/$name" }
        val first = launch { images.load(listOf("A")) }
        runCurrent()
        val second = launch { images.load(listOf("A")) }
        runCurrent()
        gate.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf("A"), asked)
    }

    @Test
    fun `cancelling stops before the next batch and the rest are asked later`() = runTest {
        val asked = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val images = ConcertArtistImages { name -> asked += name; gate.await(); "https://img/$name" }
        val names = (1..7).map { "A$it" }
        val job = launch { images.load(names) }
        runCurrent()
        assertEquals(5, asked.size) // the first batch only
        job.cancel()
        advanceUntilIdle()
        assertTrue(images.images.value.isEmpty())
        // Nothing was counted as tried: a later load asks for all seven.
        gate.complete(Unit)
        asked.clear()
        images.load(names)
        assertEquals(names, asked)
        assertEquals(7, images.images.value.size)
    }

    @Test
    fun `photo is the artist photo then the event image then none`() {
        val e = ConcertEvent(id = "1", name = "Show", artistName = " Wilco ", imageUrl = "https://event/x.jpg")
        assertEquals("https://artist/w.jpg", concertPhotoUrl(e, mapOf("wilco" to "https://artist/w.jpg")))
        assertEquals("https://event/x.jpg", concertPhotoUrl(e, mapOf("wilco" to "content://bad")))
        assertEquals("https://event/x.jpg", concertPhotoUrl(e, emptyMap()))
        assertNull(concertPhotoUrl(e.copy(imageUrl = null), emptyMap()))
        assertEquals("https://artist/w.jpg", concertPhotoUrl(e.copy(imageUrl = null), mapOf("wilco" to "https://artist/w.jpg")))
    }
}
