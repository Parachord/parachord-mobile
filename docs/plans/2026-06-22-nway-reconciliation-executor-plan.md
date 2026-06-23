# N-Way Reconciliation Redesign — Implementation Plan (cold-start)

> **For the agent picking this up:** This executes the redesign in
> `docs/plans/2026-06-22-nway-reconciliation-redesign.md` (read it first; read the
> incident + the safety invariant). Goal: make propagation **never drop a track
> that's present in / identity-matches a track in any copy.** Real-writes
> propagation stays **OFF** until the whole sequence + the no-false-drop harness
> is green. Build test-first. The dangerous code is `PlaylistMerge` (DO NOT
> change — fixture-pinned, cross-engine) and `propagateReconcilePlaylist`.
>
> **Cross-engine rule:** any change to `unifyTrackKeys` or `trackKeysOf` (keys /
> norm) changes the shared fixture contract (`docs/nway-key-unify-fixtures.json`)
> and MUST be ported to desktop `app.js` in lockstep (tracker
> Parachord/parachord#911). Steps that touch identity say so.

## The invariant (acceptance gate for every step)
> Removing a baseline track requires positive evidence a copy *deliberately*
> deleted it. Absence due to identity-mismatch, partial coverage, a stale
> ancestor, or a failed fetch is NOT removal.

---

## Step 0 — No-false-drop test harness FIRST (the gate)
**File:** new `app/src/test/java/com/parachord/android/sync/NwayReconciliationTest.kt`
(or extend `ListenBrainzPushShortCircuitTest`). Encode the incident + the redesign
doc's harness cases as failing tests BEFORE any fix:
- **add-only:** baseline 8, a copy adds 1 ⇒ merged 9, nothing dropped (the incident's expected result).
- **identity-drift no-drop:** same song in two copies with a DIFFERENT recording MBID + a remaster-suffix title ("Zombie" vs "Zombie - 2025 Remastered") but the SAME ISRC ⇒ treated as one track, NOT dropped.
- **partial-coverage no-drop:** one provider un-hydratable ⇒ others fill, nothing dropped, baseline not destructively stale.
- **real-removal still propagates:** a fully-fetched, identity-matched copy genuinely removes a track ⇒ removed everywhere.
- **idempotency sync×2:** no-op ⇒ no re-push, no key churn.

These are RED until Steps 1–3 land. They are the gate for re-enabling real-writes.

## Step 1 — Persist ISRC (activates the dormant `isrc` unify tier)
`TrackKeys` ALREADY has an `isrc` tier and `unifyTrackKeys` ALREADY unions on it —
but the playlist path always passes `isrc = null`, so it's dormant. Persisting +
populating ISRC makes same-ISRC tracks unify across services regardless of MBID /
title drift — directly killing the incident.

1. **Schema:** add `trackIsrc TEXT` to `playlist_tracks` (`PlaylistTrack.sq`), add
   it to the `insert` column list + `VALUES`, and to `backfillResolverIds`
   (COALESCE). SQLDelight regenerates the typed query — update call sites.
2. **Existing installs:** add `ALTER TABLE playlist_tracks ADD COLUMN trackIsrc TEXT`
   wrapped in try/catch in `AndroidModule.kt` (same pattern as the `sourceUrl` /
   `sync_playlist_link` bootstraps — duplicate-column error on 2nd launch is
   expected + swallowed). iOS: same DDL at its DB bind.
3. **DAO** (`PlaylistTrackDao`): `toPlaylistTrack` reads `trackIsrc`; `insertAll` /
   `replaceAll` write `track.trackIsrc`. (The model field already exists.)
4. **Capture sites** already set `trackIsrc` in-memory (Spotify `fetchPlaylistTracks`);
   now it persists. Add capture where cheap: AM catalog (the songs already carry
   isrc), local-file scan (ID3 TSRC — `tracks.isrc` already exists, surface to
   playlist rows when added). Run each through `validateIsrc`.
5. **Keys:** `nwayBaselineTrackKeys` — pass `isrc = it.trackIsrc` (not null). The
   propagation + shadow copy-gather (`trackKeysOf(isrc = …)`) — pass the fetched
   track's isrc. The baseline `TrackKeys` already serializes `isrc`, so the
   ancestor becomes ISRC-keyed.
6. **CROSS-ENGINE:** add ISRC-bridging fixtures to `docs/nway-key-unify-fixtures.json`;
   port to desktop. (The tier logic is unchanged — only that the playlist path now
   supplies isrc — so existing fixtures still pass; add new ones for the bridge.)

**Gate:** the identity-drift-no-drop test (Step 0) goes green for the same-ISRC case.

## Step 2 — Confidence-gated union-remove — DEFERRED / FOLDED INTO STEP 3
**Decision (Jun 22): removal policy = DELETE-ALWAYS-WINS** (preserve the
desktop-pinned contract — a delete on any service propagates everywhere). Under
that policy, with the identity fixes (Steps 1 + 4) in, a *separate* confidence
gate adds little and is not cleanly implementable:
- The false-drop root cause was *misidentified absence* (drift), which Steps 1+4
  fix — the merge now recognizes the same song across services, so it only removes
  when a fully-fetched copy genuinely lacks a confidently-matched track (= correct
  delete-always-wins).
- A norm-only baseline track that the merge drops **can't be re-added** anyway: we
  hold only its `TrackKeys` (no full track data), and if any copy still had it
  under a matching key it wouldn't have dropped. So "keep it" is impossible, and
  the additive-bias reading was rejected (it would change the pinned policy).
- The one piece worth keeping — *don't let a not-fully-fetched copy contribute a
  removal* — is already true (unchanged/empty/failed copies are `changed=false`
  and never feed the merge's removal set) and is reinforced by **Step 3**.

So Step 2 is **folded into Step 3** (pending-push markers) rather than built as its
own gate. Revisit only if delete-always-wins is later changed to additive-bias.

## Step 3 — Pending-push markers (baseline stability under partial coverage) ✅ DONE
Generalize the empty-mirror rule: when a provider is skipped (coverage guard) or
can't be fully covered, mark it "owes a push" (reuse `sync_playlist_link.pendingAction`
or `sync_playlist_nway`) and EXCLUDE it from the merge's removal computation until
caught up — it's a fill target, never a deletion source. Advance the baseline to the
merged result of the COVERED copies (so the ancestor stops going destructively
stale), while the pending provider retries each cycle without poisoning identity.

**Gate:** sync×2 idempotency with a permanently-uncoverable provider ⇒ no churn,
no false drop, baseline stable.

**Landed (`SyncEngine.propagateReconcilePlaylist`, commits Step 3-full + guard):**
- New marker `SyncEngine.NWAY_FILL_PENDING_ACTION = "nway-fill"` on
  `sync_playlist_link.pendingAction` (was dormant — no production writer — so the
  reuse is collision-free; the legacy canonical-source push already skips a pending
  link, which is correct for a fill target since propagation owns the fill).
- **Push side:** after hydration, a provider that covers every merged track
  CLEARS its marker; one that under-covers — a SKIP (drop > mass-change threshold)
  or a genuine CATALOG GAP (pushed N-1, below threshold) — SETS the marker. A
  catalog gap no longer pins the baseline (`allCovered` stays true); only a
  near-total miss does.
- **Gather side:** a provider carrying the marker is added to the merge as
  `changed=false` with empty keys — EXCLUDED from the removal computation (its
  absent tracks can't read as deletions) yet still a push target (empty ≠ merged)
  so propagation keeps re-attempting the fill. Generalizes the empty-mirror rule.
- **Baseline:** advances to the covered merge (N) on a catalog-gap cycle without
  stranding the under-coverer as a future deleter — the residual `NwayPartialCoverageTest`
  pinned. Decision: Option B from the redesign (advance-to-covered + pending
  exclusion), NOT a held-back/intersection baseline — gives clean sync×2
  idempotency (the next cycle has no changed copy → plan is null → no-op).
- **Tests (`NwayPartialCoverageTest`, both green):**
  - `partial coverage never drops a track no copy deleted` — flipped the old
    "TODAY: baseline advances past AM's catalog gap" residual to: AM marked a
    fill target + a sync×2 cycle that drops nothing, no churn, baseline stable.
  - `genuine removal still propagates while another provider is a pending fill
    target` — the invariant's second half (pending-exclusion must not suppress a
    legit removal another copy drives).

## Step 4 — Stronger shared norm normalization (no-ISRC tail) — CROSS-ENGINE
Pure, fixture-pinned helper: before computing `norm`, strip version/remaster
parentheticals + ` - … Remaster(ed)` / ` - Single` / ` - Live` / `(feat. …)`
suffixes so "Zombie" and "Zombie - 2025 Remastered" share a norm. Gate against
over-collapse (don't merge genuinely different tracks). Author the exact rule +
fixtures ONCE; port to desktop `app.js` in lockstep.

**Gate:** identity-drift-no-drop test passes even WITHOUT an ISRC.

## Step 5 — Re-enable real-writes + desktop parity — GATE MET, enablement pending
Only after Steps 0–4 green: flip real-writes back on behind the flag, re-validate
on-device (the LB Android test scenario: add 1 ⇒ 9 everywhere). Then desktop ports
the redesigned reconciliation (#911) and enablement gates on "all clients support
N-way."

**Steps 0–4 are green** — the no-false-drop harness passes in full (38 N-way tests,
0 failures, 0 skips): `NwayPartialCoverageTest` (2), `ListenBrainzPushShortCircuitTest`
(13 — add-only/identity-drift incident regression, idempotency sync×2, empty-mirror
fill, total-wipe abort, large-drop propagation, coverage-abort), `NwayShadowTest`
(10), `NwayKeyUnifyTest` (13 — cross-engine fixtures). So the **code + harness gate
for arming real-writes is met.**

**There is NO code-level kill-switch.** Real-writes is purely the user toggle
`isNwayPropagateEnabled()` (KvStore key `nway_propagate`, **default `false`**),
surfaced as Settings → Developer → "Enable real writes". The engine path
(`runNwayPropagation` → `propagateReconcilePlaylist`) is fully wired and gated only
on that toggle. So "re-enable behind the flag" = the user flips the toggle on a
device to validate; **nothing in code is flipped.** The default STAYS `false`.

**Do NOT change the default to ON.** Fleet-wide enablement gates on "all clients
support N-way" — desktop has NOT ported #911 yet, so a mixed fleet (Android writing,
desktop on the old reconciliation) would oscillate playlists. Real-writes default-ON
is a separate, deliberate, outward-facing flip made only after desktop parity lands.

### Remaining (human / other-repo) — not codeable in `parachord-android`:
1. **On-device re-validation.** On a debug build: Settings → Developer → enable
   N-way, enable real writes; reproduce the incident scenario (a playlist mirrored
   to Spotify + Apple Music + ListenBrainz; add 1 track on one service) and confirm
   **add ⇒ N+1 everywhere, zero drops**, then sync×2 confirms no churn. Use the
   per-playlist "Dry-run" first to preview the merge before arming the write.
2. **Desktop port (#911).** Port the **reconciliation-layer** logic to desktop
   `app.js` — this is NOT a key/norm/fixture change (Step 3 never touches
   `unifyTrackKeys`/`trackKeysOf`/norm), so `docs/nway-key-unify-fixtures.json` is
   unaffected. The desktop port = the catalog-gap-aware baseline + fill-marker:
   - A per-(playlist, provider) "owes a push" marker (desktop's `sync_playlist_links`
     entry / equivalent) set when a provider under-covers the merged list (skip OR
     catalog gap = pushed N-1), cleared on full coverage.
   - A marked provider is EXCLUDED from the merge's removal computation (fill
     target, never a deletion source) but stays a push target.
   - The baseline advances to the COVERED merge (not pinned) on a catalog-gap
     cycle; only a near-total miss pins it.
3. **Trackers.** Update Parachord/parachord#911 (desktop parity) and
   parachord-mobile#268 with the Step 3 completion + this gate status.

## Guardrails
- commonMain rules (no `System.currentTimeMillis`, etc. — see CLAUDE.md).
- Build BOTH: `./gradlew :shared:compileDebugKotlinAndroid :shared:compileKotlinIosSimulatorArm64 :shared:testDebugUnitTest :app:testDebugUnitTest`.
- Never change `PlaylistMerge` (fixture-pinned). Enforce the invariant in the
  reconciliation layer.
- Recovery: `LB Android test` (now 3 tracks) — its `sync_playlist_baseline` row
  still holds the original 6 track identities; extract to recover if desired.

## Done when
Step 0 harness green; real-writes validated on-device (add ⇒ N+1 everywhere, no
drops); desktop parity ported; trackers #911 / parachord-mobile#268 updated.

**Status (Step 3-full landed):**
- ✅ Step 0 harness green (38 N-way tests, 0 fail/0 skip; both platforms compile).
- ✅ Steps 1, 4, 0, 3 (full) implemented — the catalog-gap phantom deletion (P1b)
  is fixed in the reconciliation layer; real-writes default stays OFF.
- ⏳ On-device re-validation — pending (human; debug toggle).
- ⏳ Desktop #911 port — pending (other repo; reconciliation-layer logic above).
- ⏳ Trackers #911 / parachord-mobile#268 — pending update.
