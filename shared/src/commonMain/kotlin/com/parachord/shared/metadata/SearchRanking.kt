package com.parachord.shared.metadata

import kotlin.math.min

/**
 * Lightweight fuzzy matching inspired by Fuse.js, matching the desktop's search ranking approach.
 *
 * Desktop formula: finalScore = (fuzzyScore × 0.6) + (mbScore × 0.4)
 * Since we don't have MusicBrainz popularity scores on mobile, we use provider priority
 * as a proxy for the second factor: MusicBrainz results (priority 0) get the highest boost.
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
     * Compute the combined search score matching the desktop formula:
     * finalScore = (fuzzyScore × 0.6) + (sourceScore × 0.4)
     *
     * @param fuzzyScore The fuzzy relevance score (0.0-1.0)
     * @param providerPriority The metadata provider priority (0 = MusicBrainz, 10 = LastFm, 20 = Spotify)
     */
    fun combinedScore(fuzzyScore: Double, providerPriority: Int = 0): Double {
        // Normalize provider priority to 0.0-1.0 (lower priority number = higher score)
        val sourceScore = 1.0 - (providerPriority.coerceIn(0, 20) / 20.0)
        return (fuzzyScore * 0.6) + (sourceScore * 0.4)
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
 * Re-ranks [MetadataService] search hits by fuzzy relevance blended with the
 * provider (desktop: 60% fuzzy, 40% source score). Shared so Android Search,
 * Android Auto search and iOS Search order results the same way; iOS used to
 * show them in merge order.
 */
object SearchRanking {

    fun rankTracks(query: String, tracks: List<TrackSearchResult>): List<TrackSearchResult> =
        rank(tracks, { FuzzyMatch.trackScore(query, it.title, it.artist) }, { it.provider })

    fun rankAlbums(query: String, albums: List<AlbumSearchResult>): List<AlbumSearchResult> =
        rank(albums, { maxOf(FuzzyMatch.score(query, it.title), FuzzyMatch.score(query, it.artist) * 0.9) }, { it.provider })

    fun rankArtists(query: String, artists: List<ArtistInfo>): List<ArtistInfo> =
        rank(artists, { FuzzyMatch.score(query, it.name) }, { it.provider })

    /** Map provider name to priority value for scoring. */
    fun providerPriority(provider: String): Int = when {
        "musicbrainz" in provider -> 0
        "lastfm" in provider -> 10
        "spotify" in provider -> 20
        else -> 10
    }

    /** Drops items scoring 0, then sorts best first (stable for ties). */
    private fun <T> rank(items: List<T>, fuzzy: (T) -> Double, provider: (T) -> String): List<T> =
        items.map { it to FuzzyMatch.combinedScore(fuzzy(it), providerPriority(provider(it))) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }
}
