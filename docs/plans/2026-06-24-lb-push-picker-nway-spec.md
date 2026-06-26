# Implementation Spec — N-way-Aware ListenBrainz PUSH Picker (#266)

## 0. Problem statement & root cause

The LB push picker (`ProviderSyncConfigSheet` → `PushPlaylistPicker`) seeds its "checked" set from `getPlaylistSelection("listenbrainz")` (mode + `localPlaylistIds`), and deselect/select only writes `setPlaylistSelection`. But the user's *actual* LB sync is maintained by 173 `sync_playlist_link` rows for `"listenbrainz"`, which the picker never reads. On the user's device the selection pref is the corrupt/vestigial `mode=SELECTED, ids=∅` → the picker shows **zero checked** despite 173 live mirrors. Worse, `setPlaylistSelection` does **not** tear down a mirror — deselect is a no-op against the link table.

**Design principle:** make the LB picker *link-aware*, mirroring the proven PULL-side pattern (`applyPullSelection` → `detachPlaylistFromProvider` + keep/remove prompt). Seed from truth (links), and on save mutate the link/channel state so the next sync cycle does the right thing. This is the "fresh design" the iOS reader confirms (iOS has the identical gap and will port this later).

**Standing constraint (from MEMORY):** N-way real-writes propagation is toggle-OFF pending desktop #911. On this branch the **legacy `pushPlaylistsForProvider` is the sole maintainer** of LB mirrors. The spec must therefore work correctly with N-way OFF, *and* not break the N-way path when it eventually turns on. Both goals are satisfied by the same state: the durable `sync_playlist_link` row (read by legacy push's dedup AND by N-way's `getPlaylistMirrors`) and the `channels` override (authoritative on both paths).

---

## 1. `openConfig` push-load — computing the LB checked set

### Source of truth for "checked": the LB link table, not the selection pref.

The LB-linked local ids come from `sync_playlist_link` filtered to `providerId == "listenbrainz"`. There is **no `selectForProvider` query today** (`SyncPlaylistLink.sq` has `selectAll`, `selectForLocal`, `selectForLink`, `selectByExternalId`, `selectPendingForProvider`, `countOtherMirrors`). Add a query + DAO method (§6).

### Orphan handling (the 173-vs-~130 drift).

~43 LB links point at `localPlaylistId`s that no longer exist in `playlists`. The pushable list is `playlistDao.getAllSync()` (playlists table only — no join), so orphans are structurally invisible to the UI. **Intersect the LB-linked ids with existing playlist rows** to drop orphans from the checked set. Orphan link cleanup is a separate workstream (out of scope) — do **not** prune them here.

### The checked set = links ∩ existing playlists, with the selection pref ignored for seeding.

