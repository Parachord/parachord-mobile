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

**Adding a whole TARGET (extension / app), not just a file → use the `xcodeproj`
Ruby gem, NOT hand-edits.** A new target means target + buildConfigList + container
proxy + dependency + an embed copy-files phase — far too error-prone to hand-wire.
`gem install --user-install xcodeproj` (no sudo), then a small Ruby script:
`Xcodeproj::Project.open('Parachord.xcodeproj')` → `proj.new_target(:app_extension,
'ShareExtension', :ios, '17.0', proj.products_group, :swift)` → set build settings →
`app.add_dependency(ext)` → a copy-files phase with `symbol_dst_subfolder_spec =
:plug_ins` embedding `ext.product_reference` → `proj.save`. The save **regenerates
the whole pbxproj in canonical format** (existing `AA00…` ids preserved, new objects
get real UUIDs) — a big but functionally-identical diff that builds fine. This is how
the Share Extension (#17) was added.

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
created by `AppBootstrap` (below), shared by the Now Playing tab and the
mini player.

**Playback starts from `AppBootstrap.shared.start()`, not from a view.**
CarPlay can launch the app with the phone locked and no phone window scene,
so `AppBootstrap.swift` holds everything playback needs, in order: the `.axe`
plugin host (NativeBridge installer, then `loadPluginsAndList`),
`ResolverPrefs`, `AppPlayback.shared`, the Spinoff / Listen Along / chat playback binds, the
paused queue restore, and the current track's re-resolve on every track
change (its artwork). `start()` is idempotent: concurrent and later callers
await the same run, and it logs `APP_BOOTSTRAP started` once per launch.
The restore applies the saved queue only if nothing has played yet
(`QueueRestorePolicy`, shared with Android, checked after it read storage):
a CarPlay tap waits for the bootstrap, but a deep link or a phone tap doesn't,
and the old queue used to land on top of it (`QUEUE restore skipped` in the log).
`RootGate` (behind the splash) and `ContentView` both call it; the phone's
views keep only UI-session work (friends, collection sync, hosted-playlist
polling, banners, sheets). Don't move playback start-up back into a view.

The Dev tab keeps its OWN separate smoke-test player on purpose; it retires
with the Dev tab.

### Error and empty states — show what Android shows

A repository's `Resource.Error` carries the user-facing message (the same text
Android shows). **The iOS bridge must never drop it.** It used to: the
container's flows kept only `Resource.Success`, so a failed load left a screen
spinning forever or showing a misleading empty state ("Set your Last.fm
username" for every History failure).

- **Flows** (`criticsPicksFlow`, `freshDropsFlow`, `recommendedTracksFlow`,
  `recommendedArtistsFlow`, `concertsFlow`) go through `forDisplay()`: each list
  as it lands, and the `Resource.Error` itself (Swift: `v as? ResourceError`,
  `.message`) when the load failed with nothing to show.
- **Suspend loaders** (`loadTop*`, `loadRecentTracks`, `getFriendTop*`,
  `getFriendRecentTracks`, `getArtistEvents`) are `@Throws` and throw
  `IosLoadException` with the repository's message; Swift reads it with
  `pcLoadErrorMessage(error)` (nil for any other error). The 24 h top-list cache
  still answers when a refresh fails.
- **Both follow the shared `keepDataOnError` rule** (`model/Resource.kt`,
  Android's view models use it too): a failed refresh never replaces a list
  that's on screen; the error shows only when there's nothing to show.
- **Words come from the shared `UiMessages`** (`shared/.../ui/UiMessages.kt`):
  empty states, "Try Again" / "Retry", "Album not found", the MusicBrainz
  throttle line, the playlist-delete warning, the ListenBrainz invalid-token
  message. Android reads the same constants, so change a message there, once.
  A playback message lives beside its rule (`APPLE_MUSIC_SIGN_IN_MESSAGE`,
  `PLAY_START_FAILED_MESSAGE` in `PlayFailure.kt`).
- **Views:** `PCErrorState` (the message in the error color + an outlined
  retry, Android's `ErrorState`) and `PCEmptyState` (Android's `EmptyState`), in
  `Theme.swift`. Try Again on a curated screen calls `load(force: true)`, which
  starts over even inside `RevalidatingWatch`'s interval.

Where iOS shows these: Recommendations (per tab), Fresh Drops, Critical
Darlings, Concerts (the repository's message, no retry, as Android), History
and a friend's profile (per tab; a friend's Retry only on Recent, as Android),
an artist's On Tour tab, Album ("Album not found"), Search (the MusicBrainz
throttle line instead of "No results"), Home's AI suggestions (the provider's
message), Settings (an invalid ListenBrainz token is refused, not saved), Sync
now (the run's notices after a successful sync), deleting a playlist Apple
Music can't delete (a banner; the playlists list and the playlist screen use
the same `DeletePlaylistSheet`, which also deletes the copies on the services
the playlist syncs with, each a toggle, as Android deletes with sync), DJ chat with no AI key ("No AI providers
configured"), and a track only Apple Music could play while the app may not use
Apple Music (the sign-in message, an "Open Settings" action, stops on the track).

**Keychain items must be readable after first unlock.** A CarPlay launch can
happen with the phone locked, and iOS's default accessibility
(`kSecAttrAccessibleWhenUnlocked`) hides the sign-in tokens then.
`IosSecureTokenStore` therefore writes `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
and migrates older items once (`keychain_accessibility_v2`); until the migration
is done, reads and writes go through a legacy instance, all under one lock.
Details in the class's KDoc.

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
- **The Apple Music hooks are host globals too.** `applemusic.axe` calls
  `window.appleMusicSearchWithMusicKit` / `appleMusicLookupSong` /
  `appleMusicLookupAlbum` / `appleMusicLookupPlaylist` before iTunes (whose
  Search lags new releases). `BOOTSTRAP_JS` defines them (ported from
  Android's `bootstrap.html`) on `NativeBridge.appleMusicCatalogGet`, which
  Swift attaches and forwards to `IosContainer.appleMusicCatalogGet` → shared
  `AppleMusicCatalog`. The developer token never enters JS; the callback gets
  the body as a string and `__amCatalog` parses it.
- **ID-based resolvers return NO top-level `url`.** `applemusic.axe` returns
  `{appleMusicId, previewUrl, appleMusicUrl}`; `spotify.axe` a URI. Android's
  `AxeResolveResult` required `url` (it only runs url-based bandcamp/youtube
  `.axe`). Accept a source with EITHER a playable `url` OR a routable ID, and
  map the extra fields (`previewUrl`, `appleMusicUrl`, `soundcloudUrl`, …).
- **Most resolvers need a connection.** With no tokens: soundcloud "No token,
  skipping", spotify silent, localfiles "not available". **Apple Music needs
  its Music User Token too** (Settings → Plug-ins → Apple Music → Connect): its
  catalog search is native (the shared `AppleMusicCatalogResolver`, which
  Android uses too; no `.axe`) and returns nothing without the token (#327),
  logging "Apple Music resolution skipped: no Music User Token" once. Bandcamp needs nothing.
- **`NativeBridge.log` uses `NSLog`, not `print`** so `.axe` / resolver-loader
  `console.log` is captured by `xcrun simctl log stream`. Filter with a
  predicate like `eventMessage CONTAINS "plugin:"`. `print` output does NOT
  reliably reach os_log.
- **The `NativeBridge` installer MUST be set at app STARTUP** (`AppBootstrap.start()`),
  not just in the Dev tab. `IosJsRuntime.initialize()` only loads
  `resolver-loader.js` + the plugins when `nativeBridgeInstaller` is present;
  otherwise it stands up a bare JSContext with no `window.__resolverLoader`.
  `initialize()` is **idempotent** — if any production resolution
  (a playlist's `resolveInBackground`, a tap's on-the-fly `resolveSources`)
  runs before the installer is set, the host initializes plugin-less and stays
  broken for the whole session (no badges, nothing resolves, taps flash then
  fail). Symptom of regressing this: resolution works only after visiting the
  Dev tab. Keep the installer as `AppBootstrap`'s first step, before anything resolves.

### Running `.axe` async on iOS — poll, don't reimplement (DJ chat + dynamic models)

**RULE: don't natively reimplement what an `.axe` already does — ask first.** This bit us on dynamic AI models (#223): a native per-provider `listModels` (OpenAI/Anthropic/Gemini HTTP) got written when `chatgpt.axe`/`gemini.axe` already had `listModels`, and was reverted. The `.axe`/marketplace is the source of truth (portability, marketplace updates, one place for desktop + both mobile platforms). The ONLY legitimate native iOS layer is the GLUE that *runs* the `.axe` in JavaScriptCore — never a reimplementation of the plugin's logic. See root `CLAUDE.md` → Common Mistakes #6.

- **Any async `.axe` function returns `[object Promise]` on JSC if read synchronously** (same gotcha as `resolve` — a bare `(async()=>…)()` doesn't await). Use the SAME `window.__resolveResults['<unique-key>']` poll as `IosResolverRuntime.resolveOne`. `IosResolverRuntime.listModels(pluginId, apiKey)` does this for the AI plugins' `listModels`; `aiModels(pluginId, apiKey)` wraps it with the desktop precedence: a static `select` setting's curated `options` win, a `dynamic-select` setting fetches live via `listModels` and falls back to the plugin's `fallbackOptions` when empty/keyless. `aiModelDefault(pluginId)` reads the setting's `default` (synchronous property read — no Promise). The Settings picker seeds `stored || pluginDefault` (`app.js:59532`).
- **DJ chat tool execution bridges Kotlin → the Swift coordinator via lambdas.** `IosDjToolExecutor` (iosMain) does search/resolve in Kotlin (`metadataService`) and delegates the playback verbs to `IosChatPlaybackBridge` closures that Swift sets at app start (`IosContainer.bindChatPlayback(...)`), fire-and-forget on `@MainActor` (like the scrobble/spinoff bridges). Pool/queue tracks are metadata-only and resolve on-the-fly at play time. The `ChatContextProvider` playback snapshot is PUSHED from Swift (`updateChatPlaybackSnapshot`) so the Kotlin side never reads `@MainActor` state cross-thread.

### Queue advance on iOS — the shared policy

`QueuePlaybackCoordinator` moves the queue through one `advance(_ trigger: AdvanceTrigger)` that follows the shared `advanceAction` (root `CLAUDE.md` → "Queue advance and repeat"). `skipNext` is `advance(.nextPressed)` and a track end is `advance(.trackEnded)`; Listen Along owns an ended track first, then a spinoff pool, then the queue and repeat mode decide. The Swift `RepeatMode` enum and `originalQueue` are gone: repeat is `QueuePlaybackCoordinator.repeatMode`, of the shared `RepeatMode` type; repeat all wraps the whole queue through `QueueManager.wrapToStart` (so added tracks come round), and Next on the last track wraps under repeat all. An unplayable last track wraps too, but only when a track has played (`.played`) since the queue was set, restored or last wrapped (`playedSinceWrap`), so a queue where nothing plays stops. At the end of the queue with repeat off the cleared track goes to history (`QueueManager.pushToHistory`), so Previous goes back to it. During Listen Along repeat counts as off and the end of the queue keeps the track. Don't add a Swift-side copy of any of this.

A track with no playable source skips through `advance(.skippedUnplayable)` (or stops with `START_FAILED` offline); iOS doesn't record `PlayFailure.NO_SOURCE` yet. See root `CLAUDE.md` → "Queue advance and repeat" → "A track with no source skips, per platform".

### Spinoff + Listen Along on iOS — coordinator-level, not a queue swap

Model + cross-platform details live in root `CLAUDE.md` → "Spinoff + Listen Along". iOS specifics: the spinoff pool + `spinoffMode` + `preSpinoffContext` live on `QueuePlaybackCoordinator` (ContentView.swift) — `beginSpinoff`/`advanceSpinoff`/`exitSpinoff`; `skipNext` + `autoAdvance` check the pool BEFORE the queue. The pool and the availability check come from the shared `buildSpinoffPool` / `checkSpinoffAvailable` (#534; the similar-artists fallback is described in root `CLAUDE.md`) through `IosContainer.spinoffPool` / `spinoffAvailable`. `spinoffPool` is `@Throws` and throws `SpinoffFetchFailedException` when the similar-tracks request itself failed, so `SpinoffController` can tell `lastFetchFailed` from `lastEmptySeed`; Radio Mode B catches it too. The words are the shared `PlaybackMessages` (Android's). A radio link's pool refills from its `refill=` URL through the shared `PoolRefiller` (`IosContainer.newSpinoffRefiller()`, exported to Swift as `doNewSpinoffRefiller()`) when fewer than 3 tracks are left; a refill that lands after its station ended is dropped. The next 3 pool tracks are resolved ahead, and shuffle is refused during a spinoff or Listen Along (shared `shuffleAllowed`). Listen Along keeps its OWN suspension in `ListenAlongController`; the two never co-exist (each `stop`s/`exitSpinoff`s the other). The chat/spinoff/listen-along screens slide in as **trailing-edge drawers** (`.transition(.move(edge: .trailing))` + a left-edge swipe-to-close zone in the RootView ZStack), NOT bottom covers — matches Android's right-slide. **The Spinoff icon is the fork** (`Assets.xcassets/SpinoffFork`, Android's `ParachordIcons.Spinoff` / `ic_auto_spinoff.xml`, desktop's icon; `SpinoffForkActive` is the fork cut out of a disc, for CarPlay). Both are template SVGs with preserved vector data, so they tint and scale (13–22 pt on the phone, CarPlay's 20 pt buttons). `sparkles` is Shuffleupagus's icon, not Spinoff's.

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

A cached row whose entry lacks an active resolver asks just that resolver
again when the row appears (`resolveSingleResolver`, merged in). How long it
waits depends on why it's missing (#566): a resolver that answered "no match"
waits 30 minutes, one that couldn't answer (offline, a Spotify cooldown, a
plugin that threw or timed out) waits 2, 4, 8 … 60 minutes (shared
`ResolverRetry`). The full resolve reports both (`resolveSourcesReport` →
`ResolveReport.missed` / `.failed`), and `resolveSingleResolver` throws for a
resolver that couldn't answer, so Swift catches it rather than reading a nil.

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
- **A stored absolute path into the app container goes stale.** iOS can move
  the data container (a new UUID in the path) across launches, reinstalls and
  updates; the files move, the stored path doesn't. Playlist mosaics are saved
  as absolute `file://…/playlist_mosaics/<id>.jpg` URLs, so the mosaic check
  (`ImageEnrichmentService.refreshPlaylistMosaic`) looks for the file: a missing
  file whose name exists in today's mosaic directory gets its row re-pointed,
  otherwise the mosaic is built again (`PlaylistMosaicPaths`). Readers that can
  run before the launch pass (`pcCover`, CarPlay) map the URL through
  `IosContainer.playlistArtworkUrl(stored:)`. Imported local files avoid the
  problem with a container-relative `pcfile://` marker instead.
- **A playlist mosaic follows its tracks.** Its URL carries `?v=<fingerprint>`
  of the covers it was built from (the first four distinct track covers). The
  check rebuilds it when the playlist's covers no longer match: at launch
  (`regeneratePlaylistMosaics`) and after any track write
  (`PlaylistTrackDao.changes` → `watchTrackChanges`, 2 s after the last write).
  A new `v` also makes `PCImageStore` load the rewritten file. Shared with
  Android; see root `CLAUDE.md` → "Playlist Mosaics".

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

## Apple Music developer token — embedded at build time

The `Embed Apple Music developer token` build phase on the Parachord target
runs `scripts/embed-apple-music-token.sh`, which calls the repo's single
signer (`scripts/apple-music-token.py --ensure`) and stamps the current token
into the BUILT `Info.plist`. You do NOT hand-paste it into
`Secrets.xcconfig` before a release any more.

- **It stamps the built plist, not an xcconfig, on purpose.** xcconfig values
  are resolved before any build phase runs, so a phase cannot feed one.
  Writing `$TARGET_BUILD_DIR/$INFOPLIST_PATH` after "Process Info.plist" and
  before code signing is the supported injection point — and it's why a token
  rotated during a build still reaches that build.
- **`--ensure`, not `--print`:** rotation happens only inside the 30-day
  renewal window, so ordinary builds reuse the existing token instead of
  churning `Info.plist` on every compile.
- **Fail-soft:** no `.p8` configured (outside contributor, CI without the key)
  warns and keeps whatever `Secrets.xcconfig` supplied. A metadata token must
  never break the build. Verified by building with both the `.p8` config and
  `Secrets.xcconfig` removed: `BUILD SUCCEEDED` with a warning.
- **Don't add a second JWT signer here.** One script serves both platforms;
  a second implementation is exactly the drift this replaced.

---

## TestFlight / App Store distribution

```bash
/Users/jherskowitz/.../iosApp/scripts/testflight.sh            # archive + export .ipa
/Users/jherskowitz/.../iosApp/scripts/testflight.sh --upload   # + upload (needs ASC API key)
```

**Always invoke the script by ABSOLUTE path.** The Bash tool's cwd does not
reliably persist between calls (same rule as the `xcodebuild -project` one
above); a relative `./iosApp/scripts/testflight.sh` resolved against `iosApp/`
and died with exit 127.

> **Every build is published to Organizer automatically — and that is
> load-bearing.** The script archives into `iosApp/build/testflight/`, which is
> OUTSIDE `~/Library/Developer/Xcode/Archives`, the only directory Organizer
> reads. It therefore copies each finished archive there as
> `Parachord <version> (<build>).xcarchive`. **Do not remove that copy step:**
> without it a build is invisible in Organizer, and "select the latest archive
> → Distribute App" silently ships whatever older build IS listed. That is
> what happened to build 0.1 (4) in Sept 2026 — the fix was verifiably in the
> `.ipa`, but Organizer's newest archive was 0.1 (2) from August, so August
> is what went out. Still **confirm the build number** in Organizer before
> distributing; uploading the `.ipa` via Transporter or `--upload` avoids the
> ambiguity entirely.

`ExportOptions.plist` is `method = app-store-connect` — that names the SERVICE,
not "release to the App Store." Uploading a build only makes it available in the
TestFlight tab; an App Store release is a separate, explicit submission. There is
no TestFlight-only upload pipeline.

**Local success proves NOTHING about uploadability.** `ARCHIVE SUCCEEDED` +
`EXPORT SUCCEEDED` + a valid `codesign -v` all passed on two archives that Apple's
server-side validator then rejected. Distribution defects surface only at upload.
Both bugs below were also **invisible on the simulator**, which is why the Share
Extension shipped broken for so long — verify distribution changes by uploading.

### Trap 1 — `PlugIns` is RESERVED; never name a bundled folder that case-folds to it

The bundled `.axe` resource folder used to be `Resources/plugins`. iOS puts app
extensions in `PlugIns/`, and **the macOS build filesystem is case-INSENSITIVE**,
so `plugins` and `PlugIns` became ONE directory: `ShareExtension.appex` got swept
into the resources folder and the archive had **no `PlugIns/` at all**. App Store
validation rejected it (90680 / 90354 / 90171), and on a real (case-sensitive)
device iOS could never have registered the extension — so sharing a link into
Parachord would silently do nothing.

Fixed by renaming to **`Resources/axe-plugins`** (+ `pbxproj` file-ref path +
both `PluginFileAccess.ios.kt` `pathForResource("axe-plugins")` calls). The
plugin CACHE dir stays `<AppSupport>/plugins` — it's outside the bundle, so it
can't collide, and renaming it would orphan every downloaded plugin update.

### Trap 2 — the ShareExtension target is `GENERATE_INFOPLIST_FILE = NO`

Unlike the app target, the extension uses a hand-written
`INFOPLIST_FILE = ShareExtension/Info.plist`. In that mode Xcode **synthesizes
nothing** — it only expands `$(...)` variables — so every required key must be
declared explicitly. The plist (authored by the `xcodeproj` gem when the target
was added, #17) omitted them, and validation rejected the build: 90360 (missing
`CFBundleExecutable`) plus 90171, which reports the extension's binary as a
forbidden "standalone executable" precisely BECAUSE no `CFBundleExecutable`
claims it.

Required keys now present: `CFBundleExecutable = $(EXECUTABLE_NAME)`,
`CFBundleInfoDictionaryVersion`, `CFBundleVersion = $(CURRENT_PROJECT_VERSION)`,
`CFBundleShortVersionString = $(MARKETING_VERSION)`.

**`MARKETING_VERSION` + `CURRENT_PROJECT_VERSION` are mirrored onto BOTH
ShareExtension build configs** in `project.pbxproj` — they were app-target-only,
so those `$(...)` refs would have expanded to EMPTY strings (trading one
validation error for another). Apple also requires the extension's version to
MATCH the containing app's. **Bumping the version means editing all four configs
(app Debug/Release + extension Debug/Release).**

### Version / build numbers

`MARKETING_VERSION = 0.1`. `CURRENT_PROJECT_VERSION` is the build number, bumped
per shipped TestFlight build (0.1 (1) → 0.1 (2) → …). Each upload App Store
Connect *accepts* needs a unique, increasing build number — but a build rejected
at validation is never accepted, so the same number can be reused after a fix.
Bumping means editing **all four configs** (app + extension, Debug + Release).

### Bumping the iOS build must NOT ride the Android release pipeline

**iOS TestFlight builds are cut LOCALLY** (`scripts/testflight.sh` → Xcode
Organizer / `--upload`). They do not go through CI at all, and the iOS build
number (`CURRENT_PROJECT_VERSION`, in `project.pbxproj`) is **completely separate**
from the Android `versionCode` (in `app/build.gradle.kts`). Do not conflate them.

**The workflow: bump on a BRANCH, ship from the branch, don't merge just for the bump.**
```
git checkout -b chore/ios-testflight-build-N main
# bump CURRENT_PROJECT_VERSION in all four configs, commit
scripts/testflight.sh            # archive + export, upload via Organizer
```
Merging the bump into `main` is optional book-keeping — it is NOT required to
ship to TestFlight, and it's easy to over-worry about. Here's what actually
happens, verified against `.github/workflows/build.yml`:

- **Push to `main` (e.g. merging a PR)** → the "Build Release" workflow runs
  `assembleRelease`/`bundleRelease` and uploads the APK+AAB as **14-day CI
  artifacts only**. It does **NOT** publish a GitHub Release and does **NOT**
  upload to Play Console — both are gated `if: startsWith(github.ref, 'refs/tags/v')`.
- So merging an iOS-only bump to `main` is **harmless** — no Android publish, no
  duplicate-`versionCode` Play rejection. Its only cost is one ~15–20 min CI build
  producing throwaway artifacts. (Earlier worry that it would "republish Android at
  the same versionCode" was wrong — Play upload is tag-gated, not push-gated.)
- **Android is published only by a `v*` tag**, and every such tag must carry a
  freshly-bumped Android `versionCode`. That's the real duplicate-`versionCode`
  rule — it lives at Android *tag-release* time and has nothing to do with iOS
  build bumps.

Net: keep iOS build bumps on a branch so `main`'s CI isn't churned by
TestFlight-only changes, ship TestFlight from that branch, and fold the bump into
`main` whenever it's convenient (or alongside other work) — never tag `v*` for an
iOS-only change.
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
  *editorial* (`37i9…`) playlists return empty from the Web API for third-party apps,
  so `ProviderPlaylistResolver` falls back to the **auth-free embed page**
  (`open.spotify.com/embed/playlist/<id>` → `__NEXT_DATA__` JSON, #286). The Web-API
  path is gated on a Spotify connection but the embed fallback is NOT — public/editorial
  playlists open even with Spotify disconnected. The embed `uri` (`spotify:track:<id>`)
  is carried onto each track as a `spotifyId` hint so a Spotify source is verified
  without a search.

### Share Extension — handling Spotify/Apple links shared INTO the app (#17)

iOS can't intercept `open.spotify.com`/`music.apple.com` link TAPS (you can't
out-verify another domain's Universal Links; the `spotify:` scheme collides with
Spotify). The only mechanism is a **Share Extension** (`iosApp/ShareExtension/`, bundle
id `com.parachord.ios.share`). It's a **headless pass-through**: extracts the shared URL
(a `public.url` attachment, or a URL inside shared `public.plain-text`), filters to
Spotify/Apple hosts, and opens the host app via `parachord://external?url=<encoded>`
by walking the responder chain to `UIApplication.open` (extensions have no
`UIApplication.shared`). It does NOT link `Shared.framework` (stays light).

- Host side: `RootView.handleDeepLink` special-cases `host == "external"` →
  `IosContainer.resolveExternalLink`, which classifies via the shared `ExternalUrlParser`
  and reuses existing resolvers (playlist → `resolveProviderPlaylist`; album →
  `resolveBy{Spotify,AppleMusic}`; track → `getTrack`/`lookup` with the id as a hint;
  artist → nav). Returns an `IosComplexLink` the view dispatches (`play` / `navArtist`).
- **Device signing:** the extension needs its own App ID (`com.parachord.ios.share`)
  provisioned — automatic signing + `-allowProvisioningUpdates` should auto-register it
  on the first device build (same flavor as the associated-domains capability, #124).
  Simulator unaffected (extensions run in the sim share sheet — test from Safari/Notes,
  or simulate the hand-off directly: `simctl openurl booted "parachord://external?url=…"`).
- Activation rule is broad (any single web URL) + host-filtered in `ShareViewController`;
  narrowing via an `NSExtensionActivationRule` predicate is a follow-up.
---

## MusicKit / Apple Music playback (the full saga — verified on-device)

**Connected means the Music User Token**, as on desktop (its plugin declares
`requiresAuth`) and Android. Settings → Plug-ins → Apple Music → Connect
stores it; the Sync tab's Apple Music toggle gets it the same way.
Without it, nothing offers Apple Music:

- **Resolution:** the native catalog search returns null (#327).
- **A stored Apple Music id:** `IosContainer.resolveSources` drops the hint (#533). Otherwise it would become a 1.0 source, rank first and prompt for MusicKit authorization at play time.
- **Settings:** Apple Music `requiresConnection`, so it sits under Disabled with "Connect to enable". Connecting from either place adds it to `active_resolvers` in canonical order (`enableResolverAfterConnect` for the Sync tab).

**Disconnect** (the Apple Music sheet) clears the token. It removes Apple Music from the active resolvers and drops its cached sources (`IosTrackResolverCache.resolverRemoved`). As on Android, it also takes Apple Music out of the enabled sync providers, keeping synced items and links. The app can't revoke the MusicKit permission itself; that's in iOS Settings › Privacy & Security › Media & Apple Music.

Apple Music *playback* (`ApplicationMusicPlayer`) is the hard part. It took
many rounds. The complete, correct procedure:

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
   Denied or restricted access skips the source; if nothing else plays,
   playback stops on the track with Android's "Sign in to Apple Music to play
   this track" (`APPLE_MUSIC_SIGN_IN_MESSAGE`) and an "Open Settings" action
   (the permission is the app's own iOS Settings page), and Play retries.
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
  type), never an active *remote* phantom. Always wake on the local path,
  except in the car: there it never wakes, never shows the picker and fails at
  once if the phone's device isn't listed (see "Playback in the car").
- **The play is the track's album + an offset, never bare `uris`.** Spotify's
  Android app plays nothing for a bare `uris` play (root `CLAUDE.md`), so
  `startPlayback` asks `IosContainer.spotifyPlayRequest` (shared
  `SpotifyPlayRequests`) for the body, the same one Android sends. Whether
  Spotify's iOS app had the same failure wasn't checked. **Known gap:** Spotify
  plays on into the album when our track ends and Parachord can't reach the Web
  API to start the next one or pause. Android then pauses Spotify with a media
  key (root `CLAUDE.md`, "When Parachord can't reach Spotify"); iOS has no way
  to send another app a key.
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

## Background playback / audio session (#322) — the Android port

Playback stopping when the app backgrounds (Spotify OR Apple Music) traced to a
single root cause: `IosAVPlayer.init()` did `setActive(true)` on a **non-mixable
`.playback`** `AVAudioSession` at launch and never released it. There is ONE
`AVAudioSession` per process, so holding it active-but-silent (we produce no
audio for external engines) (a) interrupted Spotify the instant the app became
active (`otherAudioPlaying` flipped true→false on `DID_BECOME_ACTIVE` in the
trace), and (b) made iOS suspend the app ~30s after backgrounding (`INTERRUPTION
type=began` with no `type=ended`), freezing polling/auto-advance and pausing
`ApplicationMusicPlayer` (whose controller is our process).

The fix is a **direct port of Android's external-playback survival** (root
`CLAUDE.md` "External Playback Background Survival"; `PlaybackController` +
`PlaybackService`):

- **Per-engine session ownership** (`IosAudioSession` in `ContentView.swift`) —
  the category *options* are the load-bearing part, and they DIFFER per engine:
  - Local AVPlayer audio → `.playback`, no options (we own the route; activated
    in `IosAVPlayer.play()`, released in `stop()` — NOT at launch).
  - **Apple Music → `.playback` + `.mixWithOthers`** (`configureForAppleMusic`).
    AM needs our keepalive to stay alive in the background (see below), but the
    keepalive `AVAudioEngine` and `ApplicationMusicPlayer` then share ONE process
    session. On a NON-mixable session those two fight and **AM stutters ~once a
    second** (confirmed on-device with a SINGLE keepalive — so it was NOT the
    doubling). `.mixWithOthers` makes our engine register as *secondary* so it
    stops fighting AM. Cost: MusicKit force-resets the session (`options 1→0`)
    once on the first background — a one-time brief drop, far better than a
    per-second stutter. **Configure mixable BEFORE `keepAlive.start()`** so the
    engine comes up secondary. (Tried non-mixable to avoid the one-time hiccup;
    it re-introduced the per-second stutter — don't.)
  - **Spotify → `.playback` + `.mixWithOthers`** (`configureForSpotify`) — the
    iOS analog of Android's `handleAudioFocus = false`. Spotify plays in its OWN
    app, so our (silent-keepalive) session must not interrupt it; this is also
    what stops Spotify dropping out when Parachord returns to the foreground.

  Both external engines end up on `.mixWithOthers` — kept as two functions only
  for the distinct `SESSION_CONFIG context=…` log tag.
- **Silent keepalive** (`IosExternalKeepAlive`) — **BOTH external engines**
  (Spotify AND Apple Music). The iOS port of Android's `res/raw/silence.wav`
  loop **AND its partial WakeLock / WiFi lock**. A code-generated zero-filled
  PCM buffer looped at volume 0 through `AVAudioEngine` keeps
  `UIBackgroundModes: audio` satisfied so the app stays alive; iOS grants an
  actively-playing app both CPU and network, so there's no separate
  wakelock/wifi-lock to port.
  - **Spotify** plays in a separate app → without our background audio iOS
    suspends us and `startSpotifyPolling` stops → no auto-advance.
  - **Apple Music** renders OUT OF PROCESS (`RemotePlayerService`), so from
    iOS's background-execution view our app isn't "producing audio" either —
    WITHOUT the keepalive it gets suspended, the `IosMusicKitPlayer` tick loop
    freezes, and the track only auto-advances when you next foreground (an early
    #322 trace showed a track sitting idle in the background, advancing only
    ~12s AFTER `DID_BECOME_ACTIVE`). So AM needs the keepalive too;
    `applyEngineHandoff` calls `keepAlive.start()` for BOTH `.spotify` and
    `.musicKit`. WITH it, the final trace confirmed AM auto-advances fully locked.
  - **The once-a-second AM stutter was the DOUBLED keepalive** (a second
    coordinator ran a second `AVAudioEngine` on the same session), NOT the
    keepalive itself — the `AppPlayback.shared` singleton makes that impossible.
    (If a SINGLE keepalive still stutters AM on its non-mixable session, the
    fallback is `.mixWithOthers` for AM too — the 4b6a6dd trace showed
    mix-with-others + keepalive auto-advanced without the per-second stutter, at
    the cost of a one-time hiccup when MusicKit resets the session on first
    background.)
  **Resilience is load-bearing:** unlike Android's FGS + wakelock, an
  `AVAudioEngine` is stopped by the system on audio-route/config changes and
  interruptions; if it isn't restarted the app is suspended and auto-advance
  dies. So the keepalive observes `AVAudioEngineConfigurationChange` and restarts
  the engine, and `resumeActiveEngine` re-arms it (+ re-activates the session)
  after interruptions. Started/stopped on engine handoff (`applyEngineHandoff`).

  **DANGER — never reconnect a RUNNING `AVAudioEngine` on the config-change
  path.** `engine.connect(...)` on a running engine itself posts an
  `AVAudioEngineConfigurationChange`, so "reconnect on every notification" feeds
  itself into a loop that fires many times a second, thrashes the shared audio
  route, and **distorts other apps' audio (Spotify) badly enough to wedge the
  audio daemon until a device reboot** (hit once, #322). The graph is therefore
  attached+connected exactly ONCE (`buildGraphIfNeeded`, on a stopped engine),
  and the config-change handler only calls `startEngineAndPlay()` **guarded on
  `!engine.isRunning`** — restart-when-stopped can't re-trigger itself. Same
  guard on `restart()`. Don't "simplify" these guards away.
- **Interruption monitor** (`IosAudioSessionMonitor`) — resumes the active engine
  on `.ended + .shouldResume` (phone-call recovery), re-arming the session +
  keepalive for the external engines. There must be exactly one
  `QueuePlaybackCoordinator` process-wide (via the `AppPlayback.shared` singleton,
  below), so only one monitor is installed. (`installLifecycleMonitor: false` on
  the Dev-tab coordinator is belt-and-suspenders; `DevSmokeTestView` is
  `#Preview`-only and doesn't run in the app anyway.)
  - *During bring-up this class (plus a `PCAUDIO:`/`PCSPOT:` `NSLog` trace across
    the session/keepalive/Spotify/AM paths) instrumented the whole flow; the trace
    was removed once it confirmed the fix. If you're re-debugging background
    playback, re-add temporary `NSLog`s at the same points — a **doubled** trace
    is the tell that a second coordinator went live.*

**One coordinator, always — `AppPlayback.shared`.** `ContentView` binds the
`AppPlayback.shared` singleton, NOT a fresh `AppPlayback()`. On iPad
(`UIApplicationSupportsMultipleScenes = true`) a second window scene — or a
SwiftUI `@State` re-init — otherwise constructs a *second* `AppPlayback` →
second coordinator → doubled audio-session monitor, a second keepalive engine,
and a second `IosMusicKitPlayer` poll loop poking the SHARED
`ApplicationMusicPlayer` (that second AM poll loop was a suspect for the
once-a-second AM stutter). Two audio pipelines can't co-exist; there is exactly
one playback engine per process.

Background **auto-advance** works the same way on both engines: the app stays
alive on the silent keepalive, the per-engine poll loop detects the track end
(Spotify: `checkSpotifyEnd` advances ~2s early via interpolation, so it doesn't
even need a Web-API resync mid-cooldown), and `autoAdvance` starts the next
track over the Web API / MusicKit — no foreground. The next-track Spotify
`startPlayback` is the warm path (device already active), so it stays silent.
The bring-up trace CONFIRMED (#322) the app stays alive for Spotify locked — 4+
minutes of poll ticks with the screen off — AND that **cross-service Spotify→AM
auto-advance runs fully locked** (AM started ~4 min into background with no
`DID_BECOME_ACTIVE` in the trace). So the keepalive works for a separate-app
(Spotify) context too, not just Apple Music.

**Next-track Apple Music `Song` pre-fetch (perf, #322).** `play(appleMusicId:)`
turns the `appleMusicId` string into a playable MusicKit `Song` via
`MusicCatalogResourceRequest<Song>` — a ~1.6s network round-trip that the queue's
resolver cache does NOT cover (it stores the `appleMusicId`, but a `Song` is an
iOS-only type it can't hold). So `QueuePlaybackCoordinator.prefetchNextIfNeeded`
(driven off the 1s engine-agnostic tick) warms the next queued track's `Song`
~15s before the boundary — Android's pre-resolve-early pattern
(`PlaybackController.kt:1273`) applied to the MusicKit fetch — and `play()` uses
the cached `Song` (`IosMusicKitPlayer.prefetchedSong`, keyed by id) to skip the
round-trip. Measured AM start dropped ~3.1s → ~2.3s. A catalog lookup is a plain
REST call that never touches `ApplicationMusicPlayer`, so pre-fetching mid-playback
can't disrupt the outgoing Spotify or AM stream (unlike Android's WebView
`preload()` caveat). One-shot per current track; fires only when the next track
routes to AM. The remaining ~2.3s (queue-set + DRM buffer) is a tracked follow-up
(#336: pre-`prepareToPlay` on Spotify→AM only, unsafe for AM→AM).

**Retry the warm `startPlayback` on a transient failure while locked.** With the
screen locked, the Ktor PUT for the next-track warm `startPlayback` intermittently
THROWS a network error (`status=-1`, alongside `nw_protocol …udp` churn) — the
device is still valid, it's just a lock-time network blip. `startPlayback` retries
`502`/`-1` up to 3×0.8s. **Do NOT let a transient warm failure fall through to the
cold path**: cold wake foregrounds Spotify via `spotify://`, which can't happen
while locked, so a warm auto-advance would stall (the old track's interpolated
`pos` keeps counting past `dur` while nothing new starts). The retry keeps it on
the warm path so the advance succeeds silently.

**Key files:** `iosApp/Parachord/ContentView.swift` (`IosAudioSession`,
`IosExternalKeepAlive`, `IosAudioSessionMonitor`, `IosAVPlayer.play/stop`,
`QueuePlaybackCoordinator.applyEngineHandoff/resumeActiveEngine`).

---

## SoundCloud through its widget (#495, desktop parachord#1209)

SoundCloud plays in SoundCloud's embeddable widget inside a hidden `WKWebView`
(`IosSoundCloudWidget`, `SoundCloudWidget.swift`), engine `.soundCloud` in
`PlaybackRouter` / `QueuePlaybackCoordinator`. The API no longer gives full
tracks, so this is the only way to play one. Cross-platform details (the
shared page, desktop's module, previews counted as refused) are in the root
`CLAUDE.md` → "SoundCloud — the Widget".

- **Which upload resolves is the plugin's choice.** iOS resolves SoundCloud
  through `soundcloud.axe`'s `resolve`, not Android's native `pickSoundCloudTrack`
  (#582). **Known gap:** until the plugin skips preview-only tracks and uploaders
  whose name only contains the artist's (parachord#1307), iOS can resolve a fan
  podcast such as "Ramones of the Day" for "Judy Is a Punk"; Android doesn't.
- **The page is shared with Android, byte for byte.** `Resources/soundcloud-widget.html`
  and `Resources/soundcloud-widget-player.js` are copies of
  `app/src/main/assets/js/`; an Android unit test fails if they differ. Edit
  Android's and copy. iOS loads the page with `loadHTMLString` (https base URL)
  and inlines the module in place of its `<script src>`; events come back
  through the `soundCloudBridge` message handler.
- **WebKit has its OWN audio session, separate from the app's.** The widget's
  audio plays under `com.apple.WebKit` (the GPU process). When a track ends,
  WebKit releases that session, and with the screen locked iOS refuses to
  activate it again ("can't stop others because it is in the background and not
  the NowPlaying app"): the next track "starts" (position moves) but is SILENT.
  Our `IosExternalKeepAlive` can't prevent this — it holds the app's session,
  not WebKit's. Fix: the page's `scHoldAudio(true)` plays a silent looping
  `<audio>` so WebKit's session never goes idle between tracks; `stop()`
  releases it unless `keepAudioSession` (a refused track, where another
  SoundCloud track may follow). Verified on an iPad: locked auto-advance from
  one SoundCloud track to the next plays.
- **Background otherwise rides on the Apple Music setup**: mixable session
  (`IosAudioSession.configureForSoundCloud`) + the silent keepalive, so the
  coordinator stays alive for the widget's `finish` and auto-advance.
- **WebKit can't hold its media-playback process assertion** (third-party apps
  lack `com.apple.runningboard.assertions.webkit`; logged at every play). The
  keepalive + `scHoldAudio` were enough on-device; if locked playback ever stops
  mid-track, look there first.
- **Lock screen = WebKit's, driven by the page's Media Session.** WebKit
  publishes the now-playing info for the page that's playing (otherwise the
  page's `<title>`, "Parachord SoundCloud", no artwork) and takes the buttons
  itself: `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter` from Swift are
  ignored for this engine (verified on an iPad: only the Media Session handlers
  fire). So `scNowPlaying` sets the action handlers that relay play / pause /
  seek / next / previous to the coordinator (`onRemoteCommand`, `onRemoteSeek`),
  and our own scripts (`EmbedNowPlaying`, injected from Swift: the page itself
  stays byte-identical) set `navigator.mediaSession.metadata` (title, artist,
  album, the cover: its http(s) URL, then a `data:` URL once the app has
  downloaded it, as for Bandcamp below) and the position state: on play, pause, seek or a
  new duration, and every 5 s from the widget's progress. Not checked on a
  device or in the simulator (no SoundCloud sign-in there): whether WebKit
  publishes this top-level session or the widget iframe's. **Known gap:** WebKit shows
  ±10 s skip buttons instead of next / previous; nulling the page's
  `seekforward`/`seekbackward` handlers and disabling the skip commands on
  `MPRemoteCommandCenter` both had no effect.
- **A refused source falls through** to the next ranked one in `PlaybackRouter`
  (`onRefused`); in the car with no phone window at all, a source
  that would have to create the page is refused (see "Playback in the car").
  `playTrack(advancing:)` decides between "Playing via X", a
  silent skip (queue advancing or spinoff), and the `Notice` banner with
  "Open in SoundCloud" (`RootView`).

## Bandcamp through its embed (#513, desktop parachord#1231)

Bandcamp plays in its embedded player as the top-level page of a hidden
`WKWebView` (`IosBandcampEmbed`, `BandcampEmbedPlayer.swift`), engine `.bandcamp`
in `PlaybackRouter` / `QueuePlaybackCoordinator`. Cross-platform details (the
URL and state rules, the shared tracker, why there's no bridge) are in the root
`CLAUDE.md` → "Bandcamp — the Embed".

- **No message handler on this view.** Bandcamp's scripts run at its top level.
  The poll asks `bcState()` from outside, and `Resources/bandcamp-embed-control.js`
  is a byte-for-byte copy of Android's and desktop's (an Android unit test fails
  if Android's and iOS's differ).
- **In a window, or it never plays.** A view in no window stays at 0:00 (tested
  on WebKit for #513), so it's added to the key window, at `alpha 0.01`. In the
  car with no phone window at all it refuses instead (see "Playback
  in the car"): each track is a new page, so there's never one to reuse.
- **The audio-session hold lives in SoundCloud's page, and runs unmuted for the
  whole track.** See "SoundCloud through its widget": WebKit releases its session
  when a track ends, and iOS won't reactivate it locked. Each Bandcamp track is a new
  page, so a hold inside it would die with it. So `IosSoundCloudWidget` plays
  `scHoldAudio`'s silent loop in that page, which stays loaded, in a separate
  WebContent process. **Don't try to quiet it mid-track; both were tried on an
  iPad and the next track went silent:** stopped and restarted near the track's end
  (iOS won't start it again locked), and muted (WebKit counts a muted element as
  silent and interrupts it when the page isn't visible, logged as
  `MediaElementSession::visibilityChanged` / `beginInterruption`). The page carries
  the track's info too (`mirrorNowPlaying`), and its buttons route by `activeEngine`.
- **While paused, the Bandcamp page runs its own silent loop** (`__pcHold`). With no
  audio, iOS suspends the page's process, which drops the lock screen to "Not
  Playing" and leaves play with nothing to wake (seen on an iPad). It's in the same
  page as the player and started last, so WebKit shows this page's info rather than
  the hold page's. It's an unattached `Audio`, so desktop's `bcAudio()` can't find it.
  **Known gap:** while paused, the lock screen's position reads 0:00, the loop's own
  position (the track resumes from the right place). Reporting the real position
  each poll only made it bounce.
- **Lock screen and CarPlay:** WebKit publishes Now Playing from a page's Media
  Session, and shows whichever page updated last: this one or SoundCloud's
  holding page. Fixed scripts set it, each given one JSON object
  (`EmbedNowPlaying`): the track's metadata (title, artist, album, the cover the
  phone shows) in both pages, and the position (play, pause, seek, every 5 s)
  only in this one. The cover goes in first as its http(s) URL, then, once the
  app has it (through `PCImageStore`, a download capped at 5 s), as a
  `data:image/jpeg` URL of at most 512 px, if the same track is still playing:
  WebKit's own fetch of a redirecting cover (coverartarchive → archive.org)
  was the fragile step. **Known gap (iOS 18.3 simulator):** CarPlay still shows
  no cover there. WebKit hands MediaRemote the cover as TIFF data, and the GPU
  process logs `[MRArtwork] Failed to determine dimensions for provided image
  data` on every CarPlay artwork request (`CarPlayTemplateUIHost`: "No available
  artwork received"), whatever the source URL. On iOS 26.5 `mediaremoted`
  reported the cover's size (1200×1200), so it may be 18.x only; not yet seen
  on a CarPlay screen on 26.x or a device. Sent to the holding page, the position made it
  report the track as paused and take the lock screen back on each update. As a
  track starts, the holding page often updates last, so for about 5 s (until
  WebKit updates this page by itself) Now Playing shows its 1-second loop
  (0:01 / -0:00); a seek to the current position didn't make WebKit update
  sooner. After a resume WebKit kept showing the track as paused until the
  element was seeked, so the poll seeks it to where it already is
  (`__pcResync`). The paused-state silent loop is dropped on resume, not just
  paused. Checked in the simulator through `mediaremoted`'s log, not on a
  CarPlay screen. With no bridge, the button handlers only queue the command
  in the page, and the poll collects it; `drainRemoteCommands` accepts only the
  known commands and a finite position.
- **Refused:** falls through to the next ranked source in `PlaybackRouter`, as
  SoundCloud's does.
- **`cacheSchemaVersion` was deliberately not bumped** for
  `ResolvedSource.bandcampTrackId`. A bump re-resolves every track at once, which
  can trip Spotify's abuse window, and a cached source with no id has its id
  looked up from its page. A custom-domain page can't be, and stays refused until
  that entry is resolved again.

## Now Playing and remote commands (`NowPlayingPublisher`)

The lock screen, Control Center, AirPods and CarPlay's Now Playing screen all
read `MPNowPlayingInfoCenter` and send their buttons through
`MPRemoteCommandCenter`. `NowPlayingPublisher.swift` is the only Swift code
that touches either. The primary `QueuePlaybackCoordinator` owns it (the Dev-tab
smoke-test coordinator, `installLifecycleMonitor: false`, has none, since the
command center is process-wide). `IosAVPlayer` no longer touches either; it
reports play, pause, seek and a known duration through `onNowPlayingChanged`.

- **Commands go to the coordinator**: play, pause, toggle, next, previous and
  scrub call `togglePlayPause` / `skipNext` / `skipPrevious` / `seek`, so they
  act on whichever engine is playing. They used to call the AVPlayer directly,
  so a stray play during Apple Music or Spotify reactivated the local,
  non-mixable session. The ±15 s skip commands are off: while they were on, the
  lock screen showed skip buttons instead of next / previous for local files.
  The remote commands used to be registered by the AVPlayer only, so the command
  set Apple Music's lock screen shows (it may now offer next / previous where it
  offered ±15 s) changed when they moved to the coordinator. Not checked on an
  iPad yet.
- **Who publishes the info**, per engine:
  - Local files and direct streams (`.avPlayer`): the publisher, with title,
    artist, album, duration, elapsed, rate and artwork (`pcTrackArt`, through
    `PCImageStore`). Nothing is published until the player has an item, nor
    while a new track is starting (`isStarting`), when `currentTrack` is already
    the new one but the old one is still playing.
  - Apple Music: `ApplicationMusicPlayer`, natively. The publisher leaves the
    info alone.
  - Spotify: Spotify's app. Parachord's session mixes with others, so iOS
    shows Spotify's Now Playing (the CarPlay design's decision).
  - SoundCloud and Bandcamp: WebKit, from the page's Media Session (see the two
    sections above); their buttons come back through the pages.
  For Spotify, SoundCloud and Bandcamp the publisher clears the app's own info,
  so a stale local track can't show if their publisher goes away.
- **The direct-URL fast path in `playTrack` runs `applyEngineHandoff` too.** It
  used to switch `activeEngine` to `.avPlayer` without stopping the previous
  engine, so a Bandcamp page kept playing under the next track and kept the
  lock screen. `activeEngine` flips only after the handoff and a cancellation
  check, so a newer start during the handoff still sees the engine that was
  playing.
- **Play on a track no engine holds starts it.** A queue restored paused at
  launch shows its track but loads nothing, and so does a track stopped
  because nothing could play it (the car's Spotify and unplayable-run cases
  below, a tapped track an embed refused with nothing else to play it, and a
  track nothing could start because nothing could reach the network). For both,
  `needsLoadOnPlay` makes `togglePlayPause` (the phone's button, the remote
  play / toggle commands, CarPlay) run `playTrack` instead of resuming whatever
  the active engine last held. Which failures stop on the track is the shared
  `failedPlayAwaitsLoad` (`shared/.../playback/PlayFailure.kt`, Android's rule
  too): only an embed's refusal while the queue advances or a spinoff runs is
  skipped. **Offline** (`START_FAILED`): `IosMusicKitPlayer.play` reports
  `.failed` when the catalog request or `ApplicationMusicPlayer.play()` throws
  (`.unavailable`, which lets the queue move on, is for not authorized, not in
  the catalog, or a subscription MusicKit says can't play catalog content), and
  both calls have a time limit (`MusicKitDeadline`, 20 s and 30 s; a timeout is
  `START_TIMED_OUT`, and a play that starts after its limit is paused). When
  nothing plays and an Apple Music start failed, or `IosNetworkStatus`
  (`NWPathMonitor`, which also drives the "No internet connection" banner,
  `PCOfflineBanner`, after the shared 2 s delay) says the device has no network, `playTrack` stops on the
  track with `PLAY_START_FAILED_MESSAGE`, even while the queue advances or a
  spinoff runs: the next track would fail the same way, and the queue used to
  skip through to its end. A failed retry keeps the restored track's resume
  position. A track with no source that could ever play here (nothing resolved
  online, or only an Apple Music match without a subscription) still skips, as
  before; Android stops on that one too. Checked on an iPad in Airplane Mode
  (Bandcamp and SoundCloud sources, no Apple Music subscription on it): a tapped
  track and Next each stopped on their track, and Play started it once the
  network was back. The Apple Music `.failed` path, and whether `play()` can
  hang offline, haven't been seen on a device. A restored track resumes where it was when the
  app last ran (#567): the 1 s tick saves the position on a pause and every
  15 s while playing (`savePositionIfDue`, shared `PlaybackResumePosition`, its
  own `persisted_queue_position` key), `restoreQueue` reads it for the same
  track id, and the first play seeks there once the track plays
  (`seekOnceStarted`, up to 60 s). Under 5 s in, or within 10 s of the end,
  it starts from the top.
- **Checking it in the simulator.** The lock screen can't be photographed with
  `simctl`, but `mediaremoted` logs what it publishes:
  `xcrun simctl spawn booted log stream --level debug --predicate 'process == "mediaremoted"'`
  and look for `setting nowPlayingItem` (title, artist, `artworkAvailable`),
  `setting supportedCommands` and `computedNowPlayingClient`. The app is one
  client (`com.parachord.ios`), WebKit another (`com.apple.WebKit.GPU/com.parachord.ios`),
  and WebKit's wins while it plays. The publisher itself logs `NOWPLAYING
  published …` on each change.

## Playback in the car (`CarPlaySession`)

`CarPlaySession.shared.isConnected` (`CarPlaySession.swift`) is set by the
CarPlay scene delegate (`connected()` / `disconnected()`). While it's true a few
rules change; with it false every path is the phone's.

- **SoundCloud and Bandcamp need a phone window.** Their web views live in the
  phone's key window, which should keep playing them with the phone locked or
  another app in front, as on the phone (seen in the simulator with Settings in
  front; **unverified on a locked device** for a fresh car tap after a Spotify or
  Apple Music play, when no `scHoldAudio` hold is running and iOS may refuse
  WebKit's audio session, so the track could "start" silent). Only when the car launched the app and the
  phone never showed it is there no phone window scene at all (iOS won't open
  one from the background); with the car connected and no connected phone
  window scene with a key window, `blocksEmbeds` is true:
  - Bandcamp loads a new page per track, so `IosBandcampEmbed.ensureWebView`
    always refuses (reason `page`).
  - SoundCloud refuses only when `IosSoundCloudWidget.ensurePage` would have to
    create the page. A page that already exists (kept alive by `scHoldAudio`)
    plays as on the phone, and if the widget can't start the track its own
    refusal timeout still moves on.
  A refusal sends the router to the next source as for any refused embed. With
  the app open on the phone both play as usual.
- **Spotify never opens the Spotify app.** `IosSpotifyConnect.play` shows no
  device picker, ignores a saved device, and uses only the phone's own Spotify
  device (`isLocalRealDevice`), already listed by `getDevices`. If it isn't
  listed, the source fails at once: no `spotify:` wake, no polling. The warm
  path is taken only when its cached device is the phone's own
  (`lastResolvedIsLocal`, set from `isLocalRealDevice` on the device that
  played, whichever path found it), so a remembered speaker at home, or another
  phone that a post-wake poll accepted, isn't used. A car play doesn't write
  the preferred device either.
- **A track only Spotify could play stops, it isn't skipped.** When the router
  tried Spotify (`onSpotifyFailed`) and nothing else played, `playTrack` halts
  the old engine, keeps the track on screen and sets `notice` to
  `CarMessages.OPEN_SPOTIFY_ON_PHONE` (Android Auto's wording, shared). Play
  retries it once Spotify is open. This applies even while the queue is
  advancing, so a Spotify-only playlist doesn't skip to its end.
- **A run of unplayable tracks stops.** When the car launched the app and the
  phone never showed it, a Bandcamp or SoundCloud-only playlist can't play a
  single track, and on a locked phone their fresh starts may fail too; the queue
  used to skip silently to its end. `CarUnplayableRun` (shared) counts tracks in
  a row that no source could play. A track that starts, an explicit play (a tap,
  or Play on a stopped track; not the queue moving on) and a car connecting
  (`CarPlaySession.connectionCount`) each start the run over. While
  `phoneAppNotInFront` (car connected, Parachord not in front on the phone:
  locked, behind another app or never shown; wider than `blocksEmbeds`) the third
  (`CAR_UNPLAYABLE_RUN_LIMIT`) stops on that track, as the Spotify case does,
  with `CarMessages.OPEN_PARACHORD_ON_PHONE` ("Open Parachord on your phone to
  play these tracks"). Play retries it. A track tapped in the car whose
  SoundCloud or Bandcamp embed was refused for having no window (`page`) or
  didn't start (`unplayable`) gets the same message, not the phone's "only plays
  on its own site"; a `preview` refusal keeps SoundCloud's own message. With the
  car disconnected, or the phone app open, the queue skips on as on the phone.
- **Testing without a car.** Call `CarPlaySession.shared.connected()` from a
  temporary hook (never commit one), or open the CarPlay simulator. With the
  app behind another app, Bandcamp and SoundCloud play as on the phone; the
  refusal (`… car connected with no phone window, refusing`, then the next
  source) needs the car to launch the app with no phone window scene: quit the
  app, then open it from the CarPlay screen without touching the phone. With
  the app not in front, three tracks nothing could play stop with the car
  message.
  The Spotify rules need a device with Spotify installed.

## The CarPlay screens (`iosApp/Parachord/CarPlay/`)

CarPlay shows the same library as Android Auto: one root list ("Parachord")
with Playlists, Loved Songs and Collection Radio. The rows, their order and
caps, and what a tap plays all come from the shared `CarBrowse`
(`shared/.../car/CarBrowse.kt`), which Android Auto renders too. The Swift side
only draws it.

- **Scene.** `Info.plist` declares only the CarPlay role
  (`UISceneConfigurations` → `CPTemplateApplicationSceneSessionRoleApplication`
  → `$(PRODUCT_MODULE_NAME).CarPlaySceneDelegate`). The phone's window scene
  stays SwiftUI's `WindowGroup`, which needs no manifest entry; the phone app
  launches as before. On connect `CarPlaySceneDelegate` calls
  `CarPlaySession.shared.connected()`, starts `AppBootstrap.shared.start()` and
  sets the root list; on disconnect it calls `disconnected()`. The root is read
  from the database and doesn't wait for the bootstrap; a tap does.
- **Lists** (`CarBrowseTemplates`). Rows come from `IosContainer.carChildren`
  (`CarBrowse.children`). A browsable row pushes its children; a playable row
  calls `carPlayRequest` (`CarBrowse.playRequest`), then
  `AppPlayback.shared.coordinator.setQueue(…, shuffle:)` with a context from
  `carPlaybackContext` (the playlist, "Loved Songs" or "Collection Radio", so
  the phone's Now Playing names it and a Listen Along sees the takeover), and
  pushes `CPNowPlayingTemplate.shared` unless it's already on top. A null
  request (a stale id) plays and pushes nothing. Only the latest tap plays: a
  new tap or a disconnect cancels one still waiting (a cold launch waits for
  the bootstrap), and the play is dropped if the car is gone by then. Rows
  beyond `CPListTemplate.maximumItemCount` are dropped; an empty list shows a
  disabled "Nothing here yet" row. That limit was 500 in the simulator, so a
  500-track playlist shows its Shuffle row and 499 tracks on CarPlay (Android
  Auto shows all 500).
- **Now Playing** (`CarNowPlaying`). Started by the scene delegate, it
  follows the coordinator once the bootstrap has made it, and stops on
  disconnect. CarPlay draws the track and transport from Now Playing info
  (`NowPlayingPublisher`, or WebKit / MusicKit / Spotify per engine); this adds:
  - **Buttons** from the shared `carNowPlayingButtons` (Android Auto's): the
    heart (`heart` / `heart.fill`) adds the playing track to the collection or
    removes it, as the phone's heart does; Spinoff uses the phone's Spinoff
    fork (`SpinoffFork`), the fork cut out of a filled disc while a spinoff runs
    (`SpinoffForkActive`) and already while its pool loads (15 s or more, with
    no other sign in the car that the tap took, #551), and calls
    `SpinoffController.toggle()`; a tap while the pool loads is ignored, and it's
    hidden when the track has nothing similar. No buttons while nothing plays or
    before the collection has answered. A heart tapped on the phone updates the
    car (`watchTrackInCollection`). Images are drawn at
    `CPNowPlayingButtonMaximumImageSize` (20×20 pt in the simulator) as templates.
  - **Up Next** (`isUpNextButtonEnabled`): a list of the queue (title, artist,
    artwork through `CarArtwork`), refreshed while it's open; a tap calls
    `playFromQueue` and returns to Now Playing. During a spinoff or Listen Along
    it's "Your Queue" with disabled rows (Listen Along's suspended queue), as
    the phone dims it. A list's title can't change, so a spinoff that starts or
    ends while it's open swaps in a new list (pop + push, no animation). Its
    artwork loads stop once it's popped (`templateDidDisappear`, forwarded by
    `CarBrowseTemplates`, the interface controller's one delegate).
  - **Alerts:** a new `notice` shows as a `CPAlertTemplate` with OK, one at a
    time. It stays until the driver taps OK, or until a different notice
    replaces it: that alert is dismissed, then the new notice shows if it's
    one for the car. `notice` going nil doesn't dismiss it: the phone's banner
    clears `notice` after 3.5 s (8 s with an action), and the car alert must
    outlast that. OK clears the phone's banner too. A notice from before the car connected isn't shown, and
    `Notice.showInCar = false` ("Playing via X") stays on the phone, as Android
    Auto shows only errors.
  - **The heart acts only on the track whose state it shows**: a tap between a
    track change and the new track's collection answer does nothing.
- **Artwork** (`CarArtwork`). Loaded after the list shows, four at a time.
  Once the list is popped no new loads start; ones already in flight finish.
  http(s) and `file://` URLs go through `PCImageStore`, then a car-sized copy
  is kept in memory. A track whose own URL fails falls back to its resolved
  sources' art (`pcTrackArt`); anything else keeps an SF Symbol. Only a
  definite miss (a missing file, HTTP 404/410, data that isn't an image) is
  remembered for the session; a cover that failed offline or timed out is tried
  again next time. Playlist mosaics are absolute `file://` paths that go stale
  when the app container moves; a missing file is a miss, not an error.
- **Entitlement.** `com.apple.developer.carplay-audio` is in
  `Parachord.entitlements`, for simulator and device builds alike. Apple
  granted it, and it's enabled on the `com.parachord.ios` App ID (developer
  portal → Identifiers → Additional Capabilities → CarPlay Audio App).
  Automatic signing puts it in the provisioning profile; if a device build
  says the profile lacks it, refresh the profiles (Xcode → Settings → Accounts
  → Download Manual Profiles). The share extension doesn't need it.
- **Opening the CarPlay simulator.** Run the app in the iOS simulator, then in
  Simulator choose I/O → External Displays → CarPlay (bring Simulator to the
  front first: the item was greyed out with it in the background). To test a
  CarPlay-only launch, terminate the app first, then tap Parachord on the
  CarPlay screen; the app starts with no phone window.
- **Simulator workflow notes.**
  - **Use an iOS 18 simulator** (e.g. iPhone 16 Pro, iOS 18.3). The iOS 26.5
    simulator's CarPlay shell froze and ignored clicks. Xcode 26 won't build
    for an 18.3 destination, so build with this guide's 26.5 command, then
    `xcrun simctl install <18.3-udid> <…/Debug-iphonesimulator/Parachord.app>`
    — the 26.5-built app runs on the 18.3 simulator. (`generic/platform=iOS
    Simulator` fails: it also links x86_64, and `Shared.framework` is arm64.)
    A new simulator starts with an empty library; copy the app's data
    container (`xcrun simctl get_app_container <udid> com.parachord.ios data`,
    `Library/Application Support` + `Library/Preferences`) to bring one over.
  - **Screenshots:** `xcrun simctl io booted screenshot --display=external <file>`
    captures the CarPlay display headlessly (then `sips -Z 1400`).
  - **Reinstalling the app while CarPlay is connected** closes the app and the
    car returns to its home screen; open Parachord on the car screen again.
  - **A stuck CarPlay window** reconnects after I/O → External Displays →
    Disabled, then → CarPlay.
  - **Covers in Now Playing:** the iOS 18.3 simulator's WebKit can't use Media
    Session artwork ("Failed to determine dimensions"), so SoundCloud and
    Bandcamp covers don't show in CarPlay Now Playing there (see the known gap
    under Bandcamp).

## Ktor Darwin response-type guard (#368, KTOR-9807)

Every iOS `HttpClient(Darwin)` must call `engine { useResponseTypeGuard() }`
(`shared/src/iosMain/.../api/DarwinResponseGuard.kt`). Ktor's `DarwinTaskHandler`
casts the task's response `as NSHTTPURLResponse` in the `didReceiveData` and
`didCompleteWithError` callbacks, unchecked. A null or non-HTTP response throws
there, on a CFNetwork thread nothing can catch, and the app aborts (SIGABRT) —
TestFlight testers hit it opening the app after it sat locked overnight.

The guard runs the engine on our own `NSURLSession` whose delegate
(`ResponseTypeGuardDelegate`) checks the response type and forwards everything
else to Ktor's `KtorNSURLSessionDelegate` (Ktor's documented custom-delegate
path). A non-HTTP response cancels the task, or completes it with
`NSURLErrorBadServerResponse`, so the request fails like any network error.
`DarwinResponseGuardTest` (iosTest) reproduces the crash with a `file://` URL:
without the guard the test process dies with `NSURLResponse cannot be cast to
NSHTTPURLResponse`.

- **A new Darwin client without the guard brings the crash back.** Both today's
  clients have it (`createHttpClient`, `IosContainer.authHttpClient`).
- **`configureSession` and `handleChallenge` are ignored** with a
  preconfigured session; set session options inside `useResponseTypeGuard`.
- **Remove the guard** once a Ktor release checks the cast. KTOR-9807 is open
  (triaged 2026-10-26); 3.6.1 still has both casts.

---

## What's intentionally deferred (don't "fix")

- Full Koin DI graph — `IosContainer` is hand-rolled on purpose.
- Auth providers on the shared `HttpClient`: Spotify (`SpotifyAuthTokenProvider`
  + `SpotifyTokenRefresher`) and ListenBrainz (`lbTokenProvider`) are wired.
  Every other realm returns null there; Apple Music, Last.fm, Libre.fm and
  SoundCloud carry their own credentials (`IosAppleMusicAuthProvider`, the
  scrobblers' session keys, `SoundCloudAuth`).
- **No resolver-id backfill onto library rows.** Android writes the ids a
  resolve finds back onto the track row (`TrackResolverCache.backfillResolverIds`).
  iOS has the SQLDelight database, a local-files scanner
  (`MediaLibraryScanner.swift`) and a persisted resolver cache
  (`IosTrackResolverCache`, the versioned `resolver-cache-v<N>.json`, see
  above), and that cache is what keeps a resolved track from searching again.
  The one path that writes ids onto rows is the cross-resolver enrichment
  (`BackgroundSync.handleEnrichment`, local files only). Don't add a second
  cache for ephemeral tracks: weekly playlists re-resolve each session on
  Android too, and the persisted Spotify cooldown is the safety net.
- `DevSmokeTestView` (the old phase 1–4 smoke tests, with its own player) is
  only reachable from its `#Preview`; it isn't a tab in the app.
