# Cross-Platform Mobile Strategy for Parachord

## Context

Parachord exists as three codebases today:
- **Desktop**: Electron + React 18 + Tailwind (~57,700 lines in `app.js`) — the source of truth
- **Android**: Native Kotlin + Jetpack Compose (~51K lines, 186 files) — fully built
- **iOS**: Not yet built (port mapping exists in CLAUDE.md)

The question: what's the best path to supporting both Android and iOS from a single codebase (or at least with maximum code sharing)?

### What makes Parachord hard to cross-platform

1. **Multiple playback backends**: Spotify Connect (Web API), Apple Music (MusicKit — WebView bridge on Android, native framework on iOS), SoundCloud (HTTP streams), local files (ExoPlayer/AVPlayer)
2. **Background audio survival**: Foreground services, media sessions, Doze mode handling, WebView lifecycle management during screen-off
3. **Platform-specific SDKs**: Spotify App Remote SDK (different per platform), MusicKit (fundamentally different implementation per platform)
4. **Tight-loop polling**: 500ms Apple Music state polling, 300ms Spotify device polling — must not block or jank
5. **Complex native integrations**: OAuth via Custom Tabs/ASWebAuthenticationSession, Room database with 11 migrations, media notifications, home screen widget

### The .axe plugin system changes the equation (implemented April 2025)

The Android app now runs the **same .axe plugin system as the desktop Electron app**. This is significant for cross-platform strategy because:

1. **19 .axe plugins loaded on Android** via a headless WebView (`JsBridge`) running `resolver-loader.js` unchanged from desktop. The same JSON plugin files with embedded JavaScript execute on both platforms.

2. **`JsRuntime` interface** (`plugin/JsRuntime.kt`) abstracts the JS execution layer. Android implements it via `WebView`; iOS would implement it via `JavaScriptCore` (built into iOS, no WebView needed). The `PluginManager` depends on `JsRuntime`, not `JsBridge` — making it KMP-shareable.

3. **What's now shared via .axe plugins (no per-platform implementation needed):**
   - Resolver search/resolve (Bandcamp, future resolvers)
   - AI providers (ChatGPT, Gemini, Claude, Ollama) — dynamic model lists
   - Metadata services (Discogs, Wikipedia)
   - Concert services (Ticketmaster, SeatGeek, Bandsintown, Songkick)
   - Plugin marketplace sync from GitHub repo
   - Plugin versioning, hot-reload, enable/disable

4. **What this means for KMP migration:** The .axe plugin system is inherently cross-platform (JS runs everywhere). The Kotlin wrapper (`PluginManager`, `PluginSyncService`) is pure business logic that moves directly into a KMP shared module. Only the `JsRuntime` implementation is platform-specific — and it's ~100 lines per platform.

5. **What this means for native Kotlin code:** Some native Kotlin implementations (AI providers, metadata providers) can now be **gradually replaced** by .axe plugin delegation, reducing the native code that needs to be maintained per-platform. The native implementations remain as high-performance fallbacks where JS bridge latency matters (e.g., resolver search where native Spotify/Apple Music APIs are faster).

### Code shareability analysis (updated with .axe plugin system)

