package com.parachord.shared.metadata

import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.repository.ConcertArtistSeed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #840: desktop's no-various-artists-page.test.js (parachord#1661). */
class ArtistPageTest {

    @Test
    fun notForVariousArtistsByNameOrByMusicBrainzId() {
        assertFalse(hasArtistPage("Various Artists"))
        assertFalse(hasArtistPage(" various artists "))
        assertFalse(hasArtistPage("Verschiedene Interpreten", VARIOUS_ARTISTS_MBID))
        assertFalse(hasArtistPage("Varios", VARIOUS_ARTISTS_MBID.uppercase()))
    }

    @Test
    fun notForNoName() {
        for (none in listOf(null, "", "  ")) assertFalse(hasArtistPage(none))
    }

    @Test
    fun aRealActThatOnlySoundsLikeItHasOne() {
        for (name in listOf("Various", "The Various Artists", "Various Cruelties", "Various Artists Genève", "Radiohead")) {
            assertTrue(hasArtistPage(name), name)
            assertTrue(hasArtistPage(name, "a74b1b7f-71a5-4011-9441-d0b5e4122711"), name)
        }
    }

    @Test
    fun searchListsTheBandButNotThePlaceholder() = runTest {
        val rows = SearchArtistList("various artists").withMusicBrainz(
            listOf(
                ArtistInfo(name = "Various Artists", mbid = VARIOUS_ARTISTS_MBID, provider = "musicbrainz", score = 100),
                ArtistInfo(name = "The Various Artists", provider = "musicbrainz", score = 90),
            ),
        )
        assertEquals(listOf("The Various Artists"), rows.map { it.name })

        val named = SearchArtistList("various artists").withAlbums(
            listOf(AlbumSearchResult(title = "Trainspotting", artist = "Various Artists", provider = "spotify")),
        )
        assertTrue(named.isEmpty())
    }

    @Test
    fun itsConcertsAreNotLookedUp() {
        val seed = ConcertArtistSeed.seed(
            collection = listOf(Artist(id = "1", name = "Various Artists")),
            tracks = emptyList(),
            albums = listOf(
                Album(id = "a", title = "Trainspotting", artist = "Various Artists"),
                Album(id = "b", title = "OK Computer", artist = "Radiohead"),
            ),
            history = listOf("various artists"),
        )
        assertEquals(listOf("Radiohead"), seed.map { it.name })
    }
}
