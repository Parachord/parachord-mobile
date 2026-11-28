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

    /**
     * Whether a saved entry for [title] by [artist] holds another song, or
     * another song's cover. A source at 1.0 under names that aren't this
     * track's was accepted by an ISRC taken from a wrong match ([pickTrackIsrc]
     * before it skipped those), and a local file's online cover came from a
     * search whose first result was taken unchecked. A 1.0 with no names is a
     * stored id, which for a local file's track can be the wrong song's, kept
     * by a saved queue. A rip with odd tags that
     * was really matched by ISRC, and a real stored id, are dropped too, and only resolve again.
     */
    fun holdsWrongSong(title: String, artist: String, sources: List<ResolvedSource>): Boolean {
        val hasLocalFile = sources.any { it.resolver == "localfiles" }
        return sources.any { s ->
            if (s.resolver == "localfiles") return@any s.artworkUrl?.startsWith("http") == true
            if ((s.confidence ?: 0.0) < 1.0) return@any false
            val name = s.matchedTitle ?: return@any hasLocalFile
            val by = s.matchedArtist ?: return@any hasLocalFile
            scoreConfidence(title, artist, name, by) < ResolverScoring.MIN_CONFIDENCE_THRESHOLD
        }
    }

    /**
     * Stored-id [hints] with the wrong ones taken down to their name score. A
     * stored id is trusted at 1.0 whatever its song is called, except when its
     * own names aren't the track's and the search ([found]) has a source that
     * is: then the id was another song's (a wrong match saved with a queue).
     * With nothing else found the id stays, since a service may spell a
     * track's names its own way.
     */
    fun demoteWrongHints(
        hints: List<ResolvedSource>,
        found: List<ResolvedSource>,
        title: String?,
        artist: String?,
    ): List<ResolvedSource> {
        if (title == null || artist == null) return hints
        val floor = ResolverScoring.MIN_CONFIDENCE_THRESHOLD
        if (found.none { !it.noMatch && (it.confidence ?: 0.0) >= floor }) return hints
        return hints.map { hint ->
            val name = hint.matchedTitle ?: return@map hint
            val by = hint.matchedArtist ?: return@map hint
            val score = scoreConfidence(title, artist, name, by)
            if (score < floor) hint.copy(confidence = score) else hint
        }
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
