# N-Way Phase 4 — Propagation Executor (Implementation Plan)

> **For the agent picking this up:** This is the FINAL phase of the N-way
> multimaster playlist sync engine. It turns the already-computed merge into
> REAL provider writes (Spotify / Apple Music / ListenBrainz). It is the single
> most dangerous code in the engine — a subtle bug is a bad write to the user's
> real account, or an **echo loop** (re-pushing every sync forever). Build it
> test-first. Do NOT enable real writes until the echo-loop test is green and
> the user has validated on-device behind the flag.
>
> **Read first:** `docs/plans/2026-06-21-nway-multimaster-playlist-sync-design.md`
> (the overall design; reconciliation steps 7–8 are this phase) and the
> "N-Way / Spinoff / sync" sections of `CLAUDE.md`.

**Goal:** When `NWAY_PROPAGATE` is on, for each migrated playlist, push the merged
tracklist to every WRITABLE copy that lags it, capture each provider's post-push
token (echo suppression), and advance the baseline — so concurrent edits across
Spotify/AM/LB converge instead of clobbering.

**Tech:** Kotlin Multiplatform (`shared/commonMain`). Pure logic is done; this is
the I/O executor + its tests.

---

## What already exists (do NOT rebuild)

All in `shared/src/commonMain/kotlin/com/parachord/shared/sync/`:

