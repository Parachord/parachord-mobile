# iOS App — Agent Guide

Gotchas and patterns for working on the SwiftUI iOS app (`iosApp/`) that
sits on top of the KMP `shared/` module. The root `CLAUDE.md` covers
Android + the shared module; **this file is iOS-only**. Read it before
touching anything under `iosApp/` or `shared/src/iosMain/`.

The hard-won rules below each cost at least one failed build or a
shipped bug. Don't relearn them.

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
- MusicKit (when it goes live) needs the MusicKit capability added in
  Signing & Capabilities (one click) — that regenerates the profile with
  the entitlement.

---

## What's intentionally deferred (don't "fix")

- Full Koin DI graph — `IosContainer` is hand-rolled on purpose until
  the auth module is needed.
- Auth token providers are no-op stubs — every realm returns null.
  Correct for the unauthenticated endpoints the current screens use.
  Real OAuth (Spotify/Last.fm via `ASWebAuthenticationSession`) wires the
  real providers in.
- The SQLDelight DB + DAOs aren't in the container yet — Library /
  collection screens need them + an iOS media scanner.
- The "Dev" tab (phase 1–4 platform-actual smoke tests) stays until the
  real screens cover everything; it has its own playback player.