| Layer | Lines (approx) | Shareable? |
|-------|----------------|------------|
| **.axe plugin system (PluginManager, JsRuntime, sync)** | **~1.5K** | **100% — JsRuntime interface makes it KMP-ready** |
| **.axe plugins (19 JSON+JS files, resolver-loader.js)** | **~2K** | **100% — same files on all platforms** |
| API clients (Spotify, Last.fm, MusicBrainz, LB, etc.) | ~5K | 100% — pure HTTP + serialization |
| Repositories (13 repos) | ~8K | ~90% |
| Resolver pipeline (ResolverManager, Scoring, Cache) | ~3K | 95% — .axe resolvers integrated alongside native |
| AI services (native + AxeAiProvider wrapper) | ~5K | 95% — .axe AI providers for future services |
| Scrobblers (native + AxeScrobbler wrapper) | ~3K | 100% — wrapper ready for .axe migration |
| Metadata providers + enrichment | ~4K | 90% |
| Playlist/XSPF parsing, sync engine | ~3K | 85% |
| **Subtotal shareable** | **~35K** | **~73%** |
| Playback handlers (Spotify, AppleMusic, SoundCloud, ExoPlayer) | ~5K | 0% — fundamentally platform-specific |
| PlaybackService (MediaSession, foreground service, silent playback) | ~3K | 0% |
| MusicKit WebView bridge | ~1.5K | 0% (Android=WebView, iOS=native MusicKit) |
| JsBridge (Android WebView implementation of JsRuntime) | ~0.1K | 0% — iOS uses JavaScriptCore instead |
| NativeBridge (Android-specific fetch/storage polyfills) | ~0.2K | 0% — iOS version uses URLSession/UserDefaults |
| UI (Compose screens, components, navigation) | ~10K | Depends on approach |
| Database (Room entities, DAOs, migrations) | ~3K | 50% (entities yes, DAOs no) |
| **Subtotal platform-specific** | **~23K** | |

---

## Approach Comparison

### 1. Kotlin Multiplatform (KMP) + Compose Multiplatform

**What it is:** Share Kotlin business logic across Android/iOS via a `shared` module. UI via Compose Multiplatform (Compose rendering on iOS via Skia).

**Migration effort:** Medium. Extract existing Kotlin into a shared module. Android app barely changes — it imports from `shared` instead of local packages. iOS app written in Compose Multiplatform (or SwiftUI consuming shared Kotlin).

**Code sharing:**
- Business logic: ~68% shared immediately (Kotlin → Kotlin, minimal changes)
- UI: Compose Multiplatform shares ~80% of UI code if you go all-in
- Database: Room → SQLDelight (cross-platform, SQL-based, similar API)
- Networking: Retrofit → Ktor (Kotlin-native, multiplatform)
- DI: Hilt → Koin (multiplatform) or manual DI

**Native integration quality:**
- ExoPlayer (Android) / AVPlayer (iOS) via `expect`/`actual` declarations — clean abstraction
- Spotify SDK: `expect`/`actual` for each platform's SDK
- MusicKit: WebView bridge on Android (keep as-is), native MusicKit on iOS via `actual` implementation
- Background audio: Platform-specific `actual` implementations — this is the hardest part
- OAuth: Platform-specific `actual` for Custom Tabs / ASWebAuthenticationSession

**Performance:** Native Kotlin on both platforms. Polling loops run at native speed. No JS bridge overhead.

**Risks:**
- Compose Multiplatform on iOS is production-ready (1.6+, used by Google apps) but less mature than SwiftUI
- Some iOS developers dislike non-native UI frameworks
- Debugging Kotlin/Native on iOS can be harder than pure Swift
- Hilt doesn't work multiplatform — need to migrate DI

**Desktop code sharing:** Significant via .axe plugins — the same 19 plugin files run on desktop (Electron), Android (WebView), and iOS (JavaScriptCore). AI providers, metadata services, resolver logic, and concert services are shared across ALL THREE platforms through the plugin system. This is code sharing that KMP alone couldn't provide (Kotlin doesn't run in Electron).

**Verdict:** Best incremental path. Preserves your 51K lines of investment. ~73% shared day one (up from ~68% before the .axe plugin system). The `JsRuntime` interface is already defined and implemented on Android — iOS just needs a ~100-line `JavaScriptCore` implementation.

---

### 2. React Native / Expo

**What it is:** JavaScript/TypeScript UI framework rendering native components. Expo adds managed build toolchain.

**Migration effort:** High — complete rewrite of all 51K lines from Kotlin to JS/TS. Every screen, every ViewModel, every data layer component.

