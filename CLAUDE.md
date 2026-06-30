# Parachord Android — Development Guide

## Core Principle

**Always match the desktop app's approach.** The desktop Parachord app (Electron + React 18 + Tailwind) at https://github.com/Parachord/parachord is the source of truth. Before implementing any feature, check how the desktop app does it first. We spent a lot of time refining those approaches and should not reinvent anything.

**When told a feature should "work like desktop":** Do not make assumptions or do a partial implementation. Study the desktop implementation in detail first — read the relevant code in `app.js`, understand the data flow, the UI, the settings, and the edge cases. Ask follow-up questions if anything is unclear. The goal is feature parity, not a rough approximation. Half-implementations that miss settings, toggles, or data sources the desktop includes are worse than no implementation.

Desktop app structure: single `app.js` (~57,700 lines), `.axe` resolver plugins, `index.html` with CSS design tokens.

## Architecture Patterns (from Desktop)

### Metadata Providers — Cascading Lookup

The desktop uses a cascading provider pattern. Each provider has a specialty:

| Provider | Priority | Specialty | Auth Required |
|----------|----------|-----------|---------------|
| MusicBrainz | 0 (first) | MBIDs, structured data, **discography, tracklists** | No |
| Last.fm | 10 | Images, bios, similar artists, tags | API key only |
| Spotify | 20 (last) | Album art, preview URLs, Spotify IDs | OAuth token |

**MusicBrainz is the primary source for discography and album tracklists.** Last.fm and Spotify supplement with images and additional metadata. Results from all providers are merged — later providers fill in gaps from earlier ones.

### Resolver Scoring — Two-Tier System

Source selection uses priority-first, confidence-second sorting:

1. **Minimum confidence filter** — sources below `MIN_CONFIDENCE_THRESHOLD` (0.60) are discarded. `scoreConfidence()` returns 0.95 only when BOTH title and artist substring-match the target; single-axis matches (wrong artist, or wrong title) collapse to 0.50 and get filtered. This is the equivalent of desktop's `validateResolvedTrack` gate (`app.js:14257-14267`) — desktop never adds a single-axis match to `track.sources`, so it never reaches the priority sort. Without this gate, a wrong-song Apple Music result at 0.85 (title matched, artist didn't) would outrank a correct local file at 0.95 because applemusic sits above localfiles in the priority order and confidence is only a tiebreaker WITHIN the same priority tier.
2. **Resolver priority** — user-configurable ordering (lower index = higher priority)
3. **Confidence score** — tiebreaker within same priority (0.0–1.0, default 0.9)

A Spotify result at 70% confidence beats a SoundCloud result at 95% when Spotify is ranked higher. But a Spotify result at 50% confidence (no match) is filtered out entirely, allowing the higher-confidence SoundCloud or local file result to win.

**Canonical resolver order (default):**
```
spotify > applemusic > bandcamp > soundcloud > localfiles > youtube
```

### Resolver Coordinator — Shared Orchestration (#210)

Resolver *orchestration* is one `commonMain` class, `ResolverCoordinator` (`shared/.../resolver/ResolverCoordinator.kt`) — both platforms inherit a single fan-out / gating / re-score / ranking path so a resolution-logic change lives in ONE place.

**What the coordinator owns:** the parallel fan-out, the native **Spotify** branch (an injected `suspend (query) -> ResolvedSource?` lambda — the platform binding closes over the Spotify token-gate + `SpotifyClient.searchTrack`), gating (via the shared `ResolverGating` helpers), the re-score (`scoreConfidence` when both target fields are present), and ranking. It exposes three entry points:
- `resolveScored(query, targetTitle?, targetArtist?, album?, excludeResolvers)` — fan out + re-score, **unranked / unfloored**. Android's `resolve()` delegates here (the 0.60 floor + priority sort run downstream via `ResolverScoring.selectBest`).
- `resolveRanked(query, …, preferredResolver?)` — `selectRanked(resolveScored(...))`: drops sub-floor (< 0.60) sources, orders by priority. iOS's `resolveSources(artist, title, album)` delegates here.
- `resolveSingle(resolverId, query, targetTitle?, targetArtist?, album?)` — additive re-resolution of ONE resolver (used when the user enables a resolver after a track was cached). Dispatches: `spotify` → the lambda; an id in `runtime.nativeResolverIds` → `resolveNative`; otherwise, ONLY if a resolve-capable plugin with that id is loaded → `resolveAxe`, else null. Re-scored, returned only if `!noMatch && confidence >= 0.60`.

**`ResolverRuntime` interface** (`shared/.../resolver/ResolverRuntime.kt`) — the per-platform native non-Spotify resolvers + `.axe` execution that genuinely can't be shared:
- **Android** `AndroidResolverRuntime` (`app/.../resolver/AndroidResolverRuntime.kt`): Apple Music (MusicKit Tier 1 + iTunes Tier 2), SoundCloud, local files; `.axe` via `PluginManager.resolve`. `nativeResolverIds = {applemusic, soundcloud, localfiles}`.
- **iOS** `IosResolverRuntime` (`shared/iosMain/.../IosResolverRuntime.kt`): Apple Music via catalog HTTP; `.axe` via the JavaScriptCore unique-key polling workaround. `nativeResolverIds = {applemusic}`.

Android's `ResolverManager` keeps its richer `resolveWithHints` wrapper (Spotify ID verify + 429-keep-the-badge fallback + cascade-artwork merge + `excludeResolvers`) **on top of** the coordinator — it calls `coordinator.resolveScored(..., excludeResolvers)`. iOS's `IosResolverCoordinator` is now a thin delegate (just `pluginManager.ensureInitialized()` + forward to `resolveRanked` / `resolveSingle`).

**Native-gating divergence (behavior-preserving, D3).** The coordinator's native gate is `(active.isEmpty() || id in active) && id !in exclude`. Android never populates `active_resolvers` (gates natives on connection-state, so `active.isEmpty()` is always true → exclude-only), while iOS + desktop seed `active_resolvers` to all ids and remove-on-disable, so `id in active` is the real gate there. The unified gate preserves both. Converging Android onto `active_resolvers` (Finding 1) is a tracked follow-up — **OUT OF SCOPE** for #210 (it changes user-visible Android settings behavior).

### Per-Resolver Volume Offsets (dB)

```
spotify:     0    applemusic:  0    localfiles:  0
soundcloud:  0    bandcamp:   -3    youtube:    -6
```

### Playback Routing

Each resolver type routes to a specific playback mechanism:

- **Spotify** → Web API (Spotify Connect, controls Spotify app on-device via HTTP)
- **SoundCloud** → Fetch stream URL from API, play inline via ExoPlayer (no CORS issues on Android unlike desktop)
- **Local files** → ExoPlayer with content:// URI
- **Direct streams** → ExoPlayer with HTTP URL

The desktop plays SoundCloud natively via HTML5 Audio (not externally). The Android equivalent is ExoPlayer.

### Spotify Connect — Device Wake & Playback

Spotify playback uses the **Web API** (not the App Remote SDK). The `SpotifyPlaybackHandler` manages device discovery, wake, and playback via Spotify Connect HTTP endpoints.

**Device wake strategy (two-tier):**
1. **Media button broadcast (invisible):** Sends `KEYCODE_MEDIA_PLAY` targeted to `com.spotify.music`. Wakes Spotify's `MediaBrowserService` without showing any UI. Works when Spotify is in the background but **does NOT work when Spotify is fully killed** (no process to receive the broadcast).
2. **Launch intent (fallback after 2s):** Uses `getLaunchIntentForPackage` to start Spotify's activity. This brings Spotify briefly to the foreground (jarring), so immediately after launching, we bring our own app back to front. Only used when the broadcast doesn't wake Spotify within 2 seconds of polling.

**Critical: never use the launch intent as the primary wake strategy.** It opens Spotify visually, which is terrible UX for a music player controlling another music player. Always try the invisible broadcast first.

**Device polling:** 300ms intervals (not 1s — the devices endpoint is lightweight), 8s total timeout, 500ms initial delay before first poll.

**Single-pass play flow (no compounding retries):**
1. `ensureDevice()` — fast-path check, then wake + poll if needed (0-8s)
2. `pickDevice()` — accepts inactive preferred devices (transfer will activate), auto-selects single real device
3. `startPlayback()` with `device_id` — single retry on 502 after 1s
4. Quick verification on cold devices only (2 polls × 500ms)

**Warm-path optimization:** When `deviceVerified && preferredDeviceId != null`, fires `startPlayback` directly without any device fetch (~200ms).

**"This device" resolution:** When the user picks the synthetic local device, `resolveLocalDevice()` wakes Spotify (broadcast → fallback launch intent) and polls for a Smartphone-type device matching `Build.MODEL` or `Build.MANUFACTURER`. Remote devices (TVs, computers) appear in the API even when local Spotify isn't running — `deviceVerified` being true does NOT mean Spotify is running locally.

