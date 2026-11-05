package com.parachord.shared.search

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

/** #529. Cases from desktop's `tests/local-files/search-combined-query.test.js` (parachord#1274). */
class LibraryTrackSearchTest {

    private var next = 0

    private fun track(title: String, artist: String = "Radiohead", album: String? = "Amnesiac") =
        Track(id = "t${next++}", title = title, artist = artist, album = album)

    private fun titles(tracks: List<Track>, query: String) = LibraryTrackSearch.search(tracks, query).map { it.title }

    // ── A query that combines fields ──

    @Test
    fun artistAndTitleFindTheTrack_inEitherOrder() {
        val tracks = listOf(track("Roads", "Portishead", "Dummy"), track("Glory Box", "Portishead", "Dummy"))
        assertEquals(listOf("Roads"), titles(tracks, "Portishead Roads"))
        assertEquals(listOf("Roads"), titles(tracks, "Roads Portishead"))
    }

    @Test
    fun aTrackWithNoAlbumIsFound() {
        assertEquals(
            listOf("Zzq No Album Song"),
            titles(listOf(track("Zzq No Album Song", "Zzqart Testband", album = null)), "Zzqart Testband Zzq No Album Song"),
        )
    }

    @Test
    fun punctuationInTheTagsDoesNotMatter() {
        val tracks = listOf(track("Help!", "The Beatles", "Help!"), track("T.N.T.", "AC/DC", "High Voltage"))
        assertEquals(listOf("Help!"), titles(tracks, "The Beatles Help!"))
        assertEquals(listOf("T.N.T."), titles(tracks, "AC/DC T.N.T."))
    }

    @Test
    fun theWordsCanNameAnyField() {
        val tracks = listOf(track("Pyramid Song"))
        assertEquals(listOf("Pyramid Song"), titles(tracks, "radiohead amnesiac"))
        assertEquals(listOf("Pyramid Song"), titles(tracks, "pyramid song radiohead"))
    }

    @Test
    fun theExactArtistAndTitleRanksFirst() {
        val others = List(60) { track("Blue Sky $it", "Aaa", "Blue Sky") }
        val found = LibraryTrackSearch.search(others + track("Sky", "Blue", "Weather"), "Blue Sky")
        assertEquals("Sky", found.first().title)
        assertEquals("Blue", found.first().artist)
    }

    // ── What it doesn't match ──

    @Test
    fun everyWordHasToBeThere() {
        assertEquals(emptyList(), titles(listOf(track("Pyramid Song")), "radiohead creep"))
    }

    @Test
    fun wordsMatchWholeWords_notPartsOfOtherWords() {
        assertEquals(emptyList(), titles(listOf(track("Sit Down", "Gorillaz", "Demon Days")), "go it"))
    }

    @Test
    fun aQueryOfVeryManyWordsStillRuns() {
        assertEquals(listOf("Pyramid Song"), titles(listOf(track("Pyramid Song")), List(1500) { "radiohead" }.joinToString(" ")))
    }

    @Test
    fun anEmptyQueryFindsNothing() {
        val tracks = listOf(track("Pyramid Song"))
        assertEquals(emptyList(), titles(tracks, ""))
        assertEquals(emptyList(), titles(tracks, "   "))
    }

    // ── It keeps what it had ──

    private fun sky() = listOf(
        track("Sky Song", "Radiohead", "Amnesiac"),
        track("Other", "Sky Band", "Amnesiac"),
        track("Third", "Radiohead", "Sky Album"),
    )

    @Test
    fun oneWordStillMatchesInsideAWord() {
        assertEquals(listOf("Sky Song", "Third"), titles(sky(), "rad"))
        assertEquals(listOf("Sky Song", "Other"), titles(sky(), "mnesia"))
    }

    @Test
    fun titleMatchesComeFirst_thenArtist_thenAlbum() {
        assertEquals(listOf("Sky Song", "Other", "Third"), titles(sky(), "sky"))
    }

    @Test
    fun aPhraseStillMatchesAPartOfAWordAtEitherEnd() {
        assertEquals(listOf("Sky Song"), titles(sky(), "ky so"))
    }

    @Test
    fun aPhraseMatchRanksAboveWordsScatteredAcrossTheFields() {
        val found = LibraryTrackSearch.search(
            sky() + track("Song", "Sky", "Aaa") + track("Song Sky", "Bbb", "Aaa") + track("Song", "Aaa", "Sky"),
            "Sky Song",
        )
        assertEquals("Sky" to "Song", found[0].artist to found[0].title)
        assertEquals("Radiohead" to "Sky Song", found[1].artist to found[1].title)
        assertEquals(listOf("Aaa", "Bbb"), found.drop(2).map { it.artist }.sorted())
    }

    /** A name that's nothing but punctuation has no words to fold to, and used to be findable. */
    @Test
    fun aPunctuationOnlyNameIsStillFound() {
        assertEquals(listOf("Slyd"), titles(listOf(track("Slyd", "!!!", "Myth Takes"), track("Other", "Band")), "!!!"))
    }

    /** SQLite's LIKE only folds ASCII case, so these never matched before. */
    @Test
    fun caseAndAccentsFoldOutsideAscii() {
        val tracks = listOf(track("Группа крови", "Кино", "Группа крови"), track("Déjà Vu", "Beyoncé", "B'Day"))
        assertEquals(listOf("Группа крови"), titles(tracks, "КИНО"))
        assertEquals(listOf("Группа крови"), titles(tracks, "кино группа крови"))
        assertEquals(listOf("Déjà Vu"), titles(tracks, "beyonce deja vu"))
    }

    /** The plain-ASCII path and the full one must agree: the accent forces the full one. */
    @Test
    fun plainAndAccentedTagsFoldAlike() {
        val cases = listOf(
            "  AC/DC  -  T.N.T.  " to "é  AC/DC  -  T.N.T.  ",
            "Sky\tSong\nTwo" to "é Sky\tSong\nTwo",
            "Don't_Stop" to "é Don't_Stop",
            "Hello (feat. A & B)" to "é Hello (feat. A & B)",
        )
        for ((plain, accented) in cases) {
            val query = accented.removePrefix("é ")
            assertEquals(
                LibraryTrackSearch.search(listOf(track(plain)), query).size,
                LibraryTrackSearch.search(listOf(track(accented)), query).size,
                plain,
            )
        }
    }

    /** `%` and `_` used to act as wildcards, since the query went into a LIKE pattern. */
    @Test
    fun wildcardCharactersAreNotWildcards() {
        assertEquals(emptyList(), titles(listOf(track("Pyramid Song")), "py_amid"))
    }
}
