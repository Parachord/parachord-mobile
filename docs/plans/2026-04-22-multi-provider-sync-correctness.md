# Multi-Provider Playlist Sync Correctness — Plan

> **Status:** DRAFT. Requires sign-off on the open design decisions at the bottom before expanding into bite-sized executable tasks.
>
> **For Claude:** Once this draft is approved, expand each phase into bite-sized TDD tasks per `superpowers:writing-plans` and hand off to `superpowers:executing-plans`.

**Goal:** Make Android playlist sync correct when both the Android and desktop apps are connected to the same accounts and syncing across multiple providers (Spotify, Apple Music, and any future provider such as Tidal), so round-trip edits propagate through every mirror without duplicates, lost edits, or orphaned locals — including users who never connect Spotify at all.

**Architecture:** Adopt the desktop's per-playlist `syncedFrom` / `syncedTo` data model, a pure `SyncProvider` interface, and the four-piece multi-provider propagation invariants described in the desktop's `CLAUDE.md` "Android Parity Requirements" section. Keep the existing three-layer dedup and durable link map — extend them to cover multi-provider state rather than replacing them. The plan assumes desktop is already correct (per its own CLAUDE.md) and that making Android byte-compatible with that model is sufficient to make round-trip safe.

**Tech Stack:** Kotlin, SQLDelight, Koin, Retrofit + OkHttp, existing `SpotifySyncProvider`, forthcoming `AppleMusicSyncProvider`.

## Provider-agnostic design principles

No code path may hardcode "Spotify" or "Apple Music" as special. Every sync behavior keys on `providerId` and/or a capability flag from `SyncProvider.features` (see Phase 2). Concretely:

- **Any provider can be a user's "primary" (sole) sync backend.** An Apple-Music-only user must get the same round-trip correctness as a Spotify-only user; the same is true for a hypothetical Tidal-only user once the provider is added.
- **Any provider can be a playlist's `syncedFrom`.** The pull source is determined at import time; "primary" is per-playlist, not per-user.
- **Any combination of providers can be `syncedTo`.** A local playlist mirrored to Spotify + Apple Music + Tidal is just a three-entry map.
- **Each provider is its own merge oracle.** When two clients race an edit to the same playlist, the provider receiving the writes arbitrates by last-write-wins on its own endpoint; both clients converge on the next pull from that provider. There is no cross-provider ordering and no "primary provider" for tie-breaking.
- **Provider capability differences live in `SyncProvider.features`** (snapshot semantics, follow API support, deletion support, etc.) — not in `SyncEngine` branches by provider id.

Every sentence below that says "Spotify" or "Apple Music" does so because that provider *currently* has a specific idiosyncrasy worth documenting. The code paths describing those idiosyncrasies must live behind the provider's `features` flags so a new provider (Tidal, YouTube Music, etc.) only needs to declare its own capabilities, not edit `SyncEngine`.

---

## Context — what's already correct on Android

The existing code is on a strong foundation. These pieces already match desktop and the plan preserves them:

| Piece | Where | Matches desktop |
|---|---|---|
| Three-layer dedup before remote create | `SyncEngine.syncPlaylists` | ✓ |
| Durable link map keyed by `(localId, providerId)` | `sync_playlist_link` table | ✓ (mirrors `sync_playlist_links` electron-store map) |
| `migrateLinksFromPlaylists` idempotent startup pass | `SyncEngine.migrateLinksFromPlaylists` | ✓ |
| Hosted XSPF poller with SSRF re-validation | `HostedPlaylistPoller` | ✓ |
| Hosted XSPF skip-pull + include-in-push | `SyncEngine` + `PlaylistDetailViewModel` | ✓ |
| Skip-if-held sync mutex | `SyncEngine.syncMutex.tryLock()` | ✓ (maps to `playlistSyncInProgressRef`) |
| Hosted-XSPF playlist protection during cleanup | `SyncEngine:701–720` | ✓ |

## Context — what's missing

Every item below is something Android is missing that causes a specific round-trip failure mode documented in desktop CLAUDE.md.

