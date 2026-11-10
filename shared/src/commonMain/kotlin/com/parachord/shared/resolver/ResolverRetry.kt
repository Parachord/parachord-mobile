package com.parachord.shared.resolver

/**
 * When to ask a resolver again, and how a retry joins what was already found
 * (#566).
 *
 * A resolver that couldn't answer (offline, a rate-limit cooldown, a service
 * error) is not one with no match. Kept as if it were, a moment without
 * network left tracks showing only the Apple Music id they carried, for the
 * 7 days the sources are kept. Desktop records only real answers and asks an
 * errored resolver again on the next resolve.
 */
object ResolverRetry {
    /** First wait before asking a failed resolver again. */
    const val FAILED_RETRY_BASE_MS = 2L * 60 * 1000

    /** The longest wait between retries of a resolver that keeps failing. */
    const val FAILED_RETRY_MAX_MS = 60L * 60 * 1000

    /**
     * How long a resolver that answered "no match" isn't asked again for the
     * same track (iOS's per-row fill).
     */
    const val MISS_RETRY_MS = 30L * 60 * 1000

    /** Wait before retry number [failures] (1 = after the first failure): 2, 4, 8 … 60 minutes. */
    fun failedRetryDelayMs(failures: Int): Long {
        val doublings = (failures - 1).coerceIn(0, 10)
        return (FAILED_RETRY_BASE_MS shl doublings).coerceAtMost(FAILED_RETRY_MAX_MS)
    }

    /**
     * Whether [sources] are only the streaming ids a track carried (a stored
     * Spotify / Apple Music / SoundCloud id, nothing a search adds: no
     * artwork, matched names, length or ISRC). Saved as a complete answer, that
     * is what a resolve with every search failing left before #566.
     */
    fun isStoredIdOnly(sources: List<ResolvedSource>): Boolean =
        sources.isNotEmpty() && sources.all {
            it.resolver in STORED_ID_RESOLVERS &&
                it.artworkUrl == null && it.matchedTitle == null && it.matchedArtist == null &&
                it.matchedDurationMs == null && it.isrc == null
        }

    private val STORED_ID_RESOLVERS = setOf("spotify", "applemusic", "soundcloud")

    /**
     * [existing] sources plus what a retry of some resolvers found. A retried
     * resolver's source joins the list; when the list already has one for that
     * resolver (a stored-ID hint), the hint stays and only takes the retry's
     * artwork if it had none. Nothing is removed: a retry that misses or fails
     * leaves the existing sources as they were.
     */
    fun merge(existing: List<ResolvedSource>, retried: ResolveReport): List<ResolvedSource> {
        val byResolver = retried.sources.associateBy { it.resolver }
        val kept = existing.map { source ->
            val fresh = byResolver[source.resolver]
            if (fresh != null && source.artworkUrl == null && fresh.artworkUrl != null) {
                source.copy(artworkUrl = fresh.artworkUrl)
            } else {
                source
            }
        }
        val have = existing.map { it.resolver }.toSet()
        return kept + retried.sources.filter { it.resolver !in have }
    }
}