- **Pure merge** — `PlaylistMerge.merge(baseline, copies)` (delete-wins, order-LWW). Fixtures: `docs/nway-playlist-merge-fixtures.json`. **Do not touch.**
- **Key unification** — `unifyTrackKeys(lists): List<List<String>>` in `NwayKeyUnify.kt`. Takes `[baselineTrackKeys] + each copy's TrackKeys`, returns each list rewritten to representative keys. `TrackKeys{isrc,mbid,norm}` + `trackKeysOf(...)` + `nwayBaselineTrackKeys(tracks)`. Fixtures: `docs/nway-key-unify-fixtures.json`. **Do not touch.**
- **Pure propagation plan** — `computeNwayPropagationPlan(baseline, copies, writableById, massChangeThreshold): NwayPropagationPlan?` in `NwayShadow.kt`. Returns `{merged, pushTargets, massChangeAbort}`. `pushTargets` is already filtered to writable copies. Tested in `NwayShadowTest`. **This is what the executor calls.**
- **Flag** — `SettingsStore.isNwayPropagateEnabled()` / `setNwayPropagateEnabled()` / `nwayPropagateFlow` + `SyncSettingsProvider.isNwayPropagateEnabled()`. Default OFF. Requires `isNwayEnabled()` too.
- **State tables/DAOs** — `SyncPlaylistBaselineDao` (`Baseline.tracks: List<TrackKeys>` + `upsert`), `SyncPlaylistNwayDao` (`changeToken`, `editedAt`, `lastSyncedAt` per `(localId, providerId)` + `upsert`).
- **Shadow reconcile (the template to mirror)** — `SyncEngine.runNwayShadowScan()` + `shadowReconcilePlaylist(baseline, remoteLists)`. It already does: cheap per-provider list fetch, change-detection (snapshot else trackCount), fetch-only-changed, gather copies as `ShadowCopyInput`, unify, compute plan. **Copy this structure**; the executor adds tracks + the push/capture.
- **Push helpers (reuse, don't reinvent)**:
  - `extractExternalTrackIds(tracks, providerId): List<String>` — maps `PlaylistTrack` → that provider's IDs (Spotify URI / AM id / LB recording MBID).
  - `hydrateMissingTrackIds(playlistId, tracks, provider): List<PlaylistTrack>` — resolves missing provider IDs via catalog search **and persists them back into `playlistId`'s `playlist_track` rows**. ⚠️ side-effecting (see Trap 2).
  - `provider.replacePlaylistTracks(externalId, ids): String?` — pushes; **returns the new snapshot/token** (null for snapshot-less providers like LB).
  - `playlistTrackDao.replaceAll(playlistId, tracks)` — atomic delete+insert.
  - `playlistDao.clearLocallyModified(id)`.

---

## The four correctness traps (the actual spec)

### Trap 1 — key → track resolution
The merge yields representative *keys*; pushing needs *tracks*. Build
`keyToTrack: Map<reprKey, PlaylistTrack>` from the copies that have real tracks
(local + each CHANGED provider copy — unchanged copies aren't fetched). For copy
at unify-index `i`, `copy.tracks[j]` maps to `unified[i+1][j]`. Prefer the FIRST
occurrence (put local first so its richer metadata wins): `if (key !in map) map[key]=track`.
Then `mergedTracks = plan.merged.mapNotNull { keyToTrack[it] }`.
**If `mergedTracks.size != plan.merged.size`, ABORT this playlist** (a merged key
couldn't resolve to a track — never push a partial list). Log it.

### Trap 2 — hydration side-effects
`hydrateMissingTrackIds(playlistId, tracks, provider)` writes resolved IDs back
into `playlistId`'s rows via `replaceAll`. For propagation you're pushing
`mergedTracks` (which may include tracks not currently in the local playlist).
**Order:** update the LOCAL copy to `mergedTracks` FIRST (if local is a push
target), THEN hydrate against `localId` for each provider push. Or hydrate
against an in-memory list without persisting — verify what `hydrateMissingTrackIds`
actually persists before wiring; if its persistence is wrong for this path, add a
non-persisting variant rather than corrupting rows.

### Trap 3 — baseline key consistency
After a successful propagation, `syncPlaylistBaselineDao.upsert(localId,
nwayBaselineTrackKeys(mergedTracks), now)`. The stored TrackKeys MUST re-derive
(via `unifyTrackKeys`) to the same representative keys next cycle, or detection
thinks the playlist changed again. `nwayBaselineTrackKeys(mergedTracks)` gives the
representative tracks' own TrackKeys — confirm with a test that `unify([baseline])`
== `plan.merged` after the write.

### Trap 4 — echo suppression (LOAD-BEARING, the #1 risk)
After pushing to a provider, capture the **post-push** token and store it:
`syncPlaylistNwayDao.upsert(localId, providerId, changeToken = newTokenFromReplace,
editedAt = now, now)`. For snapshot-less providers (LB), `replacePlaylistTracks`
returns null — store null, and detection falls back to trackCount, which now
matches the merged size → no re-detect. Unchanged copies keep their existing
(already-matching) token. **Next cycle every copy must detect as unchanged.**

---

## Tasks (TDD — write each test FIRST, watch it fail, then implement)

### Task 1: Echo-loop / sync×2 idempotency test (the gate)
**File:** `app/src/test/java/com/parachord/android/sync/` — extend the
`ListenBrainzPushShortCircuitTest` harness (it already has `RemoteModelingProvider`
+ `nwayDao`/`baselineDao` + an `nway` flag on `FakeSyncSettings`; add a `propagate`
flag the same way).

**Test:** enable nway + propagate. Seed a local playlist, sync (creates the LB
mirror + migrates baseline). Diverge one copy (e.g. edit local tracks +
`locallyModified`). Call `engine.runNwayPropagation()`:
- assert the provider got exactly ONE `replacePlaylistTracks` with the merged list;
- call `runNwayPropagation()` AGAIN → assert **zero** further `replaceCalls`
  (echo suppressed), baseline unchanged, tokens unchanged.

This is the hard guard. It must be green before real writes are allowed near a
real account.

### Task 2: `runNwayPropagation()` + `propagateReconcilePlaylist(baseline, remoteLists)`
**File:** `shared/.../sync/SyncEngine.kt` (next to the shadow methods).
- Gate: `if (!settingsStore.isNwayEnabled() || !settingsStore.isNwayPropagateEnabled()) return`.
- Mirror `runNwayShadowScan`'s per-provider list fetch + per-baseline loop with
  the same CancellationException-rethrow + partial-fetch try/catch.
- `propagateReconcilePlaylist`: gather copies WITH tracks (local always; changed
  providers fetched), unify, build `writableById` (`copy.id == sourcePrefix ?
  playlist.writable : true`; `"local"` → true; `sourcePrefix = localId.substringBefore('-')`),
  call `computeNwayPropagationPlan(..., MASS_REMOVAL_THRESHOLD_PERCENT)`. Honor
  `massChangeAbort` (log + return). Then Traps 1–4 in order: resolve mergedTracks
  (Trap 1), push to each `plan.pushTargets` (Trap 2 — `"local"` = `replaceAll`
  the local rows; a provider = hydrate + extract + `replacePlaylistTracks` +
  capture token Trap 4), then baseline upsert (Trap 3) + `clearLocallyModified`.

### Task 3: Trap-specific unit tests
Partial-resolution abort (Trap 1), baseline re-derives to merged (Trap 3),
mass-change abort path, and a multi-provider case (push to AM + LB, not back to a
non-writable Spotify source).

### Task 4: Dev trigger + on-device dry-run validation
Add a "Run propagation" button to the Android Settings dev section (next to "Run
shadow scan", `SettingsScreen.kt` GeneralTab + `SettingsViewModel`). FIRST ship a
**dry-run**: compute the plan + resolve mergedTracks + log "would push N to X",
but skip the actual `replacePlaylistTracks` (guard the push call on a second
sub-flag or a `dryRun` param). Have the user validate the readout on their real
library (the #269 dups are cleaned now, so it should be clean). Only after that
looks right, enable real writes.

### Task 5: Per-sync wiring (last)
Once on-demand propagation is validated, call `runNwayPropagation()` from
`syncPlaylists` (gated, after migration + shadow). Respect the Spotify rate-limit
gate + stagger (pushes go through the gated `SpotifyClient`, but a burst across
many playlists still needs care — cap/stagger like `HYDRATE_MAX_CONCURRENCY`).

---

## Guardrails / gotchas
- **commonMain rules** (CLAUDE.md): no `System.currentTimeMillis()` (use
  `currentTimeMillis()`), no JVM-only APIs, `MutableMap.putIfAbsent` may not exist
  on Native — use `if (k !in m) m[k]=v`. Build BOTH: `./gradlew
  :shared:compileDebugKotlinAndroid :shared:compileKotlinIosSimulatorArm64 :shared:testDebugUnitTest :app:testDebugUnitTest`.
- **Worktree?** No — this branch (`nway-phase2-migration`) is the main checkout.
  Build/install from the repo root.
- **Apple Music** degrades PUT→POST-append (can't remove tracks); a propagation
  push to AM that removes tracks won't fully apply. Document, don't fight it.
- **Desktop parity:** propagation is engine behavior, not a fixture change. The
  per-copy writability rule is already noted on desktop issue Parachord/parachord#911.
- **Don't regress #269:** followed playlists mirror OUT (writable=false only
  blocks pushing BACK to the source — that's exactly what `writableById` +
  `pushTargets` filtering does).

## Done when
Echo-loop test green; on-device dry-run reviewed; real writes validated behind
the flag on one playlist; then per-sync wired. Update `docs/plans/2026-06-21-nway-
multimaster-playlist-sync-design.md` rollout section + the tracking issue
(parachord-mobile#268) as phases complete.
