package com.parachord.shared.model

/**
 * Core track model — shared across Android and iOS.
 *
 * On Android, Room entities in the :app module map to/from this model.
 * On iOS, SQLDelight (future) will map to/from this model.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val duration: Long? = null,
    val artworkUrl: String? = null,
    val sourceType: String? = null,
    val sourceUrl: String? = null,
    val addedAt: Long = 0L,
    /** Resolver that produced this source (e.g. "spotify", "soundcloud", "localfiles"). */
    val resolver: String? = null,
    /** Spotify URI for playback (e.g. "spotify:track:6rqhFgbbKwnb9MLmUQDhG6"). */
    val spotifyUri: String? = null,
    /** SoundCloud track ID for streaming via their API. */
    val soundcloudId: String? = null,
    /** Spotify track ID (e.g. "6rqhFgbbKwnb9MLmUQDhG6"). */
    val spotifyId: String? = null,
    /** Apple Music catalog song ID (e.g. "1440935467"). */
    val appleMusicId: String? = null,
    /**
     * ISRC of the resolved recording, carried from [com.parachord.shared.resolver.ResolvedSource.isrc]
     * when the source is attached to the playing track. Used as the
     * service-agnostic fallback to resolve [recordingMbid] via MusicBrainz
     * `/isrc/` when the ListenBrainz mapper is unavailable.
     */
    val isrc: String? = null,
    /** MusicBrainz recording MBID. */
    val recordingMbid: String? = null,
    /** MusicBrainz artist MBID. */
    val artistMbid: String? = null,
    /** MusicBrainz release MBID. */
    val releaseMbid: String? = null,
    /**
     * Epoch-ms timestamp of the last slow-trickle cross-resolver enrichment
     * attempt. Null = never tried. Set by [CrossResolverEnrichmentService]
     * regardless of outcome so un-findable tracks aren't thrashed every run.
     */
    val crossResolverEnrichedAt: Long? = null,
) {
    /**
     * Derive all available resolvers from stored IDs.
     * Returns resolver names for which this track has a usable ID,
     * sorted by the provided resolver order (user-configured priority).
     */
    fun availableResolvers(resolverOrder: List<String> = emptyList()): List<String> {
        val available = buildList {
            if (!spotifyId.isNullOrBlank() || !spotifyUri.isNullOrBlank()) add("spotify")
            if (!appleMusicId.isNullOrBlank()) add("applemusic")
            if (!soundcloudId.isNullOrBlank()) add("soundcloud")
            if (resolver != null && !contains(resolver)) add(resolver)
        }
        if (resolverOrder.isEmpty()) return available
        return available.sortedBy { r ->
            val idx = resolverOrder.indexOf(r)
            if (idx == -1) resolverOrder.size else idx
        }
    }
}
