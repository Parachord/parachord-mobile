package com.parachord.shared.search

import com.parachord.shared.model.Track
import com.parachord.shared.resolver.LETTER_OR_NUMBER_CLASS
import com.parachord.shared.resolver.foldStr

/**
 * The Library section of Search (#529), a port of desktop's
 * `LocalFilesDatabase.search` (parachord#1274). No column holds "artist title", so
 * a phrase match alone never found `Portishead Roads`. A track now matches when the
 * query is a phrase inside its title, artist or album, or when every word of the
 * query is a whole word somewhere in those fields. Whole words, because substrings
 * would let "go it" match Gorillaz "Sit Down". A one-word query is only ever a
 * phrase, so it still matches inside a word.
 *
 * Ranked as desktop does: the exact "artist title", then a phrase in the title, the
 * artist and the album, then the tracks that only hold all the words.
 *
 * Matched here rather than in SQL, because SQLite's LIKE only folds ASCII case
 * ("КИНО" wouldn't match "Кино") and desktop's normalized columns would need a
 * migration on both platforms. This also needs no cap on the number of words, which
 * desktop has only because SQLite refuses a very long AND chain, and has no row
 * limit, as this search never had one.
 */
object LibraryTrackSearch {
    private val NOT_LETTER_DIGIT_OR_SPACE = Regex("[^$LETTER_OR_NUMBER_CLASS\\s]")
    private val SPACES = Regex("\\s+")

    fun search(tracks: List<Track>, query: String): List<Track> {
        if (query.isBlank()) return emptyList()
        val phrase = fold(query)
        // A name that's nothing but punctuation ("!!!") folds away, so look for it as typed.
        if (phrase.isEmpty()) {
            return tracks.filter { track ->
                listOfNotNull(track.title, track.artist, track.album).any { it.contains(query.trim(), ignoreCase = true) }
            }.sortedWith(compareBy({ it.artist.lowercase() }, { it.album?.lowercase().orEmpty() }))
        }
        val words = phrase.split(' ').takeIf { it.size > 1 }?.toSet()
        // Artists and albums repeat across a library, so each is folded once per search.
        val folded = HashMap<String, String>()
        return tracks.mapNotNull { hit(it, phrase, words, folded) }
            .sortedWith(compareBy<Hit>({ it.rank }, { it.artist }, { it.album }))
            .map { it.track }
    }

    private class Hit(val rank: Int, val artist: String, val album: String, val track: Track)

    /** The track's place in the results, or null when it doesn't match. */
    private fun hit(track: Track, phrase: String, words: Set<String>?, folded: MutableMap<String, String>): Hit? {
        val title = fold(track.title)
        val artist = folded.getOrPut(track.artist) { fold(track.artist) }
        val album = track.album?.let { name -> folded.getOrPut(name) { fold(name) } }.orEmpty()
        val rank = when {
            "$artist $title" == phrase -> 0
            phrase in title -> 1
            phrase in artist -> 2
            phrase in album -> 3
            words != null && " $title $artist $album ".let { all -> words.all { " $it " in all } } -> 4
            else -> return null
        }
        return Hit(rank, artist, album, track)
    }

    /**
     * [foldStr] with its punctuation dropped and its spaces evened out, so "AC/DC" is
     * "acdc" on both sides and "Sky - Song" still holds the phrase "sky song".
     *
     * Every row is folded on every search, and on iOS the regex and the normalizer
     * took 18 s for 100,000 rows in a debug build. Most tags are plain ASCII, which
     * needs neither, so those take a loop instead. The two paths give the same answer.
     */
    private fun fold(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        if (s.any { it.code >= 128 }) {
            return foldStr(s).replace(NOT_LETTER_DIGIT_OR_SPACE, "").trim().replace(SPACES, " ")
        }
        val out = StringBuilder(s.length)
        var pendingSpace = false
        for (c in s) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> Unit
                c in 'A'..'Z' -> Unit
                c == ' ' || c in '\t'..'\r' || c in '\u001c'..'\u001f' -> {
                    pendingSpace = out.isNotEmpty()
                    continue
                }
                else -> continue
            }
            if (pendingSpace) out.append(' ')
            pendingSpace = false
            out.append(if (c in 'A'..'Z') c + 32 else c)
        }
        return out.toString()
    }
}
