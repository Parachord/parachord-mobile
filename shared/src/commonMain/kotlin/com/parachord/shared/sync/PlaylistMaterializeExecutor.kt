package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.platform.Log

private const val TAG = "MaterializeExecutor"

/**
 * Per-cycle outcome of [materializeToProvider]. Counts are what the executor
 * actually DID this cycle — not what the diff merely wanted.
 *
 * @property added            ids actually appended (or, for ReplaceOnly, the resolved adds the replace covered).
 * @property removed          ids/positions actually removed (or, for ReplaceOnly, the removeKeys the replace covered).
 * @property pendingAdds      adds whose native id didn't resolve — LEFT on the remote's add backlog for a future cycle.
 * @property unsupportedRemoves removes the provider can't honor (Apple Music add-only, or a partial-coverage ReplaceOnly).
 */
data class MaterializeResult(
    val added: Int,
    val removed: Int,
    val pendingAdds: Int,
    val unsupportedRemoves: Int,
)

/**
 * Apply the canonical tracklist to ONE provider's remote, non-destructively, via
 * an incremental identity-diff ([computeMaterializeDiff]).
 *
 * Non-destructive guarantee: removes come ONLY from a POSITIVE diff (a remote
 * track that is NOT in canonical) and target the track's EXISTING remote native
 * id / position, so they always succeed where supported. An add whose native id
 * doesn't resolve is left PENDING — the remote keeps everything else; we never
 * drop a track to "make room". Dispatches removal on
 * [ProviderFeatures.trackRemoveMode] — NEVER branches on `provider.id`.
 *
 * Error handling: the executor attempts each op ONCE and lets exceptions
 * propagate to the caller (SyncEngine), which has per-playlist try/catch. It does
 * NOT retry-on-throw — for ListenBrainz a thrown result can come from the second
 * `getPlaylistLastModified` call even when the write landed (add is
 * non-idempotent), so a blind retry would double-add.
 *
 * @param resolveNativeId maps a canonical track to the provider's native id for an
 *   ADD (e.g. a catalog search / hydration). Returns null when the track isn't in
 *   the provider's catalog — that add is counted as pending, never forced.
 */
