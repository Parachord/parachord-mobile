# iOS App — Agent Guide

Gotchas and patterns for working on the SwiftUI iOS app (`iosApp/`) that
sits on top of the KMP `shared/` module. The root `CLAUDE.md` covers
Android + the shared module; **this file is iOS-only**. Read it before
touching anything under `iosApp/` or `shared/src/iosMain/`.

The hard-won rules below each cost at least one failed build or a
shipped bug. Don't relearn them.

---

## Core principle: research before you build — NEVER guess

**Before implementing or restyling ANY screen, component, or behavior, do
the research first. Do not guess, approximate, or infer from memory.** This
rule exists because guessing has repeatedly shipped iOS screens that looked
nothing like the design and behaved unlike the other platforms — then had to
be torn out and rebuilt. Half-built approximations are worse than nothing.

For every UI/UX or behavior task, in this order:

1. **Read the design files.** The Claude Design handoff lives at
   `docs/design/parachord-ios/project/` — `screens.jsx`, `morescreens.jsx`,
   `nowplaying.jsx`, `app.jsx`, and `styles.css`. Find the actual component
   (search for its function / CSS classes) and read the real structure,
   measurements, gradients, tabs, grids, badges — don't infer them.
2. **Read how the OTHER platforms do it.** Android is the source of truth
   (root `CLAUDE.md`: "Always match the desktop app's approach"). Find the
   Android screen (`app/src/main/java/.../ui/screens/...`) and the shared
   logic (`shared/src/commonMain/...`). Read the real component
   (`ResolverIcon.kt`, the screen's `*Screen.kt` + `*ViewModel.kt`, the repo).
3. **Resolve conflicts explicitly: Android wins.** When the design and
   Android disagree (e.g. a grid in the design vs. a list on Android),
   **Android wins** — match Android's structure, use the design for visual
   polish where they agree. State the conflict and the choice; don't silently
   pick one.
4. **Only then implement.** Match the spec you just read, not an approximation
   of it. Verify on-device against the design/Android (screenshot).

**When debugging "it doesn't work", apply the same discipline (and the
`systematic-debugging` skill): trace the real code path and read the wire/
log evidence BEFORE theorizing a cause.** This session burned hours theorizing
a Last.fm deserialization bug that was actually a dropped-query-param (the wire
response said `error 6` the whole time), and asserting the resolver badges
"don't play" was a code bug when the path was correct and the cause was
environmental (simulator can't play Apple Music; Spotify not connected).
Read the source-of-truth first — design file, other-platform code, actual
log/wire output — then act.

---

## Build / run / screenshot workflow

**Build (headless):**
```bash
xcodebuild build \
  -project /Users/jherskowitz/Development/parachord/parachord-android/iosApp/Parachord.xcodeproj \
  -scheme Parachord \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' \
  -derivedDataPath /Users/jherskowitz/Development/parachord/parachord-android/iosApp/build
```

- **Always use the ABSOLUTE `-project` path.** The Bash tool's cwd does
  NOT reliably persist between calls — a bare `cd iosApp && xcodebuild`
  fails with "no such file or directory" on the next call. Pass absolute
  paths to `-project` and `-derivedDataPath` every time.
- **Installed simulator runtime is iOS 26.5.** Devices that exist on it:
  `iPhone 17 Pro`, `iPad (A16)` (+ the rest of the 17 / Air / iPad M4–M5
  line). The iPhone 16 / iPad-10th-gen entries are iOS 18.3.1 — Xcode 26
  rejects those as incompatible with the 26.x SDK. Match `OS=26.5` and a
  26.5 device or the build errors with "Unable to find a destination".
- The "Run gradle :shared:embedAndSignAppleFrameworkForXcode" build
  phase rebuilds `Shared.framework` from KMP source on EVERY Xcode build
  (it's `alwaysOutOfDate = 1`). Edits to `shared/src/iosMain` are picked
  up automatically — no separate gradle step needed before xcodebuild.

**Install + launch:**
```bash
xcrun simctl terminate "iPhone 17 Pro" com.parachord.ios 2>/dev/null
xcrun simctl install  "iPhone 17 Pro" .../build/Build/Products/Debug-iphonesimulator/Parachord.app
xcrun simctl launch   "iPhone 17 Pro" com.parachord.ios
```
Terminate BEFORE install — a running process keeps the old binary.

**Screenshots:**
```bash
xcrun simctl io "iPhone 17 Pro" screenshot /tmp/shot.png
sips -Z 1400 /tmp/shot.png --out /tmp/shot-small.png   # REQUIRED
```
The Read tool **rejects images larger than ~2000px** (a full iPad/iPhone
screenshot is 1640×2360+ and comes back "media removed — rejected by
API"). Always `sips -Z 1400` (or smaller) before reading a simulator
screenshot.

**simctl CANNOT drive the UI** — no taps, no typing into `.searchable`
or `TextField`. To verify a screen that needs input (search results, a
started queue), add a TEMPORARY `.task { /* auto-seed */ }` that fires
the action on appear, screenshot, then **remove it before committing**.
Every such seed in this session was removed in the same commit.

---

## Adding a Swift file to the Xcode project

`Parachord.xcodeproj/project.pbxproj` is hand-maintained (no Xcode GUI in
the loop). A new `Foo.swift` needs **FOUR** entries, all using the stable
`AA00…00XX` hex-ID convention:

1. **PBXBuildFile** — `AA…0026 /* Foo.swift in Sources */ = {isa = PBXBuildFile; fileRef = AA…0016 /* Foo.swift */; };`
2. **PBXFileReference** — `AA…0016 /* Foo.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = Foo.swift; sourceTree = "<group>"; };`
3. **PBXGroup children** — add `AA…0016 /* Foo.swift */,` to the `Parachord` group
4. **PBXSourcesBuildPhase files** — add `AA…0026 /* Foo.swift in Sources */,`

> **The #1 entry (PBXBuildFile) is the one that keeps getting missed.**
> A `perl -0pi -e` substitution against the PBXBuildFile line repeatedly
> fails to match (the `= {isa = PBXBuildFile; …}` + `/* */` escaping is
> fiddly). **Use the Edit tool for the PBXBuildFile entry**, or just
> verify afterward:
>
> ```bash
> grep -c "Foo.swift" Parachord.xcodeproj/project.pbxproj   # MUST be 4
> ```
>
> If it returns **3**, the PBXBuildFile entry is missing and the build
> fails with `cannot find 'Foo' in scope`. (The build-file line contains
> the name twice but grep counts lines: build-file=1, file-ref=1,
> group=1, sources=1 → 4 lines.)

Then add the screen as a tab in `RootView.swift` with a `.tag(n)`.

**Build settings already wired** (don't re-add): `OTHER_LDFLAGS` has
`-framework Shared` + `-lsqlite3` (SQLDelight's native driver needs
libsqlite3); `FRAMEWORK_SEARCH_PATHS` has `$(BUILT_PRODUCTS_DIR)`;
`INFOPLIST_KEY_UIBackgroundModes = audio` (background audio); Info.plist
is synthesized from `INFOPLIST_KEY_*` (`GENERATE_INFOPLIST_FILE = YES`,
no plist file in the tree).

---

## KMP → Swift bridging gotchas

These all compile-fail or mis-render at the Swift boundary.

- **NEVER name a Swift-facing Kotlin model property `description`.** It
  collides with ObjC's `NSObject.description` (which returns the
  data-class `toString()`), so `entry.description` renders
  `IosWeeklyEntry(id=…, title=…)` in the UI instead of the field. Use
  `summary` / `blurb` / `text`. (Shipped this bug once on the Discover
  screen — caught only because real data exposed it.) Same applies to
  `hash`, `debugDescription` — avoid NSObject member names.
- **Primitive `Double` / `Int` / `Bool` bridge DIRECTLY.** No
  `.doubleValue` / `.intValue` / `.boolValue`. Calling `.doubleValue` on
  a bridged Swift `Double` is a compile error. (Only `KotlinInt?`-style
  *boxed optionals* from generics need `.intValue`.)
- **Top-level Kotlin functions** → `<FileName>Kt.func(...)`.
  `scoreConfidence` in `ResolverModels.kt` is `ResolverModelsKt.scoreConfidence`.
- **Companion constants** → `Type.companion.CONST`.
  `ResolverScoring.companion.MIN_CONFIDENCE_THRESHOLD`.
- **Big initializers overwhelm Swift's type-checker inside closures.**
  The ~19-param `Track(...)` initializer inside a `.map { }` triggers
  "unable to type-check this expression in reasonable time". Extract to a
  standalone `func makeX(...) -> Track` with an explicit return type.
- **Flatten nested response types to plain DTOs.** `MbArtistSearchResponse`
  / `List<MbArtist>` with nested `artist-credit` / `release-group`
  structs bridge awkwardly. Project them to flat `data class`
  `IosSearchArtist` / `IosSearchRelease` in `IosContainer` and return
  those to Swift.
- **suspend funcs** → Swift `async throws`; call from `Task { }`.
- **Collecting a `Flow` from Swift**: Swift can't `collect` a suspend
  Flow. Use `FlowWatcher` (in `IosContainer.kt`) — it launches the
  collection on a scope and forwards each emission to a Swift `(Any?) ->
  Void`. Emissions erase to `Any?`; cast in the closure
  (`value as? String`). Store the returned `Cancellable` and call
  `.cancel()` in `onDisappear`.

---

## SwiftUI ↔ shared architecture pattern

Every screen follows the same shape — don't reinvent it:

1. **`IosContainer`** (`shared/src/iosMain/.../ios/IosContainer.kt`) — a
   hand-rolled DI singleton (`IosContainer.companion.shared`). Hosts the
   production Ktor `HttpClient` (User-Agent injected, shared plugins;
   auth via no-op stubs until OAuth lands), the API clients, repositories,
   and `SettingsStore`. Add a new dependency as a `val x by lazy { … }`.
   The full Koin graph is deliberately NOT used yet — this stays a
   hand-rolled container until the auth module needs wiring.
2. **Flat DTO methods on the container** for anything with nested or
   generic return types.
3. **`@MainActor @Observable` ViewModel** (Swift) — holds the shared
   service, subscribes to its Flows via `FlowWatcher` republishing as
   `@Observable` state, writes through suspend setters from
   `Task { }`.
4. **SwiftUI view** binds the VM; **register in pbxproj (4 entries)** +
   add a `.tag()`-ed tab in `RootView.swift`.

`AppPlayback` (in `NowPlayingView.swift`) is the single app-wide playback
engine — one `IosAVPlayer` + one shared `QueuePlaybackCoordinator`,
created at the ContentView root, shared by the Now Playing tab and the
mini player. The Dev tab keeps its OWN separate smoke-test player on
purpose; it retires with the Dev tab.

---

## JavaScriptCore (.axe plugin host)

The runtime split: **Kotlin owns lifecycle, Swift owns polyfills.**

- **`IosJsRuntime`** (Kotlin) does init/evaluate/teardown + the exception
  handler. It exposes `nativeContext: JSContext?` for Swift.
- **Kotlin/Native's JSC bindings DON'T expose** `JSContext.setObject(_:forKeyedSubscript:)`
  or `JSValue.setValue(_:forProperty:)` — there's no Kotlin-visible way
  to attach a Kotlin lambda as a JS callable. Attach callables FROM SWIFT
  (`JsPolyfills` in `ContentView.swift`): `ctx.setObject(block, forKeyedSubscript: "name" as NSString)`
  where `block` is `@convention(block) (...) -> ...`.
- **`@convention(block)` closures fire synchronously on JS's evaluate
  thread.** `JSContext` is thread-bound to its creation thread (main).
  Any async work (URLSession) must `await MainActor.run { … }` before
  calling back into JS, and SwiftUI state mutations need `Task { @MainActor in … }`.
- **JSC has no `window`.** Browser-style polyfills (`window.fetch`,
  `window.storage`) must prepend
  `if (typeof window === 'undefined') { globalThis.window = globalThis; }`
  so `.axe` plugins ported from the Android WebView host work unmodified.
- Match Android's `bootstrap.html` Promise/Response shape byte-for-byte
  so plugins don't need per-platform forks.
- Storage polyfill enforces the same allowlist as Android
  (`parachord.*` / `plugin.*` key prefixes only).

### Resolving tracks via `.axe` on iOS (the iOS-is-.axe-only consequences)

iOS has **no native resolvers** — every source resolves by running the
`.axe` plugin's `resolve()` in JSC. Android's native-first strategy means
its `applemusic`/`spotify`/`soundcloud` `.axe` `resolve()` paths **never run
there**, so several gaps only surface on iOS:

- **`PluginManager.resolve` returns `[object Promise]` on JSC.** It reads a
  bare `(async()=>…)()` synchronously. Use the `window.__resolveResults[key]`
  poll pattern (`IosResolverCoordinator.resolveOne`). For **concurrent
  fan-out** use a UNIQUE key per call — a single shared global
  (`__lastPluginResult`) gets clobbered when resolves overlap. Unique keys let
  the `async{}` fan-out interleave on the one JSC run loop.
- **`.axe` plugins expect host-provided globals that live nowhere in the
  `.axe` or `resolver-loader.js`.** `applemusic.axe` calls
  `window.iTunesRateLimiter.fetch(url)` for its no-auth iTunes Search. The
  desktop/Android hosts define it; iOS must too (added to `BOOTSTRAP_JS` as a
  passthrough — JSC has no `setTimeout` for a real throttle gap, so throttle a
  layer up in `IosTrackResolverCache`). If a `.axe` errors with "undefined is
  not an object (evaluating 'window.X')", X is a missing host global — add it.
- **ID-based resolvers return NO top-level `url`.** `applemusic.axe` returns
  `{appleMusicId, previewUrl, appleMusicUrl}`; `spotify.axe` a URI. Android's
  `AxeResolveResult` required `url` (it only runs url-based bandcamp/youtube
  `.axe`). Accept a source with EITHER a playable `url` OR a routable ID, and
  map the extra fields (`previewUrl`, `appleMusicUrl`, `soundcloudUrl`, …).
- **`.axe` resolvers need credentials for most sources.** With no tokens:
  soundcloud "No token, skipping", spotify silent, localfiles "not available".
  Apple Music **works credential-free** via iTunes Search — the one resolver
  that returns real matches on a bare simulator. Test with it.
- **`NativeBridge.log` uses `NSLog`, not `print`** so `.axe` / resolver-loader
  `console.log` is captured by `xcrun simctl log stream`. Filter with a
  predicate like `eventMessage CONTAINS "plugin:"`. `print` output does NOT
  reliably reach os_log.
- **The `NativeBridge` installer MUST be set at app STARTUP** (`ParachordApp.init`),
  not just in the Dev tab. `IosJsRuntime.initialize()` only loads
  `resolver-loader.js` + the plugins when `nativeBridgeInstaller` is present;
  otherwise it stands up a bare JSContext with no `window.__resolverLoader`.
  `initialize()` is **idempotent** — if any production resolution
  (a playlist's `resolveInBackground`, a tap's on-the-fly `resolveSources`)
  runs before the installer is set, the host initializes plugin-less and stays
  broken for the whole session (no badges, nothing resolves, taps flash then
  fail). Symptom of regressing this: resolution works only after visiting the
  Dev tab. Keep the installer wired in `ParachordApp.init()` + pre-warm there.

### Running `.axe` async on iOS — poll, don't reimplement (DJ chat + dynamic models)

**RULE: don't natively reimplement what an `.axe` already does — ask first.** This bit us on dynamic AI models (#223): a native per-provider `listModels` (OpenAI/Anthropic/Gemini HTTP) got written when `chatgpt.axe`/`gemini.axe` already had `listModels`, and was reverted. The `.axe`/marketplace is the source of truth (portability, marketplace updates, one place for desktop + both mobile platforms). The ONLY legitimate native iOS layer is the GLUE that *runs* the `.axe` in JavaScriptCore — never a reimplementation of the plugin's logic. See root `CLAUDE.md` → Common Mistakes #6.

- **Any async `.axe` function returns `[object Promise]` on JSC if read synchronously** (same gotcha as `resolve` — a bare `(async()=>…)()` doesn't await). Use the SAME `window.__resolveResults['<unique-key>']` poll as `IosResolverRuntime.resolveOne`. `IosResolverRuntime.listModels(pluginId, apiKey)` does this for the AI plugins' `listModels`; `aiModels(pluginId, apiKey)` wraps it with the desktop precedence: a static `select` setting's curated `options` win, a `dynamic-select` setting fetches live via `listModels` and falls back to the plugin's `fallbackOptions` when empty/keyless. `aiModelDefault(pluginId)` reads the setting's `default` (synchronous property read — no Promise). The Settings picker seeds `stored || pluginDefault` (`app.js:59532`).
- **DJ chat tool execution bridges Kotlin → the Swift coordinator via lambdas.** `IosDjToolExecutor` (iosMain) does search/resolve in Kotlin (`metadataService`) and delegates the playback verbs to `IosChatPlaybackBridge` closures that Swift sets at app start (`IosContainer.bindChatPlayback(...)`), fire-and-forget on `@MainActor` (like the scrobble/spinoff bridges). Pool/queue tracks are metadata-only and resolve on-the-fly at play time. The `ChatContextProvider` playback snapshot is PUSHED from Swift (`updateChatPlaybackSnapshot`) so the Kotlin side never reads `@MainActor` state cross-thread.

### Spinoff + Listen Along on iOS — coordinator-level, not a queue swap

Model + cross-platform details live in root `CLAUDE.md` → "Spinoff + Listen Along". iOS specifics: the spinoff pool + `spinoffMode` + `preSpinoffContext` live on `QueuePlaybackCoordinator` (ContentView.swift) — `beginSpinoff`/`advanceSpinoff`/`exitSpinoff`; `skipNext` + `autoAdvance` check the pool BEFORE the queue. Listen Along keeps its OWN suspension in `ListenAlongController`; the two never co-exist (each `stop`s/`exitSpinoff`s the other). The chat/spinoff/listen-along screens slide in as **trailing-edge drawers** (`.transition(.move(edge: .trailing))` + a left-edge swipe-to-close zone in the RootView ZStack), NOT bottom covers — matches Android's right-slide.

### Tracklist resolution: visibility-scoped, top-down, globally bounded

Every list screen that shows resolver badges MUST submit its tracks the same
way — this is the desktop `ResolutionScheduler` model (parachord-desktop
`app.js` ~L1126) ported into `IosTrackResolverCache`. Three invariants,
learned the hard way (each caused a Spotify 429 abuse window or a visible
bug):

- **Visibility-scoped, not whole-list.** Resolve per *visible row* via the
  row's `.onAppear`, NOT a bulk `resolveInBackground(allTracks)` on load.
  Bursting a 50-track playlist fired ~50 Spotify + ~50 iTunes searches at
  once and tripped the shared-`client_id` abuse window (mobile #177 tracks
  the Android side; iOS `PlaylistDetailView` is the reference fix).
- **Top-down, not `onAppear` order.** Submit with `cache.resolve(req,
  order: index)` where `order` is the row index. The cache drains a priority
  queue LOWEST-order-first, so badges fill from the top of the page down —
  SwiftUI's `onAppear` firing order is NOT top-down, so relying on it fills
  bottom-up.
- **One global concurrency cap.** `IosTrackResolverCache` has a SHARED worker
  pool (`cap = 3`). Do not reintroduce a per-call task group — that lets N
  rows run N concurrent resolves and defeats both the JSC-context bound and
  the rate-limit protection.

When Library / Search / other list screens land, wire them the same way
(`.onAppear { resolve(req, order: index) }`); don't bulk-submit and don't
drop the index.

### The disk cache is schema-VERSIONED — bump on any `ResolvedSource` shape change

`IosTrackResolverCache` persists its resolved-source map to disk
(`resolver-cache-v<N>.json`) and reuses it on launch **before** re-resolving, so
a stale entry is reused verbatim and NEVER re-resolves on its own. That's the
whole point (kills repeat Spotify/iTunes searches that re-arm the shared-key
abuse window) — but it means a cached entry written before a `ResolvedSource`
field was added keeps the old, field-less shape forever. This shipped a real
bug: entries cached before `ResolvedSource.isrc` landed (commit `ccea230`) had
`isrc=null`, were reused without re-resolving, so an Apple-Music-streamed track
reached scrobble with no ISRC and its Achordion submit silently skipped.

**The fix + the rule:** the version lives in the FILENAME
(`private static let cacheSchemaVersion`), so a bump makes the prior file
unreadable (we never open it) and `purgeStaleCacheFiles()` deletes it →
every track re-resolves once into the new file. **Bump `cacheSchemaVersion`
whenever a `ResolvedSource` field change makes OLD cached entries semantically
wrong to reuse** (a new field the downstream pipeline now depends on). It's at
v2 as of the ISRC work. Android has NO equivalent problem — it re-derives ISRC
from fresh resolution every play (`reselectBestSource` / `resolveOnTheFly`),
never from a persisted blob, so this is an iOS-only discipline.

---

## UIKit / CoreGraphics (mosaic, etc.)

- **`UIImage.jpegData(compressionQuality:)` is NOT bound** in K/N's UIKit
  bindings. Use the global C function `UIImageJPEGRepresentation(image, quality)`
  — it returns the same `NSData?`.
- **Pin `UIGraphicsImageRendererFormat.scale = 1.0`** to match Android's
  exact pixel output (e.g. mosaic 600×600). The default takes
  `mainScreen.scale`, so a 2× device produces a 1200×1200 file — sharper
  but inconsistent with Android and 4× the disk cache.
- **`ByteArray` → `NSData`**: `usePinned { NSData.create(bytes: $0.addressOf(0), length: size.toULong()) }`.
- Any cinterop / NSData path needs `@OptIn(ExperimentalForeignApi::class)`
  at file scope.

---

## Code signing / deploy

- Paid Apple Developer account; bundle id `com.parachord.ios` (distinct
  from Android's `com.parachord.android`). Year-long cert — no 7-day
  refresh.
- First-time provisioning needs a physical device connected (Personal
  teams can't generate a profile from nothing — "Communication with
  Apple failed" until a device is seen). Connect iPad/iPhone, unlock,
  Developer Mode on, then the profile generates.
---

## Deep links & Universal Links (`parachord://` + `https://parachord.com`)

The dispatch chain: `RootView.handleDeepLink` (parachord://) and
`handleUniversalLink` (https) → `IosContainer.parseDeepLink` /
`resolveUniversalLink` (shared `DeepLinkParser`) → `resolveComplexDeepLink` for
protocol play/playlist/radio. `parachord://` is registered as a custom scheme;
`https://parachord.com` + `go.parachord.com` work via Associated Domains (#124).

- **SwiftUI's App lifecycle delivers Universal Links through `.onOpenURL` — NOT
  `.onContinueUserActivity`.** The tapped `https://…` arrives at `.onOpenURL` as
  the raw https URL. So `.onOpenURL` MUST dispatch by scheme:
  ```swift
  .onOpenURL { url in
      if url.scheme == "parachord" { handleDeepLink(url) } else { handleUniversalLink(url) }
  }
  ```
  This bit us (parachord-mobile#280, fix `e062292`): `handleDeepLink` led with
  `guard url.scheme == "parachord" else { return }`, so the https UL was silently
  dropped — every HTTPS playlist link was a no-op while `parachord://` worked.
  `.onContinueUserActivity(NSUserActivityTypeBrowsingWeb)` is kept as
  belt-and-suspenders but does NOT fire in this lifecycle. **Symptom of regressing
  this: app foregrounds from an `https://parachord.com/...` tap but nothing happens,
  no toast.** That is a handler-wiring bug, NOT an AASA/entitlement failure — if the
  app foregrounds, association already worked.

- **Universal Links can only be tested on a REAL device** (the sim is unreliable),
  and only from a tapped link in Notes/Messages — **NOT** Safari's address bar
  (Safari ignores UL from the bar), and there's no programmatic open
  (`devicectl` has no open-url; `simctl openurl` is sim + custom-scheme only).
- **`devicectl install` OVER an existing app does NOT re-fetch the AASA** — iOS
  keeps the prior association state. **Delete + reinstall** to force a fresh AASA
  fetch before testing UL (matches the MusicKit "delete to clear cache" rule).
- **Device logs without root:** `log collect --device` needs root (won't work);
  use `idevicesyslog` (Homebrew, no root). Run it as a *managed* background
  command (no inner `&` — that detaches and dies); filter with `-m <string>` or
  grep the captured file. The app's own `NSLog`/`Log` lines show as
  `Parachord[pid]`; the playback handoff shows as SpringBoard
  `FBSystemService … open "com.spotify.client"` (Spotify Connect) when a track plays.
- **`play/playlist?url=<provider>` routing** lives in the shared
  `classifyPlaylistUrl` + `ProviderPlaylistResolver` (parachord#930 / #932 port);
  Achordion/Spotify/Apple work, SoundCloud is unsupported (#281). Spotify
  *editorial* (`37i9…`) playlists return empty from the API for third-party apps —
  not a bug, a Spotify restriction.
---

## MusicKit / Apple Music playback (the full saga — verified on-device)

Apple Music *resolution* needs nothing (the `.axe` uses no-auth iTunes
Search). Apple Music *playback* (`ApplicationMusicPlayer`) is the hard part —
it took many rounds. The complete, correct procedure:

1. **MusicKit is NOT a code-signing entitlement.** Do NOT hand-write a
   `Parachord.entitlements` with `com.apple.developer.musickit` — it's not a
   valid provisionable entitlement and automatic signing rejects it
   ("Entitlement … not found … should be removed"). It's also NOT in Xcode's
   "+ Capability" picker. (Both dead ends were tried.)
2. **MusicKit is an App Service on the App ID**, enabled at the developer
   portal: developer.apple.com → Identifiers → `com.parachord.ios` → the
   **App Services** tab (NOT the Capabilities tab — that's the trap that wastes
   time) → check **MusicKit** → Save.
3. **The explicit App ID must exist.** Automatic signing uses an `XC Wildcard`
   (`*`) profile when the app has no entitlements forcing an explicit App ID,
   so `com.parachord.ios` may not exist as an identifier until you create it
   (or a device build creates it). The wildcard is harmless once an explicit
   match exists.
4. **`NSAppleMusicUsageDescription`** must be in Info.plist (it is).
5. **Developer token 404 = stale profile + cached error.** After enabling the
   App Service, `MusicCatalogResourceRequest` / `ApplicationMusicPlayer` still
   404 (`developerTokenRequestFailed`, "com.parachord.ios … not registered as a
   valid client identifier") until BOTH:
   - **Delete the app from the device** — iOS caches the token *failure*
     ("Updated MusicKit tokens cache with new error"); a rebuild-over-install
     does NOT clear it. Deleting does.
   - **Regenerate the provisioning profile** — toggle "Automatically manage
     signing" OFF/ON, Clean Build Folder, rebuild. (Apple's token service can
     also lag 15–30 min after enabling the service.)
   The success signal: the `developerTokenRequestFailed` flood vanishes from
   the console and `api.music.apple.com` requests return 200.
6. **Deleting the app also clears the MusicKit AUTHORIZATION grant.** Symptom:
   tokens work (no 404s) but tapping a track "skips all the way down the
   playlist" with no audio — because the router's
   `guard MusicAuthorization.currentStatus == .authorized` fails. Fix is in
   `PlaybackRouter`: it now calls `MusicAuthorization.request()` on the first
   Apple Music play (`notDetermined → prompt`), so a fresh install prompts and
   plays without the Dev tab. Don't regress this to a status-only check.
7. **Catalog playback needs an active Apple Music subscription** on the
   device's Apple ID. Tokens + auth working but still no audio ⇒ subscription.

---

## Apple Music developer token (JWT) — rotation

This is a **different token** from the playback token above. The playback
token (`ApplicationMusicPlayer`) is minted on-device from the MusicKit App
Service. The token here is a hand-generated **ES256 JWT** (`exp` ≤ 6 months)
that populates `AppConfig.appleMusicDeveloperToken`, used by the shared
`AppleMusicArtistProvider` (a catalog gap-filler for **artist images** in the
metadata cascade — NOT playback).

- **iOS source:** `iosApp/Parachord/Secrets.xcconfig`
  (`APPLE_MUSIC_DEVELOPER_TOKEN`, gitignored) → `Info.plist`
  `AppleMusicDeveloperToken = $(APPLE_MUSIC_DEVELOPER_TOKEN)` →
  `IosContainer.plist("AppleMusicDeveloperToken")`.
- **Android source:** `local.properties:APPLE_MUSIC_DEVELOPER_TOKEN` (the same
  JWT). **Both files hold the same value and must BOTH be updated on every
  rotation** — forget one and that platform's Apple Music artist images go
  blank with no error (`isAvailable()` checks `isNotBlank()`, never `exp`, so
  an expired token fails silently per-request).
- **Rotation today:** paste the new JWT into BOTH `Secrets.xcconfig` and
  Android `local.properties`.
- **Planned fix:** mint the JWT at build time from the `.p8` (already used for
  Mac builds) so it never drifts or expires unexpectedly —
  [parachord-mobile#186](https://github.com/Parachord/parachord-mobile/issues/186).

---

## Spotify Connect playback (`IosSpotifyConnect` in `ContentView.swift`)

**The canonical spec is `/CLAUDE.md` → "Spotify Connect — Device Wake &
Playback" + Common Mistakes #19–#31. Treat it as the spec, not as
background reading.** That section is hard-won from real Android incidents;
the iOS flow MUST conform. (It didn't at first — a loose by-eye port shipped
an 18s ungated `getDevices` poll that flooded a rate-limited account and kept
Spotify's rolling abuse window hot for hours. Don't repeat that.)

Conformance checklist (audited 2026-06-07):

- **Honor the rate-limit cooldown before ANY Connect call.** Device/playback
  endpoints (`getDevices`, `startPlayback`, `pausePlayback`, …) are ungated by
  design. `play()` calls `spotifyClient.rateLimitRemainingMs()` first and bails
  when > 0; `resolveLocalDevice`'s poll loop re-checks and bails mid-wait.
  **Never poll Spotify during an abuse window** — it re-arms the cooldown and it
  never clears (CLAUDE.md `ResolverManager.ensureTokensFresh` post-mortem).
- **Device poll bounds:** 300ms interval, ~12s deadline (the launch-path
  value — `spotify://` is iOS's launch-intent equivalent), ~1.5s wake settle.
  Not a flat unconditional 18s.
- **Self-select THIS device** (`isLocalRealDevice`: name + Smartphone/Tablet
  type), never an active *remote* phantom. Always wake on the local path.
- **Single-pass**, single 502 retry after 1s; **cold-device verification**
  (2 polls × 500ms via `getPlaybackStateOrNull`); persist the **synthetic**
  `localDeviceId`, never the real id.
- **Deferred (low-risk on iOS today, no device picker yet):** granular
  404/hard-fail preference cleanup, and warm-path handling of a non-local
  preferred device. Do them when the picker lands.
- **Spotify writes/sync are now gated (shared, #176 — DONE).** `saveTracks`,
  `removeTracks`, `createPlaylist`, `replacePlaylistTracks`, follow, etc. route
  through `SpotifyClient.gatedSend`, so a 429 on a write arms the same persisted
  cooldown a read does. iOS inherits this for free. Only the interactive
  playback PUTs + `getDevices` stay ungated (they consult `rateLimitRemainingMs()`).

**Cooldown is now an ESCALATING circuit breaker, not a flat 1 h (shared, #182).**
The shared `RateLimitGate` doubles the cooldown on each consecutive 429
(SpotifyClient: 1h→2h→4h→6h cap; resets on the first clean response). Rationale:
Spotify's abuse ban outlasts a flat 1 h, so a flat cooldown re-pokes on every
lapse and never clears (observed 12+ h on a quiet network). iOS inherits this —
just don't add a *second*, shorter local cooldown that would undercut it.

**Mirror the resolver rules (shared spec, #182) in `IosResolverCoordinator`:**
when a track already has a `spotifyId`, verify it via `getTrack` (returns the
source + artwork) and **do NOT also fire a Spotify search** — that doubled
Spotify volume per cached track on Android. And on a 429 during verify, **keep
the known Spotify ID as a source** (don't drop it) so the badge doesn't vanish
across a list. Full rule: `/CLAUDE.md` → "Spotify Rate-Limit Gate — Abuse-Ban
Remediation".

**Last.fm `guardedGet` (shared, #183).** If Last.fm artist/charts/friends come
back empty on iOS, it's almost certainly the `apply(build)` receiver-binding bug
(a bare `crossinline build()` inside the Ktor `get {}` drops every param). Fixed
in `LastFmClient`; guarded by `LastFmClientParamsTest`. See `/CLAUDE.md` KMP
rules #12.

### Why iOS can't silently wake Spotify like Android (don't re-litigate)

Recurring question: "Android wakes Spotify silently — why can't iOS just do
what Android does?" It **cannot**, and not because we missed a trick:

- **Android's silent wake is an OS broadcast, not a Connect call.**
  `SpotifyPlaybackHandler.ensureSpotifyRunning()` sends a targeted
  `KEYCODE_MEDIA_PLAY` media-button **broadcast** to `com.spotify.music`
  (`sendBroadcast(Intent(ACTION_MEDIA_BUTTON).setPackage(...))`), which wakes
  Spotify's background `MediaBrowserService` with no UI. The Connect Web API
  (`startPlayback?device_id=…`) only runs **afterward**, once Spotify has
  registered as a device.
- **"Call Connect with its own device ID" can't wake a cold Spotify.**
  Chicken-and-egg: `startPlayback`/`transfer` can only target a device already
  in `GET /me/player/devices`. A killed Spotify isn't in that list, so there's
  no id to call, and the Web API has no "wake device X" endpoint. That's
  exactly *why* Android needs the broadcast first.
- **iOS forbids the broadcast.** App sandboxing blocks sending a media-button
  (or any targeted IPC) into another app's background service — there is no iOS
  equivalent of `sendBroadcast(setPackage("com.spotify.music"))`. The only
  inter-app mechanism is the URL scheme (`spotify://` / `spotify:track:<id>`),
  which **foregrounds** Spotify. The SDK's `authorizeAndPlayURI` foregrounds it
  too. So **there is no silent cold-wake on iOS by any path.**

**Resulting iOS contract:** silent when Spotify is already a live Connect
device (warm path controls it via Web API); one unavoidable foreground when
Spotify is cold (URL-scheme wake → poll → `startPlayback`, made reliable by the
background-task assertion in `resolveLocalDevice`). This is the best achievable;
the SDK does not improve the cold case. See
`docs/plans/2026-06-08-ios-spotify-app-remote-design.md` §0.

---

## What's intentionally deferred (don't "fix")

- Full Koin DI graph — `IosContainer` is hand-rolled on purpose until
  the auth module is needed.
- Auth token providers: **Spotify is wired** (`SpotifyAuthTokenProvider` +
  `SpotifyTokenRefresher`, OAuth via `ASWebAuthenticationSession`). Every other
  realm still returns null — correct for the unauthenticated endpoints in use.
  Last.fm / others wire their providers in when those features land.
- The SQLDelight DB + DAOs aren't in the container yet — Library /
  collection screens need them + an iOS media scanner. **When that lands,
  wire resolver-ID backfill onto the persistent track rows** — mirror
  Android's `TrackResolverCache.backfillResolverIds` (`resolveInBackground(...,
  backfillDb = true)`): after resolving a *library* track, write the discovered
  `spotifyId` / `appleMusicId` / `soundcloudId` / MBIDs back onto its row so it
  never re-resolves. **Ephemeral tracks (weekly playlists) intentionally do NOT
  persist** — Android calls them with `backfillDb = false` and re-resolves each
  session; the persisted Spotify rate-limit cooldown (`IosContainer`'s
  `spotifyClient` `loadCooldownEpochMs`/`saveCooldownEpochMs` → KvStore
  `spotify_rate_limit_cooldown_ms`) is the safety net for that, NOT a standalone
  resolver cache. Don't build a separate persistent resolver cache for ephemeral
  tracks — it diverges from Android and is throwaway once row-backfill exists.
- The "Dev" tab (phase 1–4 platform-actual smoke tests) stays until the
  real screens cover everything; it has its own playback player.
