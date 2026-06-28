package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack

/**
 * Pure shaping for the legacy → N-way migration preview (parachord#911 / mobile#289
 * brick #5). Byte-aligned port of desktop `migration-plan.js`. Consumes the shadow
 * dry-run reconcile output ([MigrationShadowOutput] — each result already carries a
 * `displayName` + a named per-target diff produced from [computeMaterializeDiff]) and
 * produces the render model for the preview modal + a GitHub-issue for "Report a
 * problem". No I/O, no UI. See docs/plans/2026-06-28-legacy-to-nway-sync-migration.md.
 */

/** A track as rendered in the preview — `{artist, title}`. */
data class PreviewTrack(val artist: String, val title: String)

/** One push target's named diff within a `would-push` reconcile result. */
data class MigrationPerTarget(
    val providerId: String,
    val addTracks: List<PreviewTrack> = emptyList(),
    val removeTracks: List<PreviewTrack> = emptyList(),
)

/**
 * One playlist's dry-run reconcile result (the shadow output's per-playlist entry).
 * `status` ∈ `would-push` | `total-wipe-abort` | `partial-abort`. A noop returns
 * null upstream (dropped from [MigrationShadowOutput.results]).
 */
data class MigrationReconcileResult(
    val status: String,
    val localId: String,
    val displayName: String? = null,
    val perTarget: List<MigrationPerTarget> = emptyList(),
)

/** The shadow dry-run output the shaper consumes. `cycles` = total playlists
 *  processed (so noopCount = cycles − results.size). */
data class MigrationShadowOutput(
    val results: List<MigrationReconcileResult> = emptyList(),
    val cycles: Int? = null,
    val errors: List<String> = emptyList(),
)

data class MigrationProviderDiff(
    val providerId: String,
    val adds: List<PreviewTrack>,
    val removes: List<PreviewTrack>,
)

data class MigrationChangedPlaylist(
    val localId: String,
    val displayName: String,
    val providers: List<MigrationProviderDiff>,
)

/** A playlist a safety abort protected from a destructive write. `reason` ∈
 *  `total-wipe` | `partial`. */
data class MigrationProtectedPlaylist(
    val localId: String,
    val displayName: String,
    val reason: String,
)

/** Render model for the preview modal. (`protected` is a Kotlin keyword, so the
 *  field is [protectedList]; semantics match desktop's `summary.protected`.) */
data class MigrationPlanSummary(
    val changed: List<MigrationChangedPlaylist>,
    val protectedList: List<MigrationProtectedPlaylist>,
    val noopCount: Int,
    val totalAdds: Int,
    val totalRemoves: Int,
    val hasRemoves: Boolean,
    val hasChanges: Boolean,
    val errorCount: Int,
    val cycles: Int,
)

data class MigrationReport(val title: String, val body: String, val githubUrl: String)

/** Byte-aligned with desktop `trackLabel`: `"$artist — $title"` (em-dash). */
fun trackLabel(t: PreviewTrack?): String {
    val artist = t?.artist ?: ""
    val title = t?.title ?: ""
    if (artist.isNotEmpty() && title.isNotEmpty()) return "$artist — $title"
    return title.ifEmpty { artist.ifEmpty { "Unknown track" } }
}

/** Shadow output → render model. A `would-push` with an empty effective diff is a
 *  noop (filtered out); noopCount is derived from the cycle count. */
fun summarizeMigrationPlan(shadowOutput: MigrationShadowOutput): MigrationPlanSummary {
    val results = shadowOutput.results
    val cycles = shadowOutput.cycles ?: results.size
    val errors = shadowOutput.errors

    val changed = mutableListOf<MigrationChangedPlaylist>()
    val protectedList = mutableListOf<MigrationProtectedPlaylist>()
    var totalAdds = 0
    var totalRemoves = 0

    for (r in results) {
        when (r.status) {
            "would-push" -> {
                val providers = r.perTarget
                    .map { MigrationProviderDiff(it.providerId, it.addTracks, it.removeTracks) }
                    .filter { it.adds.isNotEmpty() || it.removes.isNotEmpty() }
                if (providers.isNotEmpty()) {
                    for (p in providers) { totalAdds += p.adds.size; totalRemoves += p.removes.size }
                    changed.add(MigrationChangedPlaylist(r.localId, r.displayName ?: r.localId, providers))
                }
            }
            "total-wipe-abort" ->
                protectedList.add(MigrationProtectedPlaylist(r.localId, r.displayName ?: r.localId, "total-wipe"))
            "partial-abort" ->
                protectedList.add(MigrationProtectedPlaylist(r.localId, r.displayName ?: r.localId, "partial"))
        }
    }

    return MigrationPlanSummary(
        changed = changed,
        protectedList = protectedList,
        noopCount = maxOf(0, cycles - results.size),
        totalAdds = totalAdds,
        totalRemoves = totalRemoves,
        hasRemoves = totalRemoves > 0,
        hasChanges = changed.isNotEmpty(),
        errorCount = errors.size,
        cycles = cycles,
    )
}

/** Render model → a prefilled GitHub issue. The caller ALSO copies `body` to the
 *  clipboard before opening `githubUrl` (GitHub truncates very long prefilled
 *  bodies in the URL). Targets the **parachord-mobile** repo — this shared code
 *  serves the iOS + Android apps; desktop's `migration-plan.js` deliberately keeps
 *  its own `Parachord/parachord`. (The only intentional divergence from desktop.) */
