package com.parachord.shared.metadata

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Port of desktop's tests/renderer/broken-art.test.js (parachord#1101). */
class BrokenArtRetryTest {
    private val dead = "https://f4.bcbits.com/img/a111_10.jpg"
    private val fresh = "https://f4.bcbits.com/img/a222_10.jpg"

    private class Harness(var albumAnswer: String?, var artistAnswer: String? = null) {
        var albumLookups = 0
        var artistLookups = 0
        val excluded = mutableListOf<String>()
        val replaced = mutableListOf<Pair<String, String>>()
        val retry = BrokenArtRetry(
            refetchAlbumArt = { _, _, d -> albumLookups++; excluded += d; albumAnswer },
            refetchArtistImage = { _, d -> artistLookups++; excluded += d; artistAnswer },
            onReplaced = { d, f -> replaced += d to f },
        )
    }

    @Test
    fun aDeadCoverIsLookedUpAgainSkippingIt_andStoredCopiesArePatched() = runTest {
        val h = Harness(albumAnswer = fresh)
        assertEquals(fresh, h.retry.reportAlbumArt(dead, "Quarantine Angst", "Cavalcade of Indignities"))
        assertEquals(listOf(dead), h.excluded)
        assertEquals(listOf(dead to fresh), h.replaced)
    }

    @Test
    fun oneAttemptPerAlbumPerSession_sharedByConcurrentViews() = runTest {
        val h = Harness(albumAnswer = null)
        val results = List(3) { async { h.retry.reportAlbumArt(dead, "Artist", "Album") } }.awaitAll()
        assertEquals(listOf(null, null, null), results)
        // A later report (another view, or the same cover again) doesn't retry.
        h.albumAnswer = fresh
        assertNull(h.retry.reportAlbumArt(dead, "artist ", "ALBUM"))
        assertEquals(1, h.albumLookups)
        assertEquals(emptyList(), h.replaced)
    }

    @Test
    fun theSameDeadUrlComingBackIsNotAnAnswer() = runTest {
        val h = Harness(albumAnswer = dead)
        assertNull(h.retry.reportAlbumArt(dead, "Artist", "Album"))
        assertEquals(emptyList(), h.replaced)
    }

    @Test
    fun missingDetailsAreIgnored() = runTest {
        val h = Harness(albumAnswer = fresh)
        assertNull(h.retry.reportAlbumArt(null, "Artist", "Album"))
        assertNull(h.retry.reportAlbumArt(dead, "", "Album"))
        assertNull(h.retry.reportAlbumArt(dead, "Artist", null))
        assertEquals(0, h.albumLookups)
    }

    @Test
    fun artistImagesRetryIndependently() = runTest {
        val h = Harness(albumAnswer = null, artistAnswer = fresh)
        assertEquals(fresh, h.retry.reportArtistImage(dead, "Radiohead"))
        assertEquals(1, h.artistLookups)
        assertEquals(listOf(dead to fresh), h.replaced)
    }

    @Test
    fun aFailingLookupIsAMiss() = runTest {
        val retry = BrokenArtRetry(
            refetchAlbumArt = { _, _, _ -> error("network") },
            refetchArtistImage = { _, _ -> null },
            onReplaced = { _, _ -> error("should not run") },
        )
        assertNull(retry.reportAlbumArt(dead, "Artist", "Album"))
    }
}
