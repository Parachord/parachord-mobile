package com.parachord.shared.resolver

import com.parachord.shared.plugin.PluginManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The single shared resolver-orchestration unit (#210). Collapses the
 * duplicated fan-out logic from Android's `ResolverManager.resolve()` and iOS's
 * `IosResolverCoordinator.resolveSources()` into one `commonMain` class, with
 * the genuinely platform-specific async behind [ResolverRuntime] + an injected
 * Spotify lambda. A resolution-logic change now lives in ONE place; both
 * platforms inherit it.
 *
 * ## Two return contracts (D1)
 *
 * - [resolveScored] — fan out + re-score, **no** ranking/floor. Matches Android
 *   `resolve()` (the 0.60 floor + priority sort run downstream via
 *   `ResolverScoring.selectBest`). A sub-floor 0.50 source is still present.
 * - [resolveRanked] — `scoring.selectRanked(resolveScored(...))`. Matches iOS
 *   `resolveSources()`: drops sub-floor sources, orders by priority.
 *
 * ## Fan-out (D2)
 *
 * spotify (via [resolveSpotify]) + each [ResolverRuntime.nativeResolverIds] id
 * that passes the gate (→ [ResolverRuntime.resolveNative]) + each
 * [ResolverGating.axeResolverIds] id (→ [ResolverRuntime.resolveAxe]). All run
 * in parallel via `coroutineScope { async { … } }`.
 *
 * ## Gate (D3)
 *
 * `isActive(id) = (active.isEmpty() || id in active) && id !in excludeResolvers`
 * for spotify + natives. `.axe` ids come from [ResolverGating.axeResolverIds]
 * (which additionally applies `id !in disabled`), then `id !in excludeResolvers`.
 *
 * ## Constructor: narrow lambda inputs (deviation from the plan sketch)
 *
 * The plan sketched the constructor as taking the concrete `SettingsStore` +
 * `PluginManager`. Both are platform-tied and can't be cheaply constructed in
 * `commonTest`, so — per the Task 1 guidance — the coordinator instead depends
 * on `getActive` / `getDisabled` / `getPlugins` suspend/plain lambdas (the same
 * lambda-forwarding pattern used across `shared/`). The Android/iOS bindings
 * close over `settingsStore.getActiveResolvers()`,
 * `settingsStore.getDisabledPlugins()`, and `pluginManager.plugins.value`, and
 * over the platform's Spotify token-gate + `spotifyClient.searchTrack` for
 * [resolveSpotify]. This keeps the coordinator unit-testable and sidesteps
 * faking Ktor / the JS runtime.
 *
 * @param resolveSpotify resolves Spotify for a query; returns null when Spotify
 *   is off / has no token / errored (the platform binding owns that gate+search).
 */
class ResolverCoordinator(
    private val runtime: ResolverRuntime,
    private val scoring: ResolverScoring,
    private val getActive: suspend () -> List<String>,
    private val getDisabled: suspend () -> Set<String>,
    private val getPlugins: () -> List<PluginManager.PluginInfo>,
    private val resolveSpotify: suspend (query: String) -> ResolvedSource?,
) {
    /**
     * Fan out across spotify + natives + axe, returning the union re-scored
     * against the target — but **unranked and unfloored** (D1). Mirrors
     * Android's `ResolverManager.resolve()`.
     *
     * @param excludeResolvers ids to SKIP this run (e.g. Android's
     *   `resolveWithHints` excludes spotify when it already has an authoritative
     *   ID). Skipped resolvers never reach the network.
     */
    suspend fun resolveScored(
        query: String,
        targetTitle: String? = null,
        targetArtist: String? = null,
        album: String? = null,
        excludeResolvers: Set<String> = emptySet(),
    ): List<ResolvedSource> = coroutineScope {
        val active = getActive()
        val disabled = getDisabled()

        // Gate for spotify + natives (D3). `.axe` ids come pre-filtered from
        // ResolverGating.axeResolverIds (which also applies `id !in disabled`).
        fun isActive(id: String): Boolean =
            (active.isEmpty() || id in active) && id !in excludeResolvers

        val tasks = buildList {
            if (isActive("spotify")) {
                add(async { resolveSpotify(query) })
            }
            for (id in runtime.nativeResolverIds) {
                if (isActive(id)) {
                    add(async { runtime.resolveNative(id, query, targetTitle, targetArtist, album) })
                }
            }
            val axeIds = ResolverGating.axeResolverIds(
                plugins = getPlugins(),
                active = active,
                disabled = disabled,
                // spotify + natives are owned elsewhere — keep them out of the .axe set.
                nativeResolverIds = runtime.nativeResolverIds + "spotify",
            )
            for (id in axeIds) {
                if (id !in excludeResolvers) {
                    add(async { runtime.resolveAxe(id, query, targetTitle, targetArtist, album) })
                }
            }
        }

        var results = tasks.mapNotNull { it.await() }

        // Re-score only when BOTH target fields are present (Android parity):
        // 0.95 when title AND artist substring-match, 0.50 single-axis. Sources
        // with no matched fields (direct-ID hints) pass through unchanged.
        if (targetTitle != null && targetArtist != null) {
            results = ResolverGating.rescore(results, targetTitle, targetArtist)
        }
        results
    }

    /**
     * [resolveScored] + `scoring.selectRanked` (D1): drop sub-floor (< 0.60)
     * sources, order by resolver priority (a [preferredResolver] jumps to the
     * front). Mirrors iOS's `IosResolverCoordinator.resolveSources()`.
     *
     * [album] is forwarded to the runtime's `.axe` / native resolve (iOS's
     * `.axe` resolvers use it for disambiguation; Android drops it for `.axe`).
     * No `excludeResolvers` param by design: the ranked path (iOS
     * `resolveSources`) has no exclude concept; Android's exclude-aware path
     * calls [resolveScored] directly (via `resolveWithHints`).
     */
    suspend fun resolveRanked(
        query: String,
        targetTitle: String? = null,
        targetArtist: String? = null,
        album: String? = null,
        preferredResolver: String? = null,
    ): List<ResolvedSource> =
        scoring.selectRanked(
            resolveScored(query, targetTitle, targetArtist, album),
            preferredResolver,
        )

    /**
     * Resolve ONE specific resolver for a track (additive re-resolution, #1 /
     * #210 Task 6). When the user enables a resolver AFTER a track was already
     * cached, the caller resolves just the newly-enabled resolver and merges it
     * into the existing (still-good) sources — rather than re-resolving
     * everything. Mirrors the pre-#210 iOS `IosResolverCoordinator.resolveSingle`
     * dispatch verbatim:
     *
     * - `spotify` → the injected [resolveSpotify] lambda (already token-gated;
     *   returns null when off / no token / errored).
     * - an id in [ResolverRuntime.nativeResolverIds] → [ResolverRuntime.resolveNative]
     *   (already availability-gated inside the runtime, e.g. blank AM dev-token).
     * - otherwise, ONLY if a resolve-capable plugin with that id is loaded
     *   (via [getPlugins]) → [ResolverRuntime.resolveAxe]; else null.
     *
     * The raw source is re-scored via [scoreConfidence] when it carries a matched
     * title/artist, then returned ONLY if `!noMatch &&
     * confidence >= MIN_CONFIDENCE_THRESHOLD` — else null (the same 0.60 floor
     * the ranked path applies). The iOS layer keeps `pluginManager.ensureInitialized()`
     * on top (platform lifecycle), not here.
     */
    suspend fun resolveSingle(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    ): ResolvedSource? {
        val raw = when {
            resolverId == "spotify" -> resolveSpotify(query)
            resolverId in runtime.nativeResolverIds ->
                runtime.resolveNative(resolverId, query, targetTitle, targetArtist, album)
            getPlugins().any { it.id == resolverId } ->
                runtime.resolveAxe(resolverId, query, targetTitle, targetArtist, album)
            else -> null
        } ?: return null

        val scored = if (raw.matchedTitle != null || raw.matchedArtist != null) {
            raw.copy(
                confidence = scoreConfidence(
                    targetTitle ?: "",
                    targetArtist ?: "",
                    raw.matchedTitle,
                    raw.matchedArtist,
                ),
            )
        } else {
            raw
        }
        return if (!scored.noMatch && (scored.confidence ?: 0.0) >= ResolverScoring.MIN_CONFIDENCE_THRESHOLD) {
            scored
        } else {
            null
        }
    }
}
