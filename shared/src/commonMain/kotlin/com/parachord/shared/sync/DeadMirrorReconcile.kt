package com.parachord.shared.sync

/**
 * Pure decision logic for safely reconciling "is this mirror gone?" during a
 * playlist pull. Absence from a provider's just-fetched list is NOT proof a
 * mirror was deleted — the fetch may have been partial (a mid-pagination
 * timeout, an API truncation), and acting on that would mass-delete live
 * playlists. Every destructive action (row removal OR chip/link detach) is
 * therefore gated on a targeted existence PROBE that returns an explicit 404.
 *
 * Mirrors the codebase's pure-function pattern ([PlaylistMerge],
 * [computeNwayPropagationPlan], NwaySourceAuthority) so the dangerous logic is
 * unit-testable without a live provider or DB.
 *
 * STUB BODIES — replaced once the RED tests are watched failing (TDD).
 */
object DeadMirrorReconcile {

    /**
     * Of [localExternalIds] (mirrors we currently track for one provider), the
     * ones ABSENT from [bulkRemoteIds] (the provider's just-fetched list). These
     * are SUSPECTS only — absence alone is not proof; each must be probe-confirmed
     * before any destructive action.
     */
    fun suspectedGone(localExternalIds: Set<String>, bulkRemoteIds: Set<String>): Set<String> =
        localExternalIds - bulkRemoteIds

    /**
     * Of [suspected] mirrors, the ones CONFIRMED gone: the existence probe
     * ([probeExists]: externalId -> still-exists?) returned an explicit `false`
     * (definitive 404). A missing entry, or `true`, counts as "still exists" —
     * conservative by contract: a transient/ambiguous/unavailable probe must
     * NEVER trigger removal. This is the gate that stops a partial fetch from
     * mass-deleting live playlists.
     */
    fun confirmedGone(suspected: Set<String>, probeExists: Map<String, Boolean>): Set<String> =
        suspected.filter { probeExists[it] == false }.toSet()

    /**
     * The per-playlist channel-override to WRITE when detaching a dead
     * [deadProvider] mirror: the playlist's current EFFECTIVE channels minus the
     * dead provider.
     *
     * [effectiveChannels] MUST be the override-aware effective set (override ?:
     * prefix+links), e.g. `getPlaylistMirrors(id).keys`. Seeding from the
     * EFFECTIVE set is deliberate and load-bearing in BOTH directions:
     *  - it preserves every OTHER still-live mirror (a bare link-derived set
     *    could silently drop a mode-default Spotify/AM mirror — the
     *    channel-override-is-an-allowlist rule); AND
     *  - it RESPECTS an existing override, so detaching never re-enables a
     *    provider the user had turned off. (Seeding from the raw links instead
     *    would resurrect user-disabled providers — the opposite bug.)
     *
     * May return empty — when the dead mirror was the playlist's only enabled
     * provider, the result is "syncs with nothing" (now purely local). Callers
     * MUST still WRITE the empty set (it persists via a sentinel that round-trips
     * as emptySet, NOT null); falling back to null would let the stale id-prefix
     * or a lingering link resurrect the chip.
     */
    fun overrideAfterDetach(effectiveChannels: Set<String>, deadProvider: String): Set<String> =
        effectiveChannels - deadProvider
}
