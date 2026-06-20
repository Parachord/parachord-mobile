# Per-Provider Sync Selection — Design

**Date:** 2026-06-20
**Status:** Proposed (awaiting sign-off before implementation)
**Platforms:** shared (`commonMain`) + Android UI + iOS UI
**Source of truth:** Android `SyncSetupSheet`/`SyncViewModel`; desktop `app.js` push-loop gates.

## Problem

A user with Apple Music + ListenBrainz enabled had **221 playlists pushed to
ListenBrainz**, almost all empty (0 tracks), because:

1. **iOS never got the per-provider sync UI** that Android has. With no UI, every
   provider falls back to the "all four axes" default
   (`getSyncCollectionsForProvider` returns `{tracks,albums,artists,playlists}`
   when nothing is stored), so ListenBrainz silently mirrored every playlist.
2. **`pushLocalPlaylists` is a single global flag, default `true`** — desktop
   makes it **per-provider, default `false` for ListenBrainz** (`app.js:11041`,
   gate at `app.js:6733`). Mobile has no per-provider push opt-in.
3. There is **no per-provider choice of *which* playlists** mirror where.

(The empty-playlist creation itself is already fixed — `pushPlaylistsForProvider`
now resolves the pushable tracklist before deciding to create, and skips any
playlist with 0 pushable tracks. This design is the broader "let the user choose
what syncs where" feature.)

## Decisions (confirmed with user 2026-06-20)

- **Full per-playlist picker** per provider (not just on/off).
- **ListenBrainz playlists default OFF** — nothing pushes to LB until opt-in,
  matching desktop `pushLocalPlaylists=false`.

## Existing primitives (reuse, do not reinvent)

| Primitive | Location | Today |
|-----------|----------|-------|
| Per-provider axis set | `SettingsStore.getSyncCollectionsForProvider(id)` / `setSyncCollectionsForProvider` | Stored; SyncEngine already gates pull+push on `providerHasAxis(id,"playlists")` etc. Defaults to all 4. |
| Pull playlist selection | `SyncSettings.selectedPlaylistIds` (global) | Gates the Spotify **pull** import (`SyncEngine:1138`). Spotify-only. |
| Push opt-in | `SyncSettings.pushLocalPlaylists` (global, default true) | Single flag for all providers. |
| Android per-provider UI | `ui/screens/sync/SyncSetupSheet.kt` + `SyncViewModel.kt` | Axis checkboxes + Spotify-only playlist picker. |

## Design

### 1. Data model — per-provider playlist selection (shared)

Add a per-provider playlist-mirror selection, replacing the single global
`selectedPlaylistIds` for the **push** path (keep the global one for the legacy
Spotify pull until unified).

```kotlin
// SyncSettings additions (per-provider, keyed by providerId)
enum class PlaylistSyncMode { ALL, NONE, SELECTED }

data class ProviderPlaylistSelection(
    val mode: PlaylistSyncMode,
    val localPlaylistIds: Set<String> = emptySet(), // used when mode == SELECTED
)
```

Storage in `KvStore` (CSV, mirrors existing patterns):
- `sync_playlist_mode_<providerId>` → `ALL` | `NONE` | `SELECTED`
- `sync_playlist_ids_<providerId>` → CSV of **local** playlist IDs (mode SELECTED)

**Defaults (no value stored):**
- `spotify`, `applemusic` → `ALL` (preserve current mirror-everything behavior).
- `listenbrainz` → `NONE` (desktop parity; the flood fix).

`SyncSettingsProvider` gains:
```kotlin
suspend fun getPlaylistSelection(providerId: String): ProviderPlaylistSelection
suspend fun setPlaylistSelection(providerId: String, sel: ProviderPlaylistSelection)
```

### 2. Push gating (shared — `SyncEngine.pushPlaylistsForProvider`)

The candidate filter gains a selection check after `isPushCandidate`:

```kotlin
val sel = settingsStore.getPlaylistSelection(providerId)
val candidates = allPlaylists.filter {
    it.name.isNotBlank() && isPushCandidate(it, providerId) && when (sel.mode) {
        PlaylistSyncMode.ALL -> true
        PlaylistSyncMode.NONE -> false
        PlaylistSyncMode.SELECTED -> it.id in sel.localPlaylistIds
    }
}
```

`NONE` short-circuits the entire LB push (no candidates → no fetch-remote, no
loop). This is the precise fix for the flood: a fresh install has LB = NONE, so
LB never pushes until the user picks playlists.

**Axis gate still applies first** — `providerHasAxis(id,"playlists")` already
guards the whole playlist phase per provider; the selection refines *which*
playlists when the axis is on.

### 3. Last-write-wins reconciliation (#2 — verify, likely already correct)

The push loop already: (a) matches remote by ID-link → playlist.spotifyId →
name (3-layer dedup), (b) edits in place via the link rather than re-creating,
(c) short-circuits when the remote tracklist already matches
(`remoteTracklistMatches`), (d) re-pushes only when `locallyModified`. Pull paths
set `locallyModified` when other mirrors exist (mirror propagation Fix 1). **Task:
add a focused test** asserting: edit on source A → pull through provider B →
push to provider C carries the newest edit; and that an unchanged playlist does
NOT re-push (idempotency). No code change expected unless the test fails.

### 4. Android UI — extend `SyncSetupSheet`

- Axis checkboxes already exist. **Generalize the playlist picker** from
  Spotify-only (`spotifyProvider.fetchPlaylists()`) to per-provider: show the
  **local** playlists eligible to push to this provider (the `isPushCandidate`
  set), with All / None / per-row checkboxes → `setPlaylistSelection`.
- Pre-fill from `getPlaylistSelection(providerId)`.

### 5. iOS UI — new per-provider config sheet (port of Android)

- Tap a connected provider row in Settings → sheet with:
  - Axis toggles (only axes the provider supports; LB = playlists only).
  - When Playlists on: All / None / checklist of local playlists.
- Reads/writes the same shared settings via new `IosContainer` methods
  (`getPlaylistSelection` / `setPlaylistSelection` / axis getters already
  partially present).
- Matches the existing dark sheet styling (`PC.*` tokens).

### 6. Separate bug (fold in) — AM ISRC library-vs-catalog ID

`AppleMusicSyncProvider.attachIsrcs` passes Apple Music **library** IDs to the
**catalog** endpoint (`/v1/catalog/{sf}/songs?ids=`), which wants **catalog**
IDs → first batch 404s → 0 ISRCs → AM↔Spotify cross-provider track dedup never
fires. Fix: map library songs to their catalog IDs first
(`playParams.catalogId` is on the library song's `attributes.playParams`), then
look up ISRCs by catalog ID. Verify the field via a single live response before
coding.

## Out of scope

- Unifying the legacy global `selectedPlaylistIds` (Spotify pull) into the new
  per-provider model — the pull path keeps working; a later pass can converge.
- Desktop's manual remote dedup tool (`cleanupDuplicatePlaylists`).

## Task breakdown

1. Shared: `PlaylistSyncMode` + `ProviderPlaylistSelection` + settings get/set +
   defaults (LB=NONE). Unit test the defaults.
2. Shared: push-loop selection gate in `pushPlaylistsForProvider`. Unit/trace test.
3. Shared: LWW idempotency test (verify; fix only if red).
4. Android: generalize `SyncSetupSheet` playlist picker to per-provider + wire set.
5. iOS: per-provider config sheet (axes + playlist picker) + `IosContainer` methods.
6. AM ISRC library→catalog ID fix (+ verify field on a live response).
7. Build both platforms, regression, on-device verify the LB flood is gone and a
   selected subset pushes correctly.

## Acceptance

- Fresh install: ListenBrainz pushes **zero** playlists until the user opts in.
- User can pick exactly which playlists mirror to each provider; only those push.
- Turning a provider's Playlists axis off stops all playlist sync for it.
- An unchanged sync re-pushes nothing (idempotent); newest cross-source edit wins.
- Apple Music tracks dedupe with Spotify via ISRC (AM ISRC bug fixed).