**Code sharing:**
- With desktop: Potentially significant — desktop is React + JS. Could share API clients, resolver logic, scoring algorithms if extracted to a shared JS package
- Between platforms: ~90% shared (that's React Native's strength)
- But: You'd rewrite everything from scratch first

**Native integration quality:**
- ExoPlayer/AVPlayer: Need native modules. `react-native-track-player` exists but is opinionated and may not support your multi-backend architecture
- Spotify SDK: Community libraries exist (`react-native-spotify-remote`) but maintenance is inconsistent
- MusicKit: Would need a custom native module for iOS. Android would still use WebView bridge (could reuse HTML/JS from desktop)
- Background audio: Possible but tricky — React Native's bridge adds latency to lifecycle events
- OAuth: `react-native-app-auth` works well

**Performance:**
- JS bridge adds ~5-10ms per native call
- 500ms polling loops: Probably fine, but GC pauses in Hermes could cause occasional jank
- Complex state management across playback backends may struggle with React's render cycle

**Risks:**
- Complete rewrite = months of work before feature parity
- Native module maintenance burden for Spotify/MusicKit/ExoPlayer
- React Native's new architecture (Fabric/TurboModules) is still stabilizing
- Background audio with multiple backends is the hardest React Native challenge — community libraries assume single-source playback

**Desktop code sharing:** High potential for business logic (same language). But `app.js` at 57K lines would need refactoring to extract shareable modules.

**Verdict:** Only makes sense if desktop code sharing is the top priority AND you're willing to invest in a full rewrite. High risk for a music player with complex native needs.

---

### 3. Flutter

**What it is:** Google's framework using Dart language with its own rendering engine (Skia/Impeller).

**Migration effort:** Very high — complete rewrite in an entirely new language (Dart). No code reuse from Android Kotlin or desktop JS.

