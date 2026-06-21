# N-Way Phase 4 Prerequisite — Canonical-Key Consistency

**Date:** 2026-06-21
**Status:** Proposed (decision pending)
**Scope:** shared `commonMain` (the cross-engine key/match contract) + desktop parity
**Blocks:** N-way propagation (Phase 4 writes). Phases 0–3 are done + shadow-validated.

## The problem (from real validation data)

Shadow mode on a real 148-playlist library surfaced that the **same track gets
different canonical keys across services**, producing false "removes" in the
merge. Concrete case — "Guitarmageddon '26" (15 tracks): merge wanted to drop
60% of the baseline (mass-change guard correctly aborted). Root cause is NOT the
merge logic; it's key matching:

1. **`norm-` vs `mbid-`.** 4 of its 15 local tracks are un-enriched → keyed
   `norm-artist|title`. ListenBrainz is MBID-native → keys the same songs
   `mbid-…`. They can never match → counted as removed.
2. **`mbid-` vs `mbid-` variance.** Recording MBID is per-*recording*: local
   enriched to recording A (e.g. a remaster) while LB holds recording B for the
   same song → mismatch.

Today's key is a single string with precedence `isrc > mbid > norm` and **exact
equality** ([PlaylistTrackKey.kt](../../shared/src/commonMain/kotlin/com/parachord/shared/sync/PlaylistTrackKey.kt)).
Both failure modes break it. The fix changes how tracks are *matched across
copies* — which the desktop JS engine must replicate identically, so it is a
**cross-engine contract change** (extends `docs/nway-playlist-merge-fixtures.json`).

## Options

### A. Multi-key identity + norm fallback (RECOMMENDED)
Each track carries `{isrc?, mbid?, norm}`. Two tracks across copies are the SAME
if they share ANY tier (isrc OR mbid OR norm). Handles both failure modes:
`norm-` bridges a un-enriched track to its MBID twin; `norm-` also bridges two
different recording-MBIDs of the same song.

- **Pro:** the only option that fixes both failure modes; superset of B and C.
- **Con:** most complex; the `norm-` bridge reintroduces a small same-title
  false-merge risk (live vs studio, two "Intro"s) — mitigated by requiring the
  norm bridge only when the stronger ids are *absent or already agree*, never to
  *override* a confident isrc/mbid disagreement.
- **Implementation that preserves the locked merge contract:** add a pure
  **key-unification pre-pass** `unifyCopyKeys(copies) -> copies'` that builds
  cross-copy equivalence classes and rewrites every copy's tracklist to a single
  canonical representative key per class. `PlaylistMerge` then runs UNCHANGED on
  the representatives — the delete-wins/order-LWW contract is untouched; only a
  new, separately-tested pre-pass is added to the fixture suite. Requires the
  baseline + provider tracklists to carry the full `{isrc,mbid,norm}` per track
  at reconciliation time (the live track data already has these; the baseline
  table may need to store key-sets rather than single strings — a Phase-1-style
  additive schema change).

### B. Enrich-then-key only
Run `MbidEnrichmentService` over local tracks so `norm-` → `mbid-`; keep
single-key exact match.
- **Pro:** simplest; no contract change.
- **Con:** does NOT fix `mbid-` variance — remasters/versions still diverge.
  Enrichment is async + best-effort, so coverage is never complete. Partial fix;
  shadow would still show false divergence on variance.

### C. Norm-primary keying
Key every track as `norm-artist|title` only; ignore isrc/mbid for matching.
- **Pro:** simplest contract; service-independent; kills all cross-service
  variance.
- **Con:** false-MERGES two different songs sharing artist+title (live vs studio,
  multiple "Intro"s). Trades false-removes for false-merges — arguably worse
  (a wrong-track in a playlist vs a missing one). Loses isrc/mbid precision.

## Recommendation

**A — multi-key + norm fallback, via the `unifyCopyKeys` pre-pass.** It is the
only complete fix, and the pre-pass design keeps the already-locked
`PlaylistMerge` (delete-wins, order-LWW) contract intact while isolating the new
matching logic into one pure, fixture-tested function. B is a useful *companion*
(enrich anyway to maximize id coverage) but insufficient alone; C is rejected
(false-merges).

## Rollout (incremental, reversible — mirrors Phases 0–3)

1. Pure `unifyCopyKeys` module + cross-engine fixtures (its own JSON, like the
   merge fixtures). Fully unit-tested before any wiring.
2. Carry `{isrc,mbid,norm}` per track to reconciliation (baseline key-set
   storage — additive schema, behind the flag).
3. Wire the pre-pass into shadow mode; re-validate on the real library (the
   Guitarmageddon 60% should collapse toward ~0% if it's truly a key artifact).
4. THEN enable propagation (post-push token capture, normalize, real `editedAt`).

## Desktop coordination

This extends the cross-engine contract. The `unifyCopyKeys` fixtures must be
authored once and run against both Kotlin and the desktop JS, same as the merge
fixtures. Flag enablement still gated on "all the user's clients support N-way".

## Open questions

- Baseline storage: store per-track key-sets vs re-derive at reconciliation.
- Exact norm-bridge rule (when norm is allowed to unify vs when a confident
  id-disagreement blocks it) — needs its own fixtures.
- Whether to run B (enrichment) eagerly at migration to shrink the `norm-`
  population before the first real propagation.