1. **Single-provider hardcoding.** Every entry point opens with `val providerId = SpotifySyncProvider.PROVIDER_ID`. Apple Music has no sync path at all. (Tracking: [#15](https://github.com/Parachord/parachord-mobile/issues/15).)
2. **No `syncedTo` map on playlists.** Android stores only `playlists.spotifyId` (scalar). The desktop model is `syncedTo: Map<providerId, { externalId, snapshotId, syncedAt, pendingAction, unresolvedTracks }>`. Missing this means we can't even represent a playlist that mirrors to both Spotify and Apple Music.
3. **No `syncedFrom` field.** Android infers the pull source from the playlist id prefix (`spotify-…`, `hosted-xspf-…`). The desktop stores `syncedFrom: { resolver, externalId, snapshotId, ownerId }` explicitly. The implicit scheme breaks on non-prefixed locally-originated playlists that later get pulled from a second provider.
4. **No `localOnly` opt-out.** Desktop supports "never sync this playlist" per row; Android doesn't.
5. **No `pendingAction` per-provider.** Desktop queues deferred actions (e.g., remote-deleted → needs re-create on reconnect); Android doesn't.
6. **No cross-provider mirror propagation.** The four cooperating fixes from desktop CLAUDE.md — (1) pull paths setting `locallyModified` when other mirrors exist, (2) local mutators inlining the flag, (3) provider-scoped `syncedFrom` guard in push loops, (4) post-push clear filtering `relevantMirrors` — none of these exist on Android because we don't have the data to express them.
7. **No `isOwnPullSource` check on import.** If Android ever learns to pull from Apple Music, importing an AM playlist that's a push mirror for a Spotify-originated local will clobber the Spotify `syncedFrom` and create a duplicate.
8. **No post-push `locallyModified` clear logic.** Currently the flag gets cleared only by the hosted-XSPF sync path and the `pullRemoteChanges` path — there's no general "all mirrors synced → clear flag" step, because we have no notion of multiple mirrors.
9. **Wizard / picker pre-check doesn't seed from push state.** Desktop's `openSyncSetupModal` unions saved selection + any local with `syncedFrom.resolver == providerId` + any local with `syncedTo[providerId].externalId`. Android only uses the saved list.
10. **No Apple Music graceful endpoint degradation.** The CLAUDE.md table (PATCH/PUT/DELETE on Apple library returning 401) needs a typed provider implementation; throwing from any of those aborts the track push.

Every one of those is a documented failure mode on desktop. Android exhibits the same mode under the same input if we don't port the fix.

## Phases

### Phase 0 — Verification scenarios (write before any code)

Before we touch a DAO, write down the concrete end-to-end scenarios that define "works." Each scenario is a paragraph of what the user does on which device and what should be true after each step. The plan succeeds only if all of these behave correctly on real hardware with real accounts. Port these into e2e test harness stubs in Phase 7.

1. **Round-trip single provider.** Desktop creates `local-abc`. Next sync pushes to Spotify as `spot-xyz`. Android syncs; sees the playlist with `syncedFrom.resolver == "spotify"`, not as a new local. Android edits (adds a track). Next sync pushes the edit to Spotify (same `spot-xyz`, not a new remote). Desktop pulls the update.
2. **Round-trip two providers.** Same as above but Apple Music is also enabled on both clients. After the Android edit: Spotify gets it via Android's push; the Android playlist ends up with `syncedTo: { spotify: { syncedAt >= lastModified }, applemusic: { syncedAt >= lastModified } }`. Desktop pulls from Spotify and the AM mirror also updates on its next desktop sync.
3. **Edit while another client is syncing.** Desktop holds the sync mutex; Android runs `syncNow`. Android's `syncNow` sees the mutex is foreign (on a different device) and cannot block on it; resolution rule: both clients run independently, the last write of `locallyModified` wins on Spotify, then Spotify's `snapshotId` diff triggers the other client's pull on its next tick. Concurrent-edit conflict resolution is Spotify-arbitrated — this is a property of the design, not a bug; but the code must not crash or corrupt state when it happens.
4. **Hosted XSPF to two providers.** Import an XSPF on Android. Next poll detects a change. Both Spotify and Apple Music mirror get PUT with the new tracklist. Neither provider creates a duplicate on a subsequent sync after another poll cycle with the same content.
5. **Local-only playlist stays local.** Desktop creates a `local-*` playlist and marks it `localOnly`. Android syncs. The playlist must not be pushed to any provider from Android either.
6. **Cross-provider `syncedFrom` preservation.** Desktop creates `local-abc`, pushes to both Spotify and Apple Music. Spotify is the pull source (`syncedFrom.resolver == "spotify"`); Apple Music is the push mirror. Android syncs. Android must not clobber `syncedFrom` when importing the AM representation.
7. **Apple Music 401 on PATCH/PUT/DELETE.** Under any of those endpoints returning 401, the track push still runs. Rename silently no-ops for the session. A full delete reports unsupported to the user rather than throwing.
8. **Remote deleted while offline.** User deletes a Spotify playlist on the web. Android's next sync sees the linked remote gone. Behavior: mark `syncedTo[spotify].pendingAction = "remote-deleted"`, DO NOT delete the local, surface to user as "re-push or unlink?" — mirrors desktop. Android today would probably create a duplicate; confirm and fix.
9. **Both apps online editing the same playlist.** Android adds track A; desktop adds track B. Both push to the connected provider. Last write wins on the provider; both clients' next sync pulls the winner. Not a conflict we resolve — the connected provider is the merge oracle. Test that it doesn't crash or duplicate.
10. **Reconnect after long gap.** Android hasn't synced for 30 days. Desktop made 20 edits in that window. Android's next sync: resolve each local's state from `(syncedFrom.snapshotId, syncedTo.snapshotId)` vs. remote; pull what it must, push what it must. No lost edits, no duplicates.
11. **Apple Music-only round trip.** Desktop creates `local-abc` with Apple Music connected and Spotify *never connected*. Next sync pushes to AM as `am-xyz`. Android (also AM-only) syncs; sees the playlist with `syncedFrom.resolver == "applemusic"`. Android adds a track. Next sync pushes the edit to AM (same `am-xyz`, not a new playlist). Desktop sees the change via AM's `lastModified` diff and pulls. Wizards, settings, banners must never reference Spotify in this flow.
12. **Future provider (Tidal stand-in) round trip.** A new `TidalSyncProvider` is registered as the third `SyncProvider` in Koin. A user with Tidal-only configured is treated identically to scenarios 1 and 11 — `syncedFrom.resolver == "tidal"`, push targets stored as `syncedTo[tidal]`, three-layer dedup queries Tidal's owned-playlists endpoint, and the wizard renders Tidal-flavored copy. `SyncEngine` itself receives no Tidal-specific code; only the new provider class implements `SyncProvider` and declares its `features`.
13. **Add a second provider to an existing AM-only user.** User has 50 AM-mirrored playlists. They later connect Spotify. Next sync pushes every non-`localOnly` AM-originated playlist to Spotify (provider-scoped `syncedFrom` guard allows this — `syncedFrom.resolver == "applemusic"` ≠ Spotify, so the push proceeds). After all pushes succeed, every playlist has `syncedTo: { applemusic: …, spotify: … }`. No duplicates created on AM during this transition.
14. **Drop a provider mid-flight.** User disconnects Apple Music. The post-push clear logic recalculates `relevantMirrors` excluding AM and clears `locallyModified` only when remaining mirrors are caught up. Local playlists that were AM-pull-source (`syncedFrom.resolver == "applemusic"`) are NOT deleted; they become locally-owned with no upstream pull source. Tracks/albums/artists from `sync_source` for `providerId == "applemusic"` are removed.

These scenarios are the contract. Any design change must be re-checked against all fourteen.

### Phase 1 — Data model

The goal is to represent `syncedFrom` and `syncedTo` explicitly in the Android schema. Two options; pick one before coding (see open questions).

**Option A — JSON columns on `playlists`.**
- Add `syncedFromJson TEXT` and `syncedToJson TEXT` columns.
- Serialize with `kotlinx.serialization`.
- Pros: minimal schema churn, direct 1:1 mapping from desktop.
- Cons: not queryable relationally (can't `SELECT playlists WHERE syncedTo CONTAINS providerId`), every write must round-trip through decode → mutate → encode.

**Option B — relational.**
- Extend `sync_playlist_link` with `snapshotId TEXT`, `syncedAt INTEGER NOT NULL`, `pendingAction TEXT`, `unresolvedTracksJson TEXT` — this is exactly the `syncedTo[providerId]` row.
- New table `sync_playlist_source (localPlaylistId, providerId, externalId, snapshotId, ownerId)` keyed on `localPlaylistId` — this is `syncedFrom`.
- Add `localOnly INTEGER NOT NULL DEFAULT 0` to `playlists`.
- Pros: queryable, joinable, no JSON parsing in the hot path, migration is additive.
- Cons: more DAO surface, two new migrations.

**Recommendation: Option B.** The link table already exists and the shape is right — we're one `snapshotId` column plus a `pendingAction` column away from matching desktop's `syncedTo[providerId]` object exactly. For `syncedFrom`, a second table is cleaner than a JSON blob.

Migrations are idempotent `ALTER TABLE … ADD COLUMN IF NOT EXISTS` (SQLite doesn't have IF NOT EXISTS for columns — wrap in try/catch same pattern as `sourceUrl`). `createAllTables` DDL in `AndroidModule.kt` re-executes on bind.

> **Status: ✅ LANDED.** Execution plan: [`2026-04-22-phase-1-data-model.md`](2026-04-22-phase-1-data-model.md). Shipped in commits `06c396d` (test driver) through `ce3617c` (integration smoke test). 308 unit tests green; app launches clean on-device with all `ALTER TABLE` / `CREATE TABLE` migrations firing. No provider code changed yet; schema ready for Phase 2.

### Phase 2 — Provider abstraction

> **Status:** ✅ Landed in commits `d02dbd1` → `38d6404` (Apr 24, 2026). Provider abstraction in place; SyncEngine accepts `List<SyncProvider>`; behavior unchanged. Apple Music has no sync yet — that's Phase 4. Multi-provider iteration inside method bodies is Phase 3. Verified: 312 tests green, `assembleDebug` clean, installed + force-stopped on Pixel 9a (Spotify sync round-trip exercised post-install).

Per the existing `.claude/plans/look-at-the-recent-stateless-backus.md` plan (#15), the refactor creates a `shared/.../sync/SyncProvider.kt` interface with the contract below, moves `SpotifySyncProvider`'s nested models to shared, then `SpotifySyncProvider implements SyncProvider` with behavior unchanged.

```kotlin
interface SyncProvider {
    val id: String
    /** Human-readable display name shown in settings + wizard ("Spotify", "Apple Music", "Tidal"). */
    val displayName: String
    /** Capability flags — all provider-specific behavior gated here, not on `id`. */
    val features: ProviderFeatures

    // Pull
    suspend fun fetchTracks(...): List<SyncedTrack>?
    suspend fun fetchAlbums(...): List<SyncedAlbum>?
    suspend fun fetchArtists(...): List<SyncedArtist>?
    suspend fun fetchPlaylists(...): List<SyncedPlaylist>
    suspend fun fetchPlaylistTracks(externalId: String): List<PlaylistTrackEntity>
    /**
     * Returns whatever the provider considers a stable change-token for this
     * playlist — Spotify's opaque `snapshot_id`, Apple's `lastModifiedDate`
     * ISO string, Tidal's ETag, etc. The token is opaque to SyncEngine and
     * compared as a string. Returns null when [features.snapshots] is `None`.
     */
    suspend fun getPlaylistSnapshotId(externalId: String): String?

    // Push
    suspend fun createPlaylist(name: String, description: String?): RemoteCreated
    suspend fun replacePlaylistTracks(externalId: String, externalTrackIds: List<String>): String?
    suspend fun deletePlaylist(externalId: String): DeleteResult
    suspend fun saveTracks(ids: List<String>); suspend fun removeTracks(...)
    suspend fun saveAlbums(...); suspend fun removeAlbums(...)
    suspend fun followArtists(...); suspend fun unfollowArtists(...)

    // Resolution
    suspend fun resolveTrackId(title: String, artist: String, album: String?): String?
}

/**
 * Per-provider capability declarations. SyncEngine branches on these,
 * never on `provider.id`. New providers add themselves by declaring
 * features — no SyncEngine changes required.
 */
data class ProviderFeatures(
    /** What kind of snapshot/change-token this provider's playlists carry. */
    val snapshots: SnapshotKind,
    /** Whether the provider supports a follow/unfollow API for artists. Apple Music = false. */
    val supportsFollow: Boolean,
    /** Whether the provider supports playlist deletion via API. Apple Music = false (degrade to manual). */
    val supportsPlaylistDelete: Boolean,
    /** Whether the provider supports playlist rename via API. Apple Music = false. */
    val supportsPlaylistRename: Boolean,
    /** Whether full-replace PUT on tracks is reliable; if false, push degrades to append-only after first failure. */
    val supportsTrackReplace: Boolean,
)

enum class SnapshotKind {
    /** Provider returns a stable opaque token (Spotify `snapshot_id`). String-equality compare. */
    Opaque,
    /** Provider returns a date/version string (Apple `lastModifiedDate`). String-equality compare; refetch on mismatch. */
    DateString,
    /** Provider has no snapshot. Always pull-and-diff to detect changes. Costlier — 1 extra API call per playlist per sync. */
    None,
}

sealed class DeleteResult {
    object Success : DeleteResult()
    data class Unsupported(val status: Int) : DeleteResult()
    data class Failed(val error: Throwable) : DeleteResult()
}
```

`DeleteResult` captures Apple's 401-on-DELETE behavior: `Unsupported` means the UI surfaces "remove manually in the Music app"; `Failed` is a retryable network/5xx error. No `throw` inside the provider for documented-unsupported responses.

**Concrete declarations for the providers we ship:**

```kotlin
// SpotifySyncProvider
override val features = ProviderFeatures(
    snapshots = SnapshotKind.Opaque,
    supportsFollow = true,
    supportsPlaylistDelete = true,
    supportsPlaylistRename = true,
    supportsTrackReplace = true,
)

// AppleMusicSyncProvider
override val features = ProviderFeatures(
    snapshots = SnapshotKind.DateString,
    supportsFollow = false,            // no follow/unfollow API exists
    supportsPlaylistDelete = false,    // public API returns 401
    supportsPlaylistRename = false,    // PATCH returns 401
    supportsTrackReplace = false,      // PUT often returns 401, falls back to POST-append
)

// TidalSyncProvider (hypothetical; declares its own features when implemented)
```

Wire up Koin to register all providers as `SyncProvider` and expose them as `List<SyncProvider>` for `SyncEngine`.

### Phase 3 — Multi-provider propagation (the four cooperating fixes)

> **Status:** ✅ Landed in commits `1f97215` → `72b1511` (Apr 24, 2026). All four cooperating fixes implemented + the cross-provider `syncedFrom` preservation guard. Single-provider Spotify behavior unchanged today; logic becomes load-bearing the moment Phase 4 adds Apple Music as a second provider in the iteration loops. Verified: 327 tests green, `assembleDebug` clean, installed on Pixel 9a (Spotify sync round-trip exercised post-install).

Port the four pieces from desktop CLAUDE.md line by line, adapted to Kotlin / SQLDelight:

**3.1. Pull paths set `locallyModified = true` when other mirrors exist.**
- `pullRemoteChanges` in `PlaylistDetailViewModel` — set `locallyModified = hasOtherMirrors`.
- `SyncEngine.syncPlaylists` import branch (refill-on-empty equivalent) — same.

`hasOtherMirrors` predicate in SQL: `EXISTS (SELECT 1 FROM sync_playlist_link WHERE localPlaylistId = ? AND providerId != ?)`. Capture as a reusable `DAO.hasOtherMirrors(localId, providerId)` helper.

**3.2. Local-content mutators persist `locallyModified = true` in the same save.**

Every Android `addTracksToPlaylist` / `removeTrackFromPlaylist` / `moveTrack…` / `createPlaylistWithTracks` call site needs to flip `locallyModified` in the SAME DB write as the tracks. Today these may issue separate writes — audit and consolidate. Guard: only flag when `syncedFrom != null OR syncedTo.isNotEmpty()`.

**3.3. Provider-scoped `syncedFrom` guard in push loops.**
- In the Phase 2-refactored push loop, replace any blanket `if (playlist.syncedFrom != null) continue` with `if (playlist.syncedFrom?.resolver == providerId) continue`.
- Same for the id-pattern guard: `if (playlist.id.startsWith("${providerId}-")) continue`.

Both guards must be scoped to the *current* `providerId` in the iteration — not the playlist's origin. A Spotify-imported playlist (`syncedFrom.resolver == "spotify"`) must still be pushable to Apple Music.

**3.4. Post-push `locallyModified` clear with `relevantMirrors`.**
After the push loop runs for every enabled provider, iterate every `locallyModified` playlist:

```kotlin
for (playlist in locallyModifiedPlaylists) {
    val sourceProvider = playlist.syncedFrom?.resolver
    val relevantMirrors = enabledProviders.filter { pid ->
        syncPlaylistLinkDao.selectForLink(playlist.id, pid)?.externalId != null
            && pid != sourceProvider
    }
    if (relevantMirrors.isEmpty()) {
        playlistDao.clearLocallyModified(playlist.id)
    } else if (relevantMirrors.all { pid ->
        (syncPlaylistLinkDao.selectForLink(playlist.id, pid)?.syncedAt ?: 0)
            >= (playlist.lastModified ?: 0)
    }) {
        playlistDao.clearLocallyModified(playlist.id)
    }
}
```

### Phase 4 — Apple Music sync provider

> **Status:** ✅ Phase 4 fully landed in commits `cc33a61` → `2c56162` (AM provider + tests + Koin registration); Phase 4.5 push-loop generalization landed in the follow-on commit. `SyncEngine.syncPlaylists` now iterates `enabledProviders` from `SettingsStore.getEnabledSyncProviders()` and dispatches to `pushPlaylistsForProvider(provider, ...)`; the helper handles per-provider candidate filtering (Spotify pushes `local-*` + hosted; AM additionally pushes `spotify-*` since the Phase 3 `syncedFrom` guard correctly skips Spotify-imported playlists when targeting Spotify but not AM) and per-provider external-track-ID extraction (`trackSpotifyUri` for Spotify, `trackAppleMusicId` for AM — tracks without an AM ID are silently skipped pending Phase 5+ catalog-search). Spotify-specific scalars (`playlists.spotifyId`, `playlists.snapshotId`) are write-through only when Spotify is the push target; for any other provider, `sync_playlist_link` is the single source of truth (Decision 5). Default `enabledProviders` is `setOf("spotify")` — AM activates when the user opts in (currently via direct DataStore write; Phase 6 ships the wizard toggle).



Scope: per the existing sync plan in `.claude/plans/look-at-the-recent-stateless-backus.md`. Reuse its:
- `AppleMusicLibraryApi.kt` Retrofit interface (`api.music.apple.com/v1/me/library/*`)
- 150ms inter-request pacing + exponential backoff on 429
- Storefront detection via `GET /me/storefront`
- Catalog search-based track ID resolution
- `MusicKitWebBridge.authorize()` for initial MUT (already exists)
- `AppleMusicReauthRequiredException` on 401

Add to that plan:
- `DeleteResult.Unsupported` returned on 401-DELETE instead of throwing.
- `updatePlaylistDetails` (PATCH rename) wraps in try/catch and returns success-with-skipped on 401. **Load-bearing** — a throw here aborts the track push.
- Two session kill-switches (`amPutUnsupportedForSession`, `amPatchUnsupportedForSession`) — independent endpoints.
- `updatePlaylistTracks` tries PUT first; on 401/403/405 flip PUT kill-switch and degrade to POST-append; removals persist, documented.

### Phase 5 — Conflict + concurrency

> **Status:** ✅ Landed in commits `8b6cefd` → (Phase 5 wrap-up). The pull side now mirrors the push side from Phase 4.5: `pullPlaylistsForProvider(provider, ...)` extracts the per-provider import + removed-source cleanup logic. SyncEngine iterates non-Spotify enabled providers after the existing Spotify pull (the Spotify pull stays inline because it's coupled with the one-time dedup-cleanup migration). `pushPlaylist` and `pullPlaylist` helpers gained a `provider: SyncProvider` parameter (defaulted to spotifyProvider for backward-compat with existing import-branch call sites). Per-provider snapshot lookup reads from `sync_playlist_link.snapshotId` instead of the Spotify-only `playlists.snapshotId` scalar — Apple Music's `lastModifiedDate` ISO string compares the same way as Spotify's `snapshot_id`. Phase 4 (Fix 4) clear logic now passes `enabledProviders` (was hardcoded to spotify). End result: enabling AM via `enabled_sync_providers` DataStore now pulls AM library playlists into Parachord on every sync cycle, applies the cross-provider syncedFrom preservation guard, and pushes locally-modified playlists back. Concurrency contract (each provider its own merge oracle, no cross-provider locking) holds because the per-provider iteration is sequential in a single sync run.

**Concurrent multi-device edit:** there is no distributed locking and we're not building one. The contract:

- **Each provider is its own merge oracle for the playlists it hosts.** When two clients edit the same playlist simultaneously, both push to that provider; whichever request lands last wins; both clients converge on next pull from that provider. No app-side merge logic.
- **The user's "primary" provider is per-playlist, not per-account.** A playlist's pull source (`syncedFrom.resolver`) is the oracle for its content. Other mirrors (`syncedTo`) follow the source. There is no global "primary provider" tiebreaker.
- **Cross-provider divergence is possible and accepted.** If a user edits a playlist on Apple Music while a different client edits the same playlist on Spotify, neither provider knows about the other. The next time the same client syncs, whichever provider it pulls from "wins" for its local copy, then re-pushes that state to the other provider on its next push cycle. This is consistent with desktop and matches the no-cross-provider-coordination model.
- **Hosted XSPF is its own oracle.** XSPF URL content wins; every connected provider mirror is passive. Works whether the user has Spotify, Apple Music, Tidal, or any combination connected.

**Reconnect after a long gap:** snapshot diff does the work, with provider-specific semantics declared in `ProviderFeatures.snapshots`:

- `SnapshotKind.Opaque` (Spotify) — `getPlaylistSnapshotId()` returns the opaque token; string-compare against `syncedTo[pid].snapshotId`; mismatch ⇒ pull.
- `SnapshotKind.DateString` (Apple Music) — same compare, but the token is the `lastModifiedDate` ISO string. Apple updates this server-side on every edit, so a string-compare is sufficient.
- `SnapshotKind.None` (any future provider that lacks a snapshot endpoint) — always pull-and-diff. SyncEngine treats this as a synthetic mismatch every cycle. Costlier (1 extra API call per playlist per sync) but correct.

Push direction uses `lastModified > max(syncedTo[*].syncedAt over relevantMirrors)`. Identical across providers.

**Sync mutex is per-device only.** `tryLock` prevents two sync cycles on the *same* device from racing. There is no cross-device lock and we're not going to add one. Covered by scenarios 3, 9, 11 in Phase 0.

### Phase 6 — Settings, wizard, and picker

> **Status:** ⚠️ Minimum-viable Phase 6 landed in commit `(Phase 6 wrap-up)`. Settings → General now shows an "Apple Music Sync" section (only when AM is authorized — MUT present); the toggle wires to `enabled_sync_providers` in DataStore, which the multi-provider sync engine from Phases 4.5 + 5 reads on every cycle. Disconnecting Apple Music also drops it from the enabled set so the engine stops trying to call AM endpoints with a missing MUT. AM-side Apple-unsupported limitations (no delete, no rename, no track-removal) are surfaced inline as a static note under the toggle. **Deferred to Phase 6.5+:** the SyncSetupSheet wizard refactor (provider-selection step, per-provider playlist picker), the AM-deletion-unsupported toast/banner UI from Decision D8, and the per-provider sync-history UI. AM users today get an "all-or-nothing" sync experience — one toggle controls all AM library playlists.

- `SettingsStore.SyncSettings` gains `enabledProviders: Set<String>` and `selectedPlaylistIdsByProvider: Map<String, Set<String>>`.
- One-shot migration from old single-provider state — the legacy `selectedPlaylistIds` field becomes the `spotify` entry.
- `SyncSetupSheet` is **provider-driven**, not provider-coded. It iterates `Koin.getAll<SyncProvider>()` and renders one row per provider with its `displayName`, an availability indicator (configured / needs auth), and a checkbox. Adding Tidal later means registering a `TidalSyncProvider` in Koin — the wizard picks it up automatically with no UI changes.
- Per-provider playlist picker: for each enabled provider, run the picker. Pre-check seeds from union of saved selection + locals with `syncedFrom.resolver == providerId` + locals with `syncedTo[providerId].externalId`. Prevents push-only mirrors from appearing unchecked.
- Settings screen sync section is also provider-driven — one toggle row per registered `SyncProvider`. Tapping a disconnected provider triggers its auth flow (Spotify OAuth, `MusicKitWebBridge.authorize()` for AM, future Tidal OAuth, etc.). The `SyncProvider` interface gains a `suspend fun startConnection(activity: Activity): ConnectionResult` so each provider owns its own auth.
- Banners and toasts use `provider.displayName`, never hardcoded strings. A "Removals haven't synced to {displayName}" banner fires when the AM PUT kill-switch is set; if/when Tidal exhibits a similar limitation, it gets the same banner via the same `features.supportsTrackReplace = false` flag.
- AM-only / single-provider users see a single-provider wizard with no Spotify references, and vice versa for Spotify-only. Multi-provider users see the full picker. Zero special cases in code — entirely a function of which providers are registered + connected.

### Phase 7 — Tests

Before merging each phase, the scenario-matrix tests from Phase 0 must pass. Split:

- **Unit tests** (each phase):
  - `AppleMusicSyncProviderTest` — MockWebServer covering 401 on PATCH/PUT/DELETE, session kill-switch state, PUT → POST degradation, catalog search exact-match vs. first-result fallback.
  - `SyncEngineMultiProviderTest` — fake `SyncProvider` list with two providers; verify the four cooperating fixes hold under every scenario from Phase 0 (provider-scoped `syncedFrom` guard, `relevantMirrors` clear logic, pull-sets-`locallyModified` when other mirrors exist, local-mutator inline flag).
  - `SyncPlaylistSourceDaoTest` — the new `syncedFrom` table has correct insert/select/delete semantics.
- **End-to-end (manual + CI where feasible):** the ten scenarios from Phase 0, run on real hardware with real accounts after each phase.
- **Contract test against desktop:** pair with desktop instance; do a round-trip edit for a playlist synced to both providers; assert both apps converge on the same tracklist.

---

## Open design decisions (please weigh in)

1. **Apple Music sync scope for v1.** ✅ **DECIDED — B (playlists only).** v1 ships pull + push for Apple Music library playlists with the full PATCH/PUT/DELETE degradation pattern. Tracks/albums/artists library sync deferred to a follow-up. Rationale: playlists are the only surface where cross-provider round-trip correctness depends on AM behavior; library items are per-provider-independent and can ride on the existing `sync_source` rows later.
2. **Schema approach.** ✅ **DECIDED — relational (Option A from this list, "Option B" in Phase 1's write-up).** Extend `sync_playlist_link` with `snapshotId TEXT` and `pendingAction TEXT`; add a new `sync_playlist_source` table for `syncedFrom`; add `localOnly INTEGER NOT NULL DEFAULT 0` on `playlists`. Migrations are additive `ALTER TABLE … ADD COLUMN` wrapped in try/catch, same pattern as `sourceUrl`. Keeps the link map and the richer per-provider state in one representation — avoids the desktop-style "link map is the source of truth but playlist-object syncedTo must also stay in sync" split-brain risk.
3. **`localOnly` opt-out.** ✅ **DECIDED — port now (A).** Add the `localOnly INTEGER NOT NULL DEFAULT 0` column on `playlists` (already implied by Decision 2), honor it in the push loop with `if (playlist.localOnly) continue`, and add a toggle to the playlist detail overflow menu matching the "Delete Playlist" / "Rename" style.
4. **`pendingAction` per-provider.** ✅ **DECIDED — full port (A).** Add `pendingAction TEXT` to `sync_playlist_link`. On push 404, write `pendingAction = "remote-deleted"` instead of recreating. Push loop skips rows with non-null `pendingAction`. Playlist detail screen shows a banner "This playlist was deleted from {provider}. [Re-push] [Unlink]" that clears the action according to the user's choice (re-push clears and lets the next cycle recreate; unlink removes the `sync_playlist_link` row and optionally the `syncedFrom` if that provider was the pull source). Banner styled to match the existing "Update from Spotify · Pull" banner on `PlaylistDetailScreen`.
5. **Legacy `playlists.spotifyId` / `snapshotId` scalars.** ✅ **DECIDED — keep columns, auto-derive (B).** Columns stay on `PlaylistEntity` so existing UI / share code is untouched, but sync code stops writing them directly. A DAO-level accessor joins `playlists` + `sync_playlist_link WHERE providerId = 'spotify'` so reads remain stable while `sync_playlist_link` becomes the single source of truth for the Spotify link. Same pattern for `snapshotId`. No migration required; zero UI churn.
6. **Reconnect-after-gap snapshot comparison.** ✅ **DECIDED — declared semantics only (A).** Spotify's `Opaque` and Apple's `DateString` snapshots are sufficient for the providers we ship. No local content-hash column in v1. If a `SnapshotKind.None` provider ever joins (hypothetical Tidal without snapshot support, YouTube Music, etc.) we'll add the hash column at that time with concrete behavior to test against, rather than now on speculation.
7. **Cross-device locking.** ✅ **DECIDED — no cross-device lock (A).** Confirmed out of scope. `tryLock` prevents two sync cycles on the *same* device from racing; concurrent cross-device edits are arbitrated by whichever provider receives the writes. Losing edits are recoverable on next pull. Matches desktop behavior. No provider-arbitrated optimistic concurrency in v1 — asymmetric across providers and desktop doesn't do it.
8. **UI for Apple-Music-unsupported actions (delete / rename / track removal).** ✅ **DECIDED — C amended (hybrid).** Use whichever surface survives the action:
    - **Delete** — toast at the moment of deletion, since the local playlist no longer exists: *"Removed from Parachord and Spotify. Apple Music doesn't support deletion via the API — remove manually."* Toast action opens the Apple Music app via deep link (`music://…`).
    - **Rename / track changes** — banner on the playlist detail screen: *"Some changes haven't synced to Apple Music. [Open in Music]."* Matches the `pendingAction` banner pattern from Decision 4. Clears when the user taps the button.

    Both paths share the same deep-link CTA so users land on the right Apple Music playlist in a single tap.

---

## Out of scope

- Multi-provider loudness normalization.
- Per-track `pendingAction` (only per-playlist, per-provider).
- Two-phase `cleanupDuplicatePlaylists` orphan relink + link-aware keeper selection (desktop has it; Android doesn't need it yet).
- YouTube, SoundCloud, or local-files sync. Read-only.

## Execution

Once the open decisions are settled, each phase expands into bite-sized TDD tasks per the `superpowers:writing-plans` format. Phase 1 and Phase 3 are independently shippable; Phase 2/4 are gated on the provider abstraction. Recommended merge order: **Phase 1 → 2 → 3 → 4 → 5 → 6 → 7**.
