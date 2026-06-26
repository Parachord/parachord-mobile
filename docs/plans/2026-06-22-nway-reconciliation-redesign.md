# N-Way Reconciliation Redesign — Correctness Pass

> **Status: DESIGN (no code yet). Real-writes propagation stays OFF until this
> lands + a no-false-drop test harness is green.** This doc exists because
> propagation, as built through 2026-06-22, **lost data** on a real account: a
> track added on one service caused the merge to drop tracks that *no copy
> deleted*. The cause is structural, not a one-line bug, so we stop patching and
> design the reconciliation model properly.
>
> **Read first:** `docs/plans/2026-06-21-nway-multimaster-playlist-sync-design.md`
> (overall design) and `docs/plans/2026-06-22-nway-phase4-executor.md` (the
> executor). The "N-Way" sections of `CLAUDE.md`.

---

## The incident (ground truth)

Playlist `LB Android test`, synced across Spotify + Apple Music + ListenBrainz.

1. It had 6 tracks; user added 2 on Spotify → 8.
2. Propagation filled LB to 8/8 (via the ISRC→MusicBrainz fallback) but **Apple
   Music was skipped** (iTunes hydration returned 0 — the content-type bug, since
   fixed in commit `1f6cadd`). Skipped provider ⇒ **partial coverage** ⇒ the
   baseline did **not** advance (correct, by the per-provider guard).
3. User added **1 song on ListenBrainz** (LB now 9) and synced.
4. **Result: the merge produced 3 tracks** — the 2 Spotify adds + the 1 new LB
   song. **The original 6 were dropped**, and the 3-track result propagated to
   Spotify too. Expected: 9 everywhere.

Nobody deleted those 6 tracks. The merge invented a deletion.

## Root cause

Two independent weaknesses combined:

### P1 — Baseline goes stale under partial coverage
The baseline (the 3-way-merge ancestor) is **one shared row per playlist**, and
it advances **only on full coverage of all writable mirrors** (the per-provider
coverage guard, b7dcf1d). When AM couldn't hydrate, coverage was partial, so the
baseline stayed pinned at the **original 6 tracks with their original keys** —
while the live copies evolved (LB now holds ISRC-resolved recording MBIDs, etc.).
The ancestor and the copies now describe the same songs with **different keys**.

### P1b — FIXED (Step 3-full, Jun 23): catalog-gap phantom deletion
> **Resolution:** the under-covering provider is now marked a pending fill target
> (`SyncEngine.NWAY_FILL_PENDING_ACTION`) and EXCLUDED from the merge's removal
> computation, so the baseline safely advances to the covered merge without
> stranding it as a future deleter. `NwayPartialCoverageTest` flipped from
> asserting the residual to asserting the fix (sync×2 no-drop, no churn) + a guard
> that a genuine removal still propagates past a pending provider. Original finding
> (now historical) below:

### P1b — CONFIRMED reachable: catalog-gap phantom deletion (Jun 22, 2-provider test)
The 2-provider partial-coverage test (`NwayPartialCoverageTest`) confirmed a
second, distinct data-loss vector. When a provider has a **catalog gap** on a
track (the track genuinely isn't in that provider's catalog) AND the gap is
*under* the mass-change threshold (so the provider is NOT skipped — it's pushed
N-1), the baseline **advances** to the full N. Next cycle that provider (holding
N-1) reads as having **deleted** the gap track vs the advanced baseline →
delete-always-wins union-removes it → the track is dropped **everywhere**, even
though other copies still have it. Distinct from the identity-drift incident
(which Steps 1+4 fix); this is the **per-provider catalog-gap semantics** —
"a track permanently absent from one provider must not read as a deletion."
This is the load-bearing reason real-writes stays OFF and the reason **Step 3
(pending-push markers / fill-target exclusion + catalog-gap-aware baseline) is
REQUIRED, not optional**.

