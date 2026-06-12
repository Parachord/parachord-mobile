package com.parachord.shared.resolver

import com.parachord.shared.plugin.PluginManager

/**
 * Pure, model-agnostic pieces of resolver orchestration shared by Android
 * (`ResolverManager`) and iOS (`IosResolverCoordinator`) — the byte-for-byte
 * identical parts of the fan-out, extracted so a fix lands once (#210,
 * incremental step). What is NOT here, deliberately:
 *
 * - **Native-resolver gating** differs per platform (Android never populates
 *   `active_resolvers` and gates natives on connection-state; iOS + desktop use
 *   `active_resolvers` as the authoritative enable set). Converging Android onto
 *   the desktop model is the full-coordinator follow-up, not this dedup.
 * - The per-platform AM mechanism, Android's `resolveWithHints` wrapper, and the
 *   `resolve` (unranked) vs `resolveSources` (ranked) return contract.
 */
object ResolverGating {
    /**
     * The `.axe` resolver ids to fan out for a resolve: resolve-capable plugins,
     * EXCLUDING this platform's native resolvers and any user-disabled plugin,
     * filtered to the active set (empty active = all on). Identical on both
     * platforms — Android applies its `excludeResolvers` separately in its loop
     * (this helper intentionally doesn't bake that in), iOS has no such concept.
     */
    fun axeResolverIds(
        plugins: List<PluginManager.PluginInfo>,
        active: Collection<String>,
        disabled: Set<String>,
        nativeResolverIds: Set<String>,
    ): List<String> =
        plugins.filter {
            it.capabilities["resolve"] == true &&
                it.id !in nativeResolverIds &&
                it.id !in disabled &&
                (active.isEmpty() || it.id in active)
        }.map { it.id }

    /**
     * Re-score resolved sources against the target (the `.axe` placeholder 0.9
     * becomes 0.95 when title AND artist substring-match, 0.50 single-axis — the
     * 0.60 floor in [ResolverScoring] then drops the wrong-song 0.50s). Sources
     * with neither a matched title nor a matched artist pass through unchanged:
     * their confidence was set authoritatively (e.g. a direct-ID hint at 1.0).
     */
    fun rescore(
        sources: List<ResolvedSource>,
        targetTitle: String,
        targetArtist: String,
    ): List<ResolvedSource> =
        sources.map { source ->
            if (source.matchedTitle != null || source.matchedArtist != null) {
                source.copy(
                    confidence = scoreConfidence(
                        targetTitle, targetArtist, source.matchedTitle, source.matchedArtist,
                    ),
                )
            } else {
                source
            }
        }
}
