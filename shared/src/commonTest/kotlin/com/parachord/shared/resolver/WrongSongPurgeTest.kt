package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saved entries that hold another song, from before [pickTrackIsrc] skipped wrong matches. */
class WrongSongPurgeTest {
    private fun source(resolver: String, title: String?, artist: String?, confidence: Double, art: String? = null) =
        ResolvedSource(
            url = "u", sourceType = resolver, resolver = resolver,
            matchedTitle = title, matchedArtist = artist, confidence = confidence, artworkUrl = art,
        )

    private val local = source("localfiles", "Clover", "Tundra 212", 0.95, "content://media/1")

    private fun wrong(vararg sources: ResolvedSource) =
        ResolverRetry.holdsWrongSong("clover", "tundra 212", sources.toList())

    @Test fun anotherSongAcceptedByItsIsrcIsDropped() {
        assertTrue(wrong(local, source("spotify", "Worth Your Wild", "Russell Dickerson", 1.0)))
    }

    @Test fun aLocalFilesOnlineCoverIsDropped() {
        assertTrue(wrong(local.copy(artworkUrl = "https://coverartarchive.org/release-group/x/front-250")))
    }

    @Test fun theSameSongStays() {
        assertFalse(wrong(local))
        assertFalse(wrong(local, source("spotify", "Clover", "Tundra 212", 1.0)))
        // Below the floor it's never played; a hint carries no names.
        assertFalse(wrong(source("spotify", "Worth Your Wild", "Russell Dickerson", 0.5)))
        assertFalse(wrong(source("spotify", null, null, 1.0)))
    }

    @Test fun aStoredIdThatIsAnotherSongLosesToASourceThatIsThisOne() {
        val hint = source("spotify", "Alive (Nightmare)", "Kid Cudi, Ratatat", 1.0)
        val alive = source("localfiles", "Alive", "Tundra 212", 0.95)
        fun confidence(h: ResolvedSource, found: List<ResolvedSource>) =
            ResolverRetry.demoteWrongHints(listOf(h), found, "Alive", "Tundra 212").single().confidence!!
        assertTrue(confidence(hint, listOf(alive)) < ResolverScoring.MIN_CONFIDENCE_THRESHOLD)
        // Nothing else is this track: the stored id stays.
        assertTrue(confidence(hint, emptyList()) >= 1.0)
        assertTrue(confidence(hint, listOf(alive.copy(confidence = 0.5))) >= 1.0)
        // This track by its names, or with none to compare.
        assertTrue(confidence(hint.copy(matchedTitle = "Alive", matchedArtist = "Tundra 212"), listOf(alive)) >= 1.0)
        assertTrue(confidence(hint.copy(matchedTitle = null), listOf(alive)) >= 1.0)
    }

    @Test fun aStoredIdOnALocalFilesTrackIsDropped() {
        assertTrue(wrong(local, source("spotify", null, null, 1.0)))
    }
}
