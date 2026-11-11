package com.parachord.shared.resolver

import com.parachord.shared.model.Track

/**
 * Which source a track plays from when nothing has been resolved for it yet,
 * and which sources the Now Playing source switcher offers (#587). One rule
 * for both apps.
 *
 * **Nothing resolved yet** (a cold start from the car, a widget, a restored
 * queue): the track plays at once from the streaming IDs it already carries
 * ([storedIdSources]), in the user's resolver order, while a full resolve runs
 * in the background. Its stored `resolver` is only whichever service first
 * played or synced it: a Daily Brew track pulled from Spotify keeps
 * `resolver = "spotify"` with an Apple Music ID stored and Apple Music first
 * in the order, and an Android Auto cold start went to Spotify, which couldn't
 * start from the background (#452). [shouldRerouteToStoredId] keeps a track on
 * a resolver the stored IDs don't cover (a local file, SoundCloud, Bandcamp)
 * unless a stored ID ranks above it. Desktop always waits for a full resolve;
 * it has no car case.
 *
 * **The source switcher** offers only sources that can play
 * ([ResolverBadges.isPlayable]), one per resolver, in the user's order.
 */
object SourceReselection {

    /**
     * The sources [track]'s stored streaming IDs stand for, among connected
     * services: Spotify (URI or ID) and Apple Music (a catalog ID only, #265).
     * Trusted at confidence 1.0, as a resolve with hints trusts them: a stored
     * ID was validated when it was written.
     */
    fun storedIdSources(
        track: Track,
        spotifyConnected: Boolean,
        appleMusicConnected: Boolean,
    ): List<ResolvedSource> = buildList {
        val spotifyUri = track.spotifyUri ?: track.spotifyId?.let { "spotify:track:$it" }
        if (spotifyUri != null && spotifyConnected) {
            add(ResolvedSource(
                url = spotifyUri, sourceType = "spotify", resolver = "spotify",
                spotifyUri = spotifyUri, spotifyId = track.spotifyId ?: spotifyUri.substringAfterLast(':'),
                confidence = 1.0,
            ))
        }
        if (appleMusicConnected && isAppleMusicCatalogId(track.appleMusicId)) {
            add(ResolvedSource(
                url = "applemusic:song:${track.appleMusicId}", sourceType = "applemusic", resolver = "applemusic",
                appleMusicId = track.appleMusicId, confidence = 1.0,
            ))
        }
    }

    /**
     * Whether to move a track from its stored [current] resolver to [best], the
     * top stored-ID source. A streaming resolver (or none) always moves; one
     * the stored IDs don't cover keeps the track unless [best] ranks above it
     * in [order].
     */
    fun shouldRerouteToStoredId(current: String?, best: String, order: List<String>): Boolean {
        if (best == current) return false
        if (current == null || current == "spotify" || current == "applemusic") return true
        fun rank(id: String) = order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
        return rank(best) < rank(current)
    }

    /**
     * The sources the source switcher offers: those that can play, the best
     * one per resolver, in [order] (resolvers not in it last), then by
     * confidence. With an empty [order], by confidence alone.
     */
    fun switchableSources(sources: List<ResolvedSource>, order: List<String>): List<ResolvedSource> {
        fun rank(id: String) = order.indexOf(id).let { if (it < 0) order.size else it }
        return sources
            .filter(ResolverBadges::isPlayable)
            .sortedWith(compareBy<ResolvedSource> { rank(it.resolver) }.thenByDescending { it.confidence ?: 0.0 })
            .distinctBy { it.resolver }
    }

    /** The source a manual switch to [resolver] plays: its best one that can play, or null. */
    fun sourceForSwitch(sources: List<ResolvedSource>, resolver: String): ResolvedSource? =
        sources.filter { it.resolver == resolver && ResolverBadges.isPlayable(it) }
            .maxByOrNull { it.confidence ?: 0.0 }
}
