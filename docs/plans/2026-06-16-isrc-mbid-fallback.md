# ISRC-based MBID Fallback (service-abstracted)

**Goal:** When the ListenBrainz MBID mapper is unavailable (e.g. the `502`
outage observed 2026-06-14/15) or returns no confident match, resolve a track's
recording MBID from its **ISRC** via MusicBrainz `/ws/2/isrc/{isrc}` — so
streaming-link enrichment (Achordion submit on scrobble, #215) keeps working.

**Core principle:** the MBID code stays **service-agnostic**. It consumes an
`isrc` and never references Spotify/Apple/etc. The *supply* of the ISRC is
abstracted onto `ResolvedSource`, populated by whichever resolver produced the
source — including `.axe` plugins. Adding a new streaming resolver later =
populate `ResolvedSource.isrc` in that one resolver; nothing else changes.

**Decisions (confirmed):** eager capture at resolution (not lazy-on-fallback);
first cut wires native Spotify + native Apple Music + the `.axe` result
contract.

## Architecture — three layers

```
Resolver (native Spotify/Apple  |  .axe plugin)
  └─ ResolvedSource.isrc            ← SUPPLY (per-resolver, extensible)
       └─ Track.isrc                ← CARRY (merged onto the playing track)
            └─ getRecordingMbid(artist, title, isrc?)
                 └─ mapper-null? → MusicBrainzClient.lookupRecordingMbidByIsrc(isrc)
                                                       ← CONSUME (service-agnostic core)
```

ISRC → recording is **exact** (the ISRC is the specific recording the service is
streaming), so there is no fuzzy wrong-variant risk that a plain MusicBrainz
title/artist search would carry.

## Tasks

### Task 1 — `ResolvedSource.isrc` (supply field)
- Modify: `shared/.../resolver/ResolverModels.kt` — add `val isrc: String? = null`.

### Task 2 — MusicBrainz core lookup
- Modify: `shared/.../api/MusicBrainzClient.kt`
  - `suspend fun lookupRecordingMbidByIsrc(isrc: String): String?` →
    `guardedGet<MbIsrcResponse>("$BASE_URL/isrc/$isrc") {}` (guardedGet adds
    `fmt=json` + the MB rate-limit gate + User-Agent). Return
    `recordings.firstOrNull()?.id`. Swallow + log on error → null.
  - Add `@Serializable data class MbIsrcResponse(val recordings: List<MbIsrcRecording> = emptyList())`
    and `MbIsrcRecording(val id: String? = null, val title: String? = null)`.

### Task 3 — native Spotify supplies ISRC
- Modify: `shared/.../api/SpotifyClient.kt`
  - `SpTrack`: add `@SerialName("external_ids") val externalIds: SpExternalIds? = null`.
  - Add `@Serializable data class SpExternalIds(val isrc: String? = null)`.
  - `searchTrack(...)`: set `isrc = track.externalIds?.isrc?.takeIf { it.isNotBlank() }`
    on the returned `ResolvedSource` (search returns full track objects → ISRC
    is already in the response, no extra call).

### Task 4 — native Apple Music supplies ISRC
- Modify: `app/.../resolver/AndroidResolverRuntime.kt` (Apple Music branches) +
  `shared/iosMain/.../IosResolverRuntime.kt` — map `attributes.isrc` →
  `ResolvedSource.isrc` where the catalog response carries it (MusicKit catalog
  does; the iTunes-Search Tier-2 path does not → leave null there).

### Task 5 — `.axe` result contract supplies ISRC
- Modify: each platform's `ResolverRuntime.resolveAxe` (Android
  `AndroidResolverRuntime`, iOS `IosResolverRuntime`) — read optional `isrc`
  from the `.axe` resolver result JSON → `ResolvedSource.isrc`.
- Document the contract: a resolver result MAY include a string `isrc`. New
  `.axe` streaming resolvers get ISRC support for free by returning it.

### Task 6 — `Track.isrc` (carry field) + merge
- Modify: `shared/.../model/Track.kt` — add `val isrc: String? = null`.
- Modify carry points to fill it from the best resolved source that has one:
  - iOS `IosContainer.trackWithResolvedSources` — add `isrc = track.isrc ?: pick { it.isrc }`.
  - Android `PlaybackController` (the path that attaches resolved IDs to the
    routed/now-playing track) — set `isrc` from the selected source.

### Task 7 — MBID fallback (consume)
- Modify: `shared/.../metadata/MbidEnrichmentService.kt`
  - Inject nullable `musicBrainzClient: MusicBrainzClient? = null`.
  - `getRecordingMbid(artistName, recordingName, isrc: String? = null)`:
    cache → mapper; **on mapper-null**, if `isrc != null` →
    `musicBrainzClient?.lookupRecordingMbidByIsrc(isrc)`. Return it.
  - **Do NOT cache the ISRC-only result** — it lacks canonical names /
    artist+release MBIDs, and caching it (90-day TTL) would block the richer
    mapper entry once the mapper recovers. The scrobble submit only needs the
    bare MBID for *this* play; Achordion dedups by MBID per session.

### Task 8 — thread `isrc` from the scrobble paths
- Modify: `shared/.../playback/scrobbler/ScrobbleManager.kt` (`refreshTrackMbids`)
  and `ListenBrainzScrobbler.kt` — pass `track.isrc` to `getRecordingMbid`.
- `NativeBridge.resolveMbidForLove` + tests keep the default (no isrc) — no change.

### Task 9 — DI wiring
- Modify: `app/.../di/AndroidModule.kt` + `shared/iosMain/.../IosContainer.kt` —
  pass `musicBrainzClient` into `MbidEnrichmentService`. (Both already construct
  a `MusicBrainzClient` singleton.)

### Task 10 — regression test
- Add to `app/.../metadata/MbidEnrichmentServiceTest.kt`: mapper returns null +
  `isrc` provided + mocked `MusicBrainzClient.lookupRecordingMbidByIsrc` → assert
  `getRecordingMbid(artist, title, isrc)` returns the ISRC-resolved MBID.

### Task 11 — build both platforms
- `:shared` iOS + Android compile green; relevant unit tests pass.

## Out of scope / follow-ups
- Apple-Music-only tracks resolved via the no-auth iTunes Search path (no ISRC
  in that response) won't get the fallback until the MusicKit-catalog path is used.
- SoundCloud / localfiles have no ISRC (expected null).
- Desktop parity: file a ticket to add the same ISRC fallback to
  `parachord-desktop` if it doesn't already have one (it currently uses the LB
  mapper only, per the mobile CLAUDE.md MBID section).
