package com.parachord.shared.ios

import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverCoordinator

/**
 * iOS's resolver entry point for Swift. Post-#210 this is a thin delegate over
 * the shared [ResolverCoordinator] (fan-out + native Spotify branch + native
 * Apple Music + `.axe` execution + re-score + rank) — the platform-specific
 * async lives in `IosResolverRuntime`.
 *
 * The public API ([resolveSources] / [resolveSingle]) is unchanged — Swift
 * callers depend on it. Both delegate straight to the shared coordinator
 * ([ResolverCoordinator.resolveRanked] / [ResolverCoordinator.resolveSingle]);
 * the only iOS-layer responsibility left here is `pluginManager.ensureInitialized()`
 * (platform plugin lifecycle) before each call.
 */
class IosResolverCoordinator(
    private val coordinator: ResolverCoordinator,
    private val pluginManager: PluginManager,
) {
    /**
     * Resolve a track across stream-capable active resolvers and return the
     * ranked, floor-filtered sources (best first). Empty when nothing matched
     * above the confidence floor. Delegates to the shared coordinator's ranked
     * path — behavior identical to the pre-#210 inline fan-out.
     */
    suspend fun resolveSources(artist: String, title: String, album: String?): List<ResolvedSource> {
        pluginManager.ensureInitialized()
        return coordinator.resolveRanked(
            query = "$artist $title",
            targetTitle = title,
            targetArtist = artist,
            album = album,
        )
    }

    /**
     * Resolve ONE specific resolver for a track (additive re-resolution, #1).
     * When the user enables a resolver AFTER a track was already cached, we
     * resolve just the newly-enabled resolver and merge it into the existing
     * (still-good) sources — rather than re-resolving everything. Re-scored +
     * 0.60-floor-filtered like [resolveSources]; null on miss / below floor.
     */
    suspend fun resolveSingle(resolverId: String, artist: String, title: String, album: String?): ResolvedSource? {
        pluginManager.ensureInitialized()
        return coordinator.resolveSingle(
            resolverId = resolverId,
            query = "$artist $title",
            targetTitle = title,
            targetArtist = artist,
            album = album,
        )
    }
}
