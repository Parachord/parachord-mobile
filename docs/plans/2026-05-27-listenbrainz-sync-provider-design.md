# ListenBrainzSyncProvider — design

Date: 2026-05-27
Closes (eventually): #156. Unblocks #145 (Achordion playlist-links submit).

## Scope

A third `SyncProvider` implementation — alongside `SpotifySyncProvider` and `AppleMusicSyncProvider` — that two-way-syncs **playlists** between Parachord and ListenBrainz. Loved tracks stay on the existing `LovesPushService` path (no migration). Artist follow + album collection are deferred (V2).

## Architecture

### Schema change

LB keys playlists by MBID and tracks-within-playlist by `recording_mbid`. `PlaylistTrack` today stores `trackSpotifyId` / `trackAppleMusicId` / `trackSoundcloudId` but not `trackRecordingMbid`. Same pattern as the previous provider additions: add a new column, ALTER TABLE wrapped in try/catch in the SQLDelight bind path (precedent: `SyncPlaylistLink.sq`'s in-bind CREATE).

| Change | File |
|---|---|
| Add `trackRecordingMbid TEXT` column | `shared/src/commonMain/sqldelight/com/parachord/shared/db/PlaylistTrack.sq` |
| ALTER TABLE wrapped in try/catch | `app/.../di/AndroidModule.kt` bindDatabase block |
| `trackRecordingMbid` field on the model | `shared/.../model/PlaylistTrack.kt` |
| Read/write in DAO | `shared/.../db/dao/PlaylistTrackDao.kt` |

Backfill: organic via the existing `MbidEnrichmentService`. Tracks that lack `trackRecordingMbid` at push time fall through to `searchForTrackId`, which delegates to `MbidEnrichmentService.mapperLookup` and persists the result back via `applyResolvedId`.

### ListenBrainzClient mutations

Today read-only for playlists. Add five mutation endpoints + one read:

```kotlin
suspend fun createPlaylist(name, description?, isPublic = true, token): String  // returns playlist_mbid
suspend fun editPlaylist(playlistMbid, name?, description?, token)
suspend fun addPlaylistItems(playlistMbid, recordingMbids, token)
suspend fun deletePlaylistItems(playlistMbid, index, count, token)  // for full-replace
suspend fun deletePlaylist(playlistMbid, token)
suspend fun getUserOwnedPlaylists(username): List<LbPlaylist>  // augments existing getCreatedForPlaylists
```

Auth: `Authorization: Token <user_token>` from `SettingsStore.getListenBrainzToken()`. Mutations wrap `executeWithRetry` for rate-limit handling. New exception `ListenBrainzUnauthorizedException` (mirrors `AppleMusicReauthRequiredException`) thrown on 401.

### ListenBrainzSyncProvider impl

New file: `shared/.../sync/ListenBrainzSyncProvider.kt`. Mirrors `AppleMusicSyncProvider`'s shape (session-scoped `authFailedForSession` kill-switch, graceful no-op handling).

```kotlin
override val features = ProviderFeatures(
    snapshots = SnapshotKind.DateString,         // LB returns last_modified_at ISO
    supportsFollow = false,                       // V2
    supportsPlaylistDelete = true,                // DELETE /1/playlist/{mbid}
    supportsPlaylistRename = true,                // POST /1/playlist/edit/{mbid}
    supportsTrackReplace = true,                  // delete-all + add-all
)
```

Key methods:
- `fetchPlaylists` → `getUserOwnedPlaylists(username)` → map to `SyncedPlaylist`
- `fetchPlaylistTracks` → existing `getPlaylistTracksRich` → map to `PlaylistTrack`
- `getPlaylistSnapshotId` → `getPlaylistLastModified(mbid)` (small helper, ISO string)
- `createPlaylist` → `client.createPlaylist` → `RemoteCreated(mbid, snapshotId=null)` (snapshot re-fetched separately)
- `replacePlaylistTracks` → get current count → `deletePlaylistItems(mbid, 0, count)` → `addPlaylistItems(mbid, new)` → return new `lastModified`. Not atomic, but the two API calls are usually <100ms apart and the playlist isn't user-facing during sync.
- `updatePlaylistDetails` → `editPlaylist`
- `deletePlaylist` → try `client.deletePlaylist` → success / 401 → `Unsupported(401)` (kill-switch trips) / other → `Failed(error)`
- `searchForTrackId(title, artist, album?)` → `mbidEnrichmentService.mapperLookup(title, artist)?.recordingMbid` — handles the catalog-search hydration path for tracks lacking the MBID column

Library surface (`fetchTracks` / `fetchAlbums` / `fetchArtists` / `save*` / `follow*`) inherits no-op defaults.

### SyncEngine wiring

Three helpers in `SyncEngine.kt` need an LB branch:

```kotlin
extractExternalTrackIds: tracks.mapNotNull { it.trackRecordingMbid }
missingProviderId: track.trackRecordingMbid.isNullOrBlank()
applyResolvedId: track.copy(trackRecordingMbid = resolvedId)
```

Per-provider push candidate filter `isPushCandidate(playlist, providerId)`:
- LB: `local-*` + hosted XSPF + `spotify-*` + `applemusic-*` (LB mirrors any source-of-truth)

`healImportedSyncedFromMismatch`: extend the recognized ID-prefix list to include `listenbrainz-*`. One-liner. Restores the cross-platform invariant: a sync corruption on either Desktop or Android self-heals on the other client's next launch.

### DI + settings

Single Koin binding in `sharedModule` + injection into SyncEngine's provider list:

```kotlin
single { ListenBrainzSyncProvider(client = get(), settingsStore = get(), mbidEnrichmentService = get()) }
single<SyncEngine> {
    SyncEngine(
        providers = listOf(get<SpotifySyncProvider>(), get<AppleMusicSyncProvider>(), get<ListenBrainzSyncProvider>()),
        ...
    )
}
```

`SettingsStore` — add `"listenbrainz"` to `getEnabledSyncProviders()` allowed values (opt-in default-off). Add `getListenBrainzUsername()` if not present.

Settings UI — `Settings → General` shows a "ListenBrainz Sync" toggle row when LB is authorized (token validated). Note: *"Pushes Parachord-curated playlists to your ListenBrainz profile. Loved tracks already sync separately via scrobblers."*

## Multi-client convergence (Desktop ↔ LB ↔ Android)

The existing multi-provider sync engine is provider-agnostic — adding LB as a third provider inherits the convergence guarantees of Spotify/AM, **provided** it plugs into the same three-layer dedup pipeline.

**Local ID convention**:
- `listenbrainz-<mbid>` for playlists pulled FROM LB
- `local-<uuid>` for playlists created in Parachord (id stays `local-*` even after pushing to LB and getting assigned an MBID — the MBID lives in `sync_playlist_link["listenbrainz"].externalId`, not in the row's id)

**Three-layer dedup runs on every pull**:
1. `sync_playlist_link[localId]["listenbrainz"]` match → reuse local row
2. Defensive name-match (case-insensitive, owned-only) → reuse
3. Else create new `listenbrainz-<mbid>` row

Catches the "user creates same-name playlist on Desktop AND Android while offline" case — second client to sync finds a name match and links to the existing remote rather than duplicating.

**Cross-provider preservation**. A Spotify-imported playlist (`sync_source.providerId = "spotify"`) that also has LB sync enabled mirrors to both providers. The `isCrossProviderPushMirror` guard handles this with no LB-specific changes; LB-only entry in `sync_playlist_link`.

**Heal extension** — `healImportedSyncedFromMismatch`'s ID-prefix list extends to `listenbrainz-*`. One-liner.

**Snapshot churn.** LB updates `last_modified_at` on every mutation. Worst case: extra pull cycle. Three-layer dedup makes the worst case "extra pull", never "duplicate row".

**Token-failure isolation.** Per-provider `authFailedForSession` kill-switch trips on 401; LB pushes skip for the session while Spotify/AM continue. UI surfaces a re-auth banner on next foreground.

**Convergence smoke test** (manual, post-merge):
1. Desktop: create "Cross-Sync Test" with 5 tracks. Push to LB.
2. Android: sync. Expect `listenbrainz-<mbid>` local row with same name + 5 tracks.
3. Android: add 2 tracks → push to LB.
4. Desktop: re-sync. Expect 7 tracks.
5. Desktop: rename → push.
6. Android: re-sync. Expect rename to propagate (no duplicate).
7. Android: delete.
8. Desktop: re-sync. Expect playlist removed from local.

## Tests

`app/src/test/.../sync/ListenBrainzSyncProviderTest.kt` (Robolectric, mockk):

| Test | What it pins |
|---|---|
| `fetchPlaylists returns empty when token unset` | Auth gate |
| `fetchPlaylists swallows 401 by tripping auth-failed kill-switch` | Mirrors AM's pattern |
| `createPlaylist returns server-assigned MBID` | Round-trip with mock client |
| `replacePlaylistTracks does delete-all + add-all` | Verify two-step replace |
| `replacePlaylistTracks no-ops when current and desired both empty` | Skip wasted API calls |
| `deletePlaylist 401 → DeleteResult.Unsupported (not thrown)` | `SyncProvider` contract |
| `searchForTrackId returns null for low-confidence mapper hits` | Confidence floor per interface contract |
| `features struct matches LB's capability surface` | Locks `supportsTrackReplace=true` etc. |

Plus 2 `SyncEngine` tests for the LB branches of `extractExternalTrackIds` / `applyResolvedId`.

## Risks

- **Mapper rate-limit on first sync of a large library.** Even with cache, the first push of a 200-track playlist could hit the mapper 200 times. LB mapper has lax rate limits but worth flagging.
- **LB API stability.** LB playlists are community-supported; not as battle-tested as Spotify/AM. Expect occasional 5xx; degrade gracefully via try-catch in mutations.
- **Snapshot pacing.** `last_modified_at` updates on every mutation. Three-layer dedup absorbs the noise.
- **The `playlist_tracks` ALTER TABLE** runs in `bindDatabase` wrapped in try/catch. Existing installs pick up the new column on app start. New installs get it from the SQLDelight schema.

## Out of scope (V2 candidates)

- Track loves push (already in `LovesPushService`)
- Artist follow (`POST /1/user/{name}/follow`)
- LB-imported-playlist support in `PlaylistImportManager` for `jspf://` URLs
- "Promote local playlist to LB" wizard UX

## File changes

- `shared/src/commonMain/sqldelight/com/parachord/shared/db/PlaylistTrack.sq` — add column
- `app/.../di/AndroidModule.kt` — ALTER TABLE in bindDatabase + new Koin binding + SyncEngine providers list
- `shared/.../model/PlaylistTrack.kt` — `trackRecordingMbid` field
- `shared/.../db/dao/PlaylistTrackDao.kt` — read/write column
- `shared/.../api/ListenBrainzClient.kt` — 5 mutation methods + `getUserOwnedPlaylists` + new exception
- `shared/.../sync/ListenBrainzSyncProvider.kt` (new) — provider impl
- `shared/.../sync/SyncEngine.kt` — LB branches in 3 helpers, push-candidate filter, heal extension
- `shared/.../settings/SettingsStore.kt` — `listenbrainz` in enabled-providers + `getListenBrainzUsername`
- `app/.../ui/screens/settings/SettingsScreen.kt` — "ListenBrainz Sync" toggle row
- `app/src/test/.../sync/ListenBrainzSyncProviderTest.kt` (new)

## Acceptance

- [ ] `./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest` green
- [ ] Settings → General shows "ListenBrainz Sync" toggle when LB is authorized
- [ ] Toggle on → next sync pushes local playlists to LB → playlists appear in user's LB profile
- [ ] Rename local playlist → propagates to LB
- [ ] Add/remove tracks locally → reflects on LB
- [ ] Delete local playlist → removed from LB
- [ ] No regression: Spotify + Apple Music sync paths still work
- [ ] Convergence smoke test (8 steps above) passes on Desktop ↔ LB ↔ Android
