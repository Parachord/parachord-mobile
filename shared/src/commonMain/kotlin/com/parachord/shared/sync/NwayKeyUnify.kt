package com.parachord.shared.sync

import com.parachord.shared.resolver.validateIsrc

/**
 * A track's identity tiers for cross-copy unification. [norm] is always present
 * (lower+trim `artist|title`); [isrc]/[mbid] are present only when known
 * (validated / normalized identically to [canonicalTrackKey]).
 */
data class TrackKeys(val isrc: String?, val mbid: String?, val norm: String)

/** Build [TrackKeys] from raw track fields — same normalization as [canonicalTrackKey]. */
fun trackKeysOf(isrc: String?, recordingMbid: String?, artist: String?, title: String?): TrackKeys {
    val a = artist?.trim()?.lowercase() ?: ""
    val t = title?.trim()?.lowercase() ?: ""
    return TrackKeys(
        isrc = validateIsrc(isrc),
        mbid = recordingMbid?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        norm = "$a|$t",
    )
}

/**
 * Cross-copy key unification — the N-way Phase 4 prerequisite that fixes the
 * cross-service key drift shadow mode found.
 *
 * Given several track lists (baseline + each copy, in order), tracks that are
 * the SAME across copies are unified — two tracks match if they share ANY
 * identity tier (isrc OR mbid OR norm), transitively — and every list is
 * rewritten to a single canonical REPRESENTATIVE key per equivalence class.
 * [PlaylistMerge] then runs UNCHANGED on the representatives (its delete-wins /
 * order-LWW contract is untouched).
 *
 * This bridges both failure modes:
 * - an un-enriched `norm`-only track unifies with its `mbid` twin on a
 *   MBID-native service (closes the `norm-` vs `mbid-` gap);
 * - two different recording-MBIDs of the same song unify via shared `norm`
 *   (closes the `mbid-` recording-variance gap).
 *
 * Representative per class: the strongest tier present — `isrc-` else `mbid-`
 * else `norm-` — with the lexicographically-SMALLEST value in that tier, so the
 * output is deterministic regardless of input order (required for cross-engine
 * parity with the desktop JS port). A singleton class yields exactly the key
 * [canonicalTrackKey] would — backward compatible when nothing bridges.
 *
 * Residual (accepted, documented, pinned by fixtures): two genuinely different
 * recordings that share an identical normalized `artist|title` (two "Intro"s, a
 * single-vs-album cut, a cover) unify via `norm`. Rare and low-harm in a
 * playlist; the same residual the single-key `norm-` fallback already carried.
 * Guarding the `norm` bridge on disagreeing ISRCs is a tracked refinement.
 *
 * Pure + deterministic — part of the cross-engine fixture contract
 * (`docs/nway-key-unify-fixtures.json`).
 */
fun unifyTrackKeys(lists: List<List<TrackKeys>>): List<List<String>> {
    val nodes = ArrayList<TrackKeys>()
    for (l in lists) for (tk in l) nodes.add(tk)
    val n = nodes.size

    val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != c) { val nx = parent[c]; parent[c] = r; c = nx }
        return r
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) // smaller index is root → deterministic
    }

    // Union any two tracks sharing a tier value (first-seen anchors the group).
    val byIsrc = HashMap<String, Int>()
    val byMbid = HashMap<String, Int>()
    val byNorm = HashMap<String, Int>()
    for (i in 0 until n) {
        val tk = nodes[i]
        tk.isrc?.let { k -> byIsrc[k]?.let { union(it, i) } ?: run { byIsrc[k] = i } }
        tk.mbid?.let { k -> byMbid[k]?.let { union(it, i) } ?: run { byMbid[k] = i } }
        byNorm[tk.norm]?.let { union(it, i) } ?: run { byNorm[tk.norm] = i }
    }

    // Per-component strongest tier values (min for determinism).
    val compIsrc = HashMap<Int, String>()
    val compMbid = HashMap<Int, String>()
    val compNorm = HashMap<Int, String>()
    for (i in 0 until n) {
        val r = find(i); val tk = nodes[i]
        tk.isrc?.let { compIsrc[r] = minOf(compIsrc[r] ?: it, it) }
        tk.mbid?.let { compMbid[r] = minOf(compMbid[r] ?: it, it) }
        compNorm[r] = minOf(compNorm[r] ?: tk.norm, tk.norm)
    }
    fun repr(root: Int): String =
        compIsrc[root]?.let { "isrc-$it" }
            ?: compMbid[root]?.let { "mbid-$it" }
            ?: "norm-${compNorm[root]}"

    var idx = 0
    return lists.map { l -> l.map { repr(find(idx++)) } }
}
