# Option A — Pull-Source Authority in the N-way Merge: Precise Implementation Design

**Target:** `shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncEngine.kt`, method `propagateReconcilePlaylist` (the augmentation block at L1646-1657). **Branch:** `nway-phase2-migration`. **Lands behind the OFF `nway_propagate` toggle.**

---

## 1. Hypothesis: validated, with two mandatory refinements

The core hypothesis is **correct and the fix locus is exactly right** (the augmentation guard at L1648, not `PlaylistMerge`). But "skip augmenting the authoritative-source copy" as literally stated is **necessary but NOT sufficient**, and is **unsafe** for one source type. Two refinements are load-bearing:

### Refinement A — gate authority on the source being *removal-capable* (edge case 6 is a data-loss hazard otherwise)

If the authoritative source is **add-only** (`TrackRemoveMode.Unsupported` — Apple Music), granting it drop-authority is **wrong**: a key AM "lacks while pending" is an un-materialized add, not a deletion. AM's `fetchPlaylistTracks` can also transiently return a *partial* (non-empty) list — not caught by the EMPTY-MIRROR rule (L1576) or the TOTAL-WIPE floor (`merged.isEmpty()`, NwayShadow.kt:141). Skipping AM-source augmentation there would read those absences as deletions and **drop real tracks with no safety net**. Therefore:

> Source-authority is granted only when `authoritativeProvider.features.trackRemoveMode != TrackRemoveMode.Unsupported`. For an AM-sourced playlist, fall back to augment-all (status quo). This is a clean, existing capability flag (`SpotifySyncProvider` = `ByNativeId`, `ListenBrainzSyncProvider` = `ByPosition`, `AppleMusicSyncProvider` = `Unsupported`).

