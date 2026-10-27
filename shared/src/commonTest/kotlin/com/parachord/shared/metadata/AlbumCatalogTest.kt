package com.parachord.shared.metadata

import com.parachord.shared.metadata.AlbumCatalog.Album
import com.parachord.shared.metadata.AlbumCatalog.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of desktop tests/renderer/album-catalog.test.js (parachord#1129). */
class AlbumCatalogTest {
    private fun album(name: String, artist: String?, url: String = "u") =
        Album(id = null, name = name, artist = artist, albumArt = null, releaseDate = null, trackCount = null, url = url)

    private fun score(artist: String, title: String, a: Album) = AlbumCatalog.albumMatchScore(artist, title, a)

    // ── matching ──

    @Test fun editionAndFormatSuffixesDoNotBlockAMatch() {
        assertEquals(AlbumCatalog.normalizeAlbumTitle("Dulling the Horns"), AlbumCatalog.normalizeAlbumTitle("Dulling the Horns (Deluxe Edition)"))
        assertEquals(AlbumCatalog.normalizeAlbumTitle("Hey Jude"), AlbumCatalog.normalizeAlbumTitle("Hey Jude (Remastered 2015) - Single"))
        assertEquals(AlbumCatalog.normalizeAlbumTitle("Album"), AlbumCatalog.normalizeAlbumTitle("Album [Explicit]"))
    }

    @Test fun theRightAlbumByTheRightArtistMatches() {
        assertTrue(score("Wild Pink", "Dulling the Horns", album("Dulling the Horns", "Wild Pink")) > 0)
        assertTrue(score("Wild Pink", "Dulling the Horns", album("Dulling The Horns", "wild pink")) > 0)
    }

    @Test fun aSameTitledAlbumBySomeoneElseDoesNot() {
        assertEquals(0, score("Wild Pink", "Dulling the Horns", album("Dulling the Horns", "Other Band")))
    }

    @Test fun aDifferentAlbumByTheSameArtistDoesNot() {
        assertEquals(0, score("Wild Pink", "Dulling the Horns", album("Yolk in the Fur", "Wild Pink")))
        // containment needs the shorter title to be most of the longer one
        assertEquals(0, score("Wild Pink", "Wild Pink", album("Wild Pink (Audiotree Live) - EP Session Recordings", "Wild Pink")))
    }

    @Test fun oneArtistOfAJointCreditIsEnough_andTheAndAmpersandAreIgnored() {
        assertTrue(score("Elton John", "Cold Heart", album("Cold Heart", "Elton John & Dua Lipa")) > 0)
        assertTrue(score("The War on Drugs", "Lost in the Dream", album("Lost in the Dream", "War on Drugs")) > 0)
        assertTrue(score("Simon & Garfunkel", "Bookends", album("Bookends", "Simon and Garfunkel")) > 0)
    }

    @Test fun nonLatinTitlesMatchOnTheirOwnLetters() {
        assertTrue(score("ХЛЕБ", "Пушка", album("Пушка", "ХЛЕБ")) > 0)
        assertEquals(0, score("ХЛЕБ", "Пушка", album("Яйца", "ХЛЕБ")))
    }

    @Test fun pickPrefersTheExactMatchOverACloseOne() {
        val albums = listOf(
            album("Dulling the Horns (Deluxe)", "Wild Pink", "u1"),
            album("Dulling the Horns", "Wild Pink", "u2"),
            album("Yolk in the Fur", "Wild Pink", "u3"),
        )
        assertEquals("u2", AlbumCatalog.pickCatalogAlbum(albums, "Wild Pink", "Dulling the Horns")?.url)
        assertNull(AlbumCatalog.pickCatalogAlbum(listOf(album("Nope", "Wild Pink")), "Wild Pink", "Dulling the Horns"))
        assertNull(AlbumCatalog.pickCatalogAlbum(null, "Wild Pink", "Dulling the Horns"))
    }

    // ── order ──

    @Test fun activeServicesInPriorityOrderFirst_thenTheRest() {
        val ordered = AlbumCatalog.orderCatalogResolvers(
            listOf("bandcamp", "localfiles", "applemusic", "spotify", "subsonic"),
            activeIds = listOf("spotify", "localfiles"),
            order = listOf("spotify", "applemusic", "bandcamp", "localfiles", "subsonic"),
        )
        assertEquals(listOf("spotify", "localfiles", "applemusic", "bandcamp", "subsonic"), ordered)
    }

    @Test fun thePreferredServiceGoesFirst() {
        val ordered = AlbumCatalog.orderCatalogResolvers(
            listOf("bandcamp", "localfiles", "applemusic", "spotify", "subsonic"),
            activeIds = listOf("spotify"), order = listOf("spotify"), preferId = "applemusic",
        )
        assertEquals(listOf("applemusic", "spotify"), ordered.take(2))
    }

    // ── conversion ──

    private val catalogAlbum = Album(
        id = "applemusic-album-1", name = "Dulling the Horns", artist = "Wild Pink",
        albumArt = "https://example.com/a.jpg", releaseDate = "2024-10-04T07:00:00Z", trackCount = 3, url = "u",
        tracks = listOf(
            Track("Second", 200.0, 2, 1),
            Track("First", 180.4, 1, 1),
            Track("Disc Two", 0.0, 1, 2),
        ),
    )

    @Test fun convertsInDiscAndTrackOrder_withMillisecondDurations() {
        val d = AlbumCatalog.toAlbumDetail(catalogAlbum, "Apple Music", "Dulling the Horns", "Wild Pink")
        assertEquals(listOf("First", "Second", "Disc Two"), d.tracks.map { it.title })
        assertEquals(180_400L, d.tracks[0].duration)
        assertNull(d.tracks[2].duration)
        assertEquals(2024, d.year)
        assertEquals("Apple Music", d.catalogSource)
    }

    @Test fun theRequestedTitleArtistAndArtWin_soTrackIdentityMatchesTheProviderPath() {
        val d = AlbumCatalog.toAlbumDetail(catalogAlbum, "Apple Music", "Dulling The Horns", "wild pink", "cached.jpg")
        assertEquals("Dulling The Horns", d.title)
        assertEquals("wild pink", d.artist)
        assertEquals("cached.jpg", d.artworkUrl)
        assertTrue(d.tracks.all { it.artist == "wild pink" && it.album == "Dulling The Horns" })
        // No service ids carried over: the usual resolution pass finds them.
        assertTrue(d.tracks.all { it.spotifyId == null && it.mbid == null })
    }

    // ── identity ──

    @Test fun sameAlbumIdentityIgnoresEditionAndCase() {
        assertTrue(AlbumCatalog.sameAlbumIdentity("Wild Pink", "Dulling the Horns", "wild pink", "Dulling The Horns (Deluxe)"))
        assertFalse(AlbumCatalog.sameAlbumIdentity("X", "A", "X", "B"))
        assertFalse(AlbumCatalog.sameAlbumIdentity("X", "A", "Y", "A"))
        assertFalse(AlbumCatalog.sameAlbumIdentity("X", "", "X", ""))
    }

    // ── plugin JSON ──

    @Test fun parsesSearchResultsAndLookups_tolerantly() {
        val albums = AlbumCatalog.parseAlbums(
            """[{"id":"bandcamp-1","name":"Still Coming Down","artist":"Wild Pink","url":"https://wildpink.bandcamp.com/album/x","trackCount":"9"},""" +
                """{"name":"no url"},{"url":"no name"}]""",
        )
        assertEquals(listOf("Still Coming Down"), albums.map { it.name })
        assertEquals(9, albums[0].trackCount)

        val looked = AlbumCatalog.parseLookup(
            """{"album":{"name":"A","artist":"B","url":"u","tracks":[{"title":"t1","duration":61,"trackNumber":"1"}]},"resolverId":"bandcamp"}""",
        )
        assertEquals(1, looked?.tracks?.single()?.trackNumber)
        assertEquals(61.0, looked?.tracks?.single()?.duration)
        assertNull(AlbumCatalog.parseLookup("null"))
    }
}
