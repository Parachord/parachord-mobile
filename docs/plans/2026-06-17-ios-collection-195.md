# iOS Local Collection (#195) — Implementation Plan

**Goal:** Bring the saved **collection** (tracks / albums / artists) + the **Library** screen to iOS, backed by the SQLDelight DB that's already wired in `IosContainer`.

**Source of truth:** Android `LibraryScreen.kt` / `LibraryViewModel.kt` + shared `LibraryRepository.kt`. Per `iosApp/CLAUDE.md`: research the design (`docs/design/parachord-ios/`) + Android first; Android wins conflicts.

## Current state (already done)
- `IosContainer` wires `sqlDriver → ParachordDb → {track,album,artist,playlist,playlistTrack}Dao` (lines 223–229). The DB works on iOS.
- DAOs expose reactive `Flow` reads (`getAll()`, `search()`, `getByAlbumId()`) + insert/delete + the `isXInCollection(...)` flows.

## The key decision: decouple #195 from #194 (sync)
The shared `LibraryRepository` ctor requires `SyncEngine` + `SyncPlaylistLink/SourceDao` + `TrackTombstoneService`, and its `deleteTrackWithSync` / `deletePlaylistWithSync` / playlist-push call into sync. Standing up `SyncEngine` is **#194** (large).

**Decision: do NOT construct the full `LibraryRepository` on iOS yet.** Add a lean DAO-backed collection facade on `IosContainer` that mirrors the *non-sync* slice of `LibraryRepository`:
- **Reads:** delegate straight to DAO `Flow`s.
- **Add:** DAO upsert + clear tombstone (no-op until #194) + MBID enrich (lambda, already available) + loves-push (deferred to #226).
- **Remove:** **local DAO delete only** for now. Sync-propagation (`onTrackRemoved` → remote remove + tombstone) gets wired into this facade when **#194** lands. Document this as a known gap so a removed track can re-import on a future sync until #194 ships.

This keeps #195 shippable independently and avoids a premature half-`SyncEngine`. The facade is the seam #194 plugs into later.

## Scope (this ticket) vs follow-ons
- **In #195:** collection data facade + reactive in-collection state; **Library screen** with **Songs / Albums / Artists** tabs (grids/list, sort, resolver badges via existing `ResolverBadgeRow`, collection-toggle); collection add/remove from existing screens' context menus (ties to #204).
- **Deferred (separate tickets):** **Friends** tab (needs friend data; #196), **local Search** results + history (#195-adjacent but its own slice), **Playlists list/edit** (own slice), sync-propagation on remove (#194), resolver-ID/MBID **backfill onto rows** (own follow-on), local-file collection (#219).

## Milestones / tasks

### M1 — Collection data facade (`IosContainer`)
- Add Swift-facing methods over the DAOs: `collectionTracks()/Albums()/Artists()` (Flow → `FlowWatcher`), `addTrackToCollection(track)`, `removeTrackFromCollection(track)`, album/artist equivalents, and `isTrackInCollection(title,artist)` / `isAlbumInCollection` / `isArtistInCollection` (Flow<Boolean>).
- Add via DAO calls + the existing `mbidEnrichTrack` lambda; remove = DAO delete (note the #194 gap in a comment).
- Build: `:shared:compileKotlinIosSimulatorArm64` green.

### M2 — Swift `CollectionViewModel` (`@Observable`)
- Subscribes to the facade Flows via `FlowWatcher`; exposes `tracks/albums/artists` + per-tab sort state; `toggleCollection(...)` writes through.
- Mirror Android `LibraryViewModel`'s sort options + ordering.

### M3 — Library screen (SwiftUI)
- Tabs: Songs (list + resolver badges + context menu), Albums (2-col grid), Artists (grid of circles). Sort control per tab. Empty states.
- Register in `RootView` (4 pbxproj entries per the iosApp guide). Match the design file + Android structure.

### M4 — Collection toggles from existing screens
- Wire add/remove-from-collection into Album/Artist screens + relevant cards (album/artist context menus, ties to #204) using the reactive `isXInCollection` flows.

### M5 — Verify
- Headless iOS build SUCCEEDED + zero new warnings; on-device: add/remove a track/album/artist, confirm it appears/disappears in Library and the toggle state is reactive across screens.

## Risks / notes
- **Don't** construct `SyncEngine` here. If a method needs it, stop and reconsider scope.
- iOS list resolution must stay visibility-scoped (`IosTrackResolverCache.resolve(req, order:)`) — don't bulk-resolve the whole collection (rate-limit rule, iosApp/CLAUDE.md).
- Big `Track(...)` initializers → use `makeTrack` helpers (Swift type-checker).
