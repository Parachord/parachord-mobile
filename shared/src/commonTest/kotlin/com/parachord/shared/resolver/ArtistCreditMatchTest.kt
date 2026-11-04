package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #522: services join one artist credit differently ("A and B", "A & B", "A, B",
 * "A feat. B"). [ARTIST_CREDIT_VECTORS] is the cross-platform contract, copied
 * from `ARTIST_CREDIT_VECTORS` in desktop's
 * `tests/resolver/confidence-scoring.test.js` (parachord#1253). Every row must
 * give its score whichever side is the target.
 */
class ArtistCreditMatchTest {

    private data class Row(
        val targetArtist: String,
        val targetTitle: String,
        val resultArtist: String,
        val resultTitle: String,
        val expected: Double,
    ) {
        val swapped get() = Row(resultArtist, resultTitle, targetArtist, targetTitle, expected)
    }

    private data class R(val artist: String, val title: String)

    private fun pick(results: List<R>, artist: String, title: String): R? =
        selectBestMatch(results, title, artist, { it.title }, { it.artist })

    private val bothDirections get() = ARTIST_CREDIT_VECTORS + ARTIST_CREDIT_VECTORS.map { it.swapped }

    @Test
    fun everyVectorScoresAsDesktopDoes_inBothDirections() {
        val wrong = bothDirections.filter {
            scoreConfidence(it.targetTitle, it.targetArtist, it.resultTitle, it.resultArtist) != it.expected
        }
        assertEquals(emptyList(), wrong)
    }

    /** `selectBestMatch` promises that what it selects scores 0.95, so the two can't disagree. */
    @Test
    fun selectBestMatchAgreesWithTheScore_inBothDirections() {
        val wrong = bothDirections.filter {
            val result = R(it.resultArtist, it.resultTitle)
            pick(listOf(result), it.targetArtist, it.targetTitle) != result.takeIf { _ -> it.expected == 0.95 }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun theCandidateCreditingEveryNameWins() {
        val right = R("Erykah Badu & The Alchemist", "Breezy")
        assertEquals(right, pick(listOf(R("Erykah Badu & Madlib", "Breezy"), right), "Erykah Badu and The Alchemist", "Breezy"))
    }

    @Test
    fun sharingOneNameOfTwoIsNoMatch() {
        assertNull(pick(listOf(R("Simon & Schuster", "The Boxer")), "Simon & Garfunkel", "The Boxer"))
    }

    private companion object {
        val ARTIST_CREDIT_VECTORS = listOf(
            Row("Erykah Badu and The Alchemist", "Breezy", "Erykah Badu & The Alchemist", "Breezy", 0.95),
            Row("Erykah Badu and The Alchemist", "Breezy", "Erykah Badu, The Alchemist", "Breezy", 0.95),
            Row("Shakey Graves feat. Sierra Ferrell", "Ready or Not", "Shakey Graves, Sierra Ferrell", "Ready or Not (feat. Sierra Ferrell)", 0.95),
            Row("Artist One feat. Artist Two", "Song", "Artist One, Artist Two", "Song", 0.95),
            Row("Artist One feat Artist Two", "Song", "Artist One & Artist Two", "Song", 0.95),
            Row("Artist One ft. Artist Two", "Song", "Artist One and Artist Two", "Song", 0.95),
            Row("Artist One ft Artist Two", "Song", "Artist One x Artist Two", "Song", 0.95),
            Row("Artist One featuring Artist Two", "Song", "Artist One, Artist Two", "Song", 0.95),
            Row("Artist One (feat. Artist Two)", "Song", "Artist One and Artist Two", "Song", 0.95),
            Row("Artist One [ft. Artist Two]", "Song", "Artist One & Artist Two", "Song", 0.95),
            Row("Artist One X Artist Two", "Song", "Artist One, Artist Two", "Song", 0.95),
            Row("Artist One&Artist Two", "Song", "Artist One and Artist Two", "Song", 0.95),
            Row("Artist One, Artist Two, and Artist Three", "Song", "Artist One & Artist Two x Artist Three", "Song", 0.95),
            Row("Simon and Garfunkel", "The Boxer", "Simon & Garfunkel", "The Boxer", 0.95),
            Row("Earth, Wind and Fire", "September", "Earth, Wind & Fire", "September", 0.95),
            Row("Artist Two and Artist One", "Song", "Artist One, Artist Two", "Song", 0.95),
            Row("Artist One and Artist Two", "Song", "Artist One, Artist Two, Artist Three", "Song", 0.95),
            Row("Artist One feat. Artist Two", "Song", "Artist One", "Song", 0.95),
            Row("Artist One", "Song", "Artist One feat. Artist Two", "Song", 0.95),
            Row("Björk and José González", "Song", "Bjork, Jose Gonzalez", "Song", 0.95),
            Row("宇多田ヒカル＆椎名林檎", "二時間だけのバカンス", "椎名林檎, 宇多田ヒカル", "二時間だけのバカンス", 0.95),
            Row("Artist One", "Song (feat. Artist Two)", "Artist One", "Song (ft. Artist Two)", 0.95),
            Row("Artist One", "Song [featuring Artist Two]", "Artist One", "Song (feat. Artist Two)", 0.95),
            Row("Artist One", "Song (feat. Artist Two and Artist Three)", "Artist One", "Song (feat. Artist Two & Artist Three)", 0.95),
            Row("Artist One", "Song", "Artist One", "Song (feat. Artist Two)", 0.95),
            Row("The Beatles", "Yesterday", "Boyz II Men", "Yesterday", 0.50),
            Row("The Beatles", "Yesterday", "The Rolling Stones", "Yesterday", 0.50),
            Row("Erykah Badu & The Alchemist", "Breezy", "The Alchemist Project", "Breezy", 0.50),
            Row("Paul Simon", "The Boxer", "Simon & Garfunkel", "The Boxer", 0.50),
            Row("Simon & Garfunkel", "The Boxer", "Simon & Schuster", "The Boxer", 0.50),
            Row("Erykah Badu and The Alchemist", "Breezy", "Erykah Badu & Madlib", "Breezy", 0.50),
            Row("Shakey Graves feat. Sierra Ferrell", "Ready or Not", "Shakey Graves, Orville Peck", "Ready or Not", 0.50),
            Row("Little Feat", "Dixie Chicken", "Little & Large", "Dixie Chicken", 0.50),
            Row("Malcolm X", "Song", "Malcolm & Marie", "Song", 0.50),
            Row("X Ambassadors", "Song", "Ambassadors & Friends", "Song", 0.50),
            Row("And One", "Song", "One & Only", "Song", 0.50),
            Row("Max Richter", "Song", "Ma & Richter", "Song", 0.50),
            Row("Sand Castle", "Song", "S & Castle", "Song", 0.50),
            Row("Erykah Badu and The Alchemist", "Breezy", "Erykah Badu & The Alchemist", "Something Else", 0.50),
            Row("Artist One", "Hello (feat. Artist Two)", "Artist One", "Hello Goodbye", 0.50),
            Row("Artist One", "Song (feat. Artist Two)", "Artist One", "Song - Remastered", 0.50),
            Row("Artist One", "Song (feat. Artist Two)", "Artist One", "Song (Acoustic)", 0.50),
            Row("Artist One", "Song (feature film)", "Artist One", "Song (ft. Artist Two)", 0.50),
            Row("Artist One", "(feat. Artist Two)", "Artist One", "(ft. Artist Two)", 0.50),
        )
    }
}