**Spotify API device visibility limitations:** `GET /v1/me/player/devices` only returns devices with active Spotify Connect sessions, not all devices on the network. It also returns stale/phantom devices (e.g., a "Bedroom TV" that's been off for days). The desktop app may see more devices via native SDK discovery. Restricted devices (Spotify Free) are filtered from the picker.

**Key files:** `SpotifyPlaybackHandler.kt`, `SpotifyApi.kt`, `SpotifyDevicePickerDialog.kt`

### Apple Music State Polling & Auto-Advance

Apple Music playback runs in a hidden WebView via `MusicKitWebBridge`. The `startAppleMusicStatePolling()` loop polls at 500ms intervals on `Dispatchers.Default` for position/duration updates and uses two auto-advance detection paths:

1. **JS callback (primary):** MusicKit JS fires `playbackStateDidChange` → "ended" state → `onTrackEnded` callback → `skipNextInternal()`
2. **Polling safety net:** `isOurTrackDone()` checks `!isPlaying && position > 0 && duration - position < 1500`

An `AtomicBoolean(trackEndHandled)` prevents both paths from double-firing.

**Critical rule: never suspend the polling loop on network calls.** The `MusicKitWebBridge.evaluate()` function awaits JS async results via `CompletableDeferred`. If a slow operation runs inline in the polling loop, the entire loop freezes — no position updates, no safety-net checks, no stall recovery. Even though JS callbacks can fire independently, they may be delayed or suppressed when MusicKit is handling concurrent API requests in the same WebView context. Always use `scope.launch` (fire-and-forget) for non-essential async work within the polling loop.

**Critical rule: never call `preload()` during active playback.** The `preload()` function fires a `music.api.music()` catalog API request through the same MusicKit JS instance that's actively streaming. On Android WebView, this concurrent API call disrupts the active playback stream — observed as MusicKit flipping to `state:"unknown"/isPlaying:false` mid-song, causing the track to stop. The polling loop pre-resolves the next track's Apple Music ID (via `reselectBestSource`) but does NOT call `preload()`. The `setQueue()` call in `play()` is fast enough with a warm WebView.

**Critical rule: never run end-detection on stale poll data.** When the screen is off, `pollPlaybackState()` can time out (Main thread deferred in Doze mode), leaving `_isPlaying` and `playbackStateName` stale in `MusicKitWebBridge`. If the last cached `isPlaying` was `false` (from a transient buffering hiccup), `isOurTrackDone()` would falsely trigger, stopping the song mid-play. The polling loop tracks `pollSucceeded` and only runs end-detection when the poll returned fresh data.

**Pre-resolving next track:** The polling loop resolves the next Apple Music track's ID 30s before the current track ends. This runs as a fire-and-forget `scope.launch(Dispatchers.Main)` — NOT inline. Only the ID is resolved; no `preload()` API call is made (see rule above).

### .axe Plugin System — Full Integration

The Android app runs the **same .axe plugin system as desktop**. 19 plugins loaded via `JsBridge` (headless WebView) executing `resolver-loader.js` unchanged from the Electron app. The `PluginManager` handles loading, semver deduplication, hot-reload, and marketplace sync from the `parachord-plugins` GitHub repo.

**Architecture:**
- `JsRuntime` interface (`plugin/JsRuntime.kt`) — platform-agnostic JS execution abstraction. Android: `JsBridge` (WebView). iOS future: JavaScriptCore. KMP-ready.
- `PluginManager` — loads .axe files from `assets/plugins/` (bundled) + `filesDir/plugins/` (downloaded updates). Deduplicates by semver (higher version wins). Provides type-safe Kotlin methods for resolve/search/AI/scrobble calls.
- `NativeBridge` — polyfills `fetch` (async, non-blocking), `console`, and `storage` (backed by DataStore) for the JS runtime.
- `PluginSyncService` — fetches `manifest.json` from GitHub, downloads updated .axe files, calls `PluginManager.reloadPlugins()` for hot-reload. Runs on app start (24h debounce) + manual "Check for updates" in Settings.

**Plugin types loaded:**
- **Content resolvers** (stream: true): spotify, applemusic, soundcloud, localfiles — native Kotlin takes priority (faster), .axe as fallback
- **Content resolvers** (stream: false): bandcamp — resolves tracks but opens browser for playback (matches desktop behavior)
- **AI providers**: chatgpt, gemini, claude — native Kotlin handles these, `AxeAiProvider` wrapper available for .axe-only providers (ollama)
- **Meta-services**: discogs, wikipedia, lastfm, listenbrainz, librefm — native Kotlin implementations, `AxeScrobbler` wrapper ready for migration
- **Concert services**: ticketmaster, seatgeek, bandsintown, songkick — API key config in Settings
- **Filtered out on mobile**: youtube (consent redirect blocks Chromium renderer), ollama (needs local server) — via `capabilities.mobile: false`

**Native-first resolver strategy:** For spotify/applemusic/soundcloud/localfiles, the native Kotlin resolver always runs first (faster, no JS bridge overhead). .axe resolvers (bandcamp, future resolvers) fill gaps for sources with no native implementation. Both run in parallel via `ResolverManager.resolve()`.

**Key files:** `plugin/JsRuntime.kt`, `plugin/PluginManager.kt`, `plugin/PluginSyncService.kt`, `bridge/JsBridge.kt`, `bridge/NativeBridge.kt`, `ai/providers/AxeAiProvider.kt`, `playback/scrobbler/AxeScrobbler.kt`

### Extensibility principle — abstract over the plugin registry, don't hardwire services

**Default to the plugin/resolver capabilities as the abstraction; never hardwire a
specific service's hosts or logic into a feature.** When a feature needs "given a
provider URL/entity, do X" (resolve a track, fetch a playlist, list models, scrobble),
route it through the resolver-loader's capability surface — `findResolverForUrl(url)`,
`getUrlType(url)`, `resolve`, `lookupUrl`/`lookupAlbum`/`lookupPlaylist`, `listModels`
— so **whichever `.axe` declares matching `urlPatterns` + implements that capability
handles it. Adding a new streaming service should be a plugin drop-in (one `.axe` with
`urlPatterns` + the capability), needing ZERO feature code.** A hardcoded
`when (host) { "open.spotify.com" -> … ; "music.apple.com" -> … }` is the anti-pattern:
it forks per service, goes stale, and blocks new providers. (This is the same spirit as
Common Mistakes #6 — don't reimplement plugin logic natively — applied to *dispatch*.)

- **Native fast-paths are an optimization, not the gate.** It's fine to keep a native
  Spotify/Apple path for speed, but the *fallback* must be registry-driven so unlisted
  providers still work. Example: `ProviderPlaylistResolver` (playlist URL → tracks) does
  native Spotify/Apple first, then `lookupPlaylist` via the registry — so SoundCloud
  (#281) and any future resolver work with no per-service branch.
- **Reaching the `.axe` from an arbitrary URL needs the unique-key POLL on BOTH runtimes.**
  A bare `(async () => …)()` does NOT return the resolved value once it awaits a real
  `fetch` — iOS JSC yields `[object Promise]`, and Android WebView yields the unresolved
  Promise serialized as `{}` (the `Plugin resolve(bandcamp) raw result: {}` artifact).
  Use the proven pattern: a setup `evaluate` writes `window.__resolveResults['<key>'] =
  JSON.stringify(result)` from a fire-and-forget async IIFE, then poll
  `evaluate("window.__resolveResults['<key>']")`. iOS returns the JS string raw; Android
  double-encodes → `.unquote()`. See `IosResolverRuntime.resolveOne`/`lookupPlaylist`
  and `PluginManager.lookupPlaylist`.
- **Audit the plugin layer before assuming a capability exists.** A resolver "just
  works" only if its `.axe` implements the capability — e.g. only spotify/apple/youtube
  `.axe` had `lookupPlaylist`; SoundCloud needed it added (v1.1.0, #281). Many resolvers
  also need credentials (SoundCloud OAuth `config.token`) and return null without them.

**Key files:** `shared/.../deeplink/ProviderPlaylistResolver.kt` (registry fallback),
`shared/.../plugin/PluginManager.kt#lookupPlaylist` (Android poll), `shared/iosMain/.../IosResolverRuntime.kt#lookupPlaylist` (iOS poll), `parachord-plugins/<service>.axe` (the per-service capability source of truth).

### On-the-fly Track Resolution

Tracks from external sources (ListenBrainz weekly playlists, AI recommendations, DJ chat) arrive as metadata-only `TrackEntity` objects — they have title/artist/album but no `resolver`, `sourceUrl`, `spotifyUri`, or streaming IDs. These tracks must be resolved through the resolver pipeline before playback.

**Two-layer resolution strategy:**
1. **Background pre-resolution:** Call `TrackResolverCache.resolveInBackground(tracks)` when tracks are loaded into a list. This populates the cache with resolver results so (a) resolver badges appear in the UI and (b) playback starts instantly when the user taps a track.
2. **On-the-fly fallback:** `PlaybackController.playTrackInternal` detects tracks with no source info and calls `resolveOnTheFly()` before routing. This catches any tracks that weren't pre-resolved (e.g. user tapped before background resolution finished).

Results are cached in `TrackResolverCache.putSources()` so subsequent plays of the same track (or queue advancement) reuse the cached sources without re-resolving.

### Resolver Pipeline Rules

**Every tracklist must go through the resolver pipeline.** Any screen or ViewModel that displays a list of playable tracks must call `TrackResolverCache.resolveInBackground(tracks)` when tracks are loaded. This ensures:
- Resolver badges (Spotify, SoundCloud, local files, etc.) appear in the UI
- Playback starts instantly when the user taps a track
- The user's resolver priority preferences are respected

**Prefer visibility-scoped resolution over whole-list bulk submit (#177).** The desktop's `ResolutionScheduler` (app.js ~L1126) resolves only imminently-playable tracks — viewport + overscan, with abort-on-scroll-away — never a whole 50-track list on open. Bulk-submitting an entire list fans out one Spotify + one iTunes catalog search per track in a single burst; on a fresh client with no cached IDs this trips Spotify's account-wide abuse window (`429 Retry-After: 2656s` on the shared `client_id`). `TrackResolverCache` already bounds concurrency (`backgroundSemaphore`) and dedups by track key + in-flight set, so per-row submission is safe and cheap. **Weekly playlists** (`WeeklyPlaylistScreen`) drive resolution from a per-row `LaunchedEffect(track.id) { vm.resolveVisibleTrack(track) }` — the LazyColumn composes only the visible window + overscan — instead of bulk-resolving on open. Most other Android screens persist resolver IDs to the DB (`backfillResolverIds`) and pass them as hints, so repeat opens skip the catalog search; the eager whole-list submit is most dangerous for ephemeral, metadata-only lists (`backfillDb = false`) like weekly playlists, where every open re-searches. A future follow-up may port the desktop's full visibility-context model into the shared cache so all tracklist screens get it uniformly.

**Every implemented resolver must be in the pipeline.** `ResolverManager.resolve()` must include all resolvers that have working implementations. The `_resolvers` list must match — only list resolvers with actual `resolve*()` methods. Currently implemented: `spotify`, `applemusic`, `soundcloud`, `localfiles`. Planned but not yet implemented: `bandcamp`, `youtube` (these exist in `CANONICAL_RESOLVER_ORDER` and `DEFAULT_RESOLVER_ORDER` for when they're added).

**Only call active resolvers.** `ResolverManager.resolve()` checks `settingsStore.getActiveResolvers()` before calling each resolver. If the active list is empty, all implemented resolvers run. If the user has configured a specific set, only those are called. `ResolverScoring.selectBest()` also filters by active resolvers.

**Always pass `targetTitle` and `targetArtist` to `resolve()`.** The local file resolver requires both fields for exact matching. All call sites should pass these parameters — without them, local file resolution silently skips. For `resolveWithHints()` these parameters are already required by convention.

**Screens that currently call `resolveInBackground()`:**
- `HomeViewModel` — recent loves / library tracks
- `LibraryViewModel` — full library track list
- `PlaylistDetailViewModel` — playlist tracks (via `resolvePlaylistTracksInBackground`)
- `WeeklyPlaylistViewModel` — ephemeral weekly playlist tracks
- `NowPlayingViewModel` — queue tracks
- `PlaybackController` — upcoming queue items
- `SearchViewModel` — local search results
- `HistoryViewModel`, `FriendDetailViewModel`, `ArtistViewModel`, `AlbumViewModel`, `PopOfTheTopsViewModel` — via their own `resolveTracksInBackground()` methods
- `RecommendationsViewModel` — via `resolveTracksProgressively()`

### Resolver Badge Display — Confidence-Aware Icons

Resolver badges in the UI must reflect match confidence, matching the desktop's approach:

1. **Filter noMatch from display.** Sources with confidence < `MIN_CONFIDENCE_THRESHOLD` (0.60) are excluded from the resolver icon list entirely. A wrong-song Spotify result (0.50 confidence) must not show a Spotify badge — otherwise the user sees a Spotify icon but tapping plays a local file (or nothing), which is confusing.

2. **Dim low-confidence icons.** Sources with confidence ≤ 0.80 render at 60% opacity (`alpha 0.6`). Full-confidence matches (> 0.80) render at full opacity. This gives users a visual signal about match quality.

3. **Sort by priority, then confidence.** `ResolverIconRow` sorts icons by user-configured resolver priority first (left-to-right), then by confidence descending within the same priority tier. The icon that appears first is the one that will actually play.

4. **Pass confidences through the full chain.** Every path from resolution to UI must carry confidence scores: `ResolvedSource.confidence` → `RecommendedTrack.resolverConfidences` (or `TrackResolverCache.trackResolverConfidences`) → `TrackRow(resolverConfidences=...)` → `ResolverIconRow(confidences=...)` → `ResolverIconSquare(confidence=...)`.

**Example:** "Clover" by Tundra 212 — streaming resolvers return wrong-song results (0.50 confidence, filtered out). Local files has an exact match (0.95 confidence, shown at full opacity). The user sees only the local files icon and tapping plays the correct local file.

### MBID Enrichment Pipeline

The **ListenBrainz MBID Mapper** (`mapper.listenbrainz.org/mapping/lookup`) resolves artist+title pairs to MusicBrainz identifiers (recording, artist, release MBIDs) in ~4ms with no strict rate limits. `MbidEnrichmentService` manages this with a 90-day TTL disk cache and in-flight deduplication.

**MBID enrichment is wired into every track entry point:**
1. **Library import** — `LibraryRepository.addTrack()`/`addTracks()` calls `enrichBatchInBackground()`
2. **Local file scan** — `MediaScanner.scan()` → `repository.addTracks()` → same as above
3. **Background resolution** — `TrackResolverCache.resolveInBackground()` fires MBID enrichment in parallel with resolver resolution
4. **Queue addition** — `PlaybackController.addToQueue()`/`insertNext()` enriches queued tracks
5. **Playback start** — `PlaybackController.playTrackInternal()` enriches the current track
6. **Scrobble dispatch** — `ScrobbleManager` re-reads the track from Room before scrobbling to pick up backfilled MBIDs

**Canonical name fallback:** The mapper returns `artist_credit_name` and `recording_name` — the canonical MusicBrainz names. These are cached in `MbidCacheEntry` and used by `ScrobbleManager` to correct misspelled artist/track names in scrobble payloads (e.g., "Beatles" → "The Beatles").

**Scrobble payloads include MBIDs:** ListenBrainz gets `recording_mbid`, `artist_mbids`, `release_mbid` in both `track_metadata` and `additional_info`. Last.fm and Libre.fm get the `mbid` parameter (recording MBID).

### ISRC → Recording-MBID Fallback (mapper-outage resilience)

The ListenBrainz mapper is the **only** MBID source for un-enriched tracks, and it goes down: `mapper.listenbrainz.org` returned `502` for everyone for 2+ days (June 2026), confirmed even for Radiohead/Creep. During an outage, a freshly-played track never resolves its recording MBID, so the Achordion streaming-link submit on scrobble (#215) — which is recording-keyed — silently skips. The fallback resolves the MBID from the track's **ISRC** via MusicBrainz `GET /ws/2/isrc/{isrc}` (a different service, independently healthy during the mapper outage; exact, ~0.4s). Design doc: `docs/plans/2026-06-16-isrc-mbid-fallback.md`. Desktop parity tracked at [parachord#887](https://github.com/Parachord/parachord/issues/887).

**Service-agnostic by design — the MBID core never branches on a streaming service.** Three layers; adding a new streaming resolver = populate ONE field, nothing downstream changes:

1. **SUPPLY — `ResolvedSource.isrc`, populated per-resolver during resolution.** Each resolver fills it from the response it already fetched, so capture is free (no extra round-trip): native Spotify (`SpTrack.externalIds.isrc` — from the search full-track object → `SpotifyClient.searchTrack` AND the cached-ID **verify** path `ResolverManager.verifySpotifyTrack` via `getTrack`; the verify path is the common case for known-`spotifyId` tracks, so capturing there is what feeds the fallback for most Spotify plays), native Apple Music (catalog `attributes.isrc` — Android `AndroidResolverRuntime` MusicKit branch + iOS `IosResolverRuntime` catalog branch; the no-auth iTunes-Search Tier-2 path has no ISRC → null), and the **`.axe` result contract** (an `.axe` resolver MAY return `isrc`; `AxeResolveResult.isrc` → `ResolvedSource.isrc` in both platforms' `resolveAxe`). SoundCloud has no ISRC (null, expected). **Android local files DO carry ISRC** (#238): `MediaScanner` reads the ID3 `TSRC` frame via Media3's metadata extractor (`MetadataRetriever` + `TextInformationFrame` — no new dependency; media3-exoplayer is already on the classpath) at scan time and persists it to the **`tracks.isrc` DB column** — the one place ISRC IS persisted, because it's intrinsic to the file (read once at scan), unlike streaming ISRC which stays in-memory and is re-attached each play. `resolveLocalFile` surfaces it onto the `localfiles` source. ID3-centric on mobile (Vorbis/FLAC/MP4 would need a broader tag lib); iOS has no local-file scanner yet, so its `tracks.isrc` stays null. **Every capture site runs the raw value through `validateIsrc()`** (`ResolverModels.kt` — trim + uppercase + canonical `^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$`), so a malformed ISRC never enters a source record (desktop drops rather than stores; parachord#894).
2. **CARRY — `Track.isrc`, merged onto the playing track.** Picked via the shared `pickTrackIsrc(topLevelIsrc, sources)` (`ResolverModels.kt`, byte-equivalent with desktop's `window.pickTrackIsrc`): carried top-level first, then walk the resolved sources skipping `noMatch`, each candidate `validateIsrc`-normalized. Used by iOS `IosContainer.trackWithResolvedSources` and Android `PlaybackController.reselectBestSource` **and** `resolveOnTheFly`. **ISRC is NOT a DB column** — it lives only on the live, resolver-merged in-memory track, re-attached each play. Load-bearing consequence: `ScrobbleManager.refreshTrackMbids` re-reads the track from the DB to pick up backfilled MBIDs, which would drop the ISRC; it re-attaches `track.isrc` after the re-read, or the fallback dies for every in-DB track (the common case). Don't remove that re-attach.
3. **CONSUME — service-agnostic core.** `MusicBrainzClient.lookupRecordingMbidByIsrc(isrc)` is pure `isrc → MBID` (returns `recordings.firstOrNull()?.id`; an ISRC is an exact recording identifier, so first-result is safe — no fuzzy wrong-variant risk that a title/artist MB *search* would carry). `MbidEnrichmentService.getRecordingMbid(artist, title, isrc?)` falls back to it **only when the mapper returns null**, and **deliberately does NOT cache the ISRC-only result** — it lacks canonical names + artist/release MBIDs, so caching it (90-day TTL) would block the richer mapper entry once the mapper recovers. The scrobble submit only needs the bare MBID for that play; Achordion dedups by MBID per session. Threaded from `ScrobbleManager.refreshTrackMbids` + `ListenBrainzScrobbler`; `musicBrainzClient` is a nullable ctor param (the fallback is inert without it).

**Key files:** `shared/.../resolver/ResolverModels.kt` (`ResolvedSource.isrc`), `shared/.../model/Track.kt` (`Track.isrc`), `shared/.../api/MusicBrainzClient.kt` (`lookupRecordingMbidByIsrc`), `shared/.../api/SpotifyClient.kt` (`SpExternalIds`), `shared/.../metadata/MbidEnrichmentService.kt` (fallback gate), `app/.../resolver/AndroidResolverRuntime.kt` + `shared/iosMain/.../IosResolverRuntime.kt` (Apple + `.axe` supply), `app/.../playback/PlaybackController.kt` (`reselectBestSource`/`resolveOnTheFly` carry).

### Local File Artwork Validation

`MediaScanner` assigns `content://media/external/audio/albumart/{albumId}` URIs as artwork, but many local files lack embedded art — the URI string exists but points to nothing.

**Two-layer validation:**
1. **Scan time** — `MediaScanner.validateContentUri()` opens the content URI; if it has no data, `artworkUrl` is set to `null` so `ImageEnrichmentService` can fetch art from Last.fm/MusicBrainz.
2. **Playback time** — `PlaybackController.enrichArtworkIfMissing()` revalidates `content://` albumart URIs on the IO thread. If the URI is broken (pre-fix scans or stale data), it falls through to online enrichment.

This means local files without embedded art get artwork pulled from metadata providers, matching how streaming-resolved tracks get their art.

### Playlist Sync — Duplicate Prevention

Pushing local playlists to Spotify is a prime source of duplicates: `Playlist.spotifyId` is the only link between a local row and its remote, and any save path that forgets to forward it produces a fresh remote duplicate on the next sync. Mirrors desktop's `sync_playlist_links` map and three-layer dedup (desktop commits `40bb2cbf`, `214333ed`, `745e2db8`).

**Durable link table (`sync_playlist_link`):** independent SQLDelight table keyed by `(localPlaylistId, providerId)` → `externalId`. Never touched by playlist writes, so a playlist-save that drops `spotifyId` can't clobber it. Because the schema started as a frozen port of Room v12, the CREATE DDL is also re-executed at DB bind time in `AndroidModule.kt` so existing installs pick up the table without a SQLDelight schema migration.

**Three-layer dedup in `SyncEngine.syncPlaylists` before any `createPlaylistOnSpotify` call:**
1. **ID link** — look up `sync_playlist_link[localId]["spotify"]`; if the stored `externalId` is still in the remote list and owned, reuse it. If gone, delete the stale link and fall through.
2. **Playlist field** — defensive re-check of `Playlist.spotifyId` against the same remote list (redundant with the filter but kept for parity with desktop).
3. **Name match** — case-insensitive, trimmed, owned-only; richest `trackCount` wins if several match.

Only if all three miss does `createPlaylistOnSpotify` run. The link is written **immediately** after create/reuse, before the track push or playlist row update, so partial failures don't leave an unlinked remote.

**Startup link backfill (`migrateLinksFromPlaylists`):** runs at the top of every `syncPlaylists`. For every playlist row with a non-null `spotifyId`, upserts a matching link row. Idempotent — protects against upgrades from pre-link-map installs and older push paths that wrote `spotifyId` without a link.

**Startup syncedFrom heal (`healImportedSyncedFromMismatch`).** Cross-platform invariant. Runs in `syncPlaylists` between `migrateSourceFromPlaylists` and `migrateMergeCrossProviderDuplicates`. For every playlist with an ID prefix (`spotify-*` or `applemusic-*`), if `sync_playlist_source.providerId` doesn't match the implied prefix: (1) demote the wrong source into `sync_playlist_link` (only if no link exists for that provider — preserves more-recent snapshots), (2) restore `sync_playlist_source` with `impliedProvider + externalIdFromPrefix` (snapshotId null so the next sync repopulates), (3) clear `playlist.locallyModified`. Idempotent; no-op on healthy data. **Both platforms must implement it.** When one client has a sync bug that corrupts `syncedFrom`, the other client's launch silently restores it — without this on Android, an Android regression would corrupt a fleet of playlists and only desktop could heal.

**Skip-if-held mutex (not wait-if-held):** `SyncEngine.syncAll` uses `syncMutex.tryLock()` and returns early if held. `LibrarySyncWorker` (hourly), `SyncScheduler` (15 min in-app timer), and `SyncViewModel.syncNow` can all fire concurrently — wait-if-held would just queue wasted work behind the in-flight sync. Always use `tryLock` here, never `withLock`.

**Link-table cleanup hooks:** prune the link row when its remote is deleted during duplicate cleanup (`deleteByExternalId`), and clear all provider entries on `stopSyncing` (`deleteForProvider`). Link orphans otherwise accumulate across sync-disconnect / reconnect cycles.

**Invariants to preserve on every playlist save:** `spotifyId`, `snapshotId`, `locallyModified`, `lastModified`. The `.copy(...)` pattern preserves them automatically — fresh `PlaylistEntity(...)` constructors are only safe for genuinely new playlists.

**Still out of scope:** the two-phase `cleanupDuplicatePlaylists` (orphan relink + link-aware keeper selection from desktop). Single-provider scope; if it comes up it's its own workstream.

**Key files:** `shared/src/commonMain/sqldelight/com/parachord/shared/db/SyncPlaylistLink.sq`, `data/db/dao/SyncPlaylistLinkDao.kt`, `sync/SyncEngine.kt` (`migrateLinksFromPlaylists`, push loop), `sync/SpotifySyncProvider.kt`

### Track-Level Remove Tombstones

When the user removes a saved track, sync would re-import it on the next pull if the remote still has it — and the remote often still does, because remote-remove can fail or be unsupported (Apple Music has no working track-removal API; it degrades PUT to POST-append, so removals never reach the provider). Without a durable "user removed this on purpose" marker, the next pull re-adds the track and the removal silently undoes itself. Mirrors desktop parachord#865 (`sync-engine/tombstones.js`).

**Three integration points:**
1. **Write on remove** — `SyncEngine.onTrackRemoved` tombstones every synced provider's `(providerId, externalId)` (derived via `TrackTombstoneService.deriveTombstoneEntries` — spotify / applemusic / listenbrainz from the track's IDs) BEFORE attempting remote removal, regardless of whether remote-remove succeeds. This is the whole point: the tombstone must survive a failed or unsupported remote-remove (Apple Music) so the next pull still drops the track.
2. **Filter + re-arm on pull** — `SyncEngine.applyTrackDiff` filters the remote list through tombstones at the top (using `SyncedTrack.spotifyId` as the external id), dropping tombstoned tracks from the add-diff AND re-arming each hit's `removedAt = now`. The re-arm keeps the tombstone durable for the full TTL window as long as the remote keeps the track and the user keeps syncing — a tombstone only expires once the remote stops offering the track for a full year.
3. **Clear on user re-add** — `LibraryRepository.addTrack` / `addTracks` clear tombstones, because an explicit user re-add is user intent to re-enable sync import. **Load-bearing boundary: tombstones are cleared ONLY on the user re-add path, NEVER in the sync-add path** (`applyTrackDiff` → `trackDao.insertAll`). Clearing on sync-add would defeat the tombstone the instant a pull tried to re-import the removed track.

**TTL:** `TrackTombstones.TTL_MS = 365 days`. `ParachordApplication.onCreate` fires a fire-and-forget `prune()` once per launch, sweeping entries older than the TTL.

**Storage:** one JSON blob via `KvTombstoneStore` (mirrors desktop's electron-store key `removed_track_tombstones`), backed on Android by a dedicated SharedPreferences file `parachord_tombstones` through sync load/save lambdas. **Local-per-device, not synced across devices** — semantic parity with desktop (each device keeps its own removal intent), not a shared blob. `TrackTombstoneService` is a Mutex-guarded suspend facade (`addAll`, `clearAll`, `filterRemote`, `prune`) over the pure `TrackTombstones` module.

**iOS parity:** `TrackTombstones` + `TrackTombstoneService` are already KMP-shared. iOS needs only its own `TombstoneStore` backing (NSUserDefaults-equivalent JSON blob) plus an app-start `prune()` call when the shell lands.

**Key files:** `shared/.../sync/TrackTombstones.kt` (pure module), `shared/.../sync/KvTombstoneStore.kt` (JSON-blob store), `shared/.../sync/TrackTombstoneService.kt` (Mutex facade + `deriveTombstoneEntries`), `sync/SyncEngine.kt` (`onTrackRemoved` write, `applyTrackDiff` filter+re-arm), `data/repository/LibraryRepository.kt` (`addTrack`/`addTracks` clear), `app/ParachordApplication.kt` (app-start prune).

### Multi-Provider Sync — Apple Music + Spotify (Phases 1–6.5 + Collection)

The sync engine handles N providers concurrently. Phases 1 through 6.5 (Apr 2026) ported the desktop's multi-provider model: a playlist can carry one pull source (`syncedFrom`) and any number of push mirrors (`syncedTo[providerId]`). Today two providers are registered (Spotify + Apple Music); a third (e.g. Tidal) needs only a `SyncProvider` implementation + Koin registration. `SyncEngine` never branches on `provider.id`; it dispatches on `provider.features` and per-provider candidate filters.

**Schema (Phase 1).** Three additions on top of the original Spotify-only tables:
- `sync_playlist_link.snapshotId TEXT, pendingAction TEXT` — per-mirror change-token + deferred action (e.g. `"remote-deleted"`).
- `sync_playlist_source` (new table, PK = `localPlaylistId`) — the `syncedFrom` row. One pull source per playlist.
- `playlists.localOnly INTEGER NOT NULL DEFAULT 0` — push-loop opt-out.

The legacy `playlists.spotifyId` / `snapshotId` scalars stay for backward compat (Decision 5); new sync code reads from `sync_playlist_link` and writes both. `PlaylistDao.getByIdWithSpotifyLink` joins the two so legacy callers still work.

**`SyncProvider` interface (Phase 2).** Property-only at first, then extended with playlist methods in Phase 4:

```kotlin
interface SyncProvider {
    val id: String
    val displayName: String
    val features: ProviderFeatures
    suspend fun fetchPlaylists(...): List<SyncedPlaylist>
    suspend fun fetchPlaylistTracks(externalPlaylistId: String): List<PlaylistTrack>
    suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String?
    suspend fun createPlaylist(name, description?): RemoteCreated
    suspend fun replacePlaylistTracks(externalPlaylistId, externalTrackIds): String?
    suspend fun updatePlaylistDetails(externalPlaylistId, name?, description?)
    suspend fun deletePlaylist(externalPlaylistId): DeleteResult
}

data class ProviderFeatures(
    val snapshots: SnapshotKind,             // Opaque (Spotify) | DateString (AM) | None
    val supportsFollow: Boolean = false,     // AM has no follow API
    val supportsPlaylistDelete: Boolean = false,
    val supportsPlaylistRename: Boolean = false,
    val supportsTrackReplace: Boolean = false,
)
```

`DeleteResult` is a sealed class — `Success`, `Unsupported(status: Int)`, `Failed(error: Throwable)`. Providers must NEVER throw on documented-unsupported responses; return `Unsupported` so callers can surface "remove manually in the {provider}" UX.

**Apple Music endpoint degradation (Phase 4).** Apple's public API rejects PATCH/PUT/DELETE on `/me/library/playlists/{id}` with 401. `AppleMusicSyncProvider` carries two independent session kill-switches: `amPutUnsupportedForSession` (track replace) and `amPatchUnsupportedForSession` (rename). On first 401:

- **PUT** flips its kill-switch and degrades to POST-append; subsequent calls go straight to POST without re-probing PUT. Removals stay on the remote — accept this, document it.
- **PATCH** flips its kill-switch and silently no-ops; future calls short-circuit. **Load-bearing** — runs before the track push in the create-or-link path; a throw here would abort tracks too. Even network errors from PATCH are swallowed (try/catch, log only).
- **DELETE** returns `DeleteResult.Unsupported(401)`; `LibraryRepository.deletePlaylistWithSync` surfaces a toast (Phase 6.5).

**Do NOT retry-on-401 on documented-unsupported endpoints.** A defensive retry would walk the user through a System Settings revoke flow for an authorization that was never broken (the 401 is structural, not token-related). Go straight to the sentinel return on the first 401 from these endpoints. Reauth-required behavior fires only for SHOULD-WORK endpoints (`listPlaylists`, `listPlaylistTracks`, `getStorefront`, `createPlaylist`) via `AppleMusicReauthRequiredException`.

**Four-piece propagation rules (Phase 3).** Lifted line-by-line from desktop CLAUDE.md "Multi-Provider Mirror Propagation":

1. **Pull paths set `locallyModified = true` when other mirrors exist** (`SyncPlaylistLinkDao.hasOtherMirrors(localId, currentProviderId)`). Without this, an Android edit pulled through Spotify never reaches Apple Music — the desktop sees fresh tracks but no flag, so its next push loop skips it.
2. **Local mutators only flag `locallyModified` when the playlist has sync intent** (`syncedFrom` row exists OR any `syncedTo` row exists). `LibraryRepository.hasSyncIntent`. Editing a local-only playlist no longer pointlessly flags a never-syncable row.
3. **Push-loop `syncedFrom` guard is provider-scoped** — `if (pullSource?.providerId == currentProviderId) continue`, never blanket. A Spotify-imported playlist must remain pushable to Apple Music; only blanket-skipping on `syncedFrom != null` would block that.
4. **Post-push `clearLocallyModifiedFlags` filters by `relevantMirrors`** — enabled providers with a `sync_playlist_link` row, EXCLUDING the pull source. The push loop never targets the source (rule 3), so its `syncedAt` never advances and would strand the flag forever if included.

**Cross-provider `syncedFrom` preservation.** Import branch's `isOwnPullSource` guard (Phase 3 follow-on): if the matched local row already has a `sync_playlist_source` pointing at a DIFFERENT provider, this is a cross-provider push mirror — don't overwrite `syncedFrom`, don't refetch tracks, only update the link's `syncedAt`. Without this, an AM import on a Spotify-imported playlist would clobber Spotify as the pull source and produce a duplicate on Spotify's next sync.

**Hosted-XSPF preservation (Apr 2026 bugfix).** The same import-by-name preserve guard extends to hosted-XSPF rows. Hosted playlists mark canonicality with `sourceUrl != null` rather than a `sync_playlist_source` row, so the original `isCrossProviderPushMirror` check missed them. Without the extension, the import path matched the hosted local by name (because the auto-pushed Spotify/AM mirror has the same name as the hosted playlist), then deleted the local row + tracks and re-inserted using the remote's entity — wiping `sourceUrl` (so the `🌐 Hosted` chip vanished) and replacing tracks with whatever the remote had. Both pull paths (inline Spotify, `pullPlaylistsForProvider`) now compute `preserveLocal = isCrossProviderPushMirror || (existingLocal?.sourceUrl != null)` and the preserve branch keeps the local row intact while still recording the new `sync_playlist_link` + `sync_source` rows pointing at the provider mirror.

**Per-provider iteration (Phases 4.5 + 5).** `SyncEngine.syncPlaylists` reads `enabledProviders` from `SettingsStore.getEnabledSyncProviders()` (default `setOf("spotify")`) and:
- Fetches Spotify's remote list inline (coupled with the one-time dedup-cleanup migration that's Spotify-only by design)
- Iterates `pullPlaylistsForProvider(provider)` for every non-Spotify enabled provider
- Iterates `pushPlaylistsForProvider(provider)` for every enabled provider via the Phase 4.5 push generalization

`pushPlaylist` and `pullPlaylist` helpers take a `provider: SyncProvider` parameter. Per-provider snapshot reads come from `sync_playlist_link.snapshotId`, not the Spotify-only `playlists.snapshotId` scalar — Apple Music's `lastModifiedDate` ISO string compares the same way as Spotify's opaque `snapshot_id`. `isPushCandidate(playlist, providerId)` is per-provider:
- Spotify: `local-*` + hosted XSPF AND `playlist.spotifyId == null` (legacy filter preserved exactly)
- Apple Music: same baseline + `spotify-*` (Spotify-imported playlists mirror to AM since rule 3 correctly skips them when targeting Spotify but not AM)

`extractExternalTrackIds(tracks, providerId)` chooses the right ID per provider (`trackSpotifyUri` vs. `trackAppleMusicId`).

**Catalog-search-based ID hydration (un-defers Decision D1, Apr 2026).** Before `extractExternalTrackIds` runs, both push paths (`pushPlaylistsForProvider` for first-push and `pushPlaylist` for locally-modified updates) call `hydrateMissingTrackIds(playlistId, tracks, provider)`. For each track lacking the provider's ID, it parallel-resolves via `provider.searchForTrackId(title, artist, album)` and persists results back to `playlist_track` rows via `PlaylistTrackDao.replaceAll` (atomic delete+insert in one SQLDelight transaction so the playlist isn't briefly empty during the write). Subsequent syncs see fully-hydrated tracks and skip the search — bounded one-time cost.

- **Spotify** uses field-qualified search (`track:"…" artist:"…" album:"…"`) and filters to `isPlayable != false` so users don't end up with greyed-out rows.
- **Apple Music** uses iTunes Search (`/search?media=music&entity=song`) — the no-auth catalog endpoint, NOT the library API. Returns the bare numeric `trackId` which `AppleMusicLibraryClient.appendPlaylistTracks` accepts directly. `AppleMusicSyncProvider` constructor takes both `AppleMusicLibraryClient` (library API, dev-token + MUT) and `AppleMusicClient` (catalog search, no auth).
- Both gate matches against `ResolverScoring.MIN_CONFIDENCE_THRESHOLD` (0.60) using `scoreConfidence(targetTitle, targetArtist, matchedTitle, matchedArtist)`. Wrong-song matches return null and the track is silently skipped on push — better N-1 correct tracks than N tracks with one wrong.

**Why this matters:** without hydration, a freshly-imported hosted XSPF (whose tracks haven't been viewed yet, so the resolver pipeline hasn't backfilled `appleMusicId`) would push a 0-track mirror to Apple Music. The empty mirror then poisoned the next sync's import-by-name path and clobbered the local hosted row. Hydration prevents the empty-mirror creation at the source.

Tracks that genuinely don't exist in a provider's catalog still get silently skipped on push, matching desktop behavior.

**Settings UI (Phase 6).** Settings → General shows an "Apple Music Sync" toggle row only when AM is authorized (MUT present). Flipping it adds/removes `applemusic` from `enabled_sync_providers`. An inline note explains the API limitations (no delete / rename / track-removal). The wizard's per-playlist picker is Spotify-only for now — AM gets all-or-nothing; per-playlist selection is a follow-up.

**Sync-aware playlist deletion + Decision D8 toast (Phase 6.5).** `LibraryRepository.deletePlaylistWithSync(playlist)` calls `SyncEngine.onPlaylistRemoved(playlist)` to attempt remote deletion on each linked provider, then deletes the local row. Returns `List<PlaylistDeletionAttempt>`; ViewModels (`PlaylistDetailViewModel`, `PlaylistsViewModel`, `EditPlaylistViewModel`) filter for `Unsupported` and emit a one-shot Flow event; the screen renders a Toast: *"Removed from Parachord. Apple Music doesn't allow deletion via the API — remove manually in the Apple Music app."* Local cleanup runs regardless of provider response — leaving the link would re-link on next sync via three-layer dedup.

**Collection sync (Phase 7 — Apr 2026).** The previously-deferred library axis (saved tracks, saved albums, followed/library artists) now runs per-provider too. `SyncEngine.syncTracks/syncAlbums/syncArtists` iterate every enabled `SyncProvider`. Tracks keep a Spotify-only legacy path first (preserves the v2→v3 wipe-and-refetch migration) before iterating non-Spotify providers; albums and artists generalize cleanly via per-provider helpers. `onTrackRemoved` / `onAlbumRemoved` / `onArtistRemoved` now walk every `sync_source` row for the item and call the matching provider's `removeTracks` / `removeAlbums` / `unfollowArtists` — local-side deletes propagate to every provider that has the item linked, not just Spotify.

`AppleMusicSyncProvider` implements the library surface against `/me/library/{songs,albums,artists}`:

- `fetchTracks` / `fetchAlbums` — single-item probe first; if the head item matches `latestExternalId` and `localCount > 0`, return null (unchanged shortcut). Otherwise paginate at `PAGE_SIZE = 100` with the standard 150ms inter-request delay, throw `AppleMusicReauthRequiredException` on 401.
- `saveTracks` / `saveAlbums` — `POST /me/library?ids[songs|albums]=id1,id2,...` (NOT a JSON body — Apple's add-to-library endpoint is query-string-driven). Chunked at 50 IDs per request.
- `removeTracks` / `removeAlbums` — per-item `DELETE` (Apple has no bulk-delete endpoint). 404 is silently swallowed (race with another client). Other errors log + continue so a single bad ID doesn't strand the rest of the batch.
- `fetchArtists` — read-only; returns null when `result.size == localCount` (cheap unchanged-detection without a probe). `followArtists` / `unfollowArtists` inherit the no-op defaults from `SyncProvider` — Apple has no follow API.

**Concurrency contract.** No cross-device locking. Each provider is its own merge oracle for the playlists it hosts; when two clients race an edit, the provider receiving the writes arbitrates last-write-wins, both clients converge on next pull. Sync mutex (`tryLock`, never `withLock`) is per-device only — two sync cycles on the same device skip-if-held; cross-device races resolve through the provider.

**Key files:** `shared/.../sync/SyncProvider.kt` (interface + features), `shared/.../sync/SyncedModels.kt` (cross-platform models), `shared/.../sqldelight/.../SyncPlaylistLink.sq`, `SyncPlaylistSource.sq`, `Playlist.sq`, `data/db/dao/SyncPlaylistLinkDao.kt`, `SyncPlaylistSourceDao.kt`, `data/api/AppleMusicLibraryApi.kt`, `data/api/AppleMusicAuthInterceptor.kt`, `sync/SyncEngine.kt` (per-provider push/pull iteration), `sync/SpotifySyncProvider.kt`, `sync/AppleMusicSyncProvider.kt`, `sync/AppleMusicReauthRequiredException.kt`, `data/store/SettingsStore.kt` (`getEnabledSyncProviders` / `setEnabledSyncProviders`), `data/repository/LibraryRepository.kt` (`deletePlaylistWithSync`, `hasSyncIntent`).

### N-way Multimaster Reconcile — Architecture Map

The N-way reconcile (`SyncEngine.runNwayPropagation` → `propagateReconcilePlaylist`) is a distributed **3-way merge** that resolves concurrent playlist edits across Spotify, Apple Music, ListenBrainz, and the local copy, then materializes the merged result back to every writable mirror. This section is the **map**; the deep design + history live in the 10 `docs/plans/2026-06-2*` N-way design docs (indexed below). **The code is the source of truth — those docs predate the final implementation and diverge in places (called out at the end).**

**Status — N-way real writes SHIPPED as a per-client user opt-in (late June 2026).** The gate is now the 3-state **`sync_engine_mode`** (`SyncEngineMode.kt`, KvStore key `sync_engine_mode`, LOCAL + authoritative per client — no account, no central coordinator; byte-aligned port of desktop `sync-engine/sync-engine-mode.js`, parachord#911): `legacy` (default for existing installs — legacy push drives, N-way dormant), `shadow` (legacy drives, N-way computes + logs a dry-run plan, zero writes), `new` (N-way drives playlist sync; legacy playlist push/create/import **stands down** via `legacyPlaylistSyncEnabled(mode) = mode != NEW`). Library/collection sync is unaffected — N-way is playlist-only. `getSyncEngineMode()` derives from the two legacy booleans via `fromLegacyBooleans(isNwayEnabled(), isNwayPropagateEnabled())`; `nwayWritesEnabled(mode) = mode == NEW`. **A user arms it** via Settings → "New sync engine" → **"Use new sync (preview)"** → `openMigrationPreview()` renders a human-readable per-playlist dry-run diff (adds + removes + safety aborts) → **Accept** recomputes the plan at accept-time then flips `sync_engine_mode='new'` (or **Report a problem** / **Cancel**); reversible — flip back to `legacy` and N-way goes dormant, baseline persists. Shipped both platforms: Android #314 (preview to all users) + iOS #315; iOS background sync #316. Desktop is byte-aligned + armed for opt-in users too (parachord#956 arming + #957 parity fixes). The DEBUG shadow/propagation toggles remain dev-only validation tooling — the preview→accept entry is the real user path. The opt-in-with-preview model (rather than flipping the fleet default to `new`) is deliberate: the **June 22 2026 data-loss incident** — a stale baseline under partial coverage + cross-service identity drift collapsed a 6-track playlist to 3 — drove the reconciliation redesign + runtime floors that made arming safe, but neither platform has done sustained AT-SCALE live validation yet (#289 tracks the remaining cutover: default-`new` builds, legacy removal, identity-parity sign-off). **For a `legacy`/`shadow`-mode client the LEGACY directional push loop (`pushPlaylistsForProvider`) remains the SOLE live maintainer of push mirrors** — creates links, updates tracks, detaches dead mirrors, honors the channel-override allowlist; in `new` mode the N-way reconcile is the sole playlist authority. (Dead-mirror reconcile, #6, runs live regardless of mode, ungated.)

**One cycle (`propagateReconcilePlaylist`).** Cheap change-detection (one playlist-list call/provider; unchanged mirrors reuse the baseline, no fetch) → **partial-fetch floor** (a short fetch degrades to `changed=false`, never a deletion source) → **cross-copy key unification** → **presence vote** (advance/reset missingStreaks, `!dryRun` only) → **source authority** (who may drop) → **pending-aware augmentation** (re-add un-materialized keys so a catalog gap isn't read as a delete) → pure **merge** → **total-wipe floor** → **incremental materialize** to each writable target → **baseline advance + echo-token capture**.

**1. Merge + baseline (correctness core).** `PlaylistMerge.merge` is pure + fixture-pinned (bit-identical to desktop JS) — union-adds, **delete-wins** union-removes against the baseline, order by last-writer. The `sync_playlist_baseline` row (per-playlist `TrackKeys[]` JSON) is the 3-way-merge ancestor and **advances every real cycle, unconditionally** — NOT pinned on coverage (that pinning was the incident's root bug); safety comes from the layers around the merge, not from holding the baseline back.

**2. Cross-copy key unification (`NwayKeyUnify.unifyTrackKeys`).** Runs BEFORE the merge; union-finds tracks sharing ANY identity tier (ISRC > MBID > normalized title), rewriting every copy to one representative key per equivalence class. Without it a `norm`-keyed local track never matches the `mbid`-keyed LB copy of the same song → false union-removes (the cross-service identity-drift incident). ISRC is harvested from any copy and grafted onto the merged track (it's transient on fetches) so the LB ISRC→MBID fallback can fire.

**3. Incremental materialize executor (`PlaylistMaterializeDiff` + `…Executor`).** Replaces destructive replace-all with a **non-destructive identity-diff**: removes ONLY tracks present on the remote but absent from canonical; un-hydratable adds stay **pending** (re-attempted over cycles), never forced. Dispatch is on `ProviderFeatures.trackRemoveMode` (`ByNativeId` Spotify / `ByPosition` LB / `Unsupported` Apple Music) — **never on `provider.id`**. An Unsupported (add-only) mirror keeps its residue (surfaced as "N not removable"), never resurrected onto canonical. One provider's write failure isolates to that provider.

**4. Hydration negative-cache + missingStreak gate (#277).** `track_provider_id_cache` (per `(identityKey, providerId)`: `resolvedId`, `attempts`, `missingStreak`, `lastSeenAt`) is the persisted memory that (a) stops re-searching un-findable tracks every cycle (7d→30d miss cooldown; a resolved id is never re-searched) and (b) gates deletion: a materialized track's absence escalates only after `MISSING_STREAK_THRESHOLD = 2` consecutive complete-fetch omissions (the presence vote). **Load-bearing:** `HydrationCoordinator.resolve` short-circuits a known native id at step 1, so a source's OWN tracks are never cached → can't streak-escalate — which is exactly why the source-authority split (#5) gives churning sources immediate drops instead. `upsert` preserves `missingStreak`/`lastSeenAt` atomically; the vote runs `!dryRun` only (a shadow run must never escalate streaks).

**5. Pull-source authority + one-way-mirror + writability.** An authoritative pull source may DROP a baseline track (a churning Daily Brew rotating ~40/day must rotate them OUT, not accumulate — the `d81305c` fix). `authoritativeCopyId` = the removal-capable pull source, `"local"` for hosted-XSPF, else null (local-authored multimaster → augment-all). The split:
```
immediateAuthority = authoritativeCopyId == "local" || mirrorOnly || !playlist.writable
```
SINGLE-AUTHORITY (hosted-XSPF, FOLLOWED `writable=false` source, or user-flagged `mirrorOnly`) drops IMMEDIATELY, no gate; an OWNED non-mirror source is streak-gated (#4). `authoritativeDropped` is subtracted from **every** mirror's re-add set (a source delete is final everywhere). `mirrorOnly` is a per-playlist user flag (KvStore `sync_playlist_mirror_only_<id>`, never clobbered by a pull) for owned-but-dynamic playlists (a SmarterPlaylists Daily Brew that reports as user-owned); its Sync-sheet toggle shows ON+LOCKED for followed/hosted (`forced = !writable || sourceUrl != null`). `writableById` keeps a followed source from receiving merged changes back (#269). **This diverges from desktop #922 (which gates EVERY source) — mobile leads, #911 parity.**

**6. Dead-mirror reconcile (LIVE today, ungated).** Cross-device manual deletes are reconciled by a conservative **404-only existence probe** (`remotePlaylistExists` — true on ANY transport/auth/rate-limit error, false only on a definitive 404), never by whole-playlist deletion propagation. A confirmed-gone mirror is **detached via the channel override** (seeded from EFFECTIVE channels, so a user-disabled provider isn't resurrected), dropping the link + nway token + override clause atomically. `DEAD_MIRROR_MASS_FLOOR = 30` treats a mass vanish as a partial fetch (skip probes). `local-*`/hosted rows are exempt.

**7. Schema + channel overrides + push selection + LB re-export.** Four SQLDelight tables: `sync_playlist_baseline` (merge ancestor, per-playlist — never deleted on unlink), `sync_playlist_nway` (per-provider echo-suppression change-token), `sync_playlist_source` (the one pull source), `sync_playlist_link` (push mirrors). A per-playlist **channel override** (`SettingsStore.getPlaylistChannels`, KvStore) is an authoritative ALLOWLIST for push and pull; seed it from `effectivePlaylistChannels` (live mirrors + mode-defaults), **never raw links**, or you silently drop a mirror. Push candidacy: `isPlaylistPushCandidate(playlist, providerId) && (providerId in override || (autoMirrorsByDefault(playlist) && selection.includes(id)))` — override wins. **ListenBrainz re-export:** `isPlaylistPushCandidate` now makes `listenbrainz-*` eligible for Spotify/AM (it was a candidate for NOTHING — the accept-lists only ever grew INTO LB), but `autoMirrorsByDefault(listenbrainz-*) = false` so it NEVER auto-mirrors via a `mode=ALL` default — re-export is opt-in (toggle writes the override), else a pulled LB library floods Spotify + risks dups. Applied at the push gate AND the `effectivePlaylistChannels` seed. Create/edit rides the legacy directional push (not the gated N-way): first toggle → create + hydrate (confidence-gated catalog search); later LB edits → `locallyModified` → push updates the mirror. LB's per-provider selection default is NONE.

**Design docs (`docs/plans/`) — intent + history; code is truth:**
- `2026-06-21-nway-multimaster-playlist-sync-design.md` — overall model (peer copies, 3-way merge, detection, LWW order, echo suppression).
- `2026-06-21-nway-key-consistency-design.md` — cross-copy identity (the `unifyTrackKeys` multi-key pre-pass).
- `2026-06-22-nway-reconciliation-redesign.md` — the **June-22 data-loss post-mortem** (stale baseline + identity drift) + redesign.
- `2026-06-22-nway-phase4-executor.md`, `2026-06-22-nway-reconciliation-executor-plan.md` — executor design + the four correctness traps (superseded by ↓).
- `2026-06-23-incremental-materialization-design.md` + `-implementation.md` — the non-destructive incremental executor, capability dispatch, two-tier hydration.
- `2026-06-24-merge-source-authority-design.md` — pull-source authority (Option A, vectors V1–V7).
- `2026-06-24-lb-push-picker-nway-spec.md` — the N-way-aware LB push picker (#266; seed from links, per-row override toggles).
- `2026-06-25-dead-mirror-reconcile-design.md` — probe-gated detach.

**Where docs diverge from shipped code (code wins):** baseline advances unconditionally (docs said full-coverage-only); the mass-change guard is total-wipe-only — empty merge + non-empty baseline (docs said ">N%"); `NWAY_FILL_PENDING_ACTION` markers were deleted (replaced by hydration-cache pending detection; stale ones swept each sync); the background hydration drain is not built yet (inline per-cycle budget only); ISRC rides `PlaylistTrack.trackIsrc`, not a new column.

**Key files:** `shared/.../sync/SyncEngine.kt` (`runNwayPropagation`, `propagateReconcilePlaylist`, `isProviderPendingForKey`, `streakEscalated`, `pushPlaylistsForProvider`, `effectivePlaylistChannels`), `PlaylistMerge.kt`, `NwayKeyUnify.kt`, `NwayShadow.kt` (`computeNwayPropagationPlan`), `PlaylistMaterializeDiff.kt` + `PlaylistMaterializeExecutor.kt`, `HydrationCoordinator.kt`, `DeadMirrorReconcile.kt`, `SyncProvider.kt` (`TrackRemoveMode`/`ProviderFeatures`), `SyncSettings.kt` (`isPlaylistPushCandidate`, `autoMirrorsByDefault`, `isNwayPropagateEnabled`, `getSyncEngineMode`), `SyncEngineMode.kt` (the 3-state `legacy`/`shadow`/`new` gate), `PlaylistSyncChannelManager.kt`, `SettingsStore.kt` (KvStore channel/mirrorOnly), tables `sync_playlist_{baseline,nway,source,link}.sq`; tests `NwayMaterializeTest`, `NwaySourceAuthorityTest`, `DeadMirrorReconcile*Test`, `PlaylistMergeTest`, `LbPushPickerNwayTest`.

### ListenBrainz Playlist Sync — Interop Contract (hard-won)

`ListenBrainzSyncProvider` reconciles against existing LB playlists; it must NEVER re-create them. Violating any rule below recreates the user's entire LB library as new playlists every sync cycle — a May 2026 incident put **~6,400 duplicate public playlists** on a real account. These mirror the Achordion interop contract (their `AGENTS.md`) and desktop's `sync-providers/listenbrainz.js`.

1. **Fetch the COMPLETE remote list — paginate, all-or-nothing.** `ListenBrainzClient.getUserOwnedPlaylists` MUST page through `count` + `offset` until all `playlist_count` entries are pulled. LB's `GET /1/user/{user}/playlists` defaults to **`count=25`**. The root cause of the incident: without pagination the SyncEngine three-layer dedup only saw the 25 newest playlists, so every older playlist failed Layer 1 (its MBID looked deleted-remotely → link cleared) AND Layer 3 (name not in the page) → recreated. Each new dup became one of the newest 25, evicting the rest → runaway. The non-negotiable invariant: **the dedup must be handed the full owned-playlist list.** Corollary (Achordion contract rule 4 — **a failed/partial fetch is NOT a confirmed-empty result**): if the full list can't be retrieved — HTTP error, 429, malformed body, a 404 mid-walk, or an empty page before reaching `playlist_count` — `getUserOwnedPlaylists` THROWS rather than returning a truncated list. A partial list would make the dedup treat the un-fetched playlists as deleted-remotely and recreate them; throwing aborts the sync cycle for the provider, preserves the local→MBID mappings, and retries next cycle. Only a 404 on the FIRST page (offset 0) is a legitimate empty result (user has no playlists).

2. **Default PRIVATE — send an explicit `public` flag.** `createPlaylist` uses `isPublic = false`, matching desktop. **LB treats a MISSING `public` flag as public**, so it must always be sent explicitly. Pushing a user's whole library as public playlists is a privacy leak; the original `isPublic = true` is what made the 6,400 dups publicly visible.

3. **Reconcile via the MBID link, edit in place.** First push creates the LB playlist and persists `sync_playlist_link[localId]["listenbrainz"] = mbid`. Every later sync drives off that link (Layer 1) → `replacePlaylistTracks` (delete-all + add-all on the **same** MBID). A 404'd MBID (user deleted on LB) is handled by create-once + re-map — never recreate-every-run.

4. **Never touch `dateCreated`.** It's immutable on LB and a reset creation date is the canary for accidental re-creation. Don't set it on create or edit.

5. **Idempotency is the acceptance test — and it's stronger than "count unchanged."** Sync × 2 with no local changes must produce **identical remote state**: same MBIDs, same `dateCreated`, same titles, same track lists. Counting playlists alone misses the delete-then-recreate failure mode (net-zero cardinality, but every MBID is fresh and every `dateCreated` reset). The real test diffs `GET /1/user/{user}/playlists` before/after a no-op sync and asserts the diff is empty. (A full SyncEngine sync×2 integration harness doesn't exist yet — the property is currently enforced structurally by the all-or-nothing pagination fix + three-layer dedup. Adding the integration test is a tracked follow-up; assert MBID/`dateCreated` stability, not just count.)

6. **Skip-unchanged — don't re-push a mirrored playlist whose tracklist is unchanged since last push.** `replacePlaylistTracks` is a delete-all + add-all on LB, so re-pushing identical content every cycle bumps the remote's `last_modified` and burns one API round-trip (getCurrentTracks + delete + add) per playlist for no benefit. The Achordion maintainer flagged ~128 playlists "modified" in a single day from exactly this. `SyncEngine.pushPlaylistsForProvider` now ports desktop's `canShortCircuitPlaylistUpdate` (sync-providers/listenbrainz.js "Step 3.5", parachord#796): before the replace it fetches the remote's CURRENT tracklist (`provider.fetchPlaylistTracks`) and skips the replace when it equals the intended list **order-aware** (LB honors order, so a reorder still re-pushes; comparison is case-insensitive on the hex MBIDs). The short-circuit is **provider-generalizable but wired for LB only** — Spotify already short-circuits structurally via its `spotifyId == null` candidate exclusion, and Apple Music's append-only PUT degradation makes a remote-compare unreliable. **First push always pushes** (remote is freshly-created/empty ⇒ no match). **A `locallyModified` playlist always re-pushes** (the flag is part of the skip gate and clears immediately after). The short-circuit skips ONLY the track replace — three-layer dedup / link reconciliation still runs every cycle, so a missing link still creates-or-relinks and duplicate-prevention is untouched. On any remote-fetch failure it falls through to a normal push (never skip because we couldn't confirm remote state). This is the sync×2 ⇒ zero-track-re-push companion to rule 5's sync×2 ⇒ zero-recreate.

**Key files:** `shared/.../api/ListenBrainzClient.kt` (`getUserOwnedPlaylists` pagination), `shared/.../sync/ListenBrainzSyncProvider.kt` (`createPlaylist` `isPublic=false`, `replacePlaylistTracks`), `sync/SyncEngine.kt` (`pushPlaylistsForProvider` three-layer dedup + `remoteTracklistMatches` skip-unchanged short-circuit).

### Hosted XSPF Playlists

Importing a playlist from an XSPF URL (as opposed to an uploaded `.xspf` file or a Spotify/Apple Music URL) sets `sourceUrl` on the row and records a SHA-256 of the body in `sourceContentHash`. A background poller re-fetches that URL, diffs the hash, and when the content has changed replaces the playlist's tracks and flips `locallyModified = true`. The XSPF is canonical; the next Spotify sync pushes the new state up, overwriting any Spotify-side edits (mirrors desktop's `pollHostedPlaylists`, app.js L32167+).

**Dual-track polling (mirrors [SyncScheduler](app/src/main/java/com/parachord/android/sync/SyncScheduler.kt)):**
- Foreground coroutine timer every 5 min while the app is running — matches desktop cadence.
- `HostedPlaylistWorker` via `WorkManager` every 15 min (Android's floor for periodic work) for background updates.
- Both delegate to `HostedPlaylistPoller.pollAll()`; polling is always-on from `ParachordApplication.onCreate` regardless of Spotify sync state (hosted polling is orthogonal to sync).

**SSRF re-validation on every poll.** `validateXspfUrl` runs at import time AND on every poll tick — a DNS record can change between import and the next fetch, so a previously-safe URL can later resolve to an internal address. Literal-host check only (we don't resolve DNS, which would introduce a TOCTOU race).

**Content hash comparison, not conditional GET.** `sha256Hex(body)` is the change token. HTTP `ETag`/`If-Modified-Since` would be a nice efficiency win but aren't universally honored; hash works regardless. Schema stores the hash in `playlists.sourceContentHash`.

**SyncEngine hooks for hosted playlists (`sourceUrl != null`):**
1. **Never pull.** The `when` block in `syncPlaylists` takes an `isHosted` early branch: push when `locallyModified`, otherwise no-op. Pulling from Spotify would just get reverted by the next poll tick 5 min later — confusing churn that also risks losing the XSPF state if the next poll is delayed.
2. **Include in auto-push.** The local→Spotify push loop filter was broadened from `id.startsWith("local-")` to `id.startsWith("local-") || sourceUrl != null`. Hosted playlists thus appear on Spotify and stay in lockstep with the XSPF URL via the same three-layer dedup as any other local playlist.

**Schema migration for existing installs.** SQLite has no `ADD COLUMN IF NOT EXISTS`, so `ALTER TABLE playlists ADD COLUMN sourceUrl TEXT` is wrapped in a try/catch inside `AndroidModule.kt` (duplicate-column error on second launch is the only expected failure and is safe to swallow). Same pattern as the `sync_playlist_link` bootstrap. **Pre-migration `hosted-xspf-*` rows have `null sourceUrl` and are ignored by the poller** — users re-import to enable polling on existing rows.

**UI indicator.** Matches desktop's hosted chip exactly (app.js L41224+): `🌐 Hosted` text — globe-with-meridians emoji + the word — in Tailwind `text-blue-500` (`#3B82F6`) on a `bg-blue-50` (`#EFF6FF`) pill. Don't substitute a Material icon here (e.g. `Icons.Filled.CloudSync` or `Icons.Filled.Public`) and don't reskin to brand purple — the desktop chip is part of the cross-platform brand, and the literal emoji + blue is what users recognize. Hardcoded blue colors (not `MaterialTheme.colorScheme.primary`) so the toggle doesn't tint it. Appears overlaying the bottom-left of the cover on `PlaylistDetailScreen` and inline next to the Spotify chip on `PlaylistsScreen` list rows. The detail screen also shows "Mirrors <url> · updates every 5 min" under the metadata line.

**Don't call `poller.pollAll()` on a hot path.** It iterates every hosted row and issues one HTTP fetch per row. The scheduler's 5-minute interval is already tight; don't wire additional ad-hoc triggers without thinking about rate limits on the XSPF host.

**Cross-device behavior — the polling agent is whichever device imported the URL.** Spotify's API doesn't carry a `sourceUrl` field, so when a hosted-XSPF playlist gets pulled down on a *second* device via normal Spotify sync, it lands as `id="spotify-<id>"` with `sourceUrl=null`. That row is invisible to `playlistDao.getHosted()`, so `HostedPlaylistPoller` never polls it; it gets no `🌐 Hosted` chip, no "Mirrors `<url>`" subtitle, and the "Updated on Spotify · Pull" banner *will* show normally because `checkForRemoteUpdate`'s hosted-suppression keys off `sourceUrl`. Functionally it works — the second device just receives XSPF updates indirectly via Spotify pull when the polling device pushes.

**Recommendation: import each URL on exactly one device, and prefer the phone.** Two reasons: (1) WorkManager runs the poller every 15 min in the background even when the app isn't open — a laptop that sleeps overnight gets zero polls, a phone in your pocket gets dozens; (2) Android-as-polling-agent works regardless of desktop uptime, while desktop-as-polling-agent freezes the playlist whenever the laptop's offline. **Don't import the same URL on both devices** — local IDs are random UUIDs (`hosted-xspf-<uuid>`), so two imports produce two distinct local rows. Three-layer dedup catches the name match on push, but you still end up with a duplicate local row on whichever device imported second.

**Key files:** `playlist/HostedPlaylistPoller.kt`, `playlist/HostedPlaylistScheduler.kt`, `playlist/HostedPlaylistWorker.kt`, `playlist/XspfHashing.kt` (shared SSRF + SHA-256 helpers), `playlist/PlaylistImportManager.kt` (persists `sourceUrl` + hash), `sync/SyncEngine.kt` (hosted skip-pull + include-in-push), `ui/components/HostedBadge.kt`

### Outbound Sharing (Achordion + Smart Links)

Long-press / overflow → Share fires the Android share sheet. Post-migration (v0.6.x+) the share URL depends on the entity type:

- **Track / album / artist** → Achordion entity URL (`https://achordion.xyz/<type>/<mbid>`) via `AchordionClient`. Mirrors desktop's `publishSmartLink` / `publishAlbumSmartLink` / `publishArtistSmartLink` in `parachord-desktop/app.js`.
- **Playlist** → `https://go.parachord.com/<id>` via `SmartLinksClient`. Retained for playlists because Achordion has no playlist entity page yet.

Recipients on Slack / Discord / iMessage get a rich Open Graph preview either way — Achordion renders per-service "Listen on Spotify/Apple Music/SoundCloud" buttons after server-side link resolution; smart-links serve the existing feature.fm-style landing page.

**Track shares pre-warm Achordion's cache** via `POST /api/track-links/submit` so recipients see fully-resolved per-service links instead of an empty page on first load. Submit gates (all must hold):
1. `recordingMbid` is non-null.
2. At least one streaming source ID (`spotifyId`, `appleMusicId`, `soundcloudId`) is non-blank.
3. Per-session dedup by lowercase MBID — repeat shares of the same track don't re-submit.
4. Auth-failed kill-switch on 401 — flips off for the session so a bad bearer token doesn't spam the backend.

**Client attribution — every Achordion submit MUST send `X-Parachord-Client`.** Achordion records the submitting platform from this header (allowlist `desktop | android | ios`; absent/unknown → `"unknown"`, see its `lib/parachord-client.ts`). There are TWO submit paths and BOTH must send it: the `.axe` plugin fetch sets it from `window.__parachordClient` (`'android'` in `bootstrap.html`, `'ios'` in `IosJsRuntime`), and the **native** `AchordionClient.submitTrackLinks` sends it from `AppConfig.parachordClient` (set `"android"` in `AndroidModule`, `"ios"` in `IosContainer`). The native client originally sent only `Authorization`, so every native submit logged as `"unknown"` (parachord-mobile#237) even though the `.axe` path was attributed correctly. Omitted when blank (server defaults to `"unknown"` anyway). When wiring a new platform, set BOTH its `window.__parachordClient` and its `AppConfig.parachordClient`.

Album and artist shares call entity-link only — **no submit** (matches desktop; submits are recording-keyed).

**Fallback when MBID is missing or the entity-link API errors:** Achordion lookup URL patterns do server-side MusicBrainz search and 302 to the canonical entity page:
- Tracks: `https://achordion.xyz/recording/lookup?artist=&title=`
- Albums: `https://achordion.xyz/release-group/lookup?artist=&title=`
- Artists: `https://achordion.xyz/artist/lookup?name=`

Playlist shares fall back to the `https://parachord.com/go?uri=parachord://...` deeplink wrapper when smart-link creation fails — never to a raw source URL like `open.spotify.com/playlist/<id>`. The wrapper at `parachord.com/go` is a separate static GitHub Pages redirect — different service from the smart-link backend at `go.parachord.com`.

**Bearer token** for Achordion comes from `AppConfig.achordionBearerToken` (BuildConfig field, sourced from `local.properties` or CI secret). Empty token short-circuits cleanly to lookup URLs without calling the API.

**Smart-link payload requirements (playlist path, from `smart-links/functions/api/create.js`):** `title` is required; `tracks` array must be non-empty for `type=playlist`. Build the payload defensively — if there's nothing to send, skip the API and go straight to the deeplink wrapper rather than POSTing a guaranteed 400.

**Wiring:** `TrackContextMenuHost` auto-wires share for tracks (every screen that uses the host gets it for free). Album / Artist / Playlist context menus take an optional `onShare: (() -> Unit)?` parameter — every callsite passes it explicitly via `rememberShareAlbumLite`, `rememberShareArtist`, `rememberSharePlaylist`, or `rememberSharePlaylistById`. The "lite" variants skip the per-track URL map (deeplink fallback only) for screens like the artist discography where tracks aren't in scope; the rich variant is used on detail screens (`PlaylistDetailScreen`) where the full tracklist is available.

**Key files:**
- `shared/.../api/AchordionClient.kt` — Ktor client (entity-link + submit)
- `shared/.../api/SmartLinksClient.kt` — playlist-only Ktor client
- `app/.../share/ShareManager.kt` — caller, parallel `coroutineScope` for track shares (entity-link + submit fire concurrently)
- `app/.../share/ShareSheet.kt` — `openShareSheet` + `rememberShareXxx` Composable helpers
- `app/.../ui/components/{Track,Album,Artist}ContextMenu.kt`, `app/.../ui/screens/playlists/PlaylistsScreen.kt` (PlaylistContextMenu)
- `parachord-desktop/plugins/achordion.axe` — reference implementation

### Scrobbling — Shared Stack (KMP, #193)

The scrobbler stack lives in `shared/commonMain/.../playback/scrobbler/` so iOS scrobbles through the **same** `ScrobbleManager` + scrobbler instances as Android. App-side `app/.../playback/scrobbler/*` and `app/.../playback/ScrobbleManager.kt` are now **typealias shims** to the shared classes. The three native scrobblers (`ListenBrainzScrobbler`, `LastFmScrobbler`, `LibreFmScrobbler`) call the shared Ktor clients (`ListenBrainzClient.submitListens`, `LastFmClient.updateNowPlaying`/`scrobble`/`loveTrack`); MD5 `api_sig` signing is shared in `LastFmSigning.kt` (regression-guarded by `LastFmSigningTest`).

**Two platform-coupled concerns are lambda-forwarded into `ScrobbleManager`'s constructor** (the codebase's standard pattern), so the threshold/dedup/MBID-re-read/enabled-gate core stays shared:
1. `playbackStateFlow: Flow<ScrobbleState>` — Android maps `PlaybackStateHolder.state`; iOS pushes from `QueuePlaybackCoordinator` via `IosContainer.updateScrobbleState(...)` on a 1s engine-agnostic tick. `ScrobbleState` is `(currentTrack, isPlaying, positionMs, durationMs)`.
2. `dispatchToPlugins: (eventName, Track) -> Unit` — the JS-side `window.scrobbleManager` hand-off (achordion telemetry). Android wires `JsScrobblePluginDispatcher` (WebView + resolver-cache `sources` map + Base64); iOS routes `ScrobblePluginDispatch.dispatchScript(...)` through `PluginManager.evaluateJs` (JSC). The shared `PluginManager` loads the same bootstrap on both, so the contract is identical. **Dormant on both until a scrobble-telemetry `.axe` (achordion) is installed** — no such plugin is bundled, so this currently iterates zero plugins; native LB/Last.fm/Libre.fm scrobbling does not depend on it.

**The dispatched track JSON MUST carry every recording key the `.axe` can submit on — `isrc` as well as `mbid`.** achordion's tier-2 keys its track-links submit on EITHER `track.mbid` OR `track.isrc`, and logs `"no MBID or ISRC — cannot key submit"` when both are absent *from the payload*. Both `buildTrackJson` builders (`ScrobblePluginDispatch.buildTrackJson` for iOS, `JsScrobblePluginDispatcher.buildPluginTrackJson` for Android) once serialized `mbid` but not `isrc`, so an Apple-Music-streamed track carrying only an ISRC (no MBID, e.g. during a mapper outage) could never submit via the `.axe` path even though the native `submitToAchordion` (#216) could. Both now `put("isrc", …)` parallel to `mbid`, **omitted when absent (never explicit-null)** so `track.isrc` reads `undefined` (matches the desktop track shape; achordion's check is presence-based). Regression-guarded by `ScrobblePluginDispatchTest`. When adding any new recording key the `.axe` understands, add it to BOTH builders.

**Per-scrobbler auth credential — the load-bearing rule (hard-won, June 2026).** Each scrobbler's `isEnabled()` gates on a **specific** stored credential, and the platform's connect UI MUST populate *that exact credential* — not a look-alike. Scrobbling/now-playing/love are **writes** and need the write-capable token, never a read-only identifier:
- **ListenBrainz** → `getListenBrainzToken()`. A user token (token field in settings). No signing.
- **Last.fm** → `getLastFmSessionKey()`. **NOT the username** — the username powers read-only metadata (top tracks, charts) but cannot authenticate writes. Requires the OAuth `auth.getSession` flow (web-auth token → session key). The original iOS Last.fm UI captured only a username, so `isEnabled()` was always false and every Last.fm scrobble silently early-returned. Fixed by adding `LastFmClient.getSession` (shared) + `IosOAuthManager.authorizeLastFm` (ASWebAuthenticationSession, opens `last.fm/api/auth`, intercepts `parachord://auth/callback/lastfm?token=…`) + `IosContainer.connectLastFm(token)`. Android does this via `OAuthManager.handleLastFmCallback`.
- **Libre.fm** → `getLibreFmSessionKey()`. Username/password → `auth.getMobileSession` session key. **Auth and scrobble must use the SAME api_key/secret** (the conventional all-zeros placeholder) or the session key can't sign scrobbles — the same class of mismatch that, in a different form, broke Last.fm.

When adding a scrobbler or a new platform's connect UI: trace `isEnabled()`'s exact key from the connect handler to the scrobble signer, and confirm the connect flow stores precisely that key. A connect screen that "looks connected" (stores a username / display name) but doesn't capture the write credential fails silently — no error, just no scrobbles.

**Key files:** `shared/.../playback/scrobbler/{ScrobbleManager,Scrobbler,ListenBrainzScrobbler,LastFmScrobbler,LibreFmScrobbler,LbSourceEnrichment,ScrobblePluginDispatch}.kt`, `shared/.../api/{LastFmSigning,LastFmClient,ListenBrainzClient}.kt`, `app/.../playback/JsScrobblePluginDispatcher.kt`, `app/.../auth/OAuthManager.kt#handleLastFmCallback`, `iosApp/.../ContentView.swift#IosOAuthManager.authorizeLastFm`, `shared/.../ios/IosContainer.kt#connectLastFm`/`updateScrobbleState`/`startScrobbling`.

### Loved Tracks → ListenBrainz / Last.fm Push

When the user adds a track to their collection (a "love"), Parachord can mirror that to ListenBrainz's `recording-feedback` endpoint and Last.fm's `track.love` — same direction as scrobbles. Mirrors desktop's design doc `docs/plans/2026-05-03-loved-tracks-scrobbler-push-design.md`.

**Per-service toggle, default OFF.** `SettingsStore.LOVE_PUSH_ENABLED` holds a CSV (`"lastfm:true,listenbrainz:false"`). Unset service ⇒ false. Toggling on in Settings → Scrobblers enables push for new loves; toggling off doesn't un-love past pushes.

**Idempotency cache.** `SettingsStore.LOVE_PUSHED_KEYS` is a JSON map `{ "<trackId>": { "<service>": <epochMs> } }`. Survives crashes mid-backfill — each successful push writes its key before moving on, so resume picks up where it left off. Mirrors desktop's `love_pushed_keys` exactly so a user signed into both clients doesn't double-push.

**Push paths:**
- **Real-time** — `LibraryRepository.addToCollection(track)` calls `LovesPushService.pushLove(track)` after the local upsert. Fire-and-forget; failures log + skip without rolling back the local love.
- **Backfill** — Settings → Scrobblers exposes a "Backfill N loved tracks" button per service. `SettingsViewModel.runLoveBackfill(service)` enumerates the existing collection, skips entries already in the idempotency cache for that service, and pushes the rest. Progress state surfaces via `loveBackfillState: StateFlow<LovesPushService.BackfillState>`.

**Per-service gating:**
- **ListenBrainz**: needs `recording_mbid` (validates against the 36-char canonical regex). Tracks without one are silently skipped — the next mapper backfill via `MbidEnrichmentService` will eventually populate the MBID, and the next love (or backfill run) catches them.
- **Last.fm**: needs (artist, title) + a valid Last.fm session token. No MBID dependency.

**Key files:** `playback/LovesPushService.kt`, `shared/.../playback/scrobbler/{Scrobbler,ListenBrainzScrobbler,LastFmScrobbler}.kt#loveTrack` (KMP-shared, #193), `ui/screens/settings/SettingsViewModel.kt#runLoveBackfill`.

### Friend Follow / Unfollow

`FriendsRepository.addFriend(...)` and `removeFriend(...)` both call into the source service to keep the follow graph consistent across Parachord clients (so a friend added on Android shows up on desktop's friends list and vice versa).

**Token source rule:** ListenBrainz follow / unfollow uses `SettingsStore.getListenBrainzToken()` — the same scrobbler-config token, NOT a separate meta-service token. Cross-platform consistency rule: both platforms must read from the same persistence key.

**Last.fm has no follow API.** Last.fm deprecated `user.addFriend` / `user.deleteFriend` in 2018 (returns `Method "user.addFriend" is deprecated`). Local-only add / remove. The Last.fm-side state is whatever's already in the user's Last.fm follows; we can't change it via the API.

**Removal stickiness — `deletedFriendKeys` allowlist.** Last.fm has no unfollow API, so a removed Last.fm friend would re-appear on the next `syncFriendsFromServices` cycle from the user's still-active Last.fm follows. `SettingsStore.deletedFriendKeys` (CSV-encoded `Set<String>` in `KvStore` under `DELETED_FRIEND_KEYS`) carries the allowlist. `removeFriend` adds `"<service>:<username.lowercase>"` to the set; `addFriend` removes it; `syncFriendsFromServices` checks before re-inserting. Applied to **BOTH services** (LB too — even though LB has a working unfollow, a user-removed friend shouldn't sneak back through any periodic re-sync path).

**Last.fm refresh log-spam guard.** Every 2-minute `refreshAllActivity` cycle fans out one `getUserRecentTracks` call per friend. With 60+ Last.fm friends, the rate-limit gate trips after a handful of calls and every remaining call throws `LastFmRateLimitedException` synchronously. The catch in `refreshFriendActivity` demotes that specific exception to debug, and `refreshAllActivity` tracks a per-cycle flag so subsequent Last.fm calls skip without launching coroutines that immediately throw. ListenBrainz friends are unaffected (separate API + separate gate).

**Key files:** `shared/.../repository/FriendsRepository.kt#followOnService` / `unfollowOnService` / `syncFriendsFromServices` / `refreshAllActivity`, `shared/.../settings/SettingsStore.kt` (the `DELETED_FRIEND_KEYS` family).

### Cross-Resolver Enrichment (Slow Trickle)

Local-files-only tracks never get streaming-service IDs (Spotify / Apple Music / SoundCloud) populated through the normal playback or sync paths. Without intervention they never feed Achordion's match cache either — the contribution loop is broken for the ~30% of users whose libraries are mostly local files. Mirrors desktop's "Cross-Resolver Enrichment (Eager Gate + Slow Trickle)".

**Algorithm.** A `WorkManager` periodic job (`CrossResolverEnrichmentWorker`, 24h cadence) wraps `CrossResolverEnrichmentService.runOnce()` from `shared/.../enrichment/`. Each run:

1. SQL query selects up to **20** localfiles tracks (`resolver = 'localfiles'`) that are missing at least one streaming-service ID column AND haven't been visited within the **30-day cooldown** (`crossResolverEnrichedAt IS NULL OR < cutoff`). Never-tried tracks sort first.
2. For each candidate (with a **3-second** gap between):
   - If `recordingMbid` is null → call `MbidEnrichmentService.enrichTrack(...)` synchronously and re-read.
   - Call the resolver cascade (`ResolverManager.resolveWithHints`) for Spotify / Apple Music / SoundCloud matches.
   - Persist any **new** IDs additively to the track row — never overwrite an existing populated ID. Both the Kotlin filter AND the existing `backfillResolverIds` SQL enforce the null-only-update invariant.
   - If `recordingMbid` is now non-null AND we have ≥1 high-confidence (≥0.95) streaming ID, POST to Achordion's `/api/track-links/submit` via `AchordionClient.submitTrackLinks`.
3. Stamp `crossResolverEnrichedAt = now` **regardless of outcome** so un-findable tracks aren't thrashed every cycle.

**Constraints.** WorkManager requires `NetworkType.UNMETERED` + `requiresBatteryNotLow = true`. Idle-priority background work — never runs on a metered cellular connection or low battery. `KEEP` policy on `enqueueUniquePeriodicWork` so re-enabling on app start is idempotent.

**KMP-ready.** `CrossResolverEnrichmentService` is `shared/commonMain` and generic over the resolver via a `suspend (title, artist) -> ResolvedSources` lambda. The Android Koin binding closes over `ResolverManager.resolveWithHints`. iOS gets the service for free; it just needs its own background-work scheduler equivalent when the shell lands.

**Key files:** `shared/.../enrichment/CrossResolverEnrichmentService.kt`, `app/.../enrichment/CrossResolverEnrichmentWorker.kt`, `app/.../enrichment/CrossResolverEnrichmentScheduler.kt`.

### In-App Announcements

Achordion + desktop already share an `announcements:json` Upstash row that drives in-app banner notifications (outage notices, launch messaging, Discord-invite pushes, etc.). Android consumes the same feed.

**Endpoints.**
- `GET https://achordion.xyz/api/announcements` — public, no auth, cached `s-maxage=60`. Returns the full validated server list.
- `POST https://achordion.xyz/api/announcements/event` — public, no auth, per-IP rate-limited (60/min). Body: `{ id, event: "view" | "dismiss" | "cta-click" }`. Best-effort telemetry; failures are swallowed at debug level.

**Client-side filtering.** The server returns everything; clients filter:

- **Surface match** — `surfaces == null || surfaces.isEmpty() || "parachord" in surfaces`. The string is `"parachord"`, not `"parachord-mobile"` — matches desktop.
- **Version range** — `appVersion >= minVersion` AND `appVersion <= maxVersion`. Semver compare via `compareSemverOrNull`. Unparseable bounds fail-open (don't crash on a malformed version string).
- **Not expired** — `expiresAt == null || Clock.System.now() < Instant.parse(expiresAt)`. Unparseable timestamps fail-open.
- **Not dismissed** — `id` not in the persisted `announcements_dismissed_ids` set (CSV in `KvStore`).

**Polling cadence.**
- **Cold start** — `ParachordApplication.onCreate` fires `repo.refreshNow()` fire-and-forget.
- **Foreground resume** — `MainActivity.onResume` calls `repo.refreshIfStale()`, which short-circuits unless `now - announcements_last_fetched_ms >= 6h`.

**Banner UI.** Top of `HomeScreen`. Severity-themed background (`error` red, `warn` amber, `success` green, `info`/unknown brand purple). Light/dark variants via `ParachordTheme.isDark`. `LaunchedEffect(id)` fires the `view` event once per session per id. CTA opens `Intent.ACTION_VIEW` (defensive try/catch + toast fallback) and fires `cta-click`. Dismiss X persists the id + fires `dismiss` + removes from the StateFlow.

**KMP-ready.** `AchordionClient.listAnnouncements` / `trackAnnouncementEvent` + `AnnouncementsRepository` live in `shared/commonMain`. iOS shell needs its own SwiftUI banner component when it lands.

**Key files:** `shared/.../api/AchordionClient.kt` (the two endpoint methods + `Announcement`/`Cta` types), `shared/.../repository/AnnouncementsRepository.kt`, `app/.../ui/components/AnnouncementBanner.kt`, `app/.../ui/screens/home/HomeScreen.kt`.

### Apple Music Catalog Throttle (`MusicKitCatalogLimiter`)

Apple Music's catalog API (`api.music.apple.com/v1/catalog/{storefront}/...`) is aggressively throttled per dev-token / IP. Once a burst trips the throttle, subsequent calls — including the `/songs/{id}` lookup that playback does internally — fail with `MusicDataRequest.Error 1`, killing the active play path too. Mirrors desktop's `nativeMusicKitLimiter`.

**Three call sites** in `MusicKitWebBridge` wrap their JS `evaluate()` in `catalogLimiter.runThrottled`:

- `search(query, limit)` — used by `ResolverManager` Apple Music resolution
- `getPlaylist(playlistId)` — used by Apple Music playlist import
- `preload(songId)` — fired per-track during background source enrichment

**Limiter knobs** (`MusicKitCatalogLimiter`, `:app`-only since the bridge is WebView-specific):

- Concurrency cap: 3 in-flight via `Semaphore`
- Inter-request gap: 150 ms minimum, mutex-protected so simultaneous permit-grabs space themselves
- Circuit breaker: 3 consecutive rate-limit signals trip a 5-minute cooldown. In-cooldown calls short-circuit returning null without hitting the bridge. **Time-bound, NOT session-permanent** — one transient throttle shouldn't disable Apple Music for the session.
- Rate-limit detection via `looksLikeRateLimit(errorString)` — matches `"MusicDataRequest.Error 1"`, `"429"`, `"rate-limit"`, `"too many requests"`.

iOS will need its own MusicKit limiter when the shell lands — `MusicKitWebBridge` is WebView-specific. The design (concurrency cap + inter-request gap + time-bound circuit breaker) ports cleanly; the implementation does not.

**Key files:** `app/.../playback/handlers/MusicKitCatalogLimiter.kt`, `app/.../playback/handlers/MusicKitWebBridge.kt#search` / `getPlaylist` / `preload`.

### Spotify Rate-Limit Gate — Abuse-Ban Remediation (shared)

Spotify rate-limits the whole **account/`client_id`**, and Parachord's Android + iOS apps **share one `client_id`** — so a burst from either client penalizes both, and a 429 isn't a per-endpoint event. The shared `RateLimitGate` (`shared/.../api/RateLimitGate.kt`) fronts `SpotifyClient` (and `LastFmClient`, `MusicBrainzClient`) with: a persisted cooldown (survives process restart via `loadCooldownEpochMs`/`saveCooldownEpochMs`), a concurrency cap (2), and a 150 ms inter-request gap.

**The hard-won lesson (June 2026 incident):** Spotify's *abuse-mode* ban is server-side and **far outlasts the `Retry-After` it advertises** — observed still 429ing **12+ hours later** despite a quiet network overnight. A flat 1 h cooldown is therefore a trap: every time the local cooldown lapses, the next call (app open, background sync) re-pokes the still-banned account and Spotify **re-extends** its window — so it never clears. There is **no reliable way to read the true ban length**; `Retry-After` is a floor, not the truth, and the only ground-truth "is it clear?" test is a successful request, which itself re-pokes. The ban only decays with **sustained silence**.

**Escalating circuit breaker (`escalateOnRepeat`, opt-in).** On each CONSECUTIVE 429 the gate **doubles** the backoff (`base * 2^(strikes-1)`, capped at `maxCooldownMs`); the first non-rate-limited response resets the strike counter. **SpotifyClient opts in with a 6 h cap (1h→2h→4h→6h)**; LastFm/MusicBrainz keep the flat default (`escalateOnRepeat = false`). After a few consecutive strikes the local cooldown exceeds Spotify's window, so the app stops poking and the ban can decay. Background sync workers (`LibrarySyncWorker` hourly, etc.) inherit this for free — they short-circuit during the now-longer cooldown. The gate takes an injectable `nowMs` clock so the cooldown is unit-testable (`RateLimitGateTest`).

**Two write-path rules that earned the bans (don't regress):**
1. **Write/mutation methods MUST route through the gate** (`gatedSend`), not raw `httpClient.put/post/delete`. A 429 on a write has to arm the same persisted cooldown a read does, and a write must not fire mid-cooldown to re-arm the abuse window (#176). Only the interactive playback-control PUTs (`play`/`pause`/`seek`/`volume`/`transferPlayback`) and `getDevices` stay ungated — the user must control playback during a throttle; they honor the cooldown advisorily via `rateLimitRemainingMs()`.
2. **`ResolverManager.resolveWithHints` must not re-search Spotify when the ID is already known.** When a `spotifyId` hint exists, `verifySpotifyTrack` (a gated `getTrack`) already yields the source *with artwork*; running the full `resolve()` cascade additionally fired a Spotify **search** per track and then **discarded** it — doubling Spotify calls for every cached track and never tapering. `resolveWithHints` now excludes `"spotify"` from the cascade (`resolve(..., excludeResolvers)`) once it has an authoritative Spotify source. **And on a 429 during verify, keep the known Spotify ID as a source** (confidence 1.0) instead of dropping it — otherwise a transient rate-limit erases the Spotify badge across an entire list (the "all Spotify badges vanished on reconnect" symptom). `verifySpotifyTrack` rethrows `SpotifyRateLimitedException` so the caller can fall back to the cached ID (#182). AM/SC hints still run their cascade search for artwork — Spotify is the rate-limit offender.

**Still deferred:** visibility-scope the ~13 remaining bulk-resolving screens (the broader "don't burst" fix, #177 parity / #181). DB-backed screens already pass hints so rule 2 cuts their Spotify volume; the eager whole-list submit is most dangerous for ephemeral metadata-only lists.

**iOS parity.** `RateLimitGate` + `SpotifyClient` are shared, so iOS inherits the escalating breaker and `gatedSend`. iOS's own resolver coordinator (`IosResolverCoordinator`) and Spotify Connect flow must mirror rule 2 (skip the redundant search; keep the badge on 429) and honor `rateLimitRemainingMs()` before poking. See `iosApp/AGENTS.md`.

**Key files:** `shared/.../api/RateLimitGate.kt` (`escalateOnRepeat`, `nowMs`), `shared/.../api/SpotifyClient.kt` (gate opt-in, `gatedSend`, `gatedGet`), `app/.../resolver/ResolverManager.kt` (`resolveWithHints`, `verifySpotifyTrack`, `resolve(excludeResolvers)`), `shared/.../api/RateLimitGateTest.kt`, `app/.../resolver/ResolverManagerHintsTest.kt`.

### ListenBrainz Weekly Playlists

The desktop fetches `GET /1/user/{username}/playlists/createdfor?count=100` (public, no auth token needed), filters by title containing "weekly jams" or "weekly exploration", sorts by date descending, and takes the most recent 4 of each type. Tracks are loaded lazily per playlist via `GET /1/playlist/{playlistId}`.

On Android, `WeeklyPlaylistsRepository` mirrors this pattern. The home screen shows both sections as horizontal carousels (`LazyRow`). Tapping a card navigates to `WeeklyPlaylistScreen` (ephemeral view with a Save button). The play button on each card triggers immediate playback via `HomeViewModel.playWeeklyPlaylist()`.

`WeeklyPlaylistScreen` supports standard track context menus (long-press) via `TrackContextMenuHost` — Play Next, Add to Queue, Add to Playlist, Go to Artist/Album, and collection toggle.

Ephemeral playlists use the ID format `listenbrainz-{playlistMbid}` when saved to Room.

### Album Context Menus

Every album card across the app supports long-press context menus via the shared `AlbumContextMenu` component (`ui/components/AlbumContextMenu.kt`). This is an always-dark `ModalBottomSheet` with consistent styling.

**Pattern for adding album context menus to a screen:**
1. Import `AlbumContextMenu` and `hapticCombinedClickable`
2. Change the album card's modifier from `hapticClickable` to `hapticCombinedClickable(onClick = ..., onLongClick = ...)`
3. Add `var showMenu by remember { mutableStateOf(false) }` state per item
4. Show `AlbumContextMenu` when `showMenu` is true

**AlbumContextMenu callbacks** — all optional except `onToggleCollection`:
- `onPlayAlbum` — play the album immediately
- `onQueueAlbum` — add album tracks to the playback queue
- `onGoToAlbum` — navigate to the album detail screen
- `onGoToArtist` — navigate to the artist screen
- `onToggleCollection` — add/remove from collection (required)

Pass `null` for any action that isn't applicable in a given context (the menu item is hidden).

**Screens with album context menus:** LibraryScreen, HomeScreen (recent albums, AI suggestions), ArtistScreen (discography), HistoryScreen (top albums), FriendDetailScreen (top albums), PopOfTheTopsScreen (chart albums).

### Artist Context Menus

Every artist card grid across the app supports long-press context menus via the shared `ArtistContextMenu` component (`ui/components/ArtistContextMenu.kt`). This is an always-dark `ModalBottomSheet` matching the album context menu styling.

**Pattern for adding artist context menus to a screen:**
1. Import `ArtistContextMenu` and `hapticCombinedClickable`
2. Change the artist card's modifier from `hapticClickable` to `hapticCombinedClickable(onClick = ..., onLongClick = ...)`
3. Add `var showMenu by remember { mutableStateOf(false) }` state per item
4. Show `ArtistContextMenu` when `showMenu` is true

**ArtistContextMenu callbacks** — all optional except `onToggleCollection`:
- `onPlayTopSongs` — fetch and play the artist's top tracks via `metadataService.getArtistTopTracks()`
- `onQueueTopSongs` — fetch and queue the artist's top tracks
- `onGoToArtist` — navigate to the artist screen
- `onToggleCollection` — add/remove artist from collection (required)

Pass `null` for any action that isn't applicable in a given context (the menu item is hidden).

**Screens with artist context menus:** LibraryScreen (artist grid), ArtistScreen (related artists), HistoryScreen (top artists), FriendDetailScreen (top artists), RecommendationsScreen (recommended artists), HomeScreen (AI artist suggestions).

**ViewModel requirements:** Each ViewModel that supports artist context menus needs `playArtistTopSongs(artistName)`, `queueArtistTopSongs(artistName)`, and `toggleArtistCollection(artistName, imageUrl, isInCollection)` methods. These require `MetadataService`, `ResolverManager`, `ResolverScoring`, and `PlaybackController` as dependencies. Currently implemented in: LibraryViewModel, ArtistViewModel, HistoryViewModel, FriendDetailViewModel, RecommendationsViewModel, HomeViewModel.

### Queue Album by Name Pattern

To queue an album that isn't in the local library (e.g., from charts, friend history, discography), use the `queueAlbumByName` pattern present in most ViewModels:

```kotlin
fun queueAlbumByName(albumTitle: String, albumArtist: String) {
    viewModelScope.launch {
        val detail = metadataService.getAlbumTracks(albumTitle, albumArtist)
        if (detail == null || detail.tracks.isEmpty()) return@launch
        val entities = detail.tracks.mapNotNull { track ->
            val sources = resolverManager.resolveWithHints(...)
            val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
            TrackEntity(...)
        }
        if (entities.isNotEmpty()) playbackController.addToQueue(entities)
    }
}
```

This requires `MetadataService`, `ResolverManager`, `ResolverScoring`, and `PlaybackController` as ViewModel dependencies.

### Album Collection Reactive Checks

To reactively check if an album is in the user's collection (for toggle UI in context menus):

1. **DAO:** `AlbumDao.existsByTitleAndArtist(title, artist): Flow<Boolean>`
2. **Repository:** `LibraryRepository.isAlbumInCollection(title, artist): Flow<Boolean>`
3. **ViewModel:** Collect as `StateFlow` via `.stateIn(viewModelScope, ...)`

**AlbumEntity ID format:** `"album-${title.hashCode()}-${artist.hashCode()}"`

### Singleton Repository Caching

`@Singleton` repositories persist across ViewModel lifecycles. If a repository caches API results in memory (like `WeeklyPlaylistsRepository`), the cache outlives individual screens and can become stale.

**Rules for singleton caches:**
- Always add a TTL or force-refresh mechanism. A `@Volatile var cacheTimestamp` with a TTL check prevents serving stale data indefinitely.
- For data that changes frequently (weekly playlists, charts), prefer `forceRefresh = true` from the ViewModel `init` since API calls are cheap.
- For expensive data (AI recommendations), use stale-while-revalidate: show cached data immediately, refresh in background.

### External Playback Background Survival

Keeping Apple Music and Spotify playing with the screen off requires navigating several Android subsystems that actively try to kill the process. These rules are hard-won from debugging — violating any one causes intermittent playback drops.

**Foreground service — Media3 fights you.** `MediaSessionService` auto-manages foreground status based on ExoPlayer state. During external playback, ExoPlayer is idle (just metadata), so Media3 calls `stopForeground()` and `pauseAllPlayersAndStopSelf()`. Both `onUpdateNotification()` overloads and `pauseAllPlayersAndStopSelf()` must be overridden to return early when `isExternalForeground` is true.

**ExoPlayer stop triggers service demotion.** Calling `ctrl.stop()` during external→external track transitions causes `MediaSessionService` to auto-demote from foreground. Then `startForegroundService()` fails from background (Android 12+). During external→external transitions, skip `ctrl.stop()` and `ctrl.prepare()` — just update metadata via `ctrl.setMediaItems()`.

**Async stop/play race condition.** Calling `router.stopExternalPlayback()` before `playTrackInternal()` in advance paths sends an async `music.stop()` to MusicKit JS that races with the new `setQueue({startPlaying: true})`. The stop arrives after the new track starts, pausing it ~30ms later. The fix: don't call `stopExternalPlayback()` in any advance path (skipNext, skipPrevious, playFromQueue, spinoff). For same-handler transitions, `handler.play()` replaces the queue directly. For cross-handler transitions (e.g. Apple Music→Spotify), `playTrackInternalUnsafe` stops the old handler only when handlers differ.

**WebView lifecycle — don't call keepAlive() in onStop().** `WebView.onResume()`/`resumeTimers()` can block the main thread when the Chromium renderer process is suspending, causing a background ANR that kills the process. The brief WebView stutter on screen-off (~500ms) recovers on its own. Call `keepAlive()` from `MusicKitWebBridge.play()` instead (runs while app is in foreground, before each track starts).

**WebView layer type must be LAYER_TYPE_NONE.** `LAYER_TYPE_HARDWARE` causes GPU context teardown when the screen turns off, disrupting the WebView's audio pipeline. The headless WebView doesn't render anything visible, and Widevine DRM works through `MediaDrm`/`MediaCodec` at the platform level, not the View's rendering layer.

**Silent ExoPlayer playback keeps the service alive.** Media3's `MediaSessionService` validates that `mediaPlayback` foreground services have an active player. During external playback, ExoPlayer plays a 1-second silent WAV (`res/raw/silence.wav`) on loop at volume 0. This makes Media3 see `isPlaying=true`, preventing the "Stopping service due to app idle" kill at ~11 minutes. Audio focus is disabled on ExoPlayer during silence (`handleAudioFocus=false`) to avoid conflicting with Chromium's `AudioFocusDelegate`. Normal settings are restored when switching back to ExoPlayer playback.

**startService() vs startForegroundService() from background.** On Android 12+, both can throw `ForegroundServiceStartNotAllowedException` when the app is backgrounded. `startService()` is tried first (works more reliably when the service is already running). The service should already be in foreground from initial promotion when the app was visible.

### On Tour Indicator — Location-Filtered

The "On Tour" teal dot (`#10C9B4`) appears next to the artist name in the mini player and Now Playing screen when the currently playing artist has upcoming concerts near the user's configured concert area.

**Desktop behavior (source of truth):**
1. Check if the artist matches any event (primary artist or in lineup).
2. If the user has set a concert location (`concertsLocationCoords`), filter events by haversine distance ≤ `concertsLocationRadius` (default 50mi). Falls back to city-name text matching when venue has no coordinates.
3. If **no location is configured**, any upcoming event counts — the dot shows for any touring artist (desktop: `if (!concertsLocationCoords) return true`).
4. The dot is clickable — navigates to the artist page with the "On Tour" tab selected.

**Android implementation:**
- `ConcertsRepository.checkOnTour(artistName, lat, lon, radiusMiles)` queries Ticketmaster + SeatGeek with location params. When `lat`/`lon` are null (no location configured), queries without location filtering (any event counts).
- `MainViewModel` and `NowPlayingViewModel` read `settingsStore.getConcertLocation()` and pass it to `checkOnTour()`.
- The **ArtistScreen's On Tour tab** is intentionally unfiltered by location — it shows the full tour schedule for browsing all dates.

**Key files:** `ConcertsRepository.kt`, `MainViewModel.kt`, `NowPlayingViewModel.kt`, `MiniPlayer.kt`, `NowPlayingScreen.kt`

## Design System & Theming

### Brand Colors

The desktop uses **purple as the primary accent color**, not blue.

**Light theme:**
- Background: `#ffffff` (primary), `#f9fafb` (secondary), `#f3f4f6` (inset)
- Text: `#111827` (primary), `#6b7280` (secondary), `#9ca3af` (tertiary)
- Borders: `#e5e7eb` (default), `#f3f4f6` (light)
- Accent: `#7c3aed` (purple, primary), with alpha variants
- Semantic: success `#10b981`, warning `#f59e0b`, error `#ef4444`

**Dark theme:**
- Background: `#161616` (primary), `#1e1e1e` (secondary), `#252525` (elevated)
- Text: `#f3f4f6` (primary), `#9ca3af` (secondary), `#6b7280` (tertiary)
- Accent: `#a78bfa` (lighter purple for contrast)

**Brand color (icon background):** `#273441`

### Resolver-Specific Colors

Used for badges and source indicators:
- Spotify: green (`bg-green-600/20`, `text-green-400`)
- YouTube: red (`bg-red-600/20`, `text-red-400`)
- Bandcamp: cyan (`bg-cyan-600/20`, `text-cyan-400`)

### Typography

System font stack: `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue'`

On Android, use the system default (Roboto) — this naturally matches.

### Component Styling Conventions

- **Cards/containers:** `rounded-lg` equivalent (8dp), subtle hover/press states
- **Buttons:** Primary = purple fill + white text. Secondary = gray fill. Icon buttons = rounded-full
- **Inputs:** Border with purple focus ring
- **Spacing:** 4dp increment system (8, 12, 16dp common)
- **Shadows:** Subtle — small, medium, large levels. Don't over-shadow
- **Transitions:** Smooth color/opacity transitions on interactive elements
- **Text colors:** Primary for titles, secondary (gray) for artist names/metadata, purple for interactive/active states
- **Play button:** Rounded-full, purple background, white icon

### Dark Mode

The desktop supports dark/light mode toggle. Android should follow system theme by default, matching Material 3 conventions but using the desktop's color palette — not Material You dynamic colors.

**Theme toggle:** The app supports manual dark/light/system selection via settings (`MainViewModel.themeMode`). The resolved `darkTheme` boolean is passed to `ParachordTheme(darkTheme = darkTheme)` in `MainActivity`. This means `isSystemInDarkTheme()` does NOT reflect the app's actual theme — it only checks the Android system setting.

**Always use `ParachordTheme.isDark` instead of `isSystemInDarkTheme()`.** `ParachordTheme` provides `LocalIsDarkTheme` via `CompositionLocalProvider`, accessible as `ParachordTheme.isDark`. This respects the user's in-app toggle. The only places that should call `isSystemInDarkTheme()` are `Theme.kt` (default parameter) and `MainActivity` (system fallback for "auto" mode).

## Build & Deploy

- **Build + install to device:** `./gradlew installDebug` (NOT `assembleDebug`, which only builds the APK without installing)
- **After installing, force-stop the app** — Android keeps the old process alive with old code in memory. Either swipe away from recents or run: `adb shell am force-stop com.parachord.android.debug` (debug builds get a `.debug` suffix per the `applicationIdSuffix` in `app/build.gradle.kts`; targeting `com.parachord.android` silently no-ops on a debug install and you'll keep seeing the old code)
- **If code changes aren't reflected on device**, Android may cache optimized dex from a prior install. Do a full uninstall/reinstall: `adb uninstall com.parachord.android && adb install app/build/outputs/apk/debug/app-debug.apk` (clears app data)
- **Clean build if all else fails:** `./gradlew clean installDebug`
- **adb path** (not in shell PATH by default): `/Users/jherskowitz/Library/Android/sdk/platform-tools/adb`

### Working in a git worktree (`.worktrees/<branch>/`) — MANDATORY rules

This project uses git worktrees for in-progress branches. Cost: every cwd-sensitive command requires explicit attention. Skipping these checks burns hours of debugging "why aren't my changes deploying?" — multiple confirmed instances on this codebase, May 2026. **The general agent guidance to "avoid `cd` and use absolute paths" does NOT apply when the workspace is a worktree** — it's actively dangerous, because `./gradlew` resolves the project from cwd.

**Rule 1 — Every gradle (or any project-rooted) command MUST cd into the worktree first.**

```bash
cd /Users/jherskowitz/Development/parachord/parachord-android/.worktrees/<branch> && ./gradlew installDebug
```

Do NOT rely on the Bash tool's "working directory persists between commands" behavior — empirically, it does not reliably hold across multi-call sessions in this environment. Re-`cd` on every gradle invocation. The 30-character prefix is cheap; silently building the wrong tree is not. If you need to run several commands in the worktree, chain them with `&&` in a single Bash call rather than relying on persistence.

**Alternative if `cd` is genuinely impossible:** `./gradlew --project-dir <worktree-path> <task>` — explicitly tells gradle which project to build. Equivalent safety, slightly more verbose.

**Rule 2 — Verify the APK was actually rebuilt before claiming success.**

The output of `./gradlew installDebug` reports `<N> actionable tasks: <X> executed, <Y> up-to-date`. After source edits:
- `1 executed, 57 up-to-date` ⇒ **only `installDebug` ran**, gradle thinks nothing else changed. Almost always means it's building from the wrong project root (your edits are in a different worktree). **STOP. Do not trust this build.**
- `<5 executed` after non-trivial source edits ⇒ suspicious. Investigate.
- `<full-count> executed` (e.g. `58 executed`) on a small edit ⇒ also suspicious — may indicate the build is on a different worktree where gradle's cache is cold.

Cross-check the APK file mtime:

```bash
ls -la <worktree>/app/build/outputs/apk/debug/app-debug.apk
```

If mtime is older than your most recent source edit, the build did not include your changes regardless of what gradle's exit code said.

**Rule 3 — Warning paths in gradle output are diagnostic.**

Gradle prints compile warnings as `file:///<absolute-path>/...`. Scan one or two:
- `file:///.../parachord-android/.worktrees/<branch>/app/...` ⇒ correct (worktree)
- `file:///.../parachord-android/app/...` (no `.worktrees/`) ⇒ wrong (main repo) — your build is going to the wrong tree

This is the fastest tell that you ran gradle from the wrong dir. Look at it on every build.

**Rule 4 — UI-visible state is NOT a signal that fresh code deployed.**

DB columns persist across APK reinstalls (SQLite lives in app data, untouched by `installDebug`). DataStore / SharedPreferences also persist. So if some piece of UI is reading from a previously-populated DB column or a cached pref, it will display correctly even if the APK has zero current code. **"Feature X is visible on the device" is consistent with both "new code deployed" and "old data still in DB."**

To verify a build deployed, choose a signal the new code is *required* to actively produce:
- A new logcat tag/string that didn't exist before (`adb logcat | grep <new-string>`)
- A behavior change observable only when the new code runs (a new badge appearing, a previously-broken interaction working)
- Process PID change (`adb shell pidof <package>`) confirming the install restarted the process — but this only confirms restart, not that the APK contents changed

Do NOT use as deployment confirmation:
- Cached UI state (artwork loading, persisted preferences, DB-row-derived chips)
- Logs from before the install timestamp
- Anything that worked under the previous build too

**Rule 5 — Edit tool paths are absolute and route to where you point them.**

The Edit/Write tools take absolute paths. If you're working in a worktree, every `file_path` argument must include `.worktrees/<branch>/`. A path missing that prefix writes to the main repo's working tree — which gradle (run from main) will then build, but if anyone else is running gradle from the worktree, they won't see the edit. Always start file paths with `/Users/.../parachord-android/.worktrees/<branch>/...` when the active branch lives in a worktree.

## Tech Stack (Android)

- **Language:** Kotlin (KMP shared module for cross-platform business logic)
- **UI:** Jetpack Compose + Material 3
- **Playback:** ExoPlayer (Media3) for local/stream, Spotify Web API (Connect) for Spotify, MusicKit JS WebView for Apple Music
- **Database:** SQLDelight (KMP, replaced Room in April 2026)
- **Preferences:** Jetpack DataStore (non-sensitive prefs) + EncryptedSharedPreferences via `SecureTokenStore` (OAuth tokens, API keys — AES-256-GCM backed by Android Keystore)
- **DI:** Koin (KMP, replaced Hilt in April 2026)
- **Networking:** Ktor `HttpClient` (all API calls — shared between Android + iOS). OkHttp engine on Android (Darwin on iOS). Retrofit was fully dropped (April 2026). OkHttp standalone is still used by scrobblers, AI .axe wrappers, OAuth flows, and the JS bridge.
- **Image loading:** Coil 2
- **Spotify:** Web API only — Spotify App Remote SDK and Spotify Auth SDK were removed (April 2026). All Spotify interaction is via the Connect HTTP API.

## Tech Stack (iOS) — Port Reference

| Android | iOS Equivalent | Notes |
|---------|---------------|-------|
| Kotlin | Swift | Use Swift concurrency (async/await, actors) instead of coroutines |
| Jetpack Compose | SwiftUI | Declarative UI; use `@Observable` (iOS 17+) or `ObservableObject` |
| Material 3 | Custom design system | No Material 3 on iOS — build custom components matching the desktop palette |
| ExoPlayer (Media3) | AVFoundation (AVPlayer) | AVPlayer handles both local files and HTTP streams |
| Spotify App Remote SDK | Spotify iOS SDK (SpotifyiOS) | Same concept — controls Spotify app externally. Uses `SPTAppRemote` |
| Room | SwiftData (iOS 17+) or Core Data | SwiftData is simpler; Core Data if supporting iOS 16 |
| Jetpack DataStore | UserDefaults / `@AppStorage` | For simple prefs use `@AppStorage`; for complex data use a JSON file in App Support |
| Hilt (DI) | Swift `Environment` / manual DI | SwiftUI `@Environment` for view-layer DI; protocol-based DI for services |
| OkHttp + Retrofit | URLSession + async/await | Use `URLSession.shared.data(from:)` with Swift concurrency. Consider Alamofire only if needed |
| Coil | AsyncImage (SwiftUI) / Kingfisher | SwiftUI has built-in `AsyncImage`; use Kingfisher or SDWebImage for caching/placeholders |
| Navigation Compose | NavigationStack (iOS 16+) | Type-safe navigation with `NavigationPath` |
| ViewModel | `@Observable` class | In SwiftUI, observable classes replace ViewModels. No `viewModelScope` — use Swift `Task` |

### iOS Playback Routing

- **Spotify** → Spotify iOS SDK (`SPTAppRemote`). Requires Spotify app installed. Auth via `SPTConfiguration` with redirect URI.
- **SoundCloud** → Fetch stream URL from API, play via `AVPlayer` with `AVPlayerItem(url:)`
- **Local files** → `AVPlayer` with local file URL from Files app or imported media
- **Direct streams** → `AVPlayer` with HTTP(S) URL
- **Apple Music** → Use MusicKit framework (`ApplicationMusicPlayer.shared`). Requires MusicKit entitlement + user authorization.

**Key AVPlayer considerations:**
- Use `AVAudioSession.sharedInstance()` — configure category `.playback` for background audio
- Register for `AVPlayerItem.Status` observation via Combine or KVO to detect playback readiness
- Handle interruptions (phone calls) via `AVAudioSession.interruptionNotification`
- For gapless playback, use `AVQueuePlayer` with preloaded `AVPlayerItem` instances
- Set `nowPlayingInfo` on `MPNowPlayingInfoCenter.default()` for lock screen controls
- Register `MPRemoteCommandCenter` handlers for play/pause/next/previous

### iOS Design & Theming Notes

SwiftUI color definitions to match the desktop palette:

```swift
// Light theme
static let backgroundPrimary = Color(hex: "#ffffff")
static let backgroundSecondary = Color(hex: "#f9fafb")
static let backgroundInset = Color(hex: "#f3f4f6")
static let textPrimary = Color(hex: "#111827")
static let textSecondary = Color(hex: "#6b7280")
static let accentPurple = Color(hex: "#7c3aed")

// Dark theme
static let darkBackgroundPrimary = Color(hex: "#161616")
static let darkBackgroundSecondary = Color(hex: "#1e1e1e")
static let darkBackgroundElevated = Color(hex: "#252525")
static let darkTextPrimary = Color(hex: "#f3f4f6")
static let darkAccentPurple = Color(hex: "#a78bfa")
```

- Use `@Environment(\.colorScheme)` to switch between light/dark palettes
- **Do NOT use system accent color or tintColor.** Always use the explicit purple values above.
- Corner radius: 8pt for cards (same as Android 8dp), `clipShape(Circle())` for circular elements
- iOS has no `dp` — use `pt` which is equivalent (1dp ≈ 1pt on standard screens)
- For the play button, use `.clipShape(Circle())` with purple background + white SF Symbol `play.fill`

### iOS-Specific Architecture Notes

- **Concurrency:** Replace Kotlin coroutines with Swift `async/await` and `Task`. Use `@MainActor` for UI updates. Use `AsyncStream` for reactive data flows (replaces Kotlin `Flow`).
- **Metadata providers:** Same cascading pattern. Each provider is a Swift protocol conformance. Use `TaskGroup` for parallel provider lookups, then merge results.
- **Resolver scoring:** Port `ResolverScoring` logic directly — it's pure math, straightforward to translate.
- **.axe resolvers:** Use `JavaScriptCore` (JSC) framework for running embedded JS. JSC is built into iOS — no WebView needed. Alternatively, port resolvers to native Swift.
- **Error handling:** Use Swift `Result` type or throwing functions. Map to domain-specific error enums.
- **Networking:** URLSession natively supports `async/await` in Swift. No need for a wrapper library in most cases.
- **Background audio:** Add `UIBackgroundModes: audio` to Info.plist. Configure `AVAudioSession` before playback begins.

## Key Files

| Area | Files |
|------|-------|
| Resolver scoring | `shared/.../resolver/ResolverScoring.kt` |
| Resolver orchestration | `shared/.../resolver/{ResolverCoordinator,ResolverRuntime,ResolverGating}.kt` (shared fan-out / gating / re-score / rank), `app/.../resolver/{ResolverManager,AndroidResolverRuntime}.kt` (Android hints + native runtime), `shared/iosMain/.../{IosResolverCoordinator,IosResolverRuntime}.kt` (iOS delegate + native runtime) |
| Resolver cache | `resolver/TrackResolverCache.kt` |
| Playback routing | `playback/PlaybackRouter.kt`, `PlaybackController.kt` |
| Metadata cascade | `data/metadata/MetadataService.kt`, `*Provider.kt` |
| MBID enrichment | `shared/.../metadata/MbidEnrichmentService.kt` (typealias shim in `data/metadata/`), `shared/.../api/ListenBrainzClient.kt` (`mbidMapperLookup`) |
| Image enrichment | `shared/.../metadata/ImageEnrichmentService.kt` (typealias shim in `data/metadata/`); platform-specific 2x2 mosaic via `composeMosaicAndroid` lambda |
| Scrobbling | `shared/.../playback/scrobbler/{ScrobbleManager,Scrobbler,ListenBrainzScrobbler,LastFmScrobbler,LibreFmScrobbler,LbSourceEnrichment,ScrobblePluginDispatch}.kt` (KMP-shared, #193; app-side `playback/scrobbler/*` are typealias shims). Android JS dispatch: `playback/JsScrobblePluginDispatcher.kt`. Last.fm signing: `shared/.../api/LastFmSigning.kt` |
| Local file scanning | `data/scanner/MediaScanner.kt` |
| Playback handlers | `playback/handlers/SpotifyPlaybackHandler.kt`, `AppleMusicPlaybackHandler.kt`, `SoundCloudPlaybackHandler.kt` |
| Apple Music bridge | `playback/handlers/MusicKitWebBridge.kt`, `assets/js/musickit-bridge.html` |
| Settings/defaults | `shared/.../settings/SettingsStore.kt` (typealias shim in `data/store/`); `shared/.../store/KvStore.kt` + `KvStoreFactory` (Android: SharedPreferences, iOS: NSUserDefaults) |
| Theme | `ui/theme/Theme.kt` |
| Shared UI components | `ui/components/AlbumContextMenu.kt`, `ui/components/ArtistContextMenu.kt`, `ui/components/TrackContextMenu.kt`, `ui/components/TrackRow.kt`, `ui/components/ResolverIconRow.kt` |
| Weekly playlists | `data/repository/WeeklyPlaylistsRepository.kt`, `ui/screens/playlists/WeeklyPlaylistScreen.kt`, `WeeklyPlaylistViewModel.kt` |
| Concerts / On Tour | `data/repository/ConcertsRepository.kt`, `data/api/TicketmasterApi.kt`, `data/api/SeatGeekApi.kt`, `ui/screens/discover/ConcertsScreen.kt` |
| .axe plugin system | `shared/.../plugin/JsRuntime.kt`, `shared/.../plugin/PluginManager.kt`, `shared/.../plugin/PluginSyncService.kt`, `shared/.../plugin/PluginFileAccess.kt` |
| JS bridge | `bridge/JsBridge.kt`, `bridge/NativeBridge.kt`, `assets/js/bootstrap.html`, `assets/js/resolver-loader.js` |
| .axe AI wrapper | `ai/providers/AxeAiProvider.kt` |
| .axe scrobbler wrapper | `playback/scrobbler/AxeScrobbler.kt` |
| Bundled plugins | `assets/plugins/*.axe` (19 files) |
| OAuth | `auth/OAuthManager.kt`, `auth/OAuthRedirectActivity.kt` |
| Secure token storage | `shared/.../store/SecureTokenStore.kt` (interface), `AndroidSecureTokenStore` (EncryptedSharedPreferences via Keystore), `IosSecureTokenStore` (Keychain via multiplatform-settings) |
| Activity tracking | `app/CurrentActivityHolder.kt` |
| Security policy | `SECURITY.md` |
| KMP shared module | `shared/src/commonMain/kotlin/com/parachord/shared/` — models, API clients (Ktor), DI, DB (SQLDelight), plugins, resolver, metadata, AI orchestration + providers, playback queue, sync engine + providers, repositories (all 9), settings, deeplink, config, platform abstractions |
| KMP iOS actuals | `shared/src/iosMain/kotlin/com/parachord/shared/` — `Platform.ios.kt`, `DriverFactory.ios.kt`, `HttpClientFactory.ios.kt`, `PluginFileAccess.ios.kt`, `KvStoreFactory.kt`, `IosSecureTokenStore.kt` |
| DI module | `di/AndroidModule.kt` (Koin — ~200 bindings replacing 4 Hilt modules) |
| Security configs | `res/xml/network_security_config.xml`, `res/xml/data_extraction_rules.xml` |

## KMP Shared Module

The `:shared` module (`shared/`) contains Kotlin Multiplatform code shared between Android and a future iOS app. The `:app` module depends on `:shared`.

**Status (April 29, 2026): KMP migration is feature-complete.** All business logic lives in `shared/commonMain/`. Both `iosArm64` and `iosSimulatorArm64` compile green. iOS just needs a Swift app shell — repositories, AI, sync, resolvers, etc. are all callable from `import shared`.

**What lives in shared/commonMain:**
- Platform abstractions: `Log`, `randomUUID()`, `currentTimeMillis()` (expect/actual in androidMain/iosMain)
- Models: `Track`, `Album`, `Artist`, `Playlist`, `PlaylistTrack`, `Friend`, `ChatMessage`, `SearchHistory`, `SyncSource` + charts/history/recommendation models, `AiAlbumSuggestion`/`AiArtistSuggestion`, `RecommendedTrack`/`RecommendedArtist`
- API clients (Ktor): Spotify, Last.fm, MusicBrainz, AppleMusic, Ticketmaster, SeatGeek, ListenBrainz, Discogs, Wikipedia, SmartLinks. Single shared `HttpClient` with 60s `requestTimeoutMillis`, `OAuthRefreshPlugin` for 401-driven token refresh, `User-Agent` injection. Retrofit fully dropped from `:app`.
- Database: SQLDelight `.sq` files matching the old Room v12 schema (9 tables). SQLDelight is the live database; Room was removed. DAOs (`TrackDao`, `AlbumDao`, etc.) are concrete shared classes wrapping `*Queries`.
- DI: Koin `sharedModule` with Ktor client + DAO + repository bindings
- Plugin system: `JsRuntime` interface, `PluginManager`, `PluginSyncService`, `PluginFileAccess` (expect/actual)
- All 9 repositories: `ChartsRepository`, `ConcertsRepository`, `CriticalDarlingsRepository`, `FreshDropsRepository`, `FriendsRepository`, `HistoryRepository`, `LibraryRepository`, `RecommendationsRepository`, `WeeklyPlaylistsRepository`. App-side files are typealias shims.
- Sync engine: `SyncEngine`, `SyncProvider` interface, `SpotifySyncProvider`, `AppleMusicSyncProvider`, multi-provider iteration, three-layer dedup, four-piece mirror propagation rules.
- Metadata enrichment: `MbidEnrichmentService` (Mapper cache via `cacheRead`/`cacheWrite` lambdas), `ImageEnrichmentService` (mosaic composite via platform `composeMosaic` lambda).
- AI orchestration: `AiChatService`, `AiRecommendationService`, `ChatContextProvider`, `ChatCardEnricher`. System prompts and tool-call protocol live in shared.
- AI providers (Ktor): `ChatGptProvider`, `ClaudeProvider`, `GeminiProvider`. Identical wire formats and prompt handling on both platforms.
- AI tool surface: `DjToolDefinitions` (schemas), `DjToolExecutor` interface (single-method dispatch).
- Settings: `SettingsStore` backed by `KvStore` (multiplatform-settings) + `SecureTokenStore` (encrypted tokens).
- Business logic: `ResolverScoring`, `ResolverModels`, `MetadataService`, `MetadataProvider` interface, `QueueManager`, `DeepLinkAction`, `PlaybackEngine` interface, `AppConfig`.

**iOS actuals (`shared/iosMain/`):**
- `Platform.ios.kt` — `Log` (NSLog), `randomUUID` (NSUUID), `currentTimeMillis` (NSDate)
- `DriverFactory.ios.kt` — SQLDelight Native driver
- `HttpClientFactory.ios.kt` — Ktor Darwin engine
- `PluginFileAccess.ios.kt` — `NSBundle` (bundled) + `NSApplicationSupportDirectory/plugins/` (cached)
- `KvStoreFactory.kt` — `NSUserDefaults` suite (`parachord_kmp_prefs`)
- `IosSecureTokenStore.kt` — Keychain via multiplatform-settings `KeychainSettings` (service `com.parachord.tokens`)

**What stays in :app (genuinely Android-only):**
- All Compose UI (~82 files), Android `ViewModel`s + `viewModelScope`
- Media3 stack: `PlaybackService`/`MediaSessionService`, `PlaybackController`, `PlaybackRouter`
- `SpotifyPlaybackHandler`, `AppleMusicPlaybackHandler` (`MusicKitWebBridge`), `SoundCloudPlaybackHandler`
- `JsBridge` (WebView), `NativeBridge`, `MediaScanner` (ContentResolver), `NetworkMonitor` (ConnectivityManager)
- `OAuthManager`, `OAuthRedirectActivity` (Custom Tabs), `CurrentActivityHolder`
- `PluginSyncService` (WorkManager scheduling — the underlying sync runs in shared)
- The Android `DjToolExecutor` concrete class (dispatches into `PlaybackController`); the interface lives in shared
- Entity files → typealiases to shared models (`TrackEntity = Track`, etc.)
- The `composeMosaicAndroid` function (Coil + Bitmap) forwarded into the shared `ImageEnrichmentService`

**Bridge pattern:** App-module files that were moved to shared become thin typealiases (e.g., `typealias TrackEntity = com.parachord.shared.model.Track`). This preserves all existing imports across the app without mass-renaming. The typealiases live in the original packages: `data/db/entity/`, `data/repository/`, `data/metadata/`, `ai/`, `ai/providers/`, `ai/tools/`, `plugin/`, `deeplink/`.

**Lambda forwarding pattern:** When a shared class needs platform-specific resources (file I/O, image decoding, MBID lookups), the shared signature takes a `suspend (...) -> X` lambda and the platform-specific Koin binding wires the closure. Examples:
- `cacheRead`/`cacheWrite: suspend () -> String?` for disk caches (`CriticalDarlingsRepository`, `RecommendationsRepository`, `FreshDropsRepository`, `LibraryRepository`, `MbidEnrichmentService`, `AiRecommendationService`).
- `composeMosaic: suspend (playlistId, urls) -> String?` for the Coil-based 2x2 mosaic in `ImageEnrichmentService`.
- `getPlaybackSnapshot: suspend () -> ChatPlaybackSnapshot` for `ChatContextProvider` reading from `PlaybackStateHolder`.
- `mbidEnrichTrack`/`mbidEnrichBatch` in `LibraryRepository` (kept for symmetry; `MbidEnrichmentService` is now itself shared).

## OAuth Architecture

OAuth flows use **PKCE** with a **state parameter** for CSRF protection.

- **OAuthManager** (`auth/OAuthManager.kt`) — launches Chrome Custom Tabs for Spotify, SoundCloud, Last.fm auth flows. Stores per-flow `PendingOAuthFlow(service, codeVerifier, createdAt)` keyed by a 192-bit random `state` parameter. Validates state on callback. 10-minute pruning of abandoned flows.
- **OAuthRedirectActivity** (`auth/OAuthRedirectActivity.kt`) — dedicated `noHistory="true"` / `Theme.NoDisplay` activity that receives `parachord://auth/callback/*` redirects. Handles the token exchange directly via Koin-injected `OAuthManager`, then brings MainActivity to front and finishes. This causes Chrome to close the Custom Tab automatically.
- **CurrentActivityHolder** (`app/CurrentActivityHolder.kt`) — tracks the currently-resumed Activity via `ActivityLifecycleCallbacks`. OAuthManager uses it to launch Custom Tabs from an Activity context (not Application context) so Chrome runs in the same task as MainActivity — enabling Android to pop Chrome off the back stack when the redirect fires.
- **Manifest intent filters** — `parachord://auth` goes ONLY to `OAuthRedirectActivity` (priority 999). All other `parachord://` hosts are enumerated explicitly on MainActivity to avoid disambiguation chooser overlap. Spotify Auth SDK and App Remote SDK were removed (they injected their own `RedirectUriReceiverActivity` that conflicted).

## Security Posture

A full security review was completed April 2026. The review plan is at `.claude/plans/logical-munching-waffle.md`. GitHub issues #101–#108 track remaining work.

**Completed:**
- C5: `allowBackup="false"` + `data_extraction_rules.xml` (no cloud backup of tokens)
- H2: Path traversal prevention in `PluginFileAccess.writeCachedPlugin`
- H5: OAuth state parameter + CSRF protection + per-flow verifier isolation
- H8: Release build fails if `CI_KEYSTORE_PATH` unset (no debug-keystore fallback)
- H9: `network_security_config.xml` denying cleartext traffic
- H10: XSPF SSRF block (HTTPS-only, private-IP rejection) + XXE prevention
- M8: Keystore password from env var (not hardcoded)
- M12: Explicit `usesCleartextTraffic="false"`

**All findings closed** except the deferred plugin sandbox workstream:
- #107 Plugin sandbox workstream (C1 signing, C2 fetch allowlist, H1/H7/M1/M2) — requires breaking `.axe` SDK changes, deferred to separate design pass

**Key security rules:**
- **Never add secrets to BuildConfig expecting confidentiality.** The project is open source; BuildConfig values are trivially extractable. Use per-user BYO credentials (like AI providers do) for anything that needs confidentiality.
- **Plugin signing is defense-in-depth, not critical.** HTTPS transport + GitHub repo access controls are the primary defense against supply chain attacks. Sandboxing (C3 namespaced storage, C2 fetch allowlist) matters more — a signed plugin with unrestricted NativeBridge access is just as dangerous as an unsigned one.
- **MusicKit v3 `music.volume` has no effect on Android WebView.** DRM audio goes through MediaDrm/MediaCodec, bypassing the JS audio pipeline. Volume normalization between sources can't be solved app-side.
- **Global OkHttpClient User-Agent is required.** MusicBrainz rate-limits or 403s requests with the default `okhttp/x.y.z` User-Agent. The shared OkHttpClient has a `User-Agent: Parachord/<version> (Android; https://parachord.app)` interceptor. Don't create per-API OkHttp clients without it.
- **Apple Music developer token (JWT) — rotate BOTH platforms.** The `AppConfig.appleMusicDeveloperToken` JWT (used by the shared `AppleMusicArtistProvider` gap-filler for catalog artist images) is an ES256 JWT with `exp` ≤ 6 months. It is currently hand-pasted into **two independent files** that must BOTH be updated on every rotation, or that platform's Apple Music artist images silently stop working (note: `AppleMusicArtistProvider.isAvailable()` only checks `isNotBlank()`, not `exp` — an expired token fails silently per-request):
  - **Android:** `local.properties:APPLE_MUSIC_DEVELOPER_TOKEN` (→ BuildConfig → AppConfig)
  - **iOS:** `iosApp/Parachord/Secrets.xcconfig:APPLE_MUSIC_DEVELOPER_TOKEN` (→ Info.plist `AppleMusicDeveloperToken` → `IosContainer.plist`)

  This is a separate token from the on-device *playback* token (the App Service token used by `ApplicationMusicPlayer` on iOS — see `iosApp/AGENTS.md`). The hand-paste drift + silent expiry is tracked for build-time generation from the `.p8` in [parachord-mobile#186](https://github.com/Parachord/parachord-mobile/issues/186); until that lands, **rotation = update both files above.**

## Plugin Marketplace & `.axe` Repo Sync

`.axe` plugins are the **cross-platform source of truth** for resolvers, AI providers, meta-services, AND their behavior (model lists, filters, scrobble payloads). **Do NOT natively reimplement what an `.axe` already does** — see the rule in Common Mistakes below.

**Sync topology — the hub is the desktop monorepo, NOT the mobile repo:**
- **Authoring source:** `parachord-desktop/plugins/` (GitHub `Parachord/parachord`). Plugins are edited here.
- **Forward sync** (`parachord-desktop/.github/workflows/sync-repos.yml`): push to monorepo `main` touching `plugins/**` → unconditional `cp` to `Parachord/parachord-plugins` (the marketplace) + manifest merge. **NOT version-aware** — a stale monorepo plugin DOWNGRADES the marketplace (the #754 clobber class).
- **Reverse sync** (`reverse-sync.yml`, daily 6 AM UTC + dispatch): marketplace → monorepo, **version-aware (strictly-greater only, never downgrade)**, opens a PR. Pulls community contributions back.
- **Apps consume the marketplace at runtime** via `PluginSyncService` (fetches `manifest.json` from `raw.githubusercontent.com/Parachord/parachord-plugins/main/`, downloads newer `.axe`, semver-dedups vs the bundled copy — higher wins).

**Mobile (`parachord-android`) is OUTSIDE the desktop sync.** Its bundled copies — `app/src/main/assets/plugins/*.axe` (Android) AND `iosApp/Parachord/Resources/plugins/*.axe` (iOS — a **separate** copy) — are an offline fallback; runtime sync delivers updates. A **one-direction** (marketplace → mobile) pull keeps the bundle fresh: `.github/workflows/sync-plugins.yml` + `.github/scripts/sync-bundled-plugins.js` (version-aware; updates only already-bundled plugins; never downgrades/adds/removes; PR). **No mobile → marketplace forward sync exists** — mobile never authors plugins, so one would only leak in-repo bundled edits upstream.

**Landing a plugin change — two paths:**
1. **Monorepo-first (safest):** edit `parachord-desktop/plugins/<x>.axe` + bump its `manifest.version` + `marketplace-manifest.json`, push → forward-sync ships it. No clobber window.
2. **Direct-to-`parachord-plugins` (faster):** edit the marketplace `.axe` + **bump the version** (in the `.axe` AND `manifest.json`) → the reverse-sync adopts it (strictly-greater) and PRs it back. Without a version bump the no-downgrade guard ignores it. **Clobber window:** until that reverse PR merges into the monorepo, any monorepo `plugins/**` push `cp`s the older monorepo copy over your change — merge the reverse PR promptly.

**Verify against the live remote, not the local checkout.** A local `parachord-plugins` clone may sit on a stale feature branch (it was once 50 commits behind `main`, which made the marketplace *look* drifted when it wasn't). Always check `origin/main` / the `raw.githubusercontent.com/.../main/` URL.

## DJ Chat / Shuffleupagus — shared, both platforms (#223 / #202)

- **Reuse the shared stack.** `AiChatService` (the tool loop), the 3 providers, `DjToolDefinitions`, `ChatContextProvider`, `ChatCardEnricher`, `ChatMessageDao` (per-provider history) all live in `commonMain`. A platform needs only: a `DjToolExecutor` impl (8-tool dispatch), container wiring, and the UI. Don't re-derive any of this.
- **System prompt = desktop's full `AI_CHAT_SYSTEM_PROMPT`** (`app.js` ~L5286), ported verbatim into `ChatContextProvider.buildSystemPrompt()` (scoped to the 8 mobile tools). Don't author an abridged prompt — the personality, the "you MUST call the tool (saying ≠ doing)" guardrail, the album-vs-track queueing rules (never `play`+`queue_add` together), strict personalization, and the card-syntax / common-mistakes blocks all change behavior.
- **Per-provider history — STAMP the timestamp.** `ChatMessage.toEntity()` MUST set `timestamp = currentTimeMillis()`. `ChatMessageRecord` defaults to `0L`, and `AiChatService.ensureLoaded()` prunes `timestamp < now-30d` on every load — so a 0 timestamp means history is deleted on the next load / app restart (never remembered across sessions). Was a silent bug on BOTH platforms.
- **Cards trigger the player via DIRECT calls, not `parachord://`** (desktop 20508+ / Android `onPlayTrack`). Syntax: `{{track|t|a|al}}` / `{{album|t|a}}` / `{{artist|n}}` parsed from the assistant text.
- **Model lists come from the `.axe`, never native.** Read the plugin's model setting: static `select` → its curated `options`; `dynamic-select` → `listModels` (live) falling back to the plugin's curated `fallbackOptions` when empty/keyless; seed the picker with the plugin's `default` (desktop: `metaServiceConfigs[id].model || modelSetting.default`, `app.js:59532`). On iOS the `.axe` async (`listModels`) needs the JSC unique-key poll — see `iosApp/AGENTS.md`.

## Spinoff + Listen Along — queue model (#231 / #235 / #246)

Both are iOS ports of Android's `PlaybackController`; **read desktop + Android FIRST.** A by-eye first cut of Spinoff got the model wrong (pool-as-queue + immediate kick-start) and had to be rebuilt against Android.

- **Spinoff = a SEPARATE hidden pool, NOT a replaced queue.** On start the user's `QueueManager` queue is NEVER modified — only the playback `context` is saved (`preSpinoffContext`); the current song KEEPS PLAYING (`kickStartFirstTrack=false`); `skipNext`/auto-advance pull from the pool; on exhaustion `exitSpinoff` restores the context and the untouched queue resumes. Pool seed = Last.fm `track.getsimilar`; availability is pre-checked (limit=1 `getSimilarTracks`) to gate/dim the ✨ button (`spinoffAvailable != false`). The ✨ button toggles (purple when active). Mutually exclusive with Listen Along (each exits the other).
- **UP NEXT shows the station SEED** ("Spinoff from `<track>` by `<artist>`"), NOT the next pool track. The open queue shows the user's queue dimmed as **"YOUR QUEUE"** with **"··"** track numbers (suspended state, also used by Listen Along).
- **Queue count badge turns GRAY (`#6B7280`)** while spun off / listening along, purple otherwise (desktop `app.js:55180`).
- **Listen Along** plays the friend's current track, defers track-changes to the current song's END (so each scrobbles), polls every 15s, and stops following the moment the user takes control (context ≠ `listen-along`).
- **Scrobbling metadata-only tracks (both features):** their Track entities have `duration: 0`. The scrobble THRESHOLD reads the live ENGINE duration (so it fires), but the LB submit must OMIT `duration_ms <= 0` or LB 400s the whole listen. Shared fix in `ListenBrainzClient.submitListens` + `ListenBrainzScrobbler` (guarded by `ListenBrainzSubmitListensTest`); applies to ANY metadata-only track (weekly playlists, recommendations, DJ chat).

## Common Mistakes to Avoid

### Both Platforms

1. **Don't assume provider responsibilities.** MusicBrainz handles discography/tracklists. Last.fm handles bios/images. Check the desktop first.
2. **Don't use `sources.firstOrNull()` / `sources.first`.** Always use `ResolverScoring.selectBest()` (or its iOS equivalent) for source selection.
3. **Don't skip the resolver pipeline.** Even if you have a direct URL, route through `ResolverManager` → `ResolverScoring` → `PlaybackRouter` to maintain consistent behavior.
4. **Don't use blue as the accent color.** The brand accent is purple (`#7c3aed` light / `#a78bfa` dark).
5. **Don't return or select sources below the confidence floor.** `ResolverScoring.selectBest()` filters out sources with confidence < `MIN_CONFIDENCE_THRESHOLD` (0.60). `scoreConfidence()` returns 0.95 ONLY when BOTH title AND artist substring-match — single-axis matches (wrong artist, or wrong title) collapse to 0.50 so the floor filters them. This mirrors desktop's `validateResolvedTrack` two-stage gate exactly: validation pass + priority sort. A title-only match (e.g., a different artist's "Mariana") must NOT win against a correct local file just because applemusic has higher priority. If porting to iOS, replicate this gate.
6. **Don't natively reimplement `.axe` plugin functionality — ASK FIRST.** The `.axe`/marketplace plugin is the cross-platform source of truth: portability, marketplace updates, "one place for everyone." If a plugin already provides something — `resolve`, `listModels`, the model `options`/`fallbackOptions`/`default`, scrobble, AI chat — **call the plugin, don't write a native Kotlin/Swift equivalent.** A native reimplementation forks behavior and goes stale (we shipped a native per-provider `listModels` when `chatgpt.axe`/`gemini.axe` already had one — #223; it was reverted). **Before writing any native reimplementation of `.axe` logic, STOP and check with the user, with a concrete reason** (e.g. the `.axe` genuinely can't do it on this platform). The ONE legitimate native layer is the host GLUE that *runs* the `.axe` (e.g. iOS's JavaScriptCore unique-key polling for async `.axe` calls, the `NativeBridge` fetch polyfill) — never a reimplementation of the plugin's own logic. **Corollary (dispatch side): don't hardwire per-service hosts/logic into a feature** (`when (host) { "open.spotify.com" -> … }`) — route through the resolver registry (`findResolverForUrl` + `lookupPlaylist`/`resolve`/…) so new service plugins drop in with zero feature code. See "Extensibility principle — abstract over the plugin registry" under the .axe Plugin System section.

### KMP / shared/commonMain rules

When writing or editing code in `shared/src/commonMain/`, the JVM stdlib is NOT available — only Kotlin stdlib + Kotlin/Native bridges + the dependencies declared in `shared/build.gradle.kts`. These JVM-isms compile fine on Android then break the iOS build:

1. **Don't use `System.currentTimeMillis()`.** Use the shared `com.parachord.shared.platform.currentTimeMillis()` expect (which delegates to `System.currentTimeMillis()` on Android and `NSDate().timeIntervalSince1970 * 1000` on iOS).
2. **Don't use `java.util.UUID.randomUUID().toString()`.** Use the shared `com.parachord.shared.platform.randomUUID()` expect.
3. **Don't use `@Volatile` without an import.** The default-resolved `kotlin.jvm.Volatile` is JVM-only. Add `import kotlin.concurrent.Volatile` (the KMP version).
4. **Don't use `Map<K,V>.toSortedMap()`.** It returns `java.util.TreeMap` and doesn't exist on Native. Use `.entries.sortedBy { it.key }` instead.
5. **Don't use `java.util.concurrent.ConcurrentHashMap` / `newKeySet()`.** Replace with `kotlinx.coroutines.sync.Mutex` + a plain `MutableMap` / `MutableSet` (suspend lock around access). The 5 enrichment + repository moves all follow this pattern.
6. **Don't use `Dispatchers.IO`.** commonMain only has `Default` and `Main`. The shared DAOs already wrap their queries in `withContext(Dispatchers.Default)`, and Ktor is async by default — most call sites can just drop the `withContext(Dispatchers.IO)` wrapper.
7. **Don't use `SecurityException`, `IOException`, `IllegalThreadStateException`, etc.** Stick to Kotlin's `IllegalArgumentException` / `IllegalStateException` / generic `Exception`. The exception type rarely matters — callers usually catch `Exception` anyway.
8. **Don't use `java.io.File` / `java.text.SimpleDateFormat` / `java.util.Date` / `java.util.Locale`.** For dates, use `kotlinx-datetime` (`Clock.System.todayIn`, `LocalDate`, `DateTimeUnit`). For file I/O, see the lambda forwarding pattern below.
9. **Lambda forwarding for platform-specific resources.** When a shared class needs file I/O, image decoding, MBID lookups, or anything else that's platform-tied, the shared signature takes a `suspend (...) -> X` lambda and the platform-specific Koin binding wires the closure. See the "Lambda forwarding pattern" section under "KMP Shared Module" for examples (`cacheRead`/`cacheWrite`, `composeMosaic`, `getPlaybackSnapshot`, etc.).
10. **Use `internal` sparingly across modules.** `internal` in `shared/commonMain` means visible within the `:shared` module. Code in `:app` (different Gradle module) can't see internals. If a helper needs to be tested from `:app` tests, make it `public` (or move the test into shared).
11. **Don't name a Swift-facing model property `description`** (or `hash`, `debugDescription` — any `NSObject` member). On the iOS side a Kotlin `description` property is shadowed by ObjC's `NSObject.description`, so Swift reads back the data-class `toString()` instead of the field value — a silent mis-render, not a compile error. Use `summary` / `blurb` / `text`. This applies to any `data class` in `shared/` (commonMain or iosMain) that Swift consumes. Shipped + fixed once on the iOS Discover screen.
12. **Don't call a `crossinline` builder lambda bare inside a Ktor request block — bind its receiver with `apply()`.** `LastFmClient.guardedGet` built each request as `httpClient.get(BASE_URL) { build(); parameter("format","json") }` where `build: crossinline HttpRequestBuilder.() -> Unit`. A bare `build()` does **not** bind its receiver to the `get` builder, so it silently no-ops — every Last.fm request went out with only `format=json` and **no** `method`/`artist`/`api_key`, Last.fm returned 400 error 6, and every Last.fm call (artist top tracks/info, charts, friends, recent tracks) came back **empty**. Fails identically on JVM (Android) and Native (iOS); was latent + invisible on Android because Spotify supplied the same metadata. Fix: `httpClient.get(BASE_URL) { apply(build); … }`. Regression guard: `LastFmClientParamsTest`. (June 2026, #183.) General lesson: when a request-builder lambda's params silently vanish, suspect an unbound receiver.

> **iOS app guide:** everything iOS-specific — Xcode `project.pbxproj` mechanics, the full set of KMP→Swift bridging gotchas (primitive `Double` bridges directly with no `.doubleValue`; big initializers overwhelm Swift's type-checker in closures; flatten nested response types to DTOs; `FlowWatcher` for collecting Flows), JavaScriptCore polyfill split (Kotlin owns lifecycle, Swift attaches callables), CoreGraphics/UIKit (`UIImageJPEGRepresentation`, `format.scale = 1.0`), and the simulator build/screenshot workflow — lives in **`iosApp/AGENTS.md`**. Read it before touching `iosApp/` or `shared/src/iosMain/`.

### Android-Specific

5. **Don't double URL-decode.** Jetpack Navigation already decodes URI path segments. Use `Uri.encode()` for encoding (not `URLEncoder.encode()` which uses `+` for spaces).
6. **Don't play unresolved tracks without on-the-fly resolution.** Ephemeral tracks (weekly playlists, recommendations, DJ chat results) have no `resolver`, `sourceUrl`, or streaming IDs when first created — they're just metadata (title/artist/album). `PlaybackController.playTrackInternal` will resolve them on-the-fly if needed, but for best UX call `TrackResolverCache.resolveInBackground()` when tracks are loaded so resolver badges appear and playback starts faster.
7. **Don't use grids for horizontally-scrollable content on mobile.** Desktop's multi-column grids don't work on narrow screens — use `LazyRow` carousels with fixed-width cards instead. The home screen's Weekly Jams/Exploration sections use this pattern.
8. **Ephemeral playlists need their own screen and ViewModel.** Weekly playlists from ListenBrainz are not stored in Room — they use `WeeklyPlaylistScreen`/`WeeklyPlaylistViewModel` with a "Save" button (matching desktop's ephemeral playlist pattern). Don't try to reuse `PlaylistDetailScreen` which expects a Room-backed `PlaylistEntity`.
9. **Don't use `isSystemInDarkTheme()` in composables.** The app has a manual dark/light/system toggle. `isSystemInDarkTheme()` only checks the Android system setting, not the app preference. Use `ParachordTheme.isDark` instead, which reads from `LocalIsDarkTheme` provided by `ParachordTheme`. See the Dark Mode section above.
10. **Don't block the Apple Music polling loop.** Never call `withContext` on a suspend function that awaits a network response (like `MusicKitWebBridge.evaluate()` / `preload()`) inline in `startAppleMusicStatePolling()`. This freezes position updates and the safety-net auto-advance check. Use `scope.launch` (fire-and-forget) for any async work that doesn't need its result in the same loop iteration.
11. **Don't use inconsistent null defaults in filter/count pairs.** When counting items by a nullable field and filtering by it, both operations must normalize nulls the same way. E.g., `groupBy { it.type?.lowercase() ?: "album" }` counts null as "album", but `filter { it.type?.lowercase() == "album" }` excludes null. Use `((it.type?.lowercase()) ?: "album")` in both places.
12. **Don't call `router.stopExternalPlayback()` before `playTrackInternal()` in advance paths.** The async `stop()` races with the new `play()` — MusicKit's deferred stop arrives after the new track starts, pausing it. `handler.play()` replaces the queue directly for same-handler transitions. See "External Playback Background Survival" section.
13. **Don't call `ctrl.stop()` during external→external transitions.** Stopping ExoPlayer triggers `MediaSessionService` auto-demotion from foreground. Skip `stop()`/`prepare()` when `wasAlreadyExternal` is true — just update metadata with `setMediaItems()`.
14. **Don't call `WebView.onResume()`/`resumeTimers()` from Activity lifecycle callbacks.** These can block the main thread when the Chromium renderer is suspending, causing a background ANR. Call `keepAlive()` from `MusicKitWebBridge.play()` instead (runs in foreground before each track).
15. **Don't use `LAYER_TYPE_HARDWARE` on the MusicKit WebView.** The headless WebView doesn't render visually; hardware acceleration causes GPU context teardown on screen-off that disrupts audio. Use `LAYER_TYPE_NONE`.
16. **Don't call `MusicKitWebBridge.preload()` during active playback.** The catalog API request through the same MusicKit JS instance disrupts the active audio stream. Pre-resolve the Apple Music ID only; skip the actual `preload()` call.
17. **Don't run end-detection on stale Apple Music poll data.** When `pollPlaybackState()` times out (Main thread deferred in Doze), cached `isPlaying` may be stale. Only check `isOurTrackDone()` when the poll succeeded.
18. **Don't fire Spotify's stale-position watchdog during poll failures.** If API polls are failing (Doze network delays), position won't update — but that doesn't mean the track stopped. Only trigger the 15s stale-position watchdog when `consecutivePollFailures == 0`.
19. **Don't use the Spotify launch intent as the primary wake strategy.** It opens Spotify's activity, which is jarring UX. Use the media button broadcast (invisible) first; only fall back to the launch intent after 2s if the broadcast fails (Spotify fully killed). Do NOT immediately bring our app back to front after launching — this causes Android to deprioritize Spotify before it can initialize and register as a Connect device.
20. **Don't assume `deviceVerified` means Spotify is running locally.** Remote devices (TVs, computers) appear in the Spotify API without local Spotify running. `resolveLocalDevice()` must always call `ensureSpotifyRunning()` when the user picks "This device", regardless of `deviceVerified`.
23. **Don't save the resolved Spotify device ID as the preferred device when the user picks "This device".** The real device ID changes between Spotify sessions (process restarts). Save `LOCAL_DEVICE_ID` instead so the preference survives cold starts. The `resolveLocalDevice()` function resolves the real ID at play time.
24. **Don't use a short timeout after launching Spotify from a killed state.** Cold-launching Spotify takes ~5-6s to register as a Connect device (process start + auth + server registration). Extend the polling deadline to 12s when the launch intent fallback fires.
21. **Don't use compounding retry layers for Spotify playback.** The old `playWithRetry(3) × attemptPlay 502 retries(3) × verification(5 polls)` compounded to 27.5s worst case. Use a single-pass flow: one `attemptPlay()` with a single 502 retry and 2 quick verification polls.
22. **Don't require preferred Spotify devices to be active.** An inactive but present device just needs a transfer. Only show the device picker when the preferred device is completely absent from the API response (device removed/renamed).
25. **Don't use synchronous `NativeBridge.fetch()` for .axe plugin HTTP requests.** Synchronous fetch blocks the WebView JS thread, preventing concurrent plugin execution (e.g., Bandcamp search blocks while YouTube fetch hangs). Use `NativeBridge.fetchAsync()` with a callback pattern instead — HTTP runs on `Dispatchers.IO`, result injected back via `evaluateJavascript()`.
26. **Don't use JS template literals (backticks) to pass .axe JSON to `evaluateJavascript`.** Backticks and `$` in the embedded JS code break the template string. Use Base64-encoding: `atob('${base64}')` in JS to decode.
27. **Don't use async IIFE with `evaluateJavascript`.** It returns a `Promise` object (`{}`) not the resolved value. Use a callback pattern: store result in `window.__lastPluginResult`, then read it in a second `evaluate()` call.
28. **Don't manage audio focus explicitly during external playback.** Chromium's WebView has its own `AudioFocusDelegate`. Our explicit `requestAudioFocus()` competed with it, causing `AUDIOFOCUS_LOSS` → we paused MusicKit → stopped playback mid-song. Let Chromium handle it for WebView audio; Spotify handles its own.
29. **Don't use `catch (e: Exception)` in coroutine loops that should be cancellable.** `CancellationException` extends `Exception` in Kotlin. A generic catch swallows it, letting the loop continue through every iteration after cancellation (20 phantom "failed" logs in microseconds). Always `catch (e: CancellationException) { throw e }` before the generic catch, or use `catch (e: Exception) { if (e is CancellationException) throw e; ... }`.
30. **Don't call `transferPlayback()` before `startPlayback()` for Spotify.** The separate transfer activates the device, which causes Spotify to auto-resume its last track for ~270ms before our `startPlayback` replaces it. `startPlayback` with `device_id` activates inactive devices automatically — the transfer is redundant and causes an audible blip.
31. **Don't fire `startPlayback` with a cached Spotify device ID without verifying it first.** Spotify device IDs change when Spotify's process restarts. Call `getDevices()` first, check the cached ID is still in the list. If stale, clear `lastResolvedLocalId` and `deviceVerified` and fall through to the normal wake flow. Without this, stale IDs return 404 and the full flow (including the visible launch intent) fires needlessly.
32. **Don't create per-API OkHttpClients without the User-Agent interceptor.** The shared `OkHttpClient` in `AndroidModule.kt` has a global `User-Agent: Parachord/<version>` interceptor. MusicBrainz specifically blocks the default `okhttp/4.x.y` UA with HTTP 403. If you need a per-API client (e.g., different timeout), use `client.newBuilder().addInterceptor(...)` to inherit the UA interceptor.
33. **Don't use JS `if` statements inside `MusicKitWebBridge.evaluate()`.** The `evaluate()` wrapper wraps the script as `var p = $script;` — an `if` statement is not a valid JS expression. Use IIFE: `(function() { if (...) ...; return true; })()` or the Base64 approach from PluginManager.
34. **Don't use nested `hapticClickable` / `clickable` modifiers in LazyColumn items.** When the list updates progressively (e.g., Fresh Drops emitting new results every 5 artists), closures in LazyColumn items can fire with stale-captured data, navigating to the wrong item. Use a single click target per row.
35. **Don't call `MutableList.removeFirst()` / `removeLast()`.** JDK 21 added native `List.removeFirst()` methods, and Kotlin 2.0+ compiles the call to the JVM method instead of the old stdlib extension. Android's runtime (API ≤ 35) has no such method, so the call throws `NoSuchMethodError` at runtime. Use `removeAt(0)` / `removeAt(lastIndex)` instead. Unit tests catch this on JDK 17 too — the same bytecode resolves through the JDK interface.
36. **Unit tests need `testOptions.unitTests.isReturnDefaultValues = true`.** Otherwise any production code that calls `Log.d`, `Uri.parse`, or any other `android.*` method from under test throws `RuntimeException: Method ... not mocked`. This is set in `app/build.gradle.kts` — don't remove it. If you legitimately need an Android stub to return something other than the default, mock the specific call in that test.

### iOS-Specific

6. **Don't use SwiftUI `.tint()` or `.accentColor()` globally.** These override with system blue. Set explicit purple on each interactive element.
7. **Don't forget `AVAudioSession` configuration.** Call `setCategory(.playback)` before any `AVPlayer.play()` — otherwise audio won't play when the app is backgrounded or the device is silenced.
8. **Don't use `NavigationView`.** It's deprecated. Use `NavigationStack` (iOS 16+).
9. **Don't percent-encode URLs manually.** Use `URL(string:)` which handles encoding. For query parameters, use `URLComponents` with `URLQueryItem`.
10. **Don't block the main actor.** Network calls and metadata lookups must run in a `Task` or detached context. Use `@MainActor` only for UI state updates.
11. **Don't use `Timer` for playback progress.** Use `AVPlayer.addPeriodicTimeObserver(forInterval:queue:using:)` instead — it's synchronized with the audio clock.
12. **Don't forget to handle Spotify SDK auth flow.** The iOS SDK requires handling the redirect URL in `SceneDelegate.scene(_:openURLContexts:)` or via `.onOpenURL` in SwiftUI.

## Deep Link Handling

### Spotify Branch.io Referrer Format

Spotify's link sharing wraps real URLs in a Branch.io referrer: `spotify://open?_branch_referrer=<gzip+base64 data>`. The compressed payload contains `$full_url=https://open.spotify.com/artist/xxx`. `DeepLinkHandler.parseSpotifyUri()` detects this format, decompresses via `GZIPInputStream(Base64.decode(...))`, extracts the `$full_url` parameter, and routes it through `parseSpotifyUrl()`.

### Spotify URL Resolution

Spotify URLs contain opaque IDs (`/artist/4Z8W4fKeB5YxbusRwVAqVK`) with no human-readable name. `ExternalLinkResolver.resolveSpotifyArtist()` calls `GET /v1/artists/{id}` to get the name, then navigates to the artist page. Requires a valid Spotify access token — if missing, shows a helpful error directing the user to connect Spotify in Settings.

## AI Provider Integration Learnings

### Timeouts & Reliability

- **AI generation needs long timeouts.** LLM responses routinely take 30–60 seconds. Create a separate `OkHttpClient` (or `URLSession` config) with 60s read timeout for AI calls. The default 10–15s timeout will fail constantly.
- **Always surface AI errors to the user.** Don't silently swallow failures — show a toast/snackbar so users know something went wrong rather than staring at an empty screen.

### JSON Parsing from AI Providers

- **AI models wrap JSON in preamble text.** Even when asked for JSON, models often add "Here's the JSON:" or markdown fences before the actual JSON. Use a brace-depth-tracking extractor (`extractJsonObject()`) to find the JSON within the response — don't assume the entire response is valid JSON.
- **Use API-level JSON mode when available.** ChatGPT supports `response_format: { "type": "json_object" }`. Gemini supports `responseMimeType: "application/json"`. These dramatically improve reliability.
- **Claude has no native JSON mode.** Use the prefill trick: set the first assistant character to `{` so Claude continues the JSON object directly. Then reconstruct: `"{" + response.content`.
- **Always use `ignoreUnknownKeys = true`** when parsing AI-generated JSON. Models may add extra fields.

### Tool Message Ordering (DJ Chat)

- **ChatGPT requires strict tool message ordering:** `[ASSISTANT(toolCalls), TOOL(result1), TOOL(result2), ...]`. Tool result messages must immediately follow the assistant message that requested them, paired by `tool_call_id`.
- **History pruning can orphan tool messages.** When trimming old messages, never split an ASSISTANT+toolCalls message from its TOOL result messages. Walk backward to keep these groups intact. Add a `sanitizeHistory()` pass to remove any orphaned TOOL messages.
- **Claude expects tool results as user messages** with `tool_result` content blocks and `tool_use_id` fields.

### AI Suggestions Caching (Stale-While-Revalidate)

- **AI suggestions must load stale cache first, then refresh.** Like the desktop, `AiRecommendationService` persists recommendations to `ai_suggestions_cache.json` so cold starts show previous results immediately instead of a 30-60s shimmer wait. The disk cache is lazy-loaded on first access to `cachedRecommendations`, and saved after each successful AI fetch.
- **No TTL — always refresh.** Unlike Fresh Drops (6h TTL) or Critical Darlings (4h TTL), AI suggestions always fetch fresh results but display stale cache during the wait. This matches desktop's pattern where cached albums/artists stay visible while `loading: true`.
- **`AiAlbumSuggestion` and `AiArtistSuggestion` are `@Serializable`.** Required for disk cache persistence.

### Prompt Engineering for Music Recommendations

- **AI models recommend genre names as track titles** if not explicitly told otherwise. Prompts must say "recommend real, specific songs by real artists — not genre names or descriptions."
- **Always use the user's configured AI provider** (from settings), not a hardcoded default. Check `settingsStore.selectedChatPlugin`.
- **Including listening history context** (recently played tracks, top artists) significantly improves recommendation quality. Make this opt-in with a user toggle for privacy.

### Key AI Files

The full AI surface is in `shared/.../ai/` (April 2026). App-side files in `app/.../ai/` are typealias shims.

| Area | Shared file | App shim |
|------|-------------|----------|
| Recommendation generation | `shared/.../ai/AiRecommendationService.kt` | `ai/AiRecommendationService.kt` |
| DJ chat orchestration | `shared/.../ai/AiChatService.kt` | `ai/AiChatService.kt` |
| Provider interface + models | `shared/.../ai/AiChatProvider.kt` | `ai/AiChatProvider.kt` |
| ChatGPT provider (Ktor) | `shared/.../ai/providers/ChatGptProvider.kt` | `ai/providers/ChatGptProvider.kt` |
| Claude provider (Ktor) | `shared/.../ai/providers/ClaudeProvider.kt` | `ai/providers/ClaudeProvider.kt` |
| Gemini provider (Ktor) | `shared/.../ai/providers/GeminiProvider.kt` | `ai/providers/GeminiProvider.kt` |
| DJ tool schemas | `shared/.../ai/tools/DjToolDefinitions.kt` | `ai/tools/DjToolDefinitions.kt` |
| DJ tool executor (interface) | `shared/.../ai/tools/DjToolExecutor.kt` | — |
| DJ tool executor (Android impl) | — | `ai/tools/DjToolExecutor.kt` |
| Listening history context | `shared/.../ai/ChatContextProvider.kt` | `ai/ChatContextProvider.kt` |
| Card artwork enrichment | `shared/.../ai/ChatCardEnricher.kt` | `ai/ChatCardEnricher.kt` |
| JSON helpers (mapToJsonElement) | `shared/.../ai/JsonHelpers.kt` | — |
| Recommendations UI | — | `ui/screens/discover/RecommendationsScreen.kt`, `RecommendationsViewModel.kt` |
