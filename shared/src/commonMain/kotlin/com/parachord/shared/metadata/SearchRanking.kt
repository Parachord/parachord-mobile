package com.parachord.shared.metadata

import kotlin.math.log10
import kotlin.math.min

/**
 * Lightweight fuzzy matching inspired by Fuse.js, matching the desktop's search ranking approach.
 * [SearchRanking] blends it with each row's source score.
 */
object FuzzyMatch {

    /**
     * Returns a fuzzy relevance score between 0.0 (no match) and 1.0 (exact match).
     * Uses a combination of:
     * - Exact substring match (highest score)
     * - Word-boundary prefix matching
     * - Character-level fuzzy matching (Levenshtein-based)
     */
    fun score(query: String, target: String): Double {
        if (query.isBlank() || target.isBlank()) return 0.0

        val q = query.lowercase().trim()
        val t = target.lowercase().trim()

        // Exact match
        if (q == t) return 1.0

        // Starts with query
        if (t.startsWith(q)) return 0.95

        // Contains query as substring
        if (t.contains(q)) return 0.85

        // Word-boundary prefix match (e.g. "dark" matches "The Dark Side of the Moon")
        val words = t.split(" ", "-", "_")
        if (words.any { it.startsWith(q) }) return 0.80

        // Fuzzy match using normalized Levenshtein distance
        val distance = levenshtein(q, t.take(q.length + 10))
        val maxLen = maxOf(q.length, t.take(q.length + 10).length)
        val similarity = 1.0 - (distance.toDouble() / maxLen)

        // Only consider it a match if similarity > 0.4 (similar to Fuse.js threshold of 0.6)
        return if (similarity > 0.4) similarity * 0.75 else 0.0
    }

    /**
     * A track's fuzzy score: the best of its title, its artist (× 0.9), and
     * "artist title" (desktop parachord#1186's TRACK_RANK_KEY, #475). The last
     * is what ranks Portishead's "Roads" above a cover titled
     * "Portishead - Roads" for the query "portishead roads": scored field by
     * field, a query naming both matches neither of the real track's fields well.
     */
    fun trackScore(query: String, title: String, artist: String): Double =
        maxOf(score(query, title), score(query, artist) * 0.9, score(query, "$artist $title"))

    /**
     * An album's fuzzy score, the same idea as [trackScore] (desktop
     * `ALBUM_RANK_KEY`, parachord#1204): "artist title" counts too. Scored on
     * title and artist alone, a search for "Mazzy Star" ranked covers whose
     * titles start with the name ("Mazzy Star Covers", 0.95) above Mazzy Star's
     * own albums (0.9, the artist match).
     */
    fun albumScore(query: String, title: String, artist: String): Double =
        maxOf(score(query, title), score(query, artist) * 0.9, score(query, "$artist $title"))

    /**
     * The desktop blend (`rankSearchRows`): 60% fuzzy relevance, 40% the row's
     * standing (its source score, averaged with its popularity when it has one).
     *
     * @param fuzzyScore The fuzzy relevance score (0.0-1.0)
     * @param sourceScore The row's [TrackSearchResult.score] and the like, 0-100
     * @param popularity 0-100, or null to rank on the source score alone
     */
    fun combinedScore(fuzzyScore: Double, sourceScore: Int, popularity: Double? = null): Double {
        val standing = if (popularity == null) sourceScore.toDouble() else (sourceScore + popularity) / 2
        return (fuzzyScore * 0.6) + (standing / 100.0 * 0.4)
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    min(dp[i - 1][j], min(dp[i][j - 1], dp[i - 1][j - 1])) + 1
                }
            }
        }
        return dp[m][n]
    }
}

/**
 * Re-ranks [MetadataService] search hits by fuzzy relevance blended with each
 * row's source score (desktop `reRankResults`: 60% fuzzy, 40% source). Shared
 * so Android Search, Android Auto search and iOS Search order results the same
 * way.
 *
 * The source score is desktop's (#475, parachord#1186):
 * - A MusicBrainz row has MusicBrainz's own relevance, 0–100.
 * - Every other provider's row has [rankScore]: its place in that provider's
 *   list and the provider's place in the order.
 * - Each further provider returning the same row adds [AGREEMENT_BONUS]
 *   (`mergedSearchScore`).
 * - No score counts as 50.
 *
 * Mobile used to give every MusicBrainz row a flat +0.4 and every Spotify
 * row nothing, so a loose MusicBrainz match outranked Spotify's exact top hit.
 *
 * Originals rank above covers (#488, desktop parachord#1204):
 * - Albums match "artist title" too ([FuzzyMatch.albumScore]).
 * - A track or album the query names, by artist or exact title
 *   ([queryNamesRow]), gets [ARTIST_NAMED_BONUS].
 * - Popularity, where a source has it (Spotify's own for its tracks,
 *   ListenBrainz listens for MusicBrainz's rows via [SearchPopularity]), is
 *   averaged into the source score. A row without it counts as its list's
 *   median.
 *
 * Desktop also holds shown rows still and promotes a clear leader past them.
 * Mobile re-ranks each list from scratch on every provider answer, so plain
 * sorting is enough here.
 */
object SearchRanking {

    /** Added per further provider returning the same row (desktop AGREEMENT_BONUS). */
    const val AGREEMENT_BONUS = 5