The corrupt selection pref (`SELECTED, ∅`) is exactly why we must NOT seed from it. We do **not** union with the selection ids (they're vestigial/corrupt). We do **not** need to consult the channel override for seeding either — a live `sync_playlist_link[*]["listenbrainz"]` row is the ground truth that LB is currently mirrored; if a channel override excluded LB the legacy push would not have created/maintained that link. (Edge: a desktop/iOS client could write a channels override that excludes LB while the link still lingers; that is a transient inconsistency the save path will reconcile, and seeding from links is still the correct "what is actually mirrored right now" answer.)

### Concrete load logic (replaces the push portion of `openConfig`, L494–503):

```kotlin
val ax = settingsStore.getSyncCollectionsForProvider(providerId)
val all = playlistDao.getAllSync().filter { it.name.isNotBlank() }

if (isPushProvider(providerId)) {   // listenbrainz
    val existingIds = all.map { it.id }.toSet()
    // Seed checked from ACTUAL mirrors (links ∩ existing playlists → drop orphans).
    val linkedIds = syncPlaylistLinkDao.selectForProvider(providerId)   // NEW DAO method, §6
        .map { it.localPlaylistId }
        .filter { it in existingIds }
        .toSet()
    // The displayed list = push candidates ∪ currently-linked rows
    // (a linked row that's no longer a push candidate must still appear so it can be UNchecked).
    val pushable = all
        .filter { isPlaylistPushCandidate(it, providerId) || it.id in linkedIds }
        .map { ConfigPlaylist(it.id, it.name, it.trackCount) }

    _configPushChecked.value = linkedIds          // NEW flow (see §4)
    configOriginalPushChecked = linkedIds         // snapshot for diffing on Done
    _configPushable.value = pushable
}
_configAxes.value = ax
configOriginalAxes = ax
// ... pull branch unchanged ...
```

**Do not** set `_configPlaylistMode` / `_configSelectedPushIds` from the selection pref anymore (those flows are retired — §4).

---

## 2. The pushable LIST — what to display

Display rows = **`isPlaylistPushCandidate(it, "listenbrainz") || it.id ∈ linkedIds`** over `playlistDao.getAllSync()` (existing rows only).

- `isPlaylistPushCandidate` for LB (`SyncSettings.kt:47–49`) = `base || spotify-* || applemusic-*`, `base = local-* || sourceUrl != null`. This is the set the user *can* opt into.
- The `|| it.id ∈ linkedIds` union guarantees **every currently-mirrored playlist is present and checkable**, even in the rare case a row stopped being a candidate (e.g. a `spotify-`-imported row whose eligibility predicate later changed). Without the union, such a row would have a live LB mirror the user could never uncheck.
- Orphan links contribute nothing to the list (their `localPlaylistId` isn't in `getAllSync()`), by design.

---

## 3. Save/Done semantics

The picker becomes a **plain checklist** (§4). On **Done**, diff `_configPushChecked` against `configOriginalPushChecked`:

- `deselected = configOriginalPushChecked − _configPushChecked`  (was linked, now unchecked)
- `newlyChecked = _configPushChecked − configOriginalPushChecked` (not linked, now checked)

Unlike the current push path (which persists live on every toggle), **persist on Done** so a deselect can raise a confirmation dialog first (mirrors the pull keep/remove flow). Toggling only mutates `_configPushChecked` in memory.

### 3a. DESELECTED (was linked → now unchecked): local-only LB unlink

This is the load-bearing new behavior. It must (i) remove the playlist from the legacy push candidate maintenance, (ii) durably stop the link from being re-created next cycle, (iii) be safe if/when N-way turns on, (iv) **never** delete the remote LB playlist, (v) **never** touch the baseline.

Per Reader A/B, the **complete, durable local unlink** is the four-step sequence below — `detachPlaylistFromProvider` alone has two gaps (no nway-row cleanup, no channels override; and it does not survive the id-prefix re-add for `listenbrainz-*` rows). Add a new SyncEngine method that bundles them (§6) rather than mutating `detachPlaylistFromProvider` (which the pull "keep" path relies on with its current narrower behavior):

```kotlin
// SyncEngine.kt — NEW
/**
 * Local-only "stop pushing this playlist to <providerId>" for a PUSH provider.
 * Removes the durable link + N-way echo-state, and writes an authoritative
 * channel override that EXCLUDES <providerId> so neither the legacy push loop
 * (candidates filter) nor N-way (getPlaylistMirrors filter) re-adds it next
 * cycle. NEVER deletes the remote playlist. NEVER touches the baseline
 * (shared 3-way-merge ancestor for the playlist's OTHER providers).
 */
suspend fun unlinkPlaylistFromProviderLocally(localPlaylistId: String, providerId: String) {
    // 1. Drop the durable push link → removed from getPlaylistMirrors' link source
    //    AND from the legacy three-layer dedup's Layer-1 reuse.
    syncPlaylistLinkDao.deleteForLink(localPlaylistId, providerId)
    // 2. Clear per-(playlist,provider) N-way change-token / editedAt echo state.
    syncPlaylistNwayDao.deleteForLink(localPlaylistId, providerId)
    // 3. Authoritative channel override that survives the next sync AND masks the
    //    id-prefix (listenbrainz-*) re-add. Allowlist semantics: enumerate the
    //    OTHER currently-mirrored providers and drop only <providerId>.
    val current = settingsStore.getPlaylistChannels(localPlaylistId)
        ?: getPlaylistMirrors(localPlaylistId).keys           // null → seed from live mirror set
    val next = current - providerId
    settingsStore.setPlaylistChannels(localPlaylistId, next)
    // NOTE: collection-axis sync_source + pull-source rows are NOT relevant for a
    // push provider's playlist mirror; do not delete them here. Baseline untouched.
}
```

**Why each piece, addressing Reader B's baseline-re-add risk:**

- **Baseline cannot re-add the mirror** (`sync_playlist_baseline` is per-*playlist*, a JSON array of track keys — the 3-way merge ancestor — with **no per-provider entries**). Targeting is decided exclusively by `getPlaylistMirrors` (prefix + pull-source + links + channel override), never the baseline. So we **leave the baseline alone**; deleting it would corrupt the merge ancestor for the row's Spotify/AM mirrors. The "baseline re-add risk" is a non-issue *provided* we don't rely on link-delete alone.
- **Link-delete alone is insufficient and fragile** for LB-imported rows: if `localPlaylistId` starts with `listenbrainz-`, `getPlaylistMirrors` re-adds `"listenbrainz"` from the id-prefix (`SyncEngine.kt:3512`). The **channel override (step 3) is the only state that masks the prefix** (`out.filterKeys { it in override }`, L3522). It is also the only state that survives the next *legacy* sync (the candidates filter checks `if (override != null) providerId in override else selection.includes(...)`, so an override that omits LB durably blocks re-push regardless of the selection pref).
- **Allowlist gotcha (Reader A open-question):** the channel override is an **allowlist** (`filterKeys` keeps only listed providers), so to exclude *only* LB you must enumerate every *other* currently-mirrored provider. **Safe choice:** seed `current` from `getPlaylistMirrors(localPlaylistId).keys` when no override exists yet (this is the exact live mirror set), then subtract LB. This guarantees Spotify/AM mirrors on the same row are preserved. Do **not** write an empty set unless LB was the only mirror (an empty override suppresses N-way for the whole playlist — acceptable when LB was the sole provider, harmful otherwise; the `current - providerId` computation handles this correctly: if LB was the only mirror, `next == ∅`, which correctly means "no providers", and the legacy push won't re-add LB).

This guarantees N-way won't re-add it next cycle (override filter drops LB from `getPlaylistMirrors` → `mirrors` → copy-gathering + materialize), and the legacy push won't re-add it (override authoritative in the candidates filter). **No remote LB delete** is issued.

### 3b. NEWLY-CHECKED (not linked → now checked): mark for LB push

Reader C proved the **cleanest single action that actually triggers create on the legacy (N-way-OFF) branch** is to admit the playlist into the push candidate set. There are two admission mechanisms; the channel override **wins when non-null**. Because deselect (3a) now writes channel overrides, we must be consistent and use the **same authoritative mechanism for select** — otherwise a playlist that was deselected (override excludes LB) then re-checked would have its override block the selection-pref opt-in.

**Therefore: newly-checked also writes the channel override**, adding LB to the playlist's channel set:

```kotlin
// SyncEngine.kt — NEW (or inline in VM via settingsStore)
suspend fun linkPlaylistToProviderLocally(localPlaylistId: String, providerId: String) {
    val current = settingsStore.getPlaylistChannels(localPlaylistId)
        ?: getPlaylistMirrors(localPlaylistId).keys
    settingsStore.setPlaylistChannels(localPlaylistId, current + providerId)
    // Do NOT create the link or push here — pushPlaylistsForProvider does that on
    // the next sync (createPlaylist → upsertWithSnapshot link → track push).
}
```

- This admits the row into `candidates` (`if (override != null) providerId in override` → true). The next sync's `pushPlaylistsForProvider` runs three-layer dedup; on a miss it calls `createPlaylist` + writes the `sync_playlist_link` row immediately (`upsertWithSnapshot`, `SyncEngine.kt:2889`) + hydrates + pushes tracks.
- **Empty-mirror guard caveat (Reader C):** a checked row whose tracks aren't hydrated yet (e.g. an `applemusic-`-imported row never viewed → 0 pushable ids) is *skipped* (no 0-track remote), and `hydrateMissingTrackIds` runs first. This is correct/expected behavior — the row stays checked, the mirror materializes on a later sync once IDs hydrate. No spec change needed; surface nothing to the user.
- **Consistency win:** using channel overrides for BOTH select and deselect means the picker writes one coherent piece of state per playlist (`channels`), authoritative on both legacy and N-way paths. The vestigial `ProviderPlaylistSelection` for LB is no longer touched by this picker.

**Alternative considered & rejected:** writing `setPlaylistSelection(SELECTED, ids + id)` for select while writing channel overrides for deselect. Rejected — mixing the two mechanisms means a re-checked-after-deselect playlist stays blocked by its LB-excluding override (override wins). One mechanism (channels) for both directions is correct.

### Triggering a sync after Done

After persisting, the picker should request a sync so changes materialize promptly (the create/teardown happen at sync time). Reuse the existing `syncNow` path the screen already exposes (the Done handler / sheet dismissal can call it, matching how other config changes settle on the next cycle). If no immediate-sync hook is wired in this sheet today, deselect/select still take effect on the next scheduled sync — acceptable, but prefer firing a sync for responsiveness.

---

## 4. Mode UI — replace All/Choose/None with a plain checklist

**Recommendation: replace the segmented control with a plain checklist for LB.** Justification:

- The `PlaylistSyncMode` (ALL/SELECTED/NONE) selection is now **vestigial for the actual sync** — neither N-way nor the new save path reads `getPlaylistSelection` once channel overrides drive admission. Keeping a mode control would present user-facing state that no longer governs behavior (the exact confusion #266 is fixing).
- A plain checklist matches the truth model: each row is "mirrored to LB: yes/no", seeded from the link table, mutated via channel overrides. This is symmetric with the PULL picker's plain checklist (`PullPlaylistPicker`), which is the proven link-aware pattern.
- It eliminates the ALL/SELECTED/NONE semantics that map poorly onto per-playlist link state. (ALL would mean "override-include LB on every candidate" — a 130-playlist bulk write that floods LB, the original incident class; NONE would mean "override-exclude LB on every linked row" — a bulk teardown. Neither belongs behind a one-tap segment.)

**If product insists on keeping the mode** (not recommended), redefine in link terms:
- **ALL** → on Done, channel-include LB for every displayed candidate not already linked (bulk select). Must gate behind an explicit confirmation given the LB-flood history.
- **NONE** → on Done, channel-exclude LB for every currently-linked row (bulk deselect → bulk local unlink). Confirmation required (data-affecting).
- **CHOOSE** → the per-row checklist as specced.
This is strictly more code and more footguns than the plain checklist. Prefer the checklist.

**UI change:** `PushPlaylistPicker` loses the `SingleChoiceSegmentedButtonRow`; it renders `ConfigSectionLabel("Playlists mirrored to $displayName")` + the `for (pl in pushable) PlaylistCheckRow(checked = pl.id in pushChecked, onToggle = { viewModel.toggleConfigPushChecked(pl.id) })` loop unconditionally (no `mode == SELECTED` guard). Empty list copy: `"No playlists eligible to mirror to $displayName yet."`

Retire flows `_configPlaylistMode` / `_configSelectedPushIds` and methods `setConfigPlaylistMode` / `toggleConfigPushSelected` / `persistConfigSelection`; replace with `_configPushChecked` / `toggleConfigPushChecked` and Done-time persistence.

---

## 5. Confirmation UX — deselect dialog

Mirror the pull keep/remove dialog (`ProviderSyncConfigSheet.kt:169–193`) but **single-action** (push has no "delete local copy" option — the local playlist always stays; only the LB mirror link is dropped). On Done, if `deselected.isNotEmpty()`, raise a confirm dialog; persist only on confirm.

- **Title:** `"Stop syncing $n playlist${plural} to $displayName?"`
- **Body:** `"These playlists will stop syncing to $displayName. They stay on $displayName (nothing is deleted there) and remain in your Parachord library — they're just removed from Parachord's sync to $displayName."`
- **Confirm button:** `"Stop syncing"` (not destructive-red — nothing is deleted; use neutral/accent).
- **Dismiss button:** `"Cancel"` (re-checks nothing; sheet stays open so the user can re-check).

This is the push analog of the pull "Keep local copy" semantics: local-only detach, remote untouched. **Newly-checked needs no confirmation** (additive, harmless — it just opts the playlist into LB push). Match the existing prompt-then-persist control flow: `configNeedsPrompt()` returns `true` to hold the sheet open while the dialog shows; the dialog's confirm calls the persist method then dismisses.

---

## 6. Files to touch + new methods

### `shared/src/commonMain/sqldelight/com/parachord/shared/db/SyncPlaylistLink.sq` — NEW query
```sql
selectForProvider:
SELECT * FROM sync_playlist_link WHERE providerId = ?;
```

### `shared/src/commonMain/kotlin/com/parachord/shared/db/dao/SyncPlaylistLinkDao.kt` — NEW method
```kotlin
suspend fun selectForProvider(providerId: String): List<Link> =
    withContext(Dispatchers.Default) {
        queries.selectForProvider(providerId).executeAsList().map { it.toLink() }
    }
```

### `shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncEngine.kt` — NEW methods
```kotlin
suspend fun unlinkPlaylistFromProviderLocally(localPlaylistId: String, providerId: String)
suspend fun linkPlaylistToProviderLocally(localPlaylistId: String, providerId: String)
```
(Bodies in §3a / §3b. Do **not** modify `detachPlaylistFromProvider` — the pull "keep" path depends on its current narrower behavior.)

### `app/src/main/java/com/parachord/android/ui/screens/sync/SyncViewModel.kt`
- **Add helper:** `fun isPushProvider(providerId: String): Boolean = !isPullProvider(providerId)` (or `== ListenBrainzSyncProvider.PROVIDER_ID`).
- **New flows:**
  ```kotlin
  private val _configPushChecked = MutableStateFlow<Set<String>>(emptySet())
  val configPushChecked: StateFlow<Set<String>> = _configPushChecked
  private var configOriginalPushChecked: Set<String> = emptySet()
  private val _pendingPushRemoval = MutableStateFlow<PushRemovalPrompt?>(null)
  val pendingPushRemoval: StateFlow<PushRemovalPrompt?> = _pendingPushRemoval
  data class PushRemovalPrompt(val names: List<String>)
  ```
- **Retire:** `_configPlaylistMode`, `_configSelectedPushIds`, `setConfigPlaylistMode`, `toggleConfigPushSelected`, `persistConfigSelection`.
- **`openConfig`:** replace push portion per §1 (seed `_configPushChecked` + `configOriginalPushChecked` from `selectForProvider("listenbrainz") ∩ existing`; build `_configPushable` per §2).
- **New toggle:** `fun toggleConfigPushChecked(localId: String)` — in-memory mutate `_configPushChecked` only (no persist).
- **`configNeedsPrompt`:** add a push branch (parallel to the pull branch at L591–599):
  ```kotlin
  if (isPushProvider(providerId)) {
      val deselected = _configPushable.value.filter {
          it.id in configOriginalPushChecked && it.id !in _configPushChecked.value
      }
      if (deselected.isNotEmpty()) {
          _pendingPushRemoval.value = PushRemovalPrompt(deselected.map { it.name })
          return true
      }
      applyPushSelection()   // nothing deselected → persist additions now
  }
  ```
- **New persist methods:**
  ```kotlin
  fun configConfirmPushStop(onDone: () -> Unit)   // dialog confirm → applyPushSelection(); dismiss
  fun configCancelPushRemoval()                    // _pendingPushRemoval = null (sheet stays open)
  private suspend fun applyPushSelection() {
      val providerId = _configProviderId.value ?: return
      val deselected = configOriginalPushChecked - _configPushChecked.value
      val newlyChecked = _configPushChecked.value - configOriginalPushChecked
      for (id in deselected)   syncEngine.unlinkPlaylistFromProviderLocally(id, providerId)
      for (id in newlyChecked) syncEngine.linkPlaylistToProviderLocally(id, providerId)
      configOriginalPushChecked = _configPushChecked.value   // idempotent re-open
      // optionally: trigger syncNow() so changes materialize promptly
  }
  ```
  (`configConfirmPushStop` wraps `applyPushSelection()` in `viewModelScope.launch { try { … } finally { _pendingPushRemoval.value = null; onDone() } }`, matching `configConfirmPullKeep`.)

### `app/src/main/java/com/parachord/android/ui/screens/sync/ProviderSyncConfigSheet.kt`
- **`PushPlaylistPicker`:** remove the segmented mode control; render the unconditional checklist over `configPushable`, `checked = pl.id in configPushChecked`, `onToggle = toggleConfigPushChecked` (§4).
- **Add the push-deselect dialog** (parallel to the pull dialog block at L168–193), bound to `pendingPushRemoval`, copy per §5, confirm → `configConfirmPushStop { dismiss() }`, dismiss → `configCancelPushRemoval()`.

### `shared/src/commonMain/kotlin/com/parachord/shared/di/…` (Koin) / `AndroidModule.kt`
- No new bindings needed: `SyncEngine`, `SettingsStore`, `SyncPlaylistLinkDao`, `SyncPlaylistNwayDao` are already injected into `SyncViewModel` / available to `SyncEngine`. Confirm `syncPlaylistNwayDao` is reachable inside `SyncEngine` (it is — `getPlaylistMirrors` and the N-way path use the link/source DAOs; add the nway DAO as a ctor dependency if not already present).

### iOS (out of scope, note for the follow-up)
`SettingsView.swift` `ProviderConfigModel` + `IosContainer` carry the identical gap; port the same shared `unlink/linkPlaylistToProviderLocally` + `selectForProvider` and rebuild the push picker as a link-seeded checklist. The shared SyncEngine/DAO methods land now; iOS wiring is a separate task.

---

## 7. Data-loss invariants (must all hold)

1. **Only mutate diffed playlists.** `applyPushSelection` touches exactly `deselected ∪ newlyChecked`. Unchanged checked rows and unchanged unchecked rows get zero writes.
2. **Never delete the remote LB playlist.** No call to `deletePlaylistOnProvider` / `provider.deletePlaylist` anywhere in this path. Deselect = local link teardown + channel override only. The LB playlist persists remotely (mirrors the pull "Keep local copy" semantics).
3. **Never strand or corrupt baselines.** Do **not** call `syncPlaylistBaselineDao.deleteForLocal`. The baseline is the shared 3-way merge ancestor for all of the row's providers; it has no per-provider LB entry, so it cannot re-add the mirror and must be left intact.
4. **Channel override is an allowlist — preserve other providers.** Seed `current` from the live mirror set (`getPlaylistChannels ?: getPlaylistMirrors().keys`) and subtract/add only LB. Never write a bare `setOf("listenbrainz")` or `emptySet()` that would silently drop Spotify/AM mirrors on that row.
5. **Idempotent.** After Done, `configOriginalPushChecked` is reset to the new checked set; re-opening `openConfig` re-derives the same checked set from `selectForProvider("listenbrainz") ∩ existing` (deselected rows no longer have a link; newly-checked rows have a link after the next sync's create — or are still pending, in which case the channel override keeps them admitted and they'll show as a candidate the user can re-confirm). No oscillation, no duplicate writes on re-save.
6. **Orphan links untouched.** The path operates only over rows in `playlists`; the ~43 orphan LB links are neither surfaced nor mutated. Their cleanup is a separate prune workstream.
7. **No accidental bulk writes.** No ALL/NONE bulk path exists (checklist-only), so a single tap can never flood or wipe LB across the whole library.

---

## 8. Test plan

### Unit-testable VM/engine logic (JVM, `app/src/test` + `shared` tests; remember `isReturnDefaultValues = true`)

**A. Seed-from-links (`openConfig`)**
- Given `sync_playlist_link` rows `{local-A→lb, local-B→lb}` and `playlists` containing A, B, C, D → assert `_configPushChecked == {local-A, local-B}` and `_configPushable` contains A, B (and any candidate C/D), regardless of `getPlaylistSelection` being `(SELECTED, ∅)`.
- **Orphan drop:** add LB link `local-Z→lb` with no `playlists` row for Z → assert `_configPushChecked` excludes `local-Z` and the list has no Z row.
- **Linked-but-not-candidate inclusion:** a linked row whose `isPlaylistPushCandidate` returns false still appears in `_configPushable` and is checked.

**B. Deselect → link removed (`unlinkPlaylistFromProviderLocally`)**
- Seed link `local-A→lb` (+ optional Spotify link `local-A→sp`) and an nway row for `(local-A, lb)`. Run unlink(`local-A`, `listenbrainz`).
- Assert: `selectForLink(local-A, "listenbrainz") == null`; `syncPlaylistNwayDao.selectForLink(local-A, "listenbrainz") == null`; `getPlaylistChannels(local-A)` is non-null and **excludes** `"listenbrainz"` but **includes** `"spotify"`; the Spotify link is intact; **baseline row for `local-A` is unchanged**; no remote-delete call was made (verify via mock provider — zero `deletePlaylist` invocations).
- **id-prefix masking:** for `localPlaylistId = "listenbrainz-XYZ"`, after unlink assert `getPlaylistMirrors("listenbrainz-XYZ")` does **not** contain `"listenbrainz"` (override filter masks the prefix).
- **Re-add suppression:** assert the legacy candidates filter rejects the row — `getPlaylistChannels(id)` non-null and `"listenbrainz" !in it`, so `pushPlaylistsForProvider` would exclude it.

**C. Newly-checked → marked (`linkPlaylistToProviderLocally`)**
- Seed a candidate `local-C` with no LB link, no override. Run link(`local-C`, `listenbrainz`).
- Assert `getPlaylistChannels(local-C)` includes `"listenbrainz"` (and includes pre-existing mirrors). Assert **no** link row was created yet (creation is deferred to `pushPlaylistsForProvider`). Optionally, with a fake provider, run a sync cycle and assert `createPlaylist` + `upsertWithSnapshot` fired and `selectForLink(local-C, "listenbrainz")` now exists.
- **De-then-re-check consistency:** deselect a linked row, then re-check it in the same session → assert final override **includes** LB (the re-check additively restores it; no leftover exclusion).

**D. `applyPushSelection` diffing**
- Original checked `{A, B}`, final `{B, C}` → assert exactly one unlink (`A`) and one link (`C`), nothing on `B`.
- Idempotency: call `applyPushSelection` twice with no further changes → second call is a no-op (diffs empty).

**E. `configNeedsPrompt` push branch**
- Deselecting a previously-linked row → returns `true` and populates `_pendingPushRemoval`. Only-additions (no deselects) → returns `false` and persists immediately.

### On-device steps (debug build; honor worktree `cd` + APK-mtime rules)

1. Install fresh; open Settings → Sync → ListenBrainz config sheet.
2. **Verify seed:** the checklist shows the user's actual LB-mirrored playlists **checked** (not zero) — confirming it reads links, not the corrupt `(SELECTED, ∅)` pref. Cross-check count against `sync_playlist_link WHERE providerId='listenbrainz'` ∩ playlists (expect ~130, not 173 — orphans excluded).
3. **Deselect one** → Done → confirm "Stop syncing" dialog appears with the right name/copy → confirm. Re-open the sheet → that playlist is now **unchecked** and stays unchecked (idempotent). On listenbrainz.org, the playlist **still exists** (no remote delete). Run a sync; re-open → still unchecked, link still absent (override blocks re-push — the durability test).
4. **Check a not-yet-mirrored candidate** → Done (no dialog) → run a sync → on LB the new playlist is **created**; re-open the sheet → it's checked and persists.
5. **LB-imported row (`listenbrainz-*`) deselect:** pick a `listenbrainz-`-prefixed row, deselect, sync twice → confirm it does **not** re-appear as mirrored (id-prefix masked by override) — the specific regression the channel override fixes.
6. **Multi-mirror safety:** pick a playlist mirrored to both Spotify and LB; deselect LB only → confirm the Spotify mirror/chip survives and Spotify sync is unaffected (allowlist-preservation test).
7. **Logcat:** add a one-time log string in `unlink/linkPlaylistToProviderLocally` (e.g. `"LBpush: unlink <id>"`) and grep for it to confirm fresh code deployed (per Rule 4 — DB/pref state alone is not a deployment signal).

---

**Open-question resolutions (safe choices made):**
- *Touch the baseline?* **No** — it's per-playlist, shared across providers, cannot re-add the mirror; touching it risks corrupting other mirrors' merge ancestor.
- *Allowlist vs. denylist override?* It's an allowlist — **seed from the live mirror set and subtract only LB** to preserve other providers.
- *Patch `detachPlaylistFromProvider` vs. new method?* **New method** (`unlinkPlaylistFromProviderLocally`) — `detachPlaylistFromProvider` is reused by the pull "keep" path and intentionally omits nway/channels; changing it in place would shift that path's behavior.
- *Selection pref vs. channel override for select?* **Channel override for both directions** — one authoritative mechanism on both legacy and N-way paths; avoids the "override-excludes-LB blocks a re-check via selection" inconsistency.