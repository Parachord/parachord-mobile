package com.parachord.shared.sync

/**
 * One copy's state at reconciliation time. [id] is `"local"` or a providerId.
 * [keys] is the copy's CURRENT ordered canonical-key tracklist (for an
 * unchanged copy the caller passes the baseline — no fetch needed). [editedAt]
 * feeds order-LWW. [changed] is the cheap detection result (provider token ≠
 * stored token, or local `locallyModified`/`lastModified > baselineSyncedAt`).
 */
data class NwayCopyState(
    val id: String,
    val keys: List<String>,
    val editedAt: Long,
    val changed: Boolean,
)

/**
 * What the N-way engine WOULD do for one playlist this cycle (shadow mode
 * computes it; propagation later acts on it).
 * - [changedCopyIds]: copies that actually carried a delta vs the baseline.
 * - [merged]: the 3-way-merged tracklist (canonical keys).
 * - [wouldPushTo]: copies whose current tracklist ≠ [merged] — i.e. the copies
 *   that would receive a push to converge (includes unchanged copies that lag
 *   the merge, excludes any copy already equal to it).
 */
data class NwayShadowPlan(
    val changedCopyIds: List<String>,
    val merged: List<String>,
    val wouldPushTo: List<String>,
)

/**
 * A human-reviewable shadow-mode entry — what the engine WOULD do for one
 * playlist this cycle. Surfaced to a dev UI (Android Settings "N-way shadow
 * report") so the merge can be validated against a real library before
 * propagation is switched on. `name` (not `description` — Swift bridging) keeps
 * it iOS-safe for later reuse.
 */
data class NwayShadowEntry(
    val playlistName: String,
    val localPlaylistId: String,
    val changedCopies: List<String>,
    val mergedCount: Int,
    val wouldPushTo: List<String>,
    val dropPercent: Int,
    val massChange: Boolean,
)

/**
 * A human-reviewable PROPAGATION outcome (Phase 4) — what [SyncEngine.runNwayPropagation]
 * did (or, in dry-run, WOULD do) for one playlist this cycle. Surfaced to the
 * Android Settings "N-way propagation" dev section so real-library behavior can
 * be validated before/while real writes are enabled. `name` (not `description` —
 * Swift bridging) keeps it iOS-safe for later reuse.
 *
 * [status] is one of: `"would-push"` (dry-run), `"pushed"` (real write),
 * `"mass-change-abort"`, `"partial-abort"` (a merged key couldn't resolve to a
 * track). Only playlists with a pending delta produce an entry.
 */
data class NwayPropagationEntry(
    val playlistName: String,
    val localPlaylistId: String,
    val mergedCount: Int,
    val pushTargets: List<String>,
    val status: String,
    /** Adds left for backfill — a track not yet present on a provider (coverage gap). */
    val pendingAdds: Int = 0,
    /** Removes a provider can't honor (e.g. Apple Music's append-only PUT degradation). */
    val unsupportedRemoves: Int = 0,
    /** Per-target named add/remove tracks for the migration preview — populated
     *  only in dry-run/preview (#289 brick #5); empty during real propagation. */
    val perTarget: List<MigrationPerTarget> = emptyList(),
)

/**
 * Pure shadow-mode reconciliation (Phase 3): `(baseline, copies) -> plan`.
 * No I/O, no provider knowledge, no mutation — the caller fetches the copies'
 * tokens/tracklists, calls this, and LOGS the plan (pushes nothing in shadow).
 *
 * Returns `null` when no copy carried a delta (the perf short-circuit — the
 * playlist is skipped). A copy flagged [NwayCopyState.changed] but whose [keys]
 * still equal the baseline (e.g. a rename bumped the token but the tracklist is
 * untouched) contributes no delta and is filtered out, matching [PlaylistMerge].
 *
 * Presence/order policy lives entirely in [PlaylistMerge] (delete-wins, order
 * LWW); this only wires detection → merge → would-push.
 */
fun computeNwayShadowPlan(baseline: List<String>, copies: List<NwayCopyState>): NwayShadowPlan? {
    val changed = copies.filter { it.changed && it.keys != baseline }
    if (changed.isEmpty()) return null
    val merged = PlaylistMerge.merge(baseline, changed.map { PlaylistMergeCopy(it.id, it.keys, it.editedAt) })
    val wouldPushTo = copies.filter { it.keys != merged }.map { it.id }
    return NwayShadowPlan(
        changedCopyIds = changed.map { it.id },
        merged = merged,
        wouldPushTo = wouldPushTo,
    )
}

/**
 * What PROPAGATION would actually do (Phase 4) — the shadow plan refined for
 * real writes:
 * - [merged]: the merged tracklist (representative keys).
 * - [pushTargets]: the copies that will receive a push — [NwayShadowPlan.wouldPushTo]
 *   filtered to copies the user can WRITE to. A read-only followed source
 *   (`writable=false`) is excluded: we never push merged changes BACK to a
 *   playlist you can't edit. `local` and owned/collaborative mirrors stay in.
 * - [massChangeAbort]: TOTAL-WIPE floor only — the merge would collapse a
 *   non-empty playlist to ZERO tracks (the catastrophic provider-glitch
 *   signature). The caller must NOT propagate a total wipe. Large but non-empty
 *   turnover is NOT blocked — many playlists legitimately change most of their
 *   tracks daily (product decision: propagate all non-empty changes). An empty
 *   mirror copy is excluded from deletion deltas by the executor, so a single
 *   empty copy can't force a wipe.
 */
data class NwayPropagationPlan(
    val merged: List<String>,
    val pushTargets: List<String>,
    val massChangeAbort: Boolean,
)

/**
 * Pure propagation plan (Phase 4): `(baseline, copies, writability) -> plan`.
 * Reuses [computeNwayShadowPlan] for the merge, then layers the two
 * write-safety rules — per-copy writability (don't push back to a non-writable
 * source) and the total-wipe floor. Returns `null` when no copy changed (the
 * perf short-circuit, same as shadow). No I/O.
 *
 * [writableById]: copy id -> can-write. A missing entry defaults to writable
 * (the common case: `local` and owned mirrors). The propagation executor passes
 * `false` only for a non-writable source copy.
 */
fun computeNwayPropagationPlan(
    baseline: List<String>,
    copies: List<NwayCopyState>,
    writableById: Map<String, Boolean>,
): NwayPropagationPlan? {
    val shadow = computeNwayShadowPlan(baseline, copies) ?: return null
    val pushTargets = shadow.wouldPushTo.filter { writableById[it] != false }
    return NwayPropagationPlan(
        merged = shadow.merged,
        pushTargets = pushTargets,
        // Total-wipe floor only (see [NwayPropagationPlan.massChangeAbort]).
        massChangeAbort = shadow.merged.isEmpty() && baseline.isNotEmpty(),
    )
}

/**
 * Fraction of the baseline that [merged] would drop — the mass-change-guard
 * signal (design step 6). The propagation phase aborts a playlist whose merge
 * drops more than the threshold (a provider hiccup returning empty); shadow
 * mode just logs the ratio. Returns 0.0 for an empty baseline.
 */
fun nwayBaselineDropFraction(baseline: List<String>, merged: List<String>): Double {
    if (baseline.isEmpty()) return 0.0
    val mergedSet = merged.toSet()
    val dropped = baseline.count { it !in mergedSet }
    return dropped.toDouble() / baseline.size
}