fun buildMigrationReport(summary: MigrationPlanSummary, appVersion: String): MigrationReport {
    val v = appVersion.ifEmpty { "unknown" }
    val lines = mutableListOf<String>()
    lines.add("The new-sync preview showed a diff that looks wrong. Reporting it instead of accepting (parachord#911).")
    lines.add("")
    lines.add("## What looks wrong")
    lines.add("<!-- e.g. these tracks should not be removed — tell us what is off -->")
    lines.add("")
    lines.add("## Preview diff")
    lines.add("- App version: $v")
    lines.add("- Playlists with changes: ${summary.changed.size}")
    lines.add("- Would add: ${summary.totalAdds} track(s)")
    lines.add("- Would remove: ${summary.totalRemoves} track(s)")
    if (summary.protectedList.isNotEmpty()) lines.add("- Safety aborts: ${summary.protectedList.size}")
    lines.add("")
    for (pl in summary.changed) {
        lines.add("### ${pl.displayName}")
        for (p in pl.providers) {
            for (t in p.removes) lines.add("- remove · ${p.providerId} · ${trackLabel(t)}")
            for (t in p.adds) lines.add("- add · ${p.providerId} · ${trackLabel(t)}")
        }
        lines.add("")
    }
    val body = lines.joinToString("\n")
    val title = "New sync preview looks wrong (${summary.totalRemoves} remove(s), v$v)"
    val githubUrl = "https://github.com/Parachord/parachord-mobile/issues/new" +
        "?title=${encodeUriComponent(title)}" +
        "&body=${encodeUriComponent(body)}" +
        "&labels=${encodeUriComponent("sync")}"
    return MigrationReport(title, body, githubUrl)
}

/**
 * Resolve the remote tracklist for a preview per-target diff. Returns null when the
 * remote state is UNKNOWN — there's no provider for the target, OR the fresh fetch
 * FAILED (a 429 / network error). The caller MUST skip the target on null rather than
 * diff against an empty list: treating a failed fetch as a confirmed-empty remote
 * turns every canonical track into a phantom "add" (#298 — a ListenBrainz rate-limit
 * burst during the preview fabricated 649 spurious adds). Tracks already loaded for a
 * CHANGED copy are returned as-is (no fetch); a genuinely-empty remote that fetched
 * successfully is an empty (non-null) list, which legitimately diffs as all-adds.
 */
internal suspend fun resolvePreviewRemoteTracks(
    changedTracks: List<PlaylistTrack>?,
    externalId: String,
    fetch: (suspend (String) -> List<PlaylistTrack>)?,
): List<PlaylistTrack>? {
    if (changedTracks != null) return changedTracks
    val doFetch = fetch ?: return null
    val result = runCatching { doFetch(externalId) }
    return if (result.isFailure) null else result.getOrThrow()
}

/**
 * Build one push target's named diff for the preview. Runs the SAME identity
 * [computeMaterializeDiff] the executor uses, then maps the diff's OWN keyspace
 * back to tracks 1:1 with the inputs — adds resolve from the canonical (merged)
 * list, removes from the target's remote list (which the caller fetches fresh for
 * an unchanged-but-lagging mirror so a removed track still has a name). A track's
 * preview name is `{trackArtist, trackTitle}` (mobile PlaylistTrack is already
 * normalized — no `name`/`artistName` fallbacks needed). Pure.
 */
fun buildMigrationPerTarget(
    providerId: String,
    mergedTracks: List<PlaylistTrack>,
    remoteTracks: List<PlaylistTrack>,
): MigrationPerTarget {
    val diff = computeMaterializeDiff(mergedTracks, remoteTracks)
    val keyToCanonical = HashMap<String, PlaylistTrack>()
    diff.canonicalKeys.forEachIndexed { i, k -> if (k !in keyToCanonical) keyToCanonical[k] = mergedTracks[i] }
    val keyToRemote = HashMap<String, PlaylistTrack>()
    diff.remoteKeys.forEachIndexed { i, k -> if (k !in keyToRemote) keyToRemote[k] = remoteTracks[i] }
    return MigrationPerTarget(
        providerId = providerId,
        addTracks = diff.addKeys.mapNotNull { k -> keyToCanonical[k]?.let { PreviewTrack(it.trackArtist, it.trackTitle) } },
        removeTracks = diff.removeKeys.mapNotNull { k -> keyToRemote[k]?.let { PreviewTrack(it.trackArtist, it.trackTitle) } },
    )
}

/** `encodeURIComponent` equivalent — UTF-8 %XX for everything except the JS
 *  unreserved set `A-Za-z0-9-_.!~*'()`. */
private const val URI_UNRESERVED =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

internal fun encodeUriComponent(s: String): String {
    val sb = StringBuilder()
    for (byte in s.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        if (c < 128 && URI_UNRESERVED.indexOf(c.toChar()) >= 0) {
            sb.append(c.toChar())
        } else {
            sb.append('%')
            sb.append(((c shr 4) and 0xF).toString(16).uppercase())
            sb.append((c and 0xF).toString(16).uppercase())
        }
    }
    return sb.toString()
}
