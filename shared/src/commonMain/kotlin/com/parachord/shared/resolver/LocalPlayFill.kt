package com.parachord.shared.resolver

/**
 * Finding a local track's other sources when it plays (#769, desktop
 * parachord#1604, a port of desktop's `enrichment-gate.js` `needsPlayFill` /
 * `mergeFoundSources`).
 *
 * A track whose only playable source is a local file never gets its other
 * sources by itself: the background sweep skips local files on purpose (it
 * would otherwise fan out over a whole local library), and a play routes the
 * file without resolving. So the Now Playing source switcher offered nothing
 * else. When such a track starts playing, each app asks the resolvers once per
 * track per session, after the play has started, and adds what they found to
 * its resolver cache.
 */
object LocalPlayFill {
    const val LOCAL_RESOLVER = "localfiles"

    fun isLocal(resolver: String?): Boolean = resolver == LOCAL_RESOLVER

    /**
     * True when [sources] has at least one source that can play and every one
     * that can is a local file. A `noMatch` source or one below the confidence
     * floor ([ResolverBadges.isPlayable]) doesn't count either way.
     */
    fun needsPlayFill(sources: List<ResolvedSource>): Boolean {
        val playable = sources.filter(ResolverBadges::isPlayable)
        return playable.isNotEmpty() && playable.all { isLocal(it.resolver) }
    }

    /**
     * Whether a track that's starting should be filled: it plays from a local
     * file ([playingResolver]) and [cached] (its resolver-cache entry, null when
     * it has none) holds no playable source from any other resolver. A track
     * with no cache entry counts as local-only, since the file is what plays.
     */
    fun shouldFill(playingResolver: String?, cached: List<ResolvedSource>?): Boolean =
        isLocal(playingResolver) &&
            cached.orEmpty().none { ResolverBadges.isPlayable(it) && !isLocal(it.resolver) }

    /**
     * [current] plus the sources in [found] that can play from a resolver
     * [current] has no playable source of. A source the track already has is
     * never replaced; `noMatch` and below-floor finds are dropped.
     */
    fun mergeFoundSources(
        current: List<ResolvedSource>?,
        found: List<ResolvedSource>,
    ): List<ResolvedSource> {
        val have = current.orEmpty()
        val haveResolvers = have.filter(ResolverBadges::isPlayable).map { it.resolver }.toSet()
        val added = found.filter { ResolverBadges.isPlayable(it) && it.resolver !in haveResolvers }
        return have + added
    }
}

/**
 * Remembers which tracks were filled this session, so a track is asked about
 * once however often it plays (desktop's `enrichmentAttemptedRef`). Keyed by
 * the resolver cache's track key. Call it from one thread (both apps: main).
 */
class PlayFillTracker {
    private val attempted = mutableSetOf<String>()

    /** True the first time [key] is claimed this session, false after. */
    fun claim(key: String): Boolean = attempted.add(key)
}
