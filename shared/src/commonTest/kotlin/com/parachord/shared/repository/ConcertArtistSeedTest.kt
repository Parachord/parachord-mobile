package com.parachord.shared.repository

import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The concert artist seed (#589), desktop's `gatherConcertsArtists`. */
class ConcertArtistSeedTest {

    private fun artist(name: String, image: String? = null) = Artist(id = "a-$name", name = name, imageUrl = image)
    private fun track(artist: String) = Track(id = "t-$artist", title = "Song", artist = artist)
    private fun album(artist: String) = Album(id = "al-$artist", title = "Record", artist = artist)

    @Test fun `groups are dealt round-robin — collection then library then history`() {
        val seed = ConcertArtistSeed.seed(
            collection = listOf(artist("A1"), artist("A2")),
            tracks = listOf(track("L1"), track("L2")),
            albums = emptyList(),
            history = listOf("H1", "H2", "H3"),
        )
        assertEquals(listOf("A1", "L1", "H1", "A2", "L2", "H2", "H3"), seed.map { it.name })
        assertEquals(listOf("collection", "library", "history"), seed.take(3).map { it.source })
    }

    @Test fun `an artist counts in the first group that has it`() {
        val seed = ConcertArtistSeed.seed(
            collection = listOf(artist("Radiohead")),
            tracks = listOf(track(" radiohead "), track("Portishead")),
            albums = listOf(album("PORTISHEAD"), album("Björk")),
            history = listOf("Björk", "Low"),
        )
        assertEquals(listOf("Radiohead", "Portishead", "Low", "Björk"), seed.map { it.name })
        assertEquals(listOf("collection", "library", "history", "library"), seed.map { it.source })
    }

    @Test fun `tracks come before albums in the library group`() {
        val seed = ConcertArtistSeed.seed(
            collection = emptyList(),
            tracks = listOf(track("T")),
            albums = listOf(album("B")),
            history = emptyList(),
        )
        assertEquals(listOf("T", "B"), seed.map { it.name })
    }

    @Test fun `a history artist ranked early still loses to a later library entry`() {
        // Desktop removes duplicates before dealing: the library keeps "X" even
        // though history would reach it first in the round-robin.
        val seed = ConcertArtistSeed.seed(
            collection = emptyList(),
            tracks = listOf(track("L1"), track("L2"), track("X")),
            albums = emptyList(),
            history = listOf("X", "H2"),
        )
        assertEquals(listOf("L1", "H2", "L2", "X"), seed.map { it.name })
        assertEquals("library", seed.last().source)
    }

    @Test fun `capped at 40`() {
        val seed = ConcertArtistSeed.seed(
            collection = (1..30).map { artist("C$it") },
            tracks = (1..30).map { track("L$it") },
            albums = emptyList(),
            history = (1..30).map { "H$it" },
        )
        assertEquals(ConcertArtistSeed.MAX_ARTISTS, seed.size)
        assertEquals(listOf("C1", "L1", "H1"), seed.take(3).map { it.name })
        assertEquals("C14", seed.last().name) // 13 full rounds, then the 14th collection artist
    }

    @Test fun `blank names are left out and the collection keeps its image`() {
        val seed = ConcertArtistSeed.seed(
            collection = listOf(artist("A", image = "https://img/a.jpg"), artist(" ")),
            tracks = listOf(track("")),
            albums = emptyList(),
            history = listOf(""),
        )
        assertEquals(listOf("A"), seed.map { it.name })
        assertEquals("https://img/a.jpg", seed.single().imageUrl)
    }

    @Test fun `nothing anywhere is an empty seed`() {
        assertTrue(ConcertArtistSeed.seed(emptyList(), emptyList(), emptyList(), emptyList()).isEmpty())
    }
}