    /**
     * A non-MusicBrainz row's source score (desktop `rankScore`): 100 for a
     * provider's top hit, less 5 per place down its list and 5 per provider
     * ahead of it, never below 0.
     */
    fun rankScore(rank: Int, providerPosition: Int): Int =
        maxOf(0, 100 - 5 * rank - 5 * providerPosition)

    /**
     * Added to a row the query names ([queryNamesRow]), desktop's
     * `ARTIST_NAMED_BONUS` (20 on its 0–100 scale). Enough to lift the artist's
     * own albums over covers whose titles contain the name; small next to the
     * 0.6 fuzzy relevance is worth, so an unrelated artist still can't win.
     */
    const val ARTIST_NAMED_BONUS = 0.20

    /** Listens that count as popularity 100 for a release group (desktop `POPULARITY_REFERENCE_LISTENS`). */
    const val POPULARITY_REFERENCE_LISTENS = 1_000_000L

    /**
     * Listens that count as popularity 100 for an artist
     * (`ARTIST_POPULARITY_REFERENCE_LISTENS`): Mazzy Star, ~4.2M, is ~95; a
     * covering artist with ~18k is ~61.
     */
    const val ARTIST_POPULARITY_REFERENCE_LISTENS = 10_000_000L

    /**
     * A ListenBrainz listen count as popularity, 0–100 on a log scale where
     * [reference] listens is 100 (desktop `popularityFromListens`). Null for no
     * count.
     */
    fun popularityFromListens(listens: Long?, reference: Long = POPULARITY_REFERENCE_LISTENS): Double? {
        if (listens == null || listens < 0) return null
        return min(100.0, 100 * log10(1.0 + listens) / log10(1.0 + reference))
    }

    private val MARKS = Regex("\\p{M}")
    private val NOT_WORD = Regex("[^${com.parachord.shared.resolver.LETTER_OR_NUMBER_CLASS}]+")

    /** Lowercased, accent-free, punctuation as spaces, padded for whole-word tests. */
    private fun asWords(s: String): String {
        val words = com.parachord.shared.platform.nfkd(s).replace(MARKS, "").lowercase()
            .replace("&", " and ").replace(NOT_WORD, " ").trim()
        return " $words "
    }

    /**
     * Whether the query names this artist as whole words (desktop
     * `queryNamesArtist`): "mazzy star" and "mazzy star among my swan" name
     * Mazzy Star, "airplane" doesn't name Air. A leading "The" is optional on
     * either side.
     */
    fun queryNamesArtist(query: String, artistName: String): Boolean {
        val bare = asWords(artistName).trim().removePrefix("the ")
        if (bare.isEmpty()) return false
        val q = asWords(query).let { if (it.startsWith(" the ")) it.removePrefix(" the") else it }
        return q.contains(" $bare ")
    }

    /**
     * Whether the query names this row (desktop `queryNamesRow`): its artist
     * ([queryNamesArtist]), or its exact title with edition suffixes aside
     * ([AlbumCatalog.normalizeAlbumTitle]). The title half keeps a title search
     * fair: for "Abbey Road", a tribute band named Abbey Road mustn't outrank
     * the Beatles' Abbey Road just because the query names the band.
     */
    fun queryNamesRow(query: String, artistName: String, title: String): Boolean {
        if (queryNamesArtist(query, artistName)) return true
        val t = AlbumCatalog.normalizeAlbumTitle(title)
        return t.isNotEmpty() && t == AlbumCatalog.normalizeAlbumTitle(query)
    }

    fun rankTracks(query: String, tracks: List<TrackSearchResult>): List<TrackSearchResult> =
        rank(
            tracks, { FuzzyMatch.trackScore(query, it.title, it.artist) }, { it.score }, { it.popularity },
            named = { queryNamesRow(query, it.artist, it.title) },
        )

    fun rankAlbums(query: String, albums: List<AlbumSearchResult>): List<AlbumSearchResult> =
        rank(
            albums, { FuzzyMatch.albumScore(query, it.title, it.artist) }, { it.score }, { it.popularity },
            named = { queryNamesRow(query, it.artist, it.title) },
        )

    fun rankArtists(query: String, artists: List<ArtistInfo>): List<ArtistInfo> =
        rank(artists, { FuzzyMatch.score(query, it.name) }, { it.score }, { null })

    /**
     * Sorts best first, stable for ties. Like desktop, a row with no fuzzy match
     * stays, last.
     *
     * Popularity degrades gracefully: a row without one counts as the median of
     * the rows in the list that have one, so having or lacking the signal is
     * neutral (a user without Spotify isn't penalized, and one provider's rows
     * don't sink because another sends popularity). A list where nothing has
     * popularity ranks exactly as it would without it.
     */
    private fun <T> rank(
        items: List<T>,
        fuzzy: (T) -> Double,
        score: (T) -> Int?,
        popularity: (T) -> Double?,
        named: (T) -> Boolean = { false },
    ): List<T> {
        val median = median(items.mapNotNull { popularity(it)?.takeIf { p -> p.isFinite() } })
        return items.map {
            val pop = popularity(it)?.takeIf { p -> p.isFinite() } ?: median
            val bonus = if (named(it)) ARTIST_NAMED_BONUS else 0.0
            it to FuzzyMatch.combinedScore(fuzzy(it), score(it) ?: 50, pop) + bonus
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
