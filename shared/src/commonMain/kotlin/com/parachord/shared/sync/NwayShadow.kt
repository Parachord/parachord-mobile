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
