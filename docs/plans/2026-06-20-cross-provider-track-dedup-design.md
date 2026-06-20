# Cross-Provider Saved-Track Dedup + Sane Disable — Design

**Goal:** A song saved on more than one provider (Spotify Liked Songs, Apple
Music library) should be **one** row in the collection carrying every service's
ID — not one row per provider. And disabling a provider's sync should offer a
keep-or-remove choice that's multi-provider-aware. Shared-engine change → both
Android and iOS benefit.

Status: **design approved (identity = ISRC; disable = ask keep/remove, default
keep). Not yet implemented.**

---

## Problem (verified in code)

Saved tracks get a **provider-prefixed row id**: `spotify-<id>`
(`SpotifySyncProvider.kt:141`) and `applemusic-<catalogId>`
(`AppleMusicSyncProvider.kt:648`). The track diff is **per-provider**
(`applyTrackDiff`: `toAdd = remote.filter { it.spotifyId !in localByExternalId }`,
scoped to that provider's `sync_sources`), and there is **no cross-provider
merge** — `migrateMergeCrossProviderDuplicates` is playlists-only. So the same
song saved on Spotify **and** Apple Music becomes two rows → it appears twice in
the Collection. (ListenBrainz syncs playlists only, so it never contributes
saved-track rows.)

Desktop's canonical track identity precedence is **mbid → isrc → artist|title
lowercase** (`app.js:34810`). We adopt the **isrc** tier (exact recording
identifier, present at sync time, no false-merge risk). MBID is not reliably
resolved at sync time (enrichment is async); artist|title is fuzzy (live vs
studio, remasters) and is intentionally **not** used as a merge key.

## Decisions

1. **Identity key = normalized ISRC**, fallback to the provider-prefixed id when
   no ISRC. Two saved tracks merge iff they share an ISRC.
2. **Disable = ask keep-or-remove, default keep.** "Remove" deletes items that
   came *only* from that provider; items still sourced by another provider stay
   (lose only that provider's source). Android parity.

## The Apple-Music-ISRC wrinkle (load-bearing)

AM's **library** API (`/me/library/songs`) returns `name` / `playParams.id`
(catalog id) / `dateAdded` — **no `isrc`**. ISRC lives on the **catalog** API
(`/v1/catalog/{sf}/songs/{id}` → `attributes.isrc`). So for AM library tracks we
must **batch-fetch ISRC by catalog id** at sync time (the `ids=` catalog
endpoint, ~300 ids/request, reusing the existing catalog client). Without this,
AM tracks have no ISRC and can't merge with Spotify — the fix wouldn't actually
help the reported case. Spotify already exposes ISRC (`SpExternalIds.isrc`,
already validated at `SpotifyClient.kt:256`).

## Design

### Canonical row id
`Track.id = "isrc-<ISRC>"` when a validated ISRC is present, else the existing
provider-prefixed id. Build it in a shared helper `canonicalTrackId(isrc,
fallbackId)` so every provider + the migration agree byte-for-byte.

### Merge on add (applyTrackDiff)
When adding a remote saved track:
1. Compute `canonicalId`.
2. If a row with `canonicalId` already exists, **merge** the provider's IDs onto
   it (`spotifyId` / `appleMusicId` / `isrc` filled additively, never
   overwriting a populated field — mirror `backfillResolverIds` null-only rule)
   instead of inserting a new row.
3. Upsert `sync_sources (canonicalId, "track", providerId) → externalId`.
Diff keying changes from "external id in this provider's local sources" to
"external id in this provider's sources, resolved to canonical row" so a song
already present (via the OTHER provider) is an **update/merge**, not an add.

### sync_sources unchanged
PK stays `(itemId, itemType, providerId)`; `itemId` = canonical row id. Multiple
providers point at one canonical track. Per-provider removal, tombstones, and
the "keep if another provider still sources it" logic in `stopSyncing` all keep
working with zero schema change.

### Migration (one-shot, versioned like the playlist dedup)
`migrateMergeCrossProviderTrackDuplicates`: group existing `track` rows by
validated ISRC; for each group >1, pick a keeper (richest IDs), merge the others'
service IDs onto it, re-point `sync_sources.itemId` and `playlist_track.trackId`
to the keeper, delete the dupes. Idempotent; gated on a `sync_data_version` bump.

### Disable generalization
- `stopSyncing(removeItems)` → `stopSyncing(providerId, removeItems)` (currently
  hardcoded to Spotify, `SyncEngine.kt:2405`). The keep-multi-provider logic is
  already correct, just un-parameterized.
- **iOS:** toggling a provider off prompts keep-or-remove (default keep) and
  calls the generalized stop. Today iOS silently keeps everything.
- **Android:** already has the wizard; point it at the generalized signature.

## Test plan (commonTest, this is data-critical)
- Spotify+AM saved track with the same ISRC → **one** row with both IDs + two
  `sync_sources`.
- No-ISRC tracks stay separate (no false merge).
- Migration merges pre-existing dupes, re-points playlist_track + sync_sources,
  deletes dupes, is idempotent.
- Disable provider A with removeItems: a dual-sourced track **survives** (loses
  only A's source); an A-only track is removed.
- AM catalog ISRC batch-lookup populates ISRC for library tracks.

## Rollout
Shared engine (TDD) → Android build + unit tests → iOS build → on-device verify
(real Spotify+AM libraries with overlapping songs). The migration runs once on
each platform's next launch.

## Follow-up (out of scope here)
- **MBID post-enrichment backstop:** merge rows that gain the same `recordingMbid`
  after background enrichment, catching songs lacking an ISRC on either side.
  Its own ticket — the ISRC path fixes the reported case now.
