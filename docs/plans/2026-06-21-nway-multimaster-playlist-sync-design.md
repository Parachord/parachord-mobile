# N-Way Multimaster Playlist Sync — Design

**Date:** 2026-06-21
**Status:** Proposed (design approved; implementation is a separate multi-phase project)
**Scope:** shared sync engine (`commonMain`) + **desktop** (`app.js`, the real user base) + Android/iOS UI
**Supersedes:** the canonical-source model (`syncedFrom.resolver` single source + push-only mirrors) on both desktop and mobile.

## Goal

Every copy of a playlist — the local Parachord copy (per device), Spotify, Apple
Music, ListenBrainz — is a **peer**. An edit on **any** of them propagates to all
the others, and concurrent edits compose (per-track 3-way merge) rather than
clobber. Resolves the gap audited against the user's 7 use cases (#2/#3: "last
edit on any source updates all").

## Why this is greenfield (not a port)

Research (desktop `main.js:6384-7068`, `sync-engine/index.js`; Android
`SyncEngine.kt:1318-1344`) confirmed **both desktop and mobile are
canonical-source-based**: a playlist has ONE `syncedFrom.resolver`; only that
provider can update the local truth; all others are append-only push mirrors
(the `isOwnPullSource` / `isCrossProviderPushMirror` guards). There is no
cross-provider conflict resolution today. N-way is new behavior both clients
must adopt — a mobile-only N-way engine would **fight** a canonical-source
desktop on the same shared remote playlists (mobile pushes an AM edit to
Spotify; desktop pushes Spotify back over AM → oscillation). So this is the
**shared model both clients run**, gated so mixed-version clients don't conflict.

## Decisions (confirmed with user)

- **N-way multimaster**, shared model adopted by desktop + mobile.
- **Conflict policy: best-effort last-write-wins**, edit-time from real
  timestamps where available, detection-time only as a bounded Spotify fallback.
- **Granularity: per-track 3-way merge** (nothing lost on concurrent edits).
- **Migration: one-time bootstrap** (seed baseline + normalize mirrors), run
  desktop-side (where the users + data are).

## The Spotify timestamp problem (and resolution)

Conflict resolution needs a comparable "when edited" per copy. Providers differ:
- **Apple Music / ListenBrainz:** `last_modified` date — a real timestamp.
- **Local:** `lastModified` epoch.
- **Spotify:** gives only `snapshot_id` (opaque change token — tells you *that*
  it changed, never *when*). Three usable signals, combined:
  - **`snapshot_id`** → change **detection** (reliable for adds, removes, reorders).
  - **`MAX(track.added_at)`** → edit **time** (real ISO timestamp; accurate when
    the last edit ADDED a track — the common case). User-sourced insight.
  - **`ETag` / `If-None-Match` 304** → **efficiency** (skip the fetch when unchanged).
  - **Residual gap:** a pure reorder/delete bumps `snapshot_id` but not
    `MAX(added_at)`. Detection still fires (snapshot); for the *timestamp* in
    that case only, fall back to **detection time** as the floor (we know it
    changed since last sync, so it's at least "now"). Rare, bounded.

## Canonical track key (the linchpin)

The thing that lets us diff a Spotify tracklist against an Apple Music one.
Precedence: `validateIsrc(isrc)` → `isrc-<X>`; else `recordingMbid` → `mbid-<X>`;
else `norm-<artist|title>` (lower/trimmed). Spotify gives ISRC (`external_ids`),
AM gives ISRC (catalog), LB is MBID-native; `MbidEnrichmentService` backfills
the rest over time. **Residual risk:** a track with no ISRC/MBID on one service
but ISRC on another mismatches → treated as two tracks (possible dup); minimized
by enrichment, accepted otherwise.

## State model

Replace the single `syncedFrom.resolver` with a per-playlist **synced baseline**
+ a per-(playlist, provider) sync record.

**Per playlist:**
- `baseline`: the last-merged tracklist as an **ordered list of canonical keys**
  (the 3-way merge ancestor).
- `baselineSyncedAt`: when the baseline was last established.

**Per (playlist, provider)** — extends the existing `sync_playlist_link` row:
- `externalId` (have it)
- `changeToken` — Spotify `snapshot_id` / AM·LB `last_modified`, **captured
  AFTER our own push** (echo suppression).
- `editedAt` — Spotify `MAX(added_at)` (detection-time floor on reorder/delete)
  / AM·LB `last_modified`.
- `lastSyncedAt`.

The legacy `syncedFrom` / scalar fields stay during migration + shadow mode, then
retire.

## Reconciliation algorithm (per playlist, per cycle)

1. **Detect (cheap, gates everything).** For each copy, compare live token to
   stored: Spotify `snapshot_id` (ETag/304 pre-gate), AM/LB `last_modified`,
   local `locallyModified || lastModified > baselineSyncedAt`. **If no copy
   changed → skip the playlist** (the correct N-way perf short-circuit).
2. **Fetch only changed copies.** An unchanged copy's tracklist == `baseline`
   (no fetch). **Partial-fetch guard:** if a *changed* copy's fetch fails, skip
   the whole playlist this cycle (don't treat unfetched tracks as removed).
3. **Per-copy delta vs baseline:** `added(C)` = keys in C not baseline;
   `removed(C)` = keys in baseline not C.
4. **Merge (presence):** start from baseline; **union all adds** (concurrent
   adds on different services both survive); **apply removes** (a baseline track
   drops if any copy removed it — standard 3-way: a delete propagates
   everywhere) **unless** another copy re-added that key (adds beat stale
   deletes). True LWW tie-break (`editedAt`) only for a same-key add-vs-delete
   race.
5. **Merge (order):** follow the most-recently-edited copy's order (LWW), then
   append keys it lacks (added by others) in their relative order.
6. **Mass-change guard:** if the merge would drop > N% of the playlist (provider
   hiccup returning empty), **abort this playlist's merge** — never propagate a
   destructive empty.
7. **Propagate:** for each provider whose current tracklist ≠ merged, push the
   merged list (resolve canonical keys → that provider's track ids via the
   existing per-track `sources` / hydration; search only when the id is unknown).
   Update the local copy to merged. A copy already == merged gets **no push**.
8. **Echo-suppression (load-bearing):** after pushing, **capture the provider's
   NEW token** (Spotify `replacePlaylistTracks` returns the fresh `snapshot_id`;
   AM/LB re-read `last_modified` post-push) and store THAT. Set
   `baseline = merged`, `baselineSyncedAt = now`, clear `locallyModified`. Next
   cycle every token matches → nothing detected → no echo/loop.

## Failure modes / safety

- **Mass-change guard** (step 6) — destructive-empty protection.
- **Partial-fetch guard** (step 2) — never read a failed fetch as "removed".
- **Apple Music append-only PUT** — removals can't propagate *to* AM (API
  limit). AM merges *from* but lags on removals. Documented asymmetry.
- **Echo loop** — prevented by post-push token capture (step 8). The #1 risk if
  done wrong; the property to test hardest.

## Migration (one-time bootstrap, desktop-side)

Run once per playlist when the N-way engine first activates:
1. **Seed baseline** = current local tracklist (canonical keys). The local copy
   is the trustworthy state today (it's the canonical source).
2. **Normalize mirrors** = push that baseline to every linked mirror so all
   copies start **identical** — guarantees the first N-way merge has nothing to
   reconcile, so latent mirror divergence can't get pulled in.
3. **Capture each provider's post-push token** + `baselineSyncedAt`; flip the
   playlist to N-way.

- **No regression:** the canonical local was already authoritative; a
  mirror-side edit was already being ignored/overwritten. Normalize does that
  once, explicitly. Users only *gain* mirror-edit propagation afterward.
- **Cost:** one push per synced playlist — a bounded one-time burst; must
  respect the Spotify rate-limit gate + stagger.
- **AM caveat:** append-only means AM mirrors may not fully normalize on
  removals.
- Android (handful of users) runs the same shared migration; iOS (no data) no-op.

## Phased rollout (de-risks the dangerous path)

0. **Pure merge module** — the 3-way merge is a pure function
   (`baseline + copies → mergedResult`). **Fully unit-test in isolation first**;
   correctness lives here (union-adds, propagate-deletes, re-add-beats-delete,
   order LWW, mass-change abort).
1. **State model** — add `baseline` + per-provider token fields ALONGSIDE the
   existing canonical-source fields (don't remove yet).
2. **Migration** — the bootstrap above (behind the flag, before propagation).
3. **Shadow mode** — every sync, compute the merge and **log what it would do,
   push nothing**. Validate against real desktop libraries at zero risk.
4. **Enable propagation** — behind a per-user flag; retire the canonical-source
   model once validated. Built (Jun 22 2026) as `SyncEngine.runNwayPropagation` +
   `propagateReconcilePlaylist` with the four correctness traps (key→track
   resolution, hydration ordering, baseline re-derivation, post-push echo
   suppression):
   - **Executor + echo-loop gate green** — `runNwayPropagation(dryRun)` behind
     `NWAY_PROPAGATE` (real writes need `NWAY_ENABLED` too; a dry-run needs only
     `NWAY_ENABLED`). Echo-loop / mass-change / wired-into-`syncAll` regression
     tests in `ListenBrainzPushShortCircuitTest`.
   - **Dev dry-run** — Android Settings → "Developer · N-way propagation" runs a
     dry-run (preview, no writes) into `nwayPropagationLog`; reviewed on a real
     library (Jun 22 2026: a healthy 4-copy push and a correct 60% mass-change
     abort).
   - **Per-sync wiring** — `runNwayPropagation()` runs at the end of
     `syncPlaylists` (after the normal pull/push + `clearLocallyModifiedFlags`),
     gated + echo-suppressed; skipped on a provider-filtered partial sync.
   - **Mapper-outage resilience (Jun 22)** — LB hydration falls back mapper →
     MusicBrainz `/isrc/` → fuzzy MB search; AM hydration switched to the catalog
     `filter[isrc]` endpoint (the iTunes path returned `text/javascript` and
     never deserialized). Per-provider coverage guard + empty-mirror-as-fill-
     target + total-wipe-only mass-change.
   - **⛔ BLOCKED — real-writes OFF: propagation caused DATA LOSS (Jun 22).** A
     track added on one service made the 3-way merge **drop tracks no copy
     deleted** (stale baseline under partial coverage + cross-service key drift →
     false union-removes). **Do NOT enable real-writes.** The fix is a
     reconciliation correctness redesign — see
     `docs/plans/2026-06-22-nway-reconciliation-redesign.md`. Re-enabling
     real-writes is gated on that redesign + a no-false-drop test harness.
   - **Remaining before default-on:** the reconciliation redesign; then desktop
     parity (step 5).
5. **Desktop ↔ mobile parity** — desktop adopts the same algorithm (port of the
   shared `commonMain` merge logic). Gate enablement on "all the user's clients
   support N-way" so a mixed fleet never oscillates.

Plus a **sync×2 idempotency** integration harness: a no-op sync must change
nothing (same tokens, same baseline, no pushes) — the regression guard for the
echo loop. Covered structurally by the echo-loop + wired-`syncAll` tests; the
direct guard is `propagation pushes the merged list once then suppresses the echo`.

**Known residual (unify):** a provider copy whose tracks carry blank
title+artist hash to `norm="|"` and unify together (then mbid-bridge), collapsing
distinct tracks to one representative. Pre-existing in shadow mode + documented in
`NwayKeyUnify`; surfaced by an MBID-only test fake, not seen with real provider
metadata. Out of scope here; guard the `norm` bridge on a blank norm if it ever
bites real data.

## Out of scope

- Real-time / push-based sync (this stays poll-based).
- Collaborative-playlist permission semantics beyond what exists.
- Album/artist/track collection N-way (this is playlists only).

## Code-sharing decision (settled)

- **Mobile (Android + iOS): one shared KMP implementation** in
  `shared/commonMain` — the existing `SyncEngine` extended. Android + iOS run
  identical Kotlin.
- **Desktop: a SEPARATE JavaScript implementation** in `app.js` (it is not a KMP
  target — Kotlin can't compile into the Electron app). We deliberately accept
  two implementations rather than a WASM/single-engine bridge.
- **Parity is enforced by a SHARED TEST-VECTOR SUITE, not shared code.** The
  pure 3-way merge is a deterministic function (`baseline + copies → result`).
  Author the fixtures ONCE as plain JSON (input baseline + per-copy edits +
  expected merged output) and run the SAME fixtures against both the Kotlin
  merge and the JS merge. Identical fixtures passing on both sides is what
  proves the two engines can't drift apart and start fighting over the same
  remote playlists. This is why Phase 0 (the pure merge module) is first — it is
  the one piece that must be bit-for-bit identical across Kotlin and JS.

## Resolved during implementation

- **Baseline storage** → new table `sync_playlist_baseline` keyed by
  `localPlaylistId` (Phase 1, shipped). Per-provider token state in a sibling
  `sync_playlist_nway` table, isolated from the canonical-source link/source
  DAOs during migration + shadow mode.
- **Presence conflict policy** → **delete always wins; `editedAt` affects order
  only, never presence.** A baseline key dropped by any copy is gone even if a
  newer copy still holds it (un-propagated baseline ≠ re-add). `added`/`removed`
  are disjoint by construction, so there is no same-key add-vs-delete race to
  LWW-tiebreak — the doc's "re-add beats stale delete" clause is vacuous and was
  removed. Confirmed with the user; both engines conform.
- **Cross-engine fixtures** → `docs/nway-playlist-merge-fixtures.json` is the
  canonical, language-neutral vector file. Mobile's `PlaylistMergeTest` is a 1:1
  transcription; desktop transcribes the same cases into its JS test. (Wiring
  the Kotlin test to READ the JSON directly is blocked on a commonTest resource
  loader for iOS Native — tracked nicety; the file is the human-diffable
  contract today.)

## Open questions for implementation

- Exact mass-change threshold N% (reuse the existing track/album safeguard value).