### P2 — Cross-service track identity is fragile
`unifyTrackKeys` bridges copies by `isrc | mbid | norm` (any shared tier,
transitively). It fails when the *same song* has BOTH:
- a **different recording MBID** across services (e.g. the LB ISRC fallback
  resolved a different recording than the baseline's original), AND
- a **different normalized `artist|title`** (remaster/version parentheticals —
  "Zombie" vs "Zombie - 2025 Remastered", `(feat. …)`, live/single variants).

When neither tier matches, the baseline's track and the copy's track land in
different equivalence classes → the copy *appears* to lack the baseline track.

### The destructive interaction
`PlaylistMerge` uses **union-removes**: a baseline track is dropped if *any*
changed copy lacks it. With a stale baseline (P1) whose keys don't unify with the
evolved copies (P2), the copies "lack" the original 6 → union-removes drops them
→ and total-wipe-only is the sole remaining guard, so a non-empty destructive
result (3 tracks) sails through and propagates.

**The merge cannot distinguish "a copy deliberately removed this track" from
"this track's identity drifted / the ancestor is stale."** Today it assumes the
former. That assumption is the bug.

## The safety invariant (non-negotiable acceptance criterion)

> Propagation MUST NEVER drop a track that is present in — or identity-matches a
> track in — any copy. Removing a baseline track requires **positive evidence**
> that a copy *deliberately* removed it. Absence due to identity-mismatch,
> partial coverage, a stale ancestor, or a failed/partial fetch is **NOT**
> removal.

Everything below serves this invariant. A change that can't guarantee it is not
shippable behind real-writes.

## Problem 1 — Baseline stability under partial coverage

The shared single-baseline model assumes all mirrors converge together. Partial
coverage breaks that. Options:

- **A. Per-provider ancestor.** Track the last-converged tracklist *per (playlist,
  provider)* instead of one shared baseline. The merge ancestor for a given push
  is what that provider last agreed to. More state, but each provider's
  convergence is independent — an un-fillable AM can't strand LB's ancestor.
- **B. Pending-push markers (no ancestor pollution).** Keep the shared baseline
  but, when a provider is skipped, record a per-provider "owes a push" marker and
  **exclude that provider from the merge's removal computation** until it's
  caught up (it's a fill target, never a deletion source — generalizes the
  empty-mirror rule we already shipped). Advance the baseline to the merged
  result of the *covered* copies.
- **C. Covered-intersection baseline.** Advance the baseline to only the portion
  all writable copies actually hold. Conservative; risks never advancing when one
  provider perpetually can't hold some tracks (catalog gaps).

**Leaning B** (smallest delta from today, directly enforces the invariant: a
not-yet-covered provider is a fill target, not a deletion). A and B can combine.

## Problem 2 — Robust cross-service track identity

The union-remove decision must be **identity-confident**. Directions:

- **ISRC as the primary cross-service key.** ISRC is the one identifier that's
  stable across Spotify/AM/LB for a given recording. We now capture it (Spotify
  `external_ids.isrc`, AM catalog, local ID3) and backfill onto merged tracks —
  but it's **transient** (not persisted). To key the *ancestor* on ISRC it must
  be persisted (a `playlist_track.isrc` column, or persisted in the baseline
  TrackKeys). Then same-ISRC = same track, full stop.
- **Stronger norm normalization.** Strip version/remaster parentheticals,
  `(feat. …)`, `- … Remaster`, `- Single/Live` suffixes before computing `norm`,
  so "Zombie" and "Zombie - 2025 Remastered" share a norm. Must be a pure,
  fixture-pinned function shared with desktop (cross-engine parity). Risk:
  over-normalization collapsing genuinely-different tracks — gate carefully.
- **Confidence-gated union-remove.** Only treat a baseline track as "removed by
  copy X" when X is **fully fetched** AND we have an identity-confident reason it
  was deliberately dropped (e.g. it was present last cycle and X removed it). If
  identity can't be confidently established, **keep** the track (additive bias).

These compose: ISRC-keyed ancestor (kills most drift) + better norm (catches
no-ISRC cases) + confidence-gated removal (the backstop that enforces the
invariant when identity is uncertain).

## Recommended direction (synthesis, to be reviewed)

1. **Persist ISRC** on playlist tracks (and in the baseline ancestor) so identity
   is stable across cycles and services. This is the highest-leverage change —
   it removes the dominant drift source.
2. **Pending-push markers (Option B)** so a skipped/partial provider is a fill
   target, never a deletion source, and the baseline advances on the covered set.
3. **Confidence-gated union-remove** in the reconciliation layer (NOT in the pure
   `PlaylistMerge`, which stays fixture-pinned) — never drop a baseline track
   unless a fully-fetched, identity-matched copy deliberately removed it.
4. **Stronger shared norm normalization** as a pure fixture-pinned helper, for
   the no-ISRC tail.

Sequencing: (1)+(3) first — together they directly stop the data loss. (2)
removes the partial-coverage trigger. (4) is the long-tail polish.

## Test harness (gates real-writes)

A reconciliation correctness harness is the precondition for re-enabling
real-writes. Minimum cases:
- **No false drop:** baseline + copies where every copy holds the baseline tracks
  (possibly under drifted keys / partial coverage) ⇒ merge drops nothing.
- **Real removal still propagates:** a copy that genuinely removed a track (fully
  fetched, identity-matched) ⇒ that track is removed everywhere.
- **Add-only:** a track added on one service ⇒ present everywhere, nothing else
  changes (the incident's expected behavior — 8 + 1 = 9).
- **Partial coverage:** one provider un-hydratable ⇒ others fill, nothing drops,
  baseline doesn't go destructively stale; next cycle the lagging provider fills
  without a phantom deletion.
- **Idempotency (sync×2):** no-op sync changes nothing (no re-push, no key churn).
- **Identity drift:** same song with different recording MBID + remaster-suffix
  norm across two copies ⇒ treated as one track, not dropped/duplicated.

## What's already built this session (keep; fits the redesign)

All committed, all behind the OFF real-writes flag:
- Per-provider **coverage guard** + non-persisting hydration (`b7dcf1d`).
- **ISRC → MusicBrainz `/isrc/` → fuzzy-MB** fallback for LB hydration; ISRC
  capture (Spotify) + transient backfill onto merged tracks (`b7dcf1d`).
- **Empty-mirror = fill-target** rule (`b7dcf1d`) — the seed of Option B.
- **Total-wipe-only** mass-change (`b7dcf1d`) — per product decision "propagate
  all non-empty changes."
- **Apple Music catalog ISRC** hydration (exact), iTunes content-type fix kept as
  fallback (`1f6cadd`).
- Dev surface: per-playlist "Push this", "Reset state", dry-run/propagation
  readout, diagnostics.

## Out of scope / open questions for the design review
- Persisting ISRC: new `playlist_track.isrc` column (schema migration) vs.
  encoding it in the baseline TrackKeys only. Cross-platform (iOS) implications.
- Desktop parity: the same redesign must port to desktop `app.js` before
  fleet-wide enablement (mixed-fleet oscillation).
- Norm normalization rules: exact parenthetical/suffix list; fixture vectors.
- Recovery of already-corrupted playlists (e.g. `LB Android test`, now 3 tracks)
  — re-import from the richest surviving copy.