(Note: this *forgoes* propagating a user's in-AM-app track removal from an AM-sourced playlist. That's an acceptable, documented loss given AM's add-only asymmetry and the partial-fetch hazard — the alternative risks dropping live tracks. The Daily Brew / SiriusXMU win does not depend on it.)

### Refinement B — subtract the authoritative copy's deletions from *every* mirror's `pendingLacked` (the AM-residue closer)

Skipping augmentation on the **source copy alone** makes the source vote-remove, and union-remove (PlaylistMerge.kt:61) drops the key from canonical regardless of whether AM is augmented — **so for the Daily Brew (Spotify-source) vector, source-skip alone is sufficient.** Trace D's stronger formulation is still worth adopting because it is a strict superset that also makes the *intent* explicit and closes a subtle interaction: a key the authoritative source deleted must **never** be re-augmented onto *any* copy. Concretely, compute:

```
authoritativeDropped = baselineRepr − authoritativeCopy.keys   // only when source is removal-capable & changed
```

and subtract `authoritativeDropped` from each mirror's `pendingLacked`. This guarantees no mirror's augmentation can resurrect a source-deleted key, **and** it is what makes the hosted-XSPF case provably correct without special-casing (see edge case 5).

The minimal Daily-Brew-only fix is the one-disjunct guard; the **shipped** fix should be Refinement B (superset) gated by Refinement A, because it is the formulation that is correct across all six cases simultaneously.

---

### Daily Brew walkthrough under the proposed merge (yields canonical = 40)

Setup: baseline = 519 keys (40 live + 479 accumulated AM-residue). Spotify source has rotated to 40 live keys today (`changed=true`, fetched). AM mirror physically holds all ~519 (add-only, `changed=true`). `authoritativeProvider = spotify`, `trackRemoveMode = ByNativeId` → removal-capable → authority granted.

1. `authoritativeCopy` = the `spotify` copy, `keys` = 40 live. `authoritativeDropped = baselineRepr(519) − 40 = 479`.
2. **Augmentation skips the `spotify` copy** (it's the authoritative source). Spotify's view stays = 40 → it votes-remove all 479 via PlaylistMerge.kt:61.
3. **AM copy IS augmented** — but `pendingLacked` for AM has `authoritativeDropped` (479) subtracted out. AM only re-adds keys the source *still holds* that AM is pending on (catalog gaps within the live 40). AM does **not** re-add any of the 479.
4. Union-remove: every one of the 479 is lacked by `spotify` (un-augmented) → `removed` ⊇ 479. `present = baseline(519) − removed(479) + adds(0 new) = 40`.
5. `mergedRepr = 40`. `massChangeAbort`? `merged.isEmpty()`? No (40 ≠ 0) → not tripped. ✅
6. Baseline advances to 40 (L1818). Local is rewritten to 40 (the 519→40 local convergence). Push-targets recomputed from un-augmented copies: AM still physically holds the 479 → AM lags → AM is a push target → executor attempts removal → `TrackRemoveMode.Unsupported` → no-op (AM keeps its physical residue; that cannot be fixed app-side, see §6).

**Result: canonical = 40, local = 40, baseline = 40, re-push to other providers stops.** ✅

---

### Edge cases 1–6 — proofs

| # | Case | Verdict | Why |
|---|------|---------|-----|
| **1** | LOCAL-ADD-PENDING (X added locally, not pushed) | **SAFE** | The augmentation and `authoritativeDropped` only operate on `baselineRepr` keys (`lacked = baselineRepr.filter{…}`; `authoritativeDropped = baselineRepr − …`). X is **not** a baseline key → it's a union-ADD (PlaylistMerge.kt:60) carried by the `local` copy. Source-authority never touches it. Kept. |
| **2** | CATALOG-GAP on non-auth mirror (Spotify source HAS it, AM can't store) | **KEPT** | The key is **in** the source's keys → **not** in `authoritativeDropped`. AM is still augmented for it (it's in AM's `lacked ∩ pending`, and survives the `authoritativeDropped` subtraction). AM doesn't vote-remove it. Kept. |
| **3** | MULTIMASTER local-authored (`local-*`, no source row, `sourceUrl == null`) | **UNCHANGED** | `authoritativeProvider == null` and no XSPF → no `authoritativeDropped`, no source-skip → augment all non-local changed copies exactly as today. **This is what keeps `NwayMaterializeTest` cases 1 & 2 green** (they use `local-0`, no source row). |
| **4** | LOCAL REMOVAL still on source (user removes Y locally; Spotify still has Y) | **DROPS Y** | `local` is changed and lacks Y → union-remove fires from `local` (always; `local` is never augmented). Source-authority is purely *subtractive on the source's augmentation* — it never adds a re-add and never protects the source's keys from another copy's removal vote. Y is dropped. (Note: Y ∈ source.keys → not in `authoritativeDropped`, so nothing re-adds it.) |
| **5** | HOSTED XSPF (`sourceUrl != null`, local authoritative) | **DROPS** | Authoritative copy = `local`, which is *already* unconditionally exempt from augmentation (L1648) — so the XSPF-driven local removal already votes-remove. **Refinement B closes the residual:** `authoritativeDropped = baselineRepr − local.keys` (the XSPF-removed tracks) is subtracted from the **AM/Spotify mirror's** `pendingLacked`, so a changed streaming mirror cannot re-augment the XSPF-removed key. Without Refinement B, source-skip-alone does NOT cover hosted XSPF (the source copy is `local`, already skipped; the bug is a *mirror* re-adding). **This is the case the literal hypothesis misses.** |
| **6** | applemusic-IMPORTED, AM is source AND add-only | **AUTHORITY DECLINED** | `authoritativeProvider.features.trackRemoveMode == Unsupported` → Refinement A → augment-all (status quo). AM-source membership-removal does not propagate (acceptable, documented), and a transient partial AM fetch cannot drop live tracks. Safe. |

---

## 2. The exact code change

### 2a. Compute authority once, before the augmentation (insert after L1602, alongside `writableById`)

```kotlin
// ── PULL-SOURCE AUTHORITY (Daily Brew / SiriusXMU rotate-out fix) ───────────
// The authoritative PULL SOURCE has the authority to DROP a baseline track:
// when a churning source (Spotify Daily Brew rotates ~40/day; hosted-XSPF
// replaces daily) rotates a track OUT, that absence must read as a genuine
// deletion — NOT be re-added by the pending augmentation. Add-only mirrors
// (Apple Music) still keep genuine catalog-gap tracks because the augmentation
// stays ON for them.
//
//   • sync_playlist_source.providerId's copy is authoritative for an imported
//     playlist (use the source ROW, not the id-prefix — a cross-provider push
//     mirror's prefix can differ from the real pull source).
//   • the LOCAL copy is authoritative for a hosted-XSPF playlist (sourceUrl).
//   • NONE for a local-authored multimaster playlist → augment all (unchanged).
//
// Authority is granted ONLY when the source is REMOVAL-CAPABLE. An add-only
// source (TrackRemoveMode.Unsupported, e.g. an AM-imported playlist) cannot
// legitimately "drop" a track, and a transient PARTIAL fetch from it would
// otherwise read as deletions with no safety net (the empty-mirror rule and
// the total-wipe floor only catch collapse-to-zero) — so it falls back to
// augment-all.
val pullSource = syncPlaylistSourceDao.selectForLocal(localId)
val authoritativeCopyId: String? = when {
    pullSource != null -> {
        val sourceProvider = providers.firstOrNull { it.id == pullSource.providerId }
        if (sourceProvider != null &&
            sourceProvider.features.trackRemoveMode != TrackRemoveMode.Unsupported
        ) pullSource.providerId else null
    }
    playlist.sourceUrl != null -> "local"   // hosted XSPF — local copy is the truth
    else -> null                            // local-authored multimaster — augment all
}

// The set of baseline keys the AUTHORITATIVE copy itself dropped this cycle.
// These must NEVER be re-augmented onto ANY copy (a source deletion is final).
// Computed only when the authoritative copy is CHANGED (an unchanged copy reuses
// baseline.tracks, so it dropped nothing). Keyed off the copy's actual keys, NOT
// the pending cache.
val authoritativeCopy = authoritativeCopyId?.let { aid ->
    copies.firstOrNull { it.id == aid }?.takeIf { it.changed }
}
val authoritativeDropped: Set<String> =
    authoritativeCopy?.let { ac -> baselineRepr.toSet() - ac.keys.toSet() } ?: emptySet()
```

### 2b. Edit the augmentation guard + subtract `authoritativeDropped` (L1646-1657)

```kotlin
val augmentedCopies = copies.mapIndexed { i, copy ->
    val input = inputs[i]
    // Skip: local (always), unchanged copies, AND the authoritative source copy —
    // the source's missing baseline keys are GENUINE deletions, not pending fills.
    if (input.id == "local" || input.id == authoritativeCopyId || !copy.changed) {
        return@mapIndexed copy
    }
    val present = copy.keys.toSet()
    val lacked = baselineRepr.filter { it !in present }
    if (lacked.isEmpty()) return@mapIndexed copy
    val pendingLacked = lacked.filter { key ->
        // Never re-augment a key the authoritative source deleted — even onto a
        // mirror that's pending on it. A source deletion wins over a mirror's
        // pending-protection (the AM-residue closer).
        key !in authoritativeDropped &&
            isProviderPendingForKey(input.id, key, keyToTrack)
    }
    if (pendingLacked.isEmpty()) copy
    else copy.copy(keys = copy.keys + pendingLacked)
}
```

### 2c. Import

Add `TrackRemoveMode` to imports if not already present (it's in `com.parachord.shared.sync`, same package — likely no import needed; `providers` and `syncPlaylistSourceDao` are already in scope, used elsewhere in the file).

### `PlaylistMerge` signature — NO CHANGE

`PlaylistMerge.merge` stays **bit-for-bit identical** (fixture-pinned, desktop #911 parity). Authority is a **reconciliation-layer** concept expressed entirely by which keys reach the merge via `augmentedCopies`. No authority parameter is threaded into the pure merge. **This is the correct layering** and preserves desktop portability of the pure merge.

---

## 3. Data-loss invariants the change MUST preserve

1. **Never drop a local-add-pending track.** Authority logic touches only `baselineRepr` keys (`lacked`, `authoritativeDropped`). A non-baseline local add is a union-ADD and is structurally untouchable. *(Test V4.)*
2. **Never drop a catalog-gap track on a non-authoritative mirror.** A key still in the source's keys is **not** in `authoritativeDropped`, so the mirror's augmentation still re-adds it. *(Test V2.)*
3. **Never weaken delete-always-wins for a local removal.** `local` is never augmented and never the removal-capable streaming authority for an imported playlist; its absence always votes-remove. The change is purely subtractive on *non-local* augmentation. *(Test V5.)*
4. **Never grant drop-authority to an add-only source.** Gated on `trackRemoveMode != Unsupported`. *(Test V6.)*
5. **Multimaster (no source row, no XSPF) behavior is byte-identical to today.** `authoritativeCopyId == null`, `authoritativeDropped == ∅` → no change. **The existing `NwayMaterializeTest` cases 1 & 2 must stay green** — they are exactly this configuration. *(Regression assertion.)*
6. **TOTAL-WIPE floor remains the only catastrophic backstop and must not be bypassed.** The fix produces *non-empty* large turnover (40 ≠ 0), which is intentionally allowed; rely on the unit vectors (not the floor) to bound over-drop. Never apply source-authority in a way that could make `merged.isEmpty()` from a transient source state — the `changed`-gate on `authoritativeCopy` + the EMPTY-MIRROR rule (a 0-track source becomes `changed=false`, keys=baseline) prevent a transient-empty source from producing `authoritativeDropped = entire baseline`.
7. **Push-targets stay computed from UN-augmented `copies`** (L1686-1688) — do NOT switch them to `augmentedCopies`, or a genuine catalog-gap fill stops re-attempting.

---

## 4. Test vectors — add to a new `NwaySourceAuthorityTest` (mirror `NwayMaterializeTest`'s `Harness`)

The existing `Harness` constructs `SyncPlaylistSourceDao(db)` inline but doesn't expose it. **Harness change required:** add `val sourceDao = SyncPlaylistSourceDao(db)` field, pass it as `syncPlaylistSourceDao = sourceDao`, and add a helper:

```kotlin
suspend fun linkPullSource(localId: String, providerId: String, externalId: String) =
    sourceDao.upsert(localId, providerId, externalId, snapshotId = null, ownerId = null, syncedAt = 1_000L)
```

For hosted-XSPF vectors, seed the playlist with `sourceUrl = "https://example.com/p.xspf"` in `Playlist(...)`.

| V | Name | Setup | Action | Expected |
|---|------|-------|--------|----------|
| **V1** | `spotify source rotates out — residue drops to live set` | Spotify provider (`ByNativeId`) + AM provider (`Unsupported`). Seed `local-0` (`spotify-…` not needed; use `local-0` + `linkPullSource("local-0","spotify",ext)`). Baseline = 5 keys. AM remote physically holds all 5. Spotify remote rotated to 2 of the 5 (`changed=true` via token/count). | `runNwayPropagation()` | canonical/local/baseline = **2**; the 3 rotated-out keys dropped; AM `removeBy*` may fire but is `Unsupported` no-op; **no re-add of the 3 to canonical**. Spotify `addCalls` empty (already has its 2). |
| **V2** | `catalog-gap on AM mirror survives source authority` | Spotify source (has track K), AM mirror with `hydrateFn` that returns null for K's title (un-storable). Baseline includes K; Spotify changed but **still has K**. | `runNwayPropagation()` | K stays in canonical (it's in source.keys → not in `authoritativeDropped`; AM augmented for it). canonical size unchanged. |
| **V3** | `multimaster local-authored — unchanged (no source row)` | LB provider only, `local-0`, **no `linkPullSource`**, `sourceUrl=null`. Replicate `NwayMaterializeTest` case 1 (un-hydratable add). | `runNwayPropagation()` | Identical to existing case 1: 3 kept, nothing removed, baseline advances to 4. (Proves `authoritativeCopyId==null` path is inert.) |
| **V4** | `local-add-pending on a spotify-sourced playlist is kept` | Spotify source, baseline = 3 (all on Spotify). Add a 4th **local-only** track X (not on Spotify; Spotify unchanged or changed-without-X). | `runNwayPropagation()` | X kept in canonical/local (union-add); the 3 baseline tracks unaffected. canonical = 4. |
| **V5** | `local removal still on spotify source still drops` | Spotify source still holds Y. Remove Y from `local` (`markModified`, local lacks Y). | `runNwayPropagation()` | Y dropped from canonical (local votes-remove), even though Spotify (source) still lists Y. Proves authority is additive, never overrides local removal. |
| **V6** | `applemusic add-only source — authority DECLINED, augment-all` | AM source (`Unsupported`) + Spotify mirror. Baseline = 4. AM remote transiently fetches only 2 (partial, non-empty, `changed=true`). | `runNwayPropagation()` | canonical stays **4** (authority declined → AM augmented → its partial absence suppressed as pending). **No tracks dropped from the partial AM fetch.** |
| **V7** | `hosted XSPF — XSPF removal drops, mirror cannot resurrect` | `sourceUrl != null`, AM mirror holds all of baseline (add-only). `local` rows replaced to the post-XSPF set (3 of 5; `markModified`). AM `changed=true`. | `runNwayPropagation()` | canonical/local/baseline = **3**; the 2 XSPF-removed keys are in `authoritativeDropped` (baseline − local.keys) → subtracted from AM's `pendingLacked` → not resurrected. (Proves Refinement B closes the case the literal hypothesis misses.) |

**Must stay green (unchanged):**
- All `PlaylistMergeTest` vectors (11) — `PlaylistMerge` untouched.
- All `NwayShadowTest` vectors — downstream of, and unaware of, the augmentation.
- All 10 `NwayMaterializeTest` cases — they use `local-*` with no source row (multimaster), so `authoritativeCopyId == null`. **Critically cases 1 & 2** rely on mirror pending-suppression, which is preserved because `authoritativeDropped == ∅` there.

---

## 5. Desktop #911 portability note

Describe the change to the desktop team as a **reconciliation-layer augmentation gate**, NOT a merge change (the pure merge stays identical for cross-engine parity):

> **In the N-way pending-augmentation step** (the equivalent of `propagateReconcilePlaylist`'s `augmentedCopies`), before re-adding a changed mirror's pending-lacked baseline keys:
> 1. Identify the **authoritative pull-source copy**: `sync_playlist_source.providerId`'s copy for an imported playlist; the `local` copy when `sourceUrl != null` (hosted XSPF); none for a local-authored multimaster playlist.
> 2. Grant authority **only if** that provider is removal-capable (`trackRemoveMode != Unsupported` / not add-only).
> 3. **Skip augmenting the authoritative source copy** so its missing baseline keys read as genuine deletions in the union-remove.
> 4. Compute `authoritativeDropped = baseline − authoritativeCopy.keys` (when the authoritative copy is changed) and **exclude those keys from every mirror's pending re-add** — a source deletion is final and must never be resurrected by a mirror's pending-protection.
>
> The pure merge (`PlaylistMerge.merge`) is **unchanged** — these are inputs-shaping rules in the reconciliation layer. Same fixtures, same merge.

This yields identical semantics: pull-source authority to drop, mirrors retain pending-protection only for keys the source still asserts.

---

## 6. Existing-data cleanup — automatic convergence, with a precise sequence

**The fix is self-healing — no one-time prune of the local DB is needed**, because the augmentation re-evaluates `baseline − source.keys` every cycle and the source-skip makes those deletions fire *each* cycle until baseline catches up. Sequence after the fix is live (and `nway_propagate` toggle ON):

1. **User manually deletes the bloated playlist in the Apple Music app** (only step that requires the user — AM is add-only, the app cannot shrink AM's physical mirror). This makes AM's `fetchPlaylistTracks` return the live set (or empty → EMPTY-MIRROR fill target). *Without this, AM stays a perpetual non-authoritative mirror that physically holds 519, lags canonical, becomes a push-target every cycle, and the executor's removal is `Unsupported` no-op — canonical/local still converge to 40, but AM's physical residue persists.*
2. **Next sync cycle:** Spotify source (changed=true) presents 40 live keys. `authoritativeDropped = baseline(519) − 40 = 479`. Source-skip → Spotify votes-remove all 479. AM's augmentation has 479 subtracted out. `merged = 40`. `massChangeAbort` not tripped (40 ≠ 0). **Local rewritten 519→40, baseline advanced to 40.**
3. **Subsequent cycles:** converged → truly-converged short-circuit (L1695) no-ops (assuming AM was cleaned in step 1, so AM no longer lags).

**Caveat to set expectations:** if the user does **not** clean AM (step 1), the canonical/local **still converge to 40** on the next cycle (the source-skip drops the 479 from canonical regardless of AM), but AM's *physical* playlist keeps its residue — the fix cannot shrink an add-only mirror. The 519→40 *local* convergence is automatic; the AM-side 519→40 requires the manual AM-app delete. The fix's job is to stop the **canonical/local accumulation and the re-push-everywhere amplifier**, which it does without any prune.

---

**Key files referenced:** `shared/.../sync/SyncEngine.kt` (`propagateReconcilePlaylist` L1529-1714, augmentation L1646-1657, `isProviderPendingForKey` L1518-1527, `writableById` L1599-1602), `shared/.../sync/PlaylistMerge.kt` (untouched, L50-81), `shared/.../sync/SyncProvider.kt` (`ProviderFeatures.trackRemoveMode` / `TrackRemoveMode.Unsupported` L253-288), `shared/.../db/dao/SyncPlaylistSourceDao.kt` (`selectForLocal`), `app/.../sync/NwayMaterializeTest.kt` (harness to mirror for `NwaySourceAuthorityTest`).