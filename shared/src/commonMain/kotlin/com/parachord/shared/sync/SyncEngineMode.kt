package com.parachord.shared.sync

/**
 * Per-client sync-engine selection (parachord#911 — legacy → N-way migration).
 *
 * Byte-aligned port of desktop `sync-engine/sync-engine-mode.js`. The active mode
 * is stored as the KvStore key `sync_engine_mode`, **LOCAL and authoritative for
 * THIS client only** — Parachord has no user account, so there is no cross-client
 * coordinator; each client's mode is its own. The fresh-install default is a
 * build-time constant ([DEFAULT_MODE]): existing/legacy clients default `legacy`;
 * new mobile builds eventually ship `new` (the Phase-3 flip). See
 * `docs/plans/2026-06-28-legacy-to-nway-sync-migration.md` in the desktop repo
 * and parachord-mobile#289.
 *
 *   `legacy` (default) — legacy sync drives; N-way dormant.
 *   `shadow`           — legacy drives; N-way computes + logs a dry-run plan, no writes.
 *   `new`              — N-way drives; legacy PLAYLIST push/create/import stands down.
 *
 * **Mutual exclusion is scoped to PLAYLIST sync.** Library / collection sync
 * (tracks / albums / artists) is unaffected by the mode — N-way is playlist-only.
 */
object SyncEngineMode {
    const val LEGACY = "legacy"
    const val SHADOW = "shadow"
    const val NEW = "new"

    /** Mirrors desktop `SYNC_ENGINE_MODES` — order is the canonical state progression. */
    val ALL: List<String> = listOf(LEGACY, SHADOW, NEW)

    /**
     * Build-time fresh-install default. Stays `legacy` through Phases 1–2 (opt-in
     * shadow/new behind the dev toggle); flips to `new` for new builds at Phase 3.
     * Existing installs keep their persisted value regardless of this constant.
     */
    const val DEFAULT_MODE: String = LEGACY

    /** Coerce any persisted/raw value to a known mode; unknown/null → `legacy`. */
    fun normalize(raw: String?): String = if (raw != null && raw in ALL) raw else LEGACY

    /**
     * Legacy playlist sync (outbound create/push AND inbound import) runs in every
     * mode EXCEPT `new`. In `new` the N-way reconcile is the sole playlist
     * authority, so the legacy paths must stand down to avoid double-writing the
     * shared remotes and the local playlist state.
     */
    fun legacyPlaylistSyncEnabled(modeOrRaw: String?): Boolean = normalize(modeOrRaw) != NEW

    /**
     * N-way performs REAL writes only in `new`. In `shadow` it computes a dry-run
     * plan (compute + log, zero writes). Maps to the legacy `isNwayPropagateEnabled`.
     */
    fun nwayWritesEnabled(modeOrRaw: String?): Boolean = normalize(modeOrRaw) == NEW

    /**
     * N-way engine is active at all (computes a plan) — true in `shadow` and `new`.
     * Mobile bridge for the legacy `isNwayEnabled` gate (detection + shadow/dry-run).
     */
    fun nwayActive(modeOrRaw: String?): Boolean = normalize(modeOrRaw) != LEGACY

    /**
     * Derive the 3-state mode from the legacy two-boolean pair, for one-time
     * migration of existing installs off `nway_enabled` / `nway_propagate`.
     */
    fun fromLegacyBooleans(nwayEnabled: Boolean, nwayPropagate: Boolean): String = when {
        nwayEnabled && nwayPropagate -> NEW
        nwayEnabled -> SHADOW
        else -> LEGACY
    }
}
