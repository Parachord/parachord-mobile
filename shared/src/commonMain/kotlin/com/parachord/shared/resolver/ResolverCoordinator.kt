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
        @Suppress("UNUSED_PARAMETER") album: String? = null,
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
                    add(async { runtime.resolveNative(id, query, targetTitle, targetArtist) })
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
                    add(async { runtime.resolveAxe(id, query, targetTitle, targetArtist) })
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
     * [album] is reserved for future per-resolver album-scoping (e.g. the
     * desktop AM resolver's album-search path) — currently forwarded but unused.
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
}
