package com.parachord.shared.home

import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cover lookup for an album tile that came without one (both apps). */
class JumpTileArtLookupTest {

    private val tile = JumpTile.Album(LoveAlbum("OK Computer", "Radiohead", "Airbag", null))
    private val key = jumpTileKey(tile)

    @Test
    fun `an album tile with no art is looked up once — and the cover joins its art`() = runTest {
        var calls = 0
        val lookup = JumpTileArtLookup(this) { album, artist ->
            calls++
            assertEquals("OK Computer" to "Radiohead", album to artist)
            "https://cover"
        }
        lookup.shown(tile, JumpTileArt())
        lookup.shown(tile, JumpTileArt())
        advanceUntilIdle()
        lookup.shown(tile, JumpTileArt())
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(mapOf(key to "https://cover"), lookup.found.value)
        assertEquals(JumpTileArt(imageUrl = "https://cover"), jumpTilesArt(listOf(tile), emptyList(), lookup.found.value)[key])
    }

    @Test
    fun `a lookup that threw is asked again — a miss is not`() = runTest {
        var calls = 0
        val failing = JumpTileArtLookup(this) { _, _ -> calls++; error("offline") }
        failing.shown(tile, JumpTileArt())
        advanceUntilIdle()
        failing.shown(tile, JumpTileArt())
        advanceUntilIdle()
        assertEquals(2, calls)
        assertTrue(failing.found.value.isEmpty())

        var missCalls = 0
        val missing = JumpTileArtLookup(this) { _, _ -> missCalls++; null }
        missing.shown(tile, JumpTileArt())
        advanceUntilIdle()
        missing.shown(tile, JumpTileArt())
        advanceUntilIdle()
        assertEquals(1, missCalls)
    }

    @Test
    fun `tiles with art and other tiles never ask`() = runTest {
        var calls = 0
        val lookup = JumpTileArtLookup(this) { _, _ -> calls++; "x" }
        lookup.shown(tile, JumpTileArt(imageUrl = "have"))
        lookup.shown(JumpTile.Playlist(Playlist(id = "p", name = "P"), "Playlist"), JumpTileArt())
        lookup.shown(JumpTile.Loves(emptyList<Track>()), JumpTileArt())
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test
    fun `tile art keeps its own cover over a found one`() {
        val loves = listOf(Track(id = "s", title = "Airbag", artist = "Radiohead", album = "OK Computer", artworkUrl = "own"))
        assertEquals(JumpTileArt(imageUrl = "own"), jumpTilesArt(listOf(tile), loves, mapOf(key to "found"))[key])
    }
}
