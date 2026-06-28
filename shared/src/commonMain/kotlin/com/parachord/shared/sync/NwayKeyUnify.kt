package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.resolver.validateIsrc
import kotlinx.serialization.Serializable

/**
 * A track's identity tiers for cross-copy unification. [norm] is always present
 * (lower+trim `artist|title`); [isrc]/[mbid] are present only when known
 * (validated / normalized identically to [canonicalTrackKey]). `@Serializable`
 * because the N-way baseline persists these (richer than a single key string, so
 * the merge ancestor retains all tiers for bridging).
 */
@Serializable
data class TrackKeys(val isrc: String?, val mbid: String?, val norm: String)

/**
 * Trailing remaster annotation, e.g. " - 2025 Remastered", " (Remastered)",
 * " (Remastered 2011)", " - Remaster". A remaster is the SAME song, so two
 * services labeling it differently must still unify on the `norm` tier — this is
 * the no-ISRC bridge for the LB axis (ListenBrainz playlists carry no per-track
 * ISRC, so the isrc tier can't help there). CONSERVATIVE on purpose: we do NOT
 * strip Live / Acoustic / Single Version / Radio Edit / `(feat. …)` — those are
 * genuinely different recordings a user may legitimately keep both of. Anchored
 * to the END so it never touches a mid-title word.
 */
private val REMASTER_SUFFIX =
    Regex("""\s*[-(]\s*(\d{4}\s+)?remaster(ed)?(\s+\d{4})?\s*\)?\s*$""")

/**
 * Normalized title for the `norm` identity tier — lowercased, trimmed, with the
 * trailing remaster annotation stripped. **Cross-engine:** the desktop JS port
 * MUST apply the identical rule (it feeds the shared unify fixtures).
 */
fun normalizeTitleForKey(title: String?): String =
    (title?.trim()?.lowercase() ?: "").replace(REMASTER_SUFFIX, "").trim()

/** Build [TrackKeys] from raw track fields — same normalization as [canonicalTrackKey]. */
fun trackKeysOf(isrc: String?, recordingMbid: String?, artist: String?, title: String?): TrackKeys {
    val a = artist?.trim()?.lowercase() ?: ""
    val t = normalizeTitleForKey(title)
    return TrackKeys(
        isrc = validateIsrc(isrc),
        mbid = recordingMbid?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        norm = "$a|$t",
    )
}

/** Ordered [TrackKeys] for a playlist's local tracks (position order) — the
 *  N-way baseline ancestor (Phase 4). ISRC (now persisted on playlist_tracks) is
 *  the strongest cross-service identity tier — it lets the same recording unify
 *  across services even when recording-MBID or normalized title drift (the
 *  reconciliation-redesign fix); mbid + norm remain as fallbacks. */
fun nwayBaselineTrackKeys(tracks: List<PlaylistTrack>): List<TrackKeys> =
    tracks.sortedBy { it.position }
        .map { trackKeysOf(isrc = it.trackIsrc, recordingMbid = it.trackRecordingMbid, artist = it.trackArtist, title = it.trackTitle) }

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
 * Norm-bridge guard (parachord#911 P3 — docs/plans/2026-06-28-nway-norm-bridge-guard.md):
 * the `norm` tier bridges tracks in a group only when they carry NO disagreeing
 * ISRCs. ISRC is the hard per-recording discriminator — ≥2 distinct ISRC
 * identities in a norm group are genuinely different recordings (two "Intro"s,
 * album-cut vs single, original vs remaster) and are never collapsed via norm.
 * A shared MBID may still span two ISRCs (same recording, reissue/market
 * re-registration — the strong phase unions those first). **MBID *disagreement*
 * does NOT block the norm bridge:** recording-MBID legitimately varies for the
 * same song across services/enrichment, and blocking it would re-introduce the
 * false-REMOVE data-loss class (2026-06-22) the MBID-variance bridge fixed.
 * Residual (accepted): two genuinely-different ISRC-less recordings sharing a
 * norm still unify — rare + low-harm (a duplicate, never a false-remove).
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

    // PHASE 1 — strong unions (unconditional): equal ISRC, then equal MBID.
    // These establish "same recording"; a shared MBID may legitimately span two
    // ISRCs (reissue), which is why the per-component ISRC presence is what the
    // norm guard counts (NOT distinct ISRC values).
    val byIsrc = HashMap<String, Int>()
    val byMbid = HashMap<String, Int>()
    for (i in 0 until n) {
        val tk = nodes[i]
        tk.isrc?.let { k -> byIsrc[k]?.let { union(it, i) } ?: run { byIsrc[k] = i } }
        tk.mbid?.let { k -> byMbid[k]?.let { union(it, i) } ?: run { byMbid[k] = i } }
    }

    // Snapshot the post-phase-1 components + per-component ISRC presence (ISRC is
    // the only hard discriminator the norm bridge must not cross). Decisions below
    // read this stable snapshot, so the result is independent of group iteration
    // order; the unions are applied afterward.
    val root1 = IntArray(n) { find(it) }
    val rootHasIsrc = HashMap<Int, Boolean>()
    for (i in 0 until n) if (nodes[i].isrc != null) rootHasIsrc[root1[i]] = true

    // PHASE 2 — guarded norm bridge, per norm group.
    val byNorm = LinkedHashMap<String, MutableList<Int>>()
    for (i in 0 until n) byNorm.getOrPut(nodes[i].norm) { ArrayList() }.add(i)
    for ((_, group) in byNorm) {
        val isrcComps = group.asSequence().map { root1[it] }.filter { rootHasIsrc[it] == true }.toSet()
        if (isrcComps.size <= 1) {
            // ≤1 confident ISRC identity (absent or agree) → norm bridges the whole
            // group (closes norm↔mbid / norm↔isrc cross-service dedup + MBID variance).
            for (k in 1 until group.size) union(group[0], group[k])
        } else {
            // ≥2 disagreeing ISRCs → never collapse them via norm. Bridge only the
            // ISRC-free nodes (mbid-only + pure-norm) among themselves; a missed
            // dedup is a duplicate, never a false-remove.
            val free = group.filter { rootHasIsrc[root1[it]] != true }
            for (k in 1 until free.size) union(free[0], free[k])
        }
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
