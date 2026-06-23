package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack

/**
 * Result of an identity-level diff between a playlist's canonical (merged)
 * tracklist and one provider's current remote tracklist.
 *
 * The keys here are the cross-copy **representative key strings** produced by
 * [unifyTrackKeys] (e.g. `"isrc-…"`, `"mbid-…"`, `"norm-artist|title"`), NOT raw
 * MBIDs/ISRCs. The executor (next task) maps each key back to a concrete
 * `PlaylistTrack` (and its provider-native id) to actually add/remove it.
 *
 * Both [addKeys] and [removeKeys] are de-duplicated — each represented identity
 * appears at most once, even if a source list contained two tracks that collapse
 * to the same representative key. The caller can therefore map them 1:1 to native
 * ids without re-de-duping (otherwise a duplicate would double-add / double-remove).
 *
 * @property addKeys    representative keys present in canonical but absent from remote — tracks to add (de-duplicated).
 * @property removeKeys representative keys present in remote but absent from canonical — tracks to remove (de-duplicated).
 * @property reorderNeeded membership is identical but order differs (best-effort hint).
 *           Only meaningful when both add/remove lists are empty; an add or remove
 *           will itself rewrite order, so reorder isn't separately flagged then.
 * @property canonicalKeys canonical's unified representative keys, SAME order/length as the
 *           `canonical` input list (NOT de-duplicated). The executor zips this with the
 *           `canonical` tracks to map each diff key back to its concrete track — the ONE
 *           authoritative keyspace, so the executor never recomputes [unifyTrackKeys].
 * @property remoteKeys   remote's unified representative keys, SAME order/length as the
 *           `remote` input list (NOT de-duplicated). Zipped with `remote` for native-id /
 *           position lookup of a remove.
 */
data class MaterializeDiff(
    val addKeys: List<String>,
    val removeKeys: List<String>,
    val reorderNeeded: Boolean,
    val canonicalKeys: List<String>,
    val remoteKeys: List<String>,
)

/**
 * Pure, I/O-free, provider-agnostic identity diff for incremental playlist
 * materialization.
 *
 * Both lists are run through the SAME [trackKeysOf] / [unifyTrackKeys] used by
 * the N-way merge, so the same song matches across drifted identity tiers — a
 * different recording-MBID, a remaster-suffixed title, an un-enriched `norm`-only
 * copy vs its MBID twin — and is therefore NOT spuriously seen as an
 * add-on-one-side / remove-on-the-other. This is the whole point: a
 * non-destructive incremental write must not churn a track just because two
 * services label the same recording differently.
 *
 * - **addKeys**: in canonical, not in remote → the executor adds these.
 * - **removeKeys**: in remote, not in canonical → the executor removes these.
 * - **reorderNeeded**: membership equal, order differs. Set-based membership uses
 *   the unified representatives, so order comparison is also representative-based.
 *
 * Edge cases:
 * - Empty canonical + empty remote → all-empty diff, no reorder (idempotent).
 * - Empty remote (fresh/empty mirror) → every canonical key is an add.
 * - Duplicate tracks within ONE list collapse to the same representative; the
 *   set-based membership treats them as one identity (matching the merge's own
 *   collapse semantics) AND [addKeys]/[removeKeys] are `.distinct()`-ed, so the
 *   identity is emitted at most once. Reorder compares the full ordered lists, so a
 *   duplicate that shifts order can still flag reorder — acceptable; the executor
 *   reconciles against the canonical order.
 *
 * Deterministic and fixture-testable — no clock, no network, no provider id.
 */
fun computeMaterializeDiff(
    canonical: List<PlaylistTrack>,
    remote: List<PlaylistTrack>,
): MaterializeDiff {
    fun keysOf(ts: List<PlaylistTrack>) = ts.map {
        trackKeysOf(
            isrc = it.trackIsrc,
            recordingMbid = it.trackRecordingMbid,
            artist = it.trackArtist,
            title = it.trackTitle,
        )
    }

    val unified = unifyTrackKeys(listOf(keysOf(canonical), keysOf(remote)))
    val canKeys = unified[0]
    val remKeys = unified[1]

    val canSet = canKeys.toSet()
    val remSet = remKeys.toSet()

    // De-dup so an intra-list duplicate identity (two tracks collapsing to the same
    // representative) is emitted once — the executor maps these 1:1 to native ids and
    // appends, so a repeated key would double-add / double-remove. reorderNeeded still
    // compares the FULL ordered key lists below (NOT the de-duped add/remove lists).
    val addKeys = canKeys.filter { it !in remSet }.distinct()
    val removeKeys = remKeys.filter { it !in canSet }.distinct()
    val reorderNeeded = addKeys.isEmpty() && removeKeys.isEmpty() && canKeys != remKeys

    return MaterializeDiff(
        addKeys = addKeys,
        removeKeys = removeKeys,
        reorderNeeded = reorderNeeded,
        canonicalKeys = canKeys,
        remoteKeys = remKeys,
    )
}
