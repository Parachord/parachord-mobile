package com.parachord.shared.resolver

/** One resolver badge on a track row: the source that resolver would play. */
data class ResolverBadge(val resolver: String, val confidence: Double) {
    /** Drawn at 60% opacity ([ResolverBadges.DIM_AT_OR_BELOW]). */
    val dimmed: Boolean get() = ResolverBadges.isDimmed(confidence)
}

/**
 * Which resolver badges a track row shows, and how (#592). One rule for both
 * apps, as desktop's list views draw them (`app.js` badge rows):
 *
 * - **Only sources that can play:** not `noMatch`, at or above
 *   [ResolverScoring.MIN_CONFIDENCE_THRESHOLD] (a wrong-song match would show a
 *   badge for a service that won't play it), and from a resolver the user has
 *   on: in the active list when there is one (an empty list means all), and
 *   not disabled. Desktop badges only `playbackResolvers`.
 * - **One badge per resolver**, its best source (desktop keys sources by resolver).
 * - **Ordered by the user's resolver order**, then confidence; resolvers not in
 *   it go last. The leftmost badge is the source that plays.
 * - **Dimmed at or below [DIM_AT_OR_BELOW].**
 */
object ResolverBadges {
    /**
     * A badge at or below this confidence is drawn dimmed. Desktop dims below
     * 0.95 since May 2026 (`getBestSourceConfidence`); the phones keep 0.80 for now.
     */
    const val DIM_AT_OR_BELOW = 0.80

    fun isDimmed(confidence: Double): Boolean = confidence <= DIM_AT_OR_BELOW

    /** A source that could play: matched, and at or above the confidence floor. */
    fun isPlayable(source: ResolvedSource): Boolean =
        !source.noMatch && (source.confidence ?: 0.0) >= ResolverScoring.MIN_CONFIDENCE_THRESHOLD

    /** Whether any source could play the track (a row with none is greyed out). */
    fun hasPlayableSource(sources: List<ResolvedSource>): Boolean = sources.any(::isPlayable)

    /**
     * The badges for [sources]. [order] is the user's resolver order (empty: no
     * order), [active] the active resolvers (empty: all), [disabled] the ones
     * turned off.
     */
    fun badges(
        sources: List<ResolvedSource>,
        order: List<String>,
        active: List<String> = emptyList(),
        disabled: List<String> = emptyList(),
    ): List<ResolverBadge> {
        val best = LinkedHashMap<String, Double>()
        for (source in sources) {
            if (!isPlayable(source)) continue
            val id = source.resolver
            if (active.isNotEmpty() && id !in active) continue
            if (id in disabled) continue
            val confidence = source.confidence ?: continue
            val held = best[id]
            if (held == null || confidence > held) best[id] = confidence
        }
        fun rank(id: String) = order.indexOf(id).let { if (it < 0) order.size else it }
        return best.entries
            .sortedWith(compareBy<Map.Entry<String, Double>> { rank(it.key) }.thenByDescending { it.value })
            .map { ResolverBadge(it.key, it.value) }
    }
}