suspend fun materializeToProvider(
    provider: SyncProvider,
    externalId: String,
    canonical: List<PlaylistTrack>,
    remote: List<PlaylistTrack>,
    resolveNativeId: suspend (PlaylistTrack) -> String?,
): MaterializeResult {
    val diff = computeMaterializeDiff(canonical, remote)

    // The diff already computed the unified representatives — consume them rather than
    // recomputing unifyTrackKeys(keysOf(...)) here (a verbatim duplicate of the diff's
    // own key derivation, and a silent drift hazard if trackKeysOf ever changes). One
    // authoritative keyspace: diff.canonicalKeys/remoteKeys line up 1:1 (order + length)
    // with canonical/remote, so we zip to map each diff key back to a concrete track.
    val canRepr = diff.canonicalKeys
    val remRepr = diff.remoteKeys

    // FIRST occurrence wins for each representative key (matches the diff's
    // .distinct() collapse — a duplicate identity maps to its first track).
    val keyToCanonical = HashMap<String, PlaylistTrack>()
    canRepr.forEachIndexed { i, key -> keyToCanonical.getOrPut(key) { canonical[i] } }
    val keyToRemote = HashMap<String, PlaylistTrack>()
    remRepr.forEachIndexed { i, key -> keyToRemote.getOrPut(key) { remote[i] } }
    val remKeyToPosition = HashMap<String, Int>()
    remRepr.forEachIndexed { i, key -> remKeyToPosition.getOrPut(key) { i } }

    var added = 0
    var removed = 0
    var pendingAdds = 0
    var unsupportedRemoves = 0

    when (provider.features.trackRemoveMode) {
        TrackRemoveMode.ByNativeId -> {
            removed = removeByNativeId(provider, externalId, diff.removeKeys, keyToRemote)
            val resolved = resolveAdds(diff.addKeys, keyToCanonical, resolveNativeId)
            pendingAdds = resolved.pending
            added = appendAdds(provider, externalId, resolved.ids)
        }

        TrackRemoveMode.ByPosition -> {
            val positions = diff.removeKeys.mapNotNull { remKeyToPosition[it] }.distinct()
            if (positions.isNotEmpty()) {
                provider.removePlaylistTracksByPosition(externalId, positions)
                removed = positions.size
            }
            val resolved = resolveAdds(diff.addKeys, keyToCanonical, resolveNativeId)
            pendingAdds = resolved.pending
            added = appendAdds(provider, externalId, resolved.ids)
        }

        TrackRemoveMode.Unsupported -> {
            unsupportedRemoves = diff.removeKeys.size
            // Adds still apply — the remote is add-only, not read-only.
            val resolved = resolveAdds(diff.addKeys, keyToCanonical, resolveNativeId)
            pendingAdds = resolved.pending
            added = appendAdds(provider, externalId, resolved.ids)
        }

        TrackRemoveMode.ReplaceOnly -> {
            // A wholesale replace REWRITES the remote, so it's only safe when the ENTIRE
            // canonical list maps to a native id — both the ADDS (which must hydrate) AND
            // the EXISTING canonical tracks (whose native-id column must be non-null).
            // A null id on EITHER side would silently DROP that track from the replace →
            // the remote SHRINKS → violates the non-destructive invariant. Resolve the
            // adds first (same hydration path as the additive route, step 4).
            val addKeys = diff.addKeys.toHashSet()
            val resolvedAdds = HashMap<String, String>() // addKey → native id
            var addCoverageGap = 0
            for (key in diff.addKeys) {
                val track = keyToCanonical[key] ?: continue
                val nid = resolveNativeId(track)
                if (nid.isNullOrBlank()) addCoverageGap++ else resolvedAdds[key] = nid
            }

            // Build the full canonical native-id list in canonical order: an ADD uses its
            // freshly-resolved id; an existing (already-remote) track uses its known
            // native id. Skip blanks so a dropped track shrinks the list (caught below).
            val allNativeIds = ArrayList<String>(canonical.size)
            canRepr.forEachIndexed { i, key ->
                val nid = if (key in addKeys) resolvedAdds[key] else provider.nativeIdOf(canonical[i])
                if (!nid.isNullOrBlank()) allNativeIds.add(nid)
            }
            // Full coverage = EVERY canonical track contributed a non-blank id (no silent
            // drop). Only then can a replace cover all canonical tracks without shrinking.
            val fullCoverage = allNativeIds.size == canonical.size

            if (fullCoverage && diff.removeKeys.isNotEmpty()) {
                // Full coverage AND there's something to remove → one replace covers both.
                provider.replacePlaylistTracks(externalId, allNativeIds)
                removed = diff.removeKeys.size
                added = diff.addKeys.size
            } else {
                // Incomplete coverage (some add unresolved, OR an existing canonical track
                // has a null native id) OR nothing to remove. We can't safely replace —
                // a replace here would shrink the remote — so skip removes and degrade to
                // the additive add path. The remote keeps every existing track
                // (non-destructive); only the resolvable ADDS are appended. No removes →
                // additive append (preserves existing order; reorder is v1-deferred).
                if (diff.removeKeys.isNotEmpty()) unsupportedRemoves = diff.removeKeys.size
                pendingAdds = addCoverageGap
                // Preserve canonical add order (HashMap iteration order is unspecified).
                val orderedAddIds = diff.addKeys.mapNotNull { resolvedAdds[it] }
                added = appendAdds(provider, externalId, orderedAddIds)
            }
        }
    }

    // v1: best-effort order is deferred (YAGNI). No reorder API call.
    if (diff.reorderNeeded && provider.features.canReorder) {
        Log.d(TAG, "materializeToProvider: reorder needed for $externalId on ${provider.id} — deferred (v1)")
    }

    return MaterializeResult(
        added = added,
        removed = removed,
        pendingAdds = pendingAdds,
        unsupportedRemoves = unsupportedRemoves,
    )
}

/** Resolved adds: the native ids that hydrated, plus a count of the ones that didn't. */
private data class ResolvedAdds(val ids: List<String>, val pending: Int)

private suspend fun resolveAdds(
    addKeys: List<String>,
    keyToCanonical: Map<String, PlaylistTrack>,
    resolveNativeId: suspend (PlaylistTrack) -> String?,
): ResolvedAdds {
    val ids = ArrayList<String>(addKeys.size)
    var pending = 0
    for (key in addKeys) {
        val track = keyToCanonical[key] ?: continue
        val nid = resolveNativeId(track)
        if (nid.isNullOrBlank()) pending++ else ids.add(nid)
    }
    return ResolvedAdds(ids, pending)
}

/** Append guard: only call the provider when there's at least one id. */
private suspend fun appendAdds(
    provider: SyncProvider,
    externalId: String,
    ids: List<String>,
): Int {
    if (ids.isEmpty()) return 0
    provider.addPlaylistTracks(externalId, ids)
    return ids.size
}

private suspend fun removeByNativeId(
    provider: SyncProvider,
    externalId: String,
    removeKeys: List<String>,
    keyToRemote: Map<String, PlaylistTrack>,
): Int {
    val ids = removeKeys.mapNotNull { key ->
        val track = keyToRemote[key] ?: return@mapNotNull null
        provider.nativeIdOf(track)
    }
    if (ids.isEmpty()) return 0
    provider.removePlaylistTracksByNativeId(externalId, ids)
    return ids.size
}
