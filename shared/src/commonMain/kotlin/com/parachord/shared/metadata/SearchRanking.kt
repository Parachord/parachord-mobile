package com.parachord.shared.metadata

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
     * The desktop blend (`reRankResults`): 60% fuzzy relevance, 40% the row's
     * source score.
     *
     * @param fuzzyScore The fuzzy relevance score (0.0-1.0)
     * @param sourceScore The row's [TrackSearchResult.score] and the like, 0-100
     */
    fun combinedScore(fuzzyScore: Double, sourceScore: Int): Double =
        (fuzzyScore * 0.6) + (sourceScore / 100.0 * 0.4)

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

    fun rankTracks(query: String, tracks: List<TrackSearchResult>): List<TrackSearchResult> =
        rank(tracks, { FuzzyMatch.trackScore(query, it.title, it.artist) }, { it.score })

    fun rankAlbums(query: String, albums: List<AlbumSearchResult>): List<AlbumSearchResult> =
        rank(albums, { maxOf(FuzzyMatch.score(query, it.title), FuzzyMatch.score(query, it.artist) * 0.9) }, { it.score })

    fun rankArtists(query: String, artists: List<ArtistInfo>): List<ArtistInfo> =
        rank(artists, { FuzzyMatch.score(query, it.name) }, { it.score })

    /** Sorts best first, stable for ties. Like desktop, a row with no fuzzy match stays, last. */
    private fun <T> rank(items: List<T>, fuzzy: (T) -> Double, score: (T) -> Int?): List<T> =
        items.map { it to FuzzyMatch.combinedScore(fuzzy(it), score(it) ?: 50) }
            .sortedByDescending { it.second }
            .map { it.first }
}