**Code sharing:**
- Between Android/iOS: ~90-95% (Flutter's strength)
- With desktop: Zero — Dart shares nothing with JS or Kotlin
- Flutter desktop exists but is not a realistic path for an Electron app

**Native integration quality:**
- ExoPlayer/AVPlayer: `just_audio` or `audioplayers` packages work but don't expose the full ExoPlayer API you need for media session integration
- Spotify SDK: `spotify_sdk` package exists, wraps both platform SDKs. Maintenance varies.
- MusicKit: No mature Flutter package. Would need custom platform channels (~500-1000 lines of platform channel glue per platform)
- Background audio: `audio_service` handles foreground services and media sessions, but multi-backend routing through platform channels adds complexity
- OAuth: `flutter_appauth` works well

**Performance:** Dart compiles to native ARM. Rendering via Impeller is smooth. But platform channels for native audio introduce serialization overhead on every call. The 500ms polling loop sending data across the platform channel boundary is fine, but complex state synchronization gets messy.

**Risks:**
- Complete rewrite in unfamiliar language
- No code sharing with any existing codebase
- Platform channel "tax" on every native interaction — and Parachord has A LOT of native interactions
- Flutter's "own rendering engine" means no native text selection, no native scroll physics without extra work
- Google's long-term commitment is debated (though it remains actively developed as of 2026)

**Desktop code sharing:** None.

**Verdict:** Poor fit. The massive native integration surface (3+ playback backends, media sessions, WebView bridge) means you'd spend most of your time writing platform channels instead of shared Dart code. No code sharing with desktop or existing Android.

---

### 4. Capacitor (Wrap Desktop Web App)

**What it is:** Run your existing web app (React + Tailwind) inside a native WebView shell with plugin access to native APIs.

**Migration effort:** Low for basic app — wrap the desktop `app.js` in Capacitor. But the devil is in the details.

**Code sharing:**
- With desktop: Theoretically ~100% — same codebase
- Between platforms: ~95% (web code is identical)
- But: The desktop app's playback architecture won't translate

**Native integration quality — this is where it falls apart:**
- ExoPlayer/AVPlayer: Need Capacitor plugins. Desktop uses HTML5 Audio/Electron APIs — these don't exist in a mobile WebView context. Would need to rewrite all playback routing.
- Spotify: Desktop likely uses the Web Playback SDK (browser-based). On mobile, the Web Playback SDK doesn't work — you need the native SDKs. Custom Capacitor plugin required.
- MusicKit: MusicKit JS might work in the WebView, but token management, background playback, and DRM are different on mobile
- Background audio: **This is the dealbreaker.** WebView audio stops when the app is backgrounded on both Android and iOS. You'd need native audio players anyway, which means the "shared web code" advantage evaporates for the core feature (playing music).
- Media session / lock screen controls: Need native plugin
- Local file access: Need Capacitor Filesystem plugin, can't use web File API

**Performance:**
- WebView rendering adds overhead — scrolling through 1000-track libraries will be noticeably worse than native
- JS execution in WKWebView (iOS) / Chrome WebView (Android) is fast enough for business logic
- But audio polling loops in JS are deprioritized when the WebView is backgrounded

**Risks:**
- **Background audio is fundamentally broken in WebViews.** This alone likely disqualifies Capacitor for a music player.
- You'd need to rewrite the entire playback layer as native Capacitor plugins — at which point you've lost the "reuse desktop code" advantage
- WebView memory usage is higher than native UI
- App Store reviews sometimes reject WebView-wrapped apps that feel non-native
- Desktop's `app.js` (57K lines) includes Electron-specific code that won't work in Capacitor

**Desktop code sharing:** High for UI and business logic. Zero for playback — the entire core feature.

**Verdict:** Non-starter for a music player. Background audio in WebViews is a fundamental platform limitation. You'd end up writing native playback plugins that are just as much work as a native app, but with worse UI performance.

---

### 5. Separate Native Codebases (Android Kotlin + iOS Swift)

**What it is:** Keep the Android app as-is. Build iOS from scratch in Swift/SwiftUI, guided by the port reference in CLAUDE.md.

**Migration effort:** High for iOS build-out, but zero disruption to Android.

**Code sharing:**
- Between platforms: 0% shared code. Two complete implementations.
- With desktop: Can port logic patterns but no actual code sharing
- **But:** The CLAUDE.md already maps every Android component to its iOS equivalent. The architecture is documented.

**Native integration quality: Best possible on both platforms.**
- ExoPlayer (Android) / AVPlayer (iOS) — each uses the best native player
- Spotify: Android SDK + iOS SDK — each platform's official SDK, no wrappers
- MusicKit: WebView bridge on Android (proven), native MusicKit framework on iOS (the intended way)
- Background audio: Full native control. Foreground service on Android, Background Modes on iOS. No abstraction layer complications.
- Media session: MediaSession on Android, MPNowPlayingInfoCenter on iOS
- OAuth: Custom Tabs on Android, ASWebAuthenticationSession on iOS

**Performance:** Best possible on both platforms. Native UI, native audio, native threading.

**Risks:**
- Two codebases means two sets of bugs, two feature development cycles
- Features must be implemented twice — business logic isn't shared
- Divergence risk: platforms drift apart over time
- iOS build takes significant effort (~30-40K lines of Swift estimated)
- Need developers comfortable in both Kotlin and Swift

**Desktop code sharing:** None directly, but patterns translate well.

**Verdict:** Highest quality result. Highest maintenance cost. Makes sense if the team is small and can accept iOS lagging Android, or if the native integration quality is paramount (it is for a music player).

---

## Summary Matrix

| Factor | KMP + Compose | React Native | Flutter | Capacitor | Separate Native |
|--------|:---:|:---:|:---:|:---:|:---:|
| Migration effort | **Medium** | High | Very High | Low* | High (iOS only) |
| Code reuse (existing Android) | **~73%** | 0% | 0% | 0%** | 100% Android / 0% iOS |
| Code sharing (cross-platform) | **~80%** | ~90% | ~90% | ~60%*** | 0% |
| Desktop code sharing | **High (.axe plugins)** | Medium | None | High* | **Medium (.axe plugins)** |
| Background audio | **Native** | Bridged | Bridged | **Broken** | **Native** |
| Spotify/MusicKit integration | **Native** | Bridged | Bridged | **Broken** | **Native** |
| Playback polling performance | **Native** | Good | Good | Poor | **Native** |
| UI quality | Good-Great | Good | Good | Fair | **Best** |
| Long-term maintenance | **Medium** | High | High | High | **Highest** |
| Team skill requirement | Kotlin | JS/TS | Dart | JS/TS | Kotlin + Swift |

\* Low only until you hit playback — then it's very high
\** Desktop code can't handle mobile playback requirements
\*** After rewriting playback as native plugins, actual sharing drops

---

## Recommendation

**For Parachord specifically: KMP + Compose Multiplatform.**

Reasoning:
1. **You've already written 51K lines of refined Kotlin.** KMP preserves ~73% of that investment. Every other option throws it away.
2. **The .axe plugin system bridges KMP and desktop.** The same 19 .axe plugins run on desktop (Electron JS), Android (WebView JS), and iOS (JavaScriptCore). This gives KMP something no other framework offers: **three-platform code sharing** for AI providers, resolvers, metadata, and concert services — without rewriting the desktop app. The `JsRuntime` interface is already KMP-ready.
3. **The hard parts are unavoidably platform-specific.** ExoPlayer vs AVPlayer, MediaSession vs MPNowPlayingInfoCenter, WebView MusicKit vs native MusicKit — no framework eliminates this work. KMP's `expect`/`actual` gives the cleanest abstraction for these boundaries.
4. **Capacitor is disqualified** — background WebView audio is fundamentally broken on mobile.
5. **React Native and Flutter** require complete rewrites with questionable native integration quality for a multi-backend music player.
6. **Separate native** gives the best quality but doubles maintenance forever. The .axe plugin system reduces this penalty somewhat (plugins are shared), but the Kotlin business logic still needs to be rewritten in Swift.
7. **KMP is the Kotlin-native path** — Google officially endorses it, JetBrains maintains it, and the Android ecosystem is converging on it.

### Runner-up: Separate Native Codebases

If Compose Multiplatform's iOS maturity concerns you, or if you want the absolute best platform-native experience, separate codebases with the existing CLAUDE.md port guide is a solid choice. The tradeoff is maintaining two implementations of your business logic (resolver pipeline, API clients, scrobblers, AI services) — but the architecture is well-documented and Claude/AI can help generate the Swift port.

### If desktop code sharing matters: React Native (distant third)

Only if sharing code between mobile and the Electron desktop app becomes a top priority. This requires accepting a complete mobile rewrite and the ongoing native module maintenance burden. Not recommended unless the team has strong React Native experience.

---

## If KMP is chosen: high-level migration phases

1. **Phase 1 — Shared module extraction** (no iOS yet)
   - Create `:shared` KMP module
   - Move `JsRuntime` interface + `PluginManager` + `PluginSyncService` to `commonMain` (already KMP-ready — no Android imports)
   - Migrate pure business logic: resolver pipeline, scoring, API clients (Retrofit → Ktor), serialization models
   - Android app imports from `:shared` — verify nothing breaks
   - Migrate Room → SQLDelight for cross-platform database

2. **Phase 2 — Platform abstraction layer**
   - Define `expect`/`actual` interfaces for: playback (ExoPlayer/AVPlayer), auth (OAuth flows), settings (DataStore/UserDefaults), media session
   - Add `expect`/`actual` for `JsRuntime`: Android `actual` = existing `JsBridge` (WebView), iOS `actual` = `JavaScriptCore` wrapper (~100 lines)
   - Android `actual` implementations wrap existing code
   - This is refactoring, not rewriting — same logic, new module boundaries

3. **Phase 3 — iOS app**
   - iOS `actual` implementations: AVPlayer, MusicKit (native), Spotify iOS SDK, MPNowPlayingInfoCenter
   - iOS `JsRuntime` via JavaScriptCore — loads the same .axe plugins and resolver-loader.js
   - **Day-one iOS features via .axe plugins:** All 17 mobile-compatible plugins work immediately — AI providers, Bandcamp resolver, concert services, metadata. No native Kotlin/Swift implementation needed for these.
   - UI in Compose Multiplatform (shares ~80% with Android) OR SwiftUI consuming shared Kotlin
   - Start with playback + resolver pipeline → Now Playing screen → Library → remaining screens

4. **Phase 4 — Feature parity**
   - AI DJ chat, scrobbling, friends, weekly playlists, concerts
   - These are mostly shared-module code — iOS gets them "for free" once Phase 1 is complete
   - .axe plugins provide immediate coverage for AI providers (no Swift AI provider code needed)
   - Marketplace sync works on both platforms — plugin updates reach iOS and Android simultaneously

### Key library migrations for KMP

| Current (Android) | KMP Replacement | Effort |
|---|---|---|
| Retrofit + OkHttp | Ktor | Medium (API surface is similar) |
| Room | SQLDelight | Medium (SQL-first vs annotation-first) |
| Hilt | Koin or kotlin-inject | Medium |
| DataStore | KMP-native settings lib or `expect`/`actual` | Low |
| Coil | Coil 3 (has KMP support) | Low |
| kotlinx.serialization | Same (already KMP) | None |
| JsBridge (WebView) | `expect`/`actual` JsRuntime | **Low — interface already defined** |
| PluginManager | Same (already pure Kotlin) | **None — move to commonMain** |
| PluginSyncService | Same (swap OkHttp for Ktor) | **Low** |
| NativeBridge | Platform-specific `actual` | Low (URLSession on iOS) |

---

## Appendix: Key Concerns

### Does KMP help maintain feature parity between Android and iOS?

**Yes, significantly — for the ~68% that's shared code.** When you add a new API client, repository, resolver, AI provider, or scrobbler to the shared module, both platforms get it automatically. That's where most new features live — it's business logic, not UI.

**For the ~32% that's platform-specific, it helps somewhat.** The `expect`/`actual` pattern forces you to define the interface in shared code, so iOS can't accidentally miss a method. If you add a new playback capability to the `expect` interface, the iOS `actual` won't compile until it's implemented. That's a compiler-enforced parity check.

**Where it doesn't help:** UI screens still need per-platform attention. And genuinely different implementations (MusicKit native vs WebView, ExoPlayer vs AVPlayer) will always need separate feature work.

**Compare to separate native codebases:** With two independent codebases, feature parity is purely a discipline problem. There's no compiler telling you "iOS is missing this." Features drift apart silently. KMP makes drift structurally harder for shared code.

### Does KMP decrease the likelihood of open source contributions?

**It raises the barrier somewhat — but how much depends on what's being contributed.**

- **Shared module contributions:** Contributors need to understand KMP project structure (`expect`/`actual`, Ktor, SQLDelight). The pool of developers who know KMP well is smaller than either native ecosystem alone.
- **Platform-specific contributions:** Fixing an iOS AVPlayer bug or an Android ExoPlayer issue is standard platform Kotlin — not harder than a native codebase.
- **Business logic contributions:** Actually *easier* — write once in shared Kotlin, works on both platforms. No need for two PRs.

**Contributor pool comparison:**

| Approach | Contributor pool | Contribution complexity |
|----------|-----------------|------------------------|
| Separate native (Kotlin + Swift) | Largest — any Android or iOS dev | Low per platform, need 2 PRs for shared logic |
| KMP + Compose Multiplatform | Medium — Kotlin devs who know KMP | Medium for shared, low for platform-specific |
| React Native | Large — huge JS/React community | Low for JS, high for native modules |
| Flutter | Medium — Dart community | Low for UI, high for platform channels |

**Mitigation:** Good documentation (`CONTRIBUTING.md` explaining module structure and `expect`/`actual`) reduces the learning curve for a Kotlin developer to a few hours. Most shareable code is just normal Kotlin — KMP-specific parts are only at the boundaries.

**Bottom line:** KMP trades a slightly smaller contributor pool for structurally enforced feature parity on 68% of the codebase.

---

## Appendix: Repo Structure

### Is this actually a single codebase/repo?

**KMP + Compose Multiplatform: Yes — single repo, single build system.**

```
parachord-mobile/                    # One repo
├── shared/                          # KMP shared module (~68% of code)
│   └── src/
│       ├── commonMain/kotlin/       # Business logic — resolver pipeline, API clients,
│       │                            # repositories, AI services, scrobblers, models
│       ├── androidMain/kotlin/      # Android `actual` implementations
│       │                            # (ExoPlayer, Room→SQLDelight, DataStore)
│       └── iosMain/kotlin/          # iOS `actual` implementations
│                                    # (AVPlayer, MusicKit native, UserDefaults)
├── androidApp/                      # Android entry point
│   └── src/main/
│       ├── kotlin/                  # Android-only: PlaybackService, MusicKitWebBridge,
│       │                            # Compose screens, Hilt→Koin modules
│       ├── AndroidManifest.xml
│       └── res/
├── iosApp/                          # iOS entry point (Xcode project)
│   └── iosApp/
│       ├── ContentView.swift        # SwiftUI or Compose Multiplatform entry
│       ├── Info.plist
│       └── iosApp.xcodeproj
├── build.gradle.kts                 # Root build — configures KMP targets
└── settings.gradle.kts              # Includes :shared, :androidApp
```

One `git clone`. One CI pipeline. `./gradlew :androidApp:installDebug` builds Android. Xcode opens `iosApp/` and pulls in the `shared` framework as a dependency. A change to `shared/src/commonMain/` is immediately available to both platforms.

**How the other approaches compare:**

| Approach | Repo structure | Build system |
|----------|---------------|--------------|
| KMP + Compose Multiplatform | **Single repo** | Gradle (Android + shared) + Xcode (iOS) |
| React Native / Expo | Single repo (new) | Metro bundler + Xcode + Gradle |
| Flutter | Single repo (new) | Flutter CLI + Xcode + Gradle |
| Capacitor | Single repo (new) | npm + Capacitor CLI + Xcode + Gradle |
| Separate native | **Two repos** | Gradle (Android) + Xcode (iOS) independently |

### Migration path: transform existing repo vs. new repo

**Option A — Transform `parachord-mobile` in place (recommended):**
- Add `:shared` module to this repo
- Gradually move code from `app/` → `shared/commonMain/`
- Add `iosApp/` directory for the iOS target
- Rename repo to `parachord-mobile` when ready
- Git history preserved, no parallel maintenance
- Repo gets more complex during migration (hybrid state)

**Option B — New `parachord-mobile` repo:**
- Start fresh with KMP project template
- Port shared code from `parachord-mobile`
- Build iOS alongside
- Archive `parachord-mobile` when done
- Clean start, but parallel repos during migration and lost git history

**Option A is the standard approach** — recommended by JetBrains and Google. The migration is incremental: extract one package at a time into `shared/`, verify Android still works, repeat. No big bang.

---

## Appendix: Cross-Platform vs Platform-Specific Feature Breakdown

### Cross-Platform (`shared/commonMain/`) — ~73% of codebase

**.axe Plugin System (shares code with desktop Electron app):**
- `JsRuntime` interface — Android: WebView, iOS: JavaScriptCore
- `PluginManager` — loading, semver deduplication, hot-reload, evaluateJs()
- `PluginSyncService` — marketplace sync from GitHub (24h debounce)
- `AxeAiProvider` — wraps .axe AI plugins as `AiChatProvider` implementations
- `AxeScrobbler` — wraps .axe scrobbler plugins (ready for future migration)
- 19 .axe plugin files (JSON+JS) — same files on Android, iOS, and desktop
- `resolver-loader.js` — unchanged from desktop (422 lines)
- Platform capability filtering (`mobile: false` hides YouTube, Ollama on mobile)

**Resolver Pipeline (all of it):**
- `ResolverManager.resolve()` — calls search APIs across all resolver backends
- `ResolverScoring.selectBest()` — priority-first, confidence-second ranking
- `TrackResolverCache` — per-track source caching
- Confidence scoring, min threshold filtering (0.60 floor)

**All API Clients (pure HTTP + JSON):**
- Spotify Web API (search, albums, artists, playlists, devices)
- Last.fm API (artist info, similar artists, tags, images)
- MusicBrainz API (discography, tracklists, MBIDs)
- ListenBrainz API (listens, friends, weekly playlists, MBID mapper)
- Ticketmaster + SeatGeek (concerts)
- Apple Music catalog API
- SoundCloud API

**All 13 Repositories:**
- ChartsRepository, ConcertsRepository, FriendsRepository, HistoryRepository
- LibraryRepository, PlaylistRepository, RecommendationsRepository
- WeeklyPlaylistsRepository, and others

**Metadata Cascade:**
- MetadataService (provider orchestration)
- MusicBrainzProvider, LastFmProvider, SpotifyMetadataProvider
- MbidEnrichmentService (MBID lookup + 90-day disk cache)
- ImageEnrichmentService

**AI Services:**
- AiRecommendationService, AiChatService
- ChatGptProvider, ClaudeProvider, GeminiProvider
- DjToolDefinitions, DjToolExecutor
- ChatContextProvider (listening history context)

**Scrobblers:**
- ListenBrainzScrobbler, LastFmScrobbler, LibreFmScrobbler
- ScrobbleManager (timing, deduplication, MBID inclusion)

**Playback Logic (not the actual audio playback):**
- Queue management (ordering, shuffle, repeat modes)
- Track advance logic (skip next/prev, auto-advance decisions)
- PlaybackController business logic (which track to play, resolver lookup)
- Volume offset calculations per resolver type

**Data Models:**
- TrackEntity, AlbumEntity, ArtistEntity, PlaylistEntity, FriendEntity
- All API response/request models
- ResolvedSource, AiRecommendation, etc.

**Other shared logic:**
- XSPF playlist parser
- SyncEngine logic
- OAuth token refresh logic (not the browser UI)
- Settings keys and defaults

### Platform-Specific — ~32% of codebase

**Android (`androidMain/` + `androidApp/`):**
- ExoPlayer setup, media source factories
- `PlaybackService` (MediaSessionService, foreground service, notification)
- `MusicKitWebBridge` (WebView-based Apple Music, ~1500 lines)
- Spotify device wake (media button broadcast, launch intent fallback)
- Room DAOs + migrations (replaced by SQLDelight `actual`)
- DataStore preferences (`actual` implementation)
- Jetpack Compose UI screens + components
- DI module wiring
- Home screen widget (`MiniPlayerWidgetProvider`)
- Deep link intent filters
- OAuth via Custom Tabs

**iOS (`iosMain/` + `iosApp/`):**
- AVPlayer setup, AVAudioSession configuration
- `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`
- Native MusicKit framework (replaces WebView bridge — simpler on iOS)
- Spotify iOS SDK (`SPTAppRemote`)
- Background audio mode (`UIBackgroundModes: audio`)
- UI screens (SwiftUI or Compose Multiplatform)
- UserDefaults / `@AppStorage`
- OAuth via ASWebAuthenticationSession

**Key insight:** The platform-specific layer is relatively stable plumbing that rarely changes once built. Every new feature — new API integration, recommendation algorithm, resolver, scrobbler — goes in the shared module and works on both platforms immediately. With the .axe plugin system, new resolvers, AI providers, and services can be added as .axe plugins that work on **all three platforms** (desktop, Android, iOS) without any native code.
