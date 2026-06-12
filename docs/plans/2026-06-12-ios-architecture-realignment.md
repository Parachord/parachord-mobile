# iOS Architecture Realignment — finish the KMP shared migration

> Goal: bring the iOS implementation back in line with the documented KMP architecture
> (`docs/kmp-migration-plan.md`, `docs/cross-platform-mobile-strategy.md`, CLAUDE.md
> "KMP Shared Module"). Business logic — repositories, resolver pipeline/coordination,
> caching of repo data, scrobblers — belongs in `shared/commonMain`; only UI + native
> playback stay per-platform.

## Audit findings (drift), highest-value first

### A. `IosContainer` collect-and-return-last anti-pattern (ROOT CAUSE) — HIGH
`IosContainer` exposes `loadXxx()` suspend funcs that `.collect` a repository `Flow`
and return only the LAST value, discarding the repo's **cached-first** emission.
Affected (`IosContainer.kt`): `loadRecommendedTracks` (324), `loadRecommendedArtists`
(332), `loadCriticalDarlings` (387), `loadConcerts` (411), `loadTopTracks` (430),
`loadTopAlbums` (435), `loadTopArtists` (440), `loadRecentTracks` (445),
`loadFreshDrops` (465).

Because the cached emission is thrown away, the Swift side cold-starts blank and waits
for the network — so I bolted **parallel iOS view caches** on top:
`ios_critical_view.json`, `ios_concerts_view.json`, `ios_fresh_view.json`,
`discover_previews.json`, plus the `encodeCriticsPicks/Concerts/FreshDrops` +
`decode*` shims (`IosContainer.kt:709-725`). The repos ALREADY persist to disk via
their `cacheRead`/`cacheWrite` lambdas, so these are redundant — and the two caches
fighting is the likely cause of the **Concerts "cache not working"** bug.

**Fix:** expose each repo's value-flow to Swift (unwrap `Resource` in Kotlin so Swift
gets a plain `Flow<List<T>>`), watch it via the existing `FlowWatcher`, and update the
view model on every emission (cached-first → instant, fresh → fade-in). Then delete the
iOS view-cache JSON + the `encode*/decode*` curated-list shims.

```kotlin
// IosContainer — example
fun criticsPicksFlow(): Flow<List<CriticsPickAlbum>> =
    criticalDarlingsRepository.getCriticsPicks(false)
        .mapNotNull { (it as? Resource.Success)?.data }
```
```swift
// model — watch instead of load()
watcher.watch(flow: container.criticsPicksFlow()) { v in
    if let list = v as? [CriticsPickAlbum], !list.isEmpty {
        withAnimation(.easeInOut(duration: 0.4)) { self.albums = list }
    }
}
```
Note: `loadConcerts` is two-step (recommended artists → `getPersonalizedEvents`); its
flow needs the artists first, so it's the last/most involved of this group.

### B. Resolver coordinator duplicated per platform — HIGH/structural
`IosResolverCoordinator.kt` (iosMain) and Android `ResolverManager.kt` (app)
re-implement the same fan-out + native-branch + re-score + rank orchestration.
`ResolverScoring`/`ResolverModels` are already shared; the orchestration is not — so
fixes (disabled-resolver gate, additive retry, AM bidirectional match) had to be
written twice.

**Fix:** extract a `commonMain` `ResolverCoordinator` holding the orchestration
(`resolveSources`, re-score, `selectRanked`, native-branch gating on active/disabled),
with an `expect/actual ResolverRuntime` for the platform async bits (iOS JSC
unique-key polling vs Android direct `PluginManager.resolve`). Both platforms call the
shared coordinator. Keep the JSC polling workaround in `iosMain` (genuinely platform-
specific).

### C. Scrobblers not shared (#193) — HIGH
`ScrobbleManager` + `Scrobbler` + `ListenBrainz/LastFm/LibreFm/AxeScrobbler` are still
in `app/` though the migration plan (kmp-migration-plan.md:569, 609-614) lists them as
a "direct move" to `commonMain`. Android-isms to abstract: raw `okHttpClient` →
shared Ktor clients (`ListenBrainzClient.submitListens`, new `LastFmClient.scrobble/
updateNowPlaying`), `android.util.Base64` → Kotlin/shared, `JsBridge` → shared
`PluginManager` for `.axe` dispatch, `TrackEntity` → shared `Track`. Then wire iOS:
container binding + a hook from the playback coordinator (start → now-playing; past
threshold → scrobble with MBIDs) + Achordion `submitTrackLinks` (already shared) +
per-scrobbler settings UI (not General).

### D. `ArtistImageCache` (Swift) duplicates ImageEnrichmentService caching — MEDIUM
`ResolverBadgeRow.swift:174-213` persists `artist-images.json` itself. Should live in
(or behind) the shared `ImageEnrichmentService`/metadata layer.

### Acceptable per-platform (NO change)
- `PlaybackRouter.swift`, `IosSpotifyConnect` (ContentView) — native playback.
- `ResolverBadgeRow` rendering, `Interactions.swift` — pure UI.
- `IosTrackResolverCache` visibility/queue/throttle coordination — UI-loop optimization
  (Android uses a different scheduler); the resolve/rank calls it makes ARE shared.
- `resolver-cache.json` disk persistence — temporary stopgap until iOS SQLDelight DB
  (Phase 3) lands; then it becomes `trackDao.backfillResolverIds` like Android.

## Execution order
1. **A** — curated-list flows (fixes Concerts bug, deletes the drift I introduced). Do
   Critical Darlings first as the pattern, then Fresh Drops, Recommendations, History,
   Discover previews, then Concerts (two-step). Delete iOS view caches + encode/decode
   shims as each converts.
2. **C** — scrobblers → shared + iOS wiring (#193).
3. **B** — shared `ResolverCoordinator` (collapses the two implementations).
4. **D** — artist image cache into shared.

Each step: build iOS (`xcodebuild ... -destination id=3D28327F-...`) + Android
(`./gradlew :app:compileDebugKotlin`) green before moving on.
