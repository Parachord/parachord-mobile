package com.parachord.shared.sync

/**
 * One copy of a playlist for the N-way 3-way merge. [tracks] is the copy's
 * CURRENT ordered list of canonical track keys (`isrc-…` / `mbid-…` /
 * `norm-artist|title`). [editedAt] is when this copy was last edited — the
 * latest-edited copy sets the merged ORDER (order LWW). [id] is for diagnostics
 * only (`"local"` / `"spotify"` / `"applemusic"` / `"listenbrainz"`).
 */
data class PlaylistMergeCopy(
    val id: String,
    val tracks: List<String>,
    val editedAt: Long,
)

/**
 * The pure N-way 3-way playlist merge — Phase 0 of the multimaster sync engine
 * (`docs/plans/2026-06-21-nway-multimaster-playlist-sync-design.md`).
 *
 * Pure function: `(baseline, changedCopies) -> mergedTracks`. No I/O, no
 * provider knowledge, no timestamps beyond the per-copy `editedAt`. This is the
 * piece that MUST be bit-for-bit identical to the desktop JS implementation, so
 * its behavior is pinned by the [PlaylistMergeTest] vectors (the same fixtures
 * will drive the desktop port).
 *
 * `baseline` is the last tracklist all copies agreed on (the 3-way ancestor).
 * `copies` are the copies that CHANGED since that baseline — an unchanged copy
 * equals the baseline and contributes no delta (the caller passes only changed
 * copies; a copy that happens to equal the baseline is filtered out here too).
 *
 * Policy (chosen for "nothing lost", documented + tested):
 * - **Adds compose (union).** A track added by ANY copy is in the result.
 *   Concurrent adds on different services BOTH survive.
 * - **Removes propagate (union).** A baseline track is dropped if ANY copy
 *   removed it. A deliberate delete is honoured everywhere. (A baseline key can
 *   only be kept-or-removed by a copy, never "re-added" — so there is no
 *   same-key add-vs-delete race to LWW-tiebreak; presence is fully
 *   deterministic from the deltas.)
 * - **Order follows the most-recently-edited copy** (order LWW); tracks it
 *   lacks (added by other copies, or surviving baseline tracks) are appended in
 *   baseline-then-other-copy order. Order is low-stakes, so LWW keeps it
 *   predictable.
 *
 * NOTE: the mass-change safeguard (abort a merge that would drop > N% of the
 * playlist) and the partial-fetch guard live in the RECONCILIATION layer
 * (Phase 1+), not here — this stays a pure merge.
 */
object PlaylistMerge {

    fun merge(baseline: List<String>, copies: List<PlaylistMergeCopy>): List<String> {
        // Belt-and-suspenders: ignore any copy that equals the baseline (no delta).
        val changed = copies.filter { it.tracks != baseline }
        if (changed.isEmpty()) return baseline

        val baselineSet = baseline.toSet()
        val added = LinkedHashSet<String>()   // preserve first-seen order for appends
        val removed = HashSet<String>()
        for (c in changed) {
            val cset = c.tracks.toSet()
            for (k in c.tracks) if (k !in baselineSet) added.add(k)        // union adds
            for (k in baseline) if (k !in cset) removed.add(k)             // union removes
        }

        val present = HashSet(baselineSet).apply {
            removeAll(removed)
            addAll(added)
        }

        // Order: winner (latest edit) first, then surviving baseline order, then
        // any remaining present keys in other-copy order. Dedup, present-only.
        val winner = changed.maxByOrNull { it.editedAt }!!
        val result = ArrayList<String>(present.size)
        val seen = HashSet<String>()
        fun take(keys: List<String>) {
            for (k in keys) if (k in present && seen.add(k)) result.add(k)
        }
        take(winner.tracks)
        take(baseline)
        for (c in changed) take(c.tracks)
        return result
    }
}
