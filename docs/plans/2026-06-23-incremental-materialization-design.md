# Incremental-Diff Materialization — N-Way Playlist Sync Redesign

> **Status: DESIGN (validated via brainstorming 2026-06-23, no code yet).** Real
> provider writes stay default-OFF (`nway_propagate`) until the new materialization
> harness is green. This supersedes the *materialization* half of the N-way work;
> the *reconciliation* half (identity keys, 3-way merge) is kept and reused.
>
> **Read first:** `docs/plans/2026-06-22-nway-reconciliation-redesign.md` (the
> reconciliation correctness pass) and the "N-way" sections of `CLAUDE.md`.

---

## Why we kept getting stuck

Multiple sessions hardened the **merge/reconciliation** layer ("will the merge drop
a track nobody deleted?" — identity drift, stale baseline P1, catalog-gap phantom
deletion P1b, pending markers). That work is real and made the merge *safe*. But the
product requirements are about the **materialization** layer ("can we reliably
*write* the reconciled list to each provider?"), and that layer is where everything
actually breaks on a real device.

The root cause of every data-loss/non-convergence bug is the **same coupling**: the
baseline and the merge are tied to **per-provider materialization coverage**. "Can I
resolve enough native IDs to push?" leaks into "what is the canonical tracklist?"

Concretely, the write primitive is a **destructive replace-all** (`replacePlaylistTracks`
= wipe + rebuild from only the IDs we resolved this cycle). That forces full per-cycle
ID hydration, which:
- bursts one search per track → rate limits (iTunes 429 session kill-switch,
  MusicBrainz fuzzy 429, Spotify abuse window),
- is gated by a **>25% coverage SKIP** (`MASS_REMOVAL_THRESHOLD_PERCENT = 0.25`,
  `SyncEngine.kt:1571`) so a chronically-<75%-coverable playlist **never converges**
  (observed on-device: Guitarmageddon `3/6 → SKIP`, SiriusXMU `86/244 → SKIP`),
- and conflates "the playlist legitimately churned a lot" with "we couldn't resolve a
  lot of IDs this cycle" — both look identical to the SKIP guard, so #4's protection
  over-blocks #1.

The fix is not more merge cleverness. It is to change the write layer.

## Requirements (the four we must satisfy)

1. **Whole-playlist daily churn** propagates (e.g. an algorithmic-style playlist that
   replaces most of its tracks).
2. **Latest change to any copy** reaches all other copies the user can write to.
3. **Use native track IDs where we have them, fall back to metadata** — because that's
   how Parachord resolves anyway. (Honest reading: a provider *write* needs its native
   ID; the achievable version is "add what we can resolve, leave the rest visibly
   pending, never destroy what's already there.")
4. **Protect against massive unexpected change, without over-protecting** the UX above.

## Decisions locked (brainstorming 2026-06-23)

- **Convergence target: best-effort order.** Guarantee *membership*; apply order only
  as a cheap reorder where a provider supports it and is fully resolved; never block or
  destructively re-push for order alone.
- **Remove guard: total-wipe-only + trust upstream.** Only hard-block a canonical→empty
  collapse. Rely on the existing upstream guards (a copy that returned a partial/failed
  fetch, or an empty mirror, never drives removals) to catch glitches. A legit
  large-churn playlist flows freely.
- **Rollout: Android-first.** Identity keys are unchanged, so Android's incremental
  writes converge through the provider regardless of desktop. Ship Android-first; arm
  real-writes once its own harness is green. Mixed-fleet worst case self-heals toward
  *more* tracks, never data loss.
- **Surfacing:** incomplete coverage is shown ("N tracks not yet on {provider}"), not
  silently dropped.

## Verified per-provider write capabilities (2026-06-23, from the clients)

No provider is "wipe-only"; they differ on the **removal** axis.

| Provider | Add specific items | Remove specific items | Wholesale replace |
|---|---|---|---|
| **Spotify** | ✓ `POST .../tracks` (wired) | ✓ `DELETE .../tracks` by URI — API supports it, **not yet wired** | `PUT` (wired; the destructive path we stop using) |
| **ListenBrainz** | ✓ `POST /item/add` (wired) | ✓ `POST /item/delete` by **position** (`index`+`count`, wired) | **none** — LB has no replace endpoint |
| **Apple Music** | ✓ `POST` append (wired) | ✗ **impossible** — `PUT` 401s for most users; no playlist item-delete endpoint | `PUT` (401s → degrades to append) |

Two findings that *strengthen* the design:
- **ListenBrainz is incremental-native** — its current "delete-all + add-all" is a
  manual wipe synthesized from `deletePlaylistItems(0, fullCount)` + `addPlaylistItems`.
- **Apple Music is inherently non-destructive** — add-only; it literally cannot drop a
  track. Removals never propagate to AM under *any* model (already documented/accepted).

The only genuinely new client code is wiring Spotify's standard `DELETE /v1/playlists/{id}/tracks`.

---

## Section 1 — Decouple reconciliation from materialization

- **Reconcile layer (identity-only).** Compute the canonical tracklist from metadata
  identity (isrc→mbid→norm; `NwayKeyUnify.kt`, `PlaylistTrackKey.kt`), 3-way merge,
  delete-always-wins. The baseline tracks **identity only** and advances to canonical
  **every cycle**, regardless of whether any provider finished materializing.
  `PlaylistMerge` stays fixture-pinned and untouched.
- **Materialize layer (new, per-provider, non-destructive).** Instead of
  `replacePlaylistTracks(fullList)`, do an **incremental identity-diff**: fetch the
  provider's current remote, unify-and-diff against canonical *by identity*, emit
  `add` / `remove` (+ optional `reorder`) ops. An unresolvable add is **skipped this
  cycle, left pending** — the remote keeps everything else. A remove targets the track's
  *existing* remote ID, so it always works on providers that can remove.

**What this kills:** the >25% coverage SKIP (`SyncEngine.kt:1571`), the catalog-gap
pending markers (`NWAY_FILL_PENDING_ACTION`, Step 3), the "baseline advances only on full
coverage" rule, and the `persist=false` propagation oddity. A track absent from a
provider's catalog is a no-op add at the WRITE layer, never a phantom delete there.
Partial hydration stops being destructive, so playlists **converge incrementally** over
cycles as IDs backfill.

> **CORRECTION (Jun 23, after the `NwayMaterializeTest` gate caught a data-loss
> regression in the swap).** An earlier draft of this section claimed the decoupling
> "subsumes P1 and P1b structurally — they were artifacts of the coupling, not of the
> merge." **That was wrong, and the gate proved it.** Making the *write* non-destructive
> is necessary but NOT sufficient: the **merge (reconcile) layer runs UPSTREAM of the
> write** and still consumes each provider's fetched tracklist, so a track a provider
> couldn't *materialize* (pending) is indistinguishable, to the merge, from one the user
> *deleted* — `PlaylistMerge` delete-always-wins drops it (P1b reborn at the merge layer).
> So pending-awareness had to be **re-introduced at the merge layer** (a leaner,
> cache-driven version of what `NWAY_FILL_PENDING_ACTION` did): a CHANGED provider's
> merge view is augmented with the canonical keys it lacks *only where the hydration
> cache says they are pending* (absent / null `resolvedId` = pending; non-null
> `resolvedId` = confirmed-materialized, so its absence IS a genuine deletion). The
> materialize target is then recomputed from the *un-augmented* keys so the pending
> provider is still re-filled. The non-destructive executor and the merge augmentation
> are **two independent layers** that BOTH must hold the invariant — the executor protects
> the write, the augmentation protects the merge. See `SyncEngine.isProviderPendingForKey`
> + the augmentation in `propagateReconcilePlaylist`, and `NwayMaterializeTest`
> "incremental convergence" (the case that caught it). The lesson: the harness gate
> earned its keep — it caught what the design premise and two review rounds missed.

The identity work already done (ISRC persistence, norm strip — Steps 1/4) is **kept**:
it minimizes diff churn/dupes when the *same song* appears under drifted keys across
copies (the diff degrades that case from data-loss to, at worst, a transient dupe).

## Section 2 — Extensibility: capability-driven materialization

**Rule: the shared diff/merge/safety code never learns a provider's name** (extends the
existing CLAUDE.md rule "SyncEngine never branches on `provider.id`"). Adding
Tidal/YouTube Music/Deezer = implement the interface, declare capabilities, register in
Koin. Zero edits to merge, diff executor, guards, or identity keys.

1. **Extend `ProviderFeatures` with a write-capability surface** (small, orthogonal —
   the three real providers already span it):
   - `addItems`: universal.
   - `removeMode`: `ByNativeId` (Spotify) · `ByPosition` (ListenBrainz) · `Unsupported`
     (Apple Music) · `ReplaceOnly` (reserved for a future wipe-only provider — now just
     one enum value, not a special case).
   - `canReorder`: Spotify yes, others no.
2. **The provider owns its primitives** — the central `when(providerId)` in
   `extractExternalTrackIds` (`SyncEngine.kt:2774`) dies:
   - `fetchRemoteTracks(extId)` → tracks carrying *both* native ID and identity metadata
   - `nativeIdOf(track)` / `resolveNativeId(track)` → which column, and how to hydrate it
   - `addItems(ids)`, `removeItems(spec)` per `removeMode`, optional `reorder(...)`
3. **One shared executor** computes the identity diff once, then drives a **uniform
   fallback ladder** by capability: targeted remove if `ByNativeId`/`ByPosition`; if
   `ReplaceOnly`, replace-all *only when add-coverage is full*, else degrade to add-only;
   if `Unsupported`, skip removes and surface "N removals couldn't apply to {provider}."
   Adds + the non-destructive guarantee are identical for every provider.

The identity layer stays the **single cross-engine contract** — a new provider
contributes its native ID as one more resolution source but never touches the keys, so
desktop #911 parity is unaffected by adding providers.

## Section 3 — Persistent hydration backfill

Under the diff model, hydration only runs for **adds** — removes reuse the existing
remote ID. Volume collapses to "genuinely new tracks, once."

1. **Resolve-once-ever + negative cache.** Native IDs persist null-only (never
   overwritten). Add a negative cache keyed by **(identity key, providerId)** →
   `resolvedId? · lastAttemptAt · attempts`. Identity-keyed (not row-keyed), so a track
   resolved once serves every playlist holding it, and a failure backs off (7d→30d
   cooldown) instead of re-burning the limit each cycle (today: an unresolvable track is
   re-searched every sync).
2. **Two-tier resolution — prompt for edits, patient for imports.**
   - *Inline, budgeted:* during a sync cycle, resolve IDs for that cycle's adds under a
     **hard per-cycle lookup budget** honoring the shared `RateLimitGate`, stopping on
     the first 429. A few new tracks resolve immediately (fast propagation); a 244-track
     import resolves a slice and defers the rest.
   - *Background trickle:* a slow worker (the existing `CrossResolverEnrichment` pattern
     — UNMETERED, battery-not-low, 24h) mops up the tail, honoring cooldowns. The diff
     never blocks on resolution — it adds whatever's resolved so far; coverage climbs
     cycle over cycle.
3. **Time-bounded breakers, not session kill-switches.** Replace the iTunes
   "disable for the rest of the session" flag (`AppleMusicSyncProvider.kt:452`) with a
   ~5-minute cooldown (like `MusicKitCatalogLimiter`). One 429 pauses *that* provider's
   hydration briefly; never blocks other providers or the reconcile.

**Surfacing (the honest #3):** per playlist/provider, a quiet "N tracks not yet on
{provider}." Pending tracks self-heal via backfill; genuine catalog gaps stay visibly
pending instead of silently dropped.

## Section 4 — Test harness + rollout

**The harness gates real-writes (Android-first).** A 2–3 provider in-memory sim (extend
`NwayPartialCoverageTest`'s shape) with fake providers declaring different capabilities
and controllable hydration. `nway_propagate` stays default-OFF until every case is green:

- **Non-destructive partial coverage** — canonical with un-hydratable adds → existing
  remote untouched, only resolvable adds applied, rest pending. *Nothing dropped.*
  (replaces the coverage-SKIP's safety)
- **Incremental convergence** — unresolvable cycle 1, resolvable cycle 2 → added cycle 2,
  no destructive intermediate.
- **Add-heavy daily churn (#1)** — 80% replaced → adds flow, removes apply, converges.
- **Multi-master (#2)** — add on copy A + remove on copy B → both on every writable copy.
- **Real removal propagates** — diffs into a remove on Spotify/LB; **AM skips + surfaces**,
  no abort.
- **Capability dispatch (extensibility)** — fake providers with each `removeMode`
  (`ByNativeId | ByPosition | Unsupported | ReplaceOnly`) exercise the fallback ladder.
- **Total-wipe-only (#4)** — canonical→0 blocked; large non-empty drop allowed.
- **Idempotency ×2** — no-op cycle emits zero ops.
- **Identity-diff** — drifted-key same-song produces no spurious add+remove.
- **Negative-cache cooldown** — unresolvable track not re-searched within the window.

**Sequencing:**
1. Wire Spotify targeted `DELETE`; build capability `ProviderFeatures` + provider
   primitives (`fetchRemoteTracks`, `add/remove/reorder`, `nativeIdOf/resolveNativeId`).
2. Shared diff executor + fallback ladder; negative-cache + two-tier hydration +
   time-bounded breakers.
3. Harness green (TDD) → **then** delete the now-subsumed coverage-SKIP + Step 3
   pending-marker machinery (keep the safety net until its replacement is proven).
4. Coverage-surfacing UI; on-device validate (small mainstream playlist); arm real-writes.

**Desktop #911** unaffected — identity contract unchanged; desktop adopts the same
materialization on its own timeline; mixed-fleet self-heals toward more tracks.

## What is kept vs. removed

- **Kept:** identity keys + `unifyTrackKeys` (isrc/mbid/norm), `PlaylistMerge` (fixture-
  pinned), the baseline as the 3-way-merge ancestor, total-wipe-only floor, upstream
  partial-fetch / empty-mirror guards, ISRC persistence (Step 1), norm strip (Step 4).
- **Removed (once the diff harness is green):** the >25% per-provider coverage SKIP,
  `NWAY_FILL_PENDING_ACTION` markers (Step 3), "baseline advances only on full coverage,"
  `persist=false` propagation hydration, and the `when(providerId)` in
  `extractExternalTrackIds`.
- **Net:** less code, no per-provider deletion-source bookkeeping, and convergence that
  no longer depends on resolving every ID in one cycle.
