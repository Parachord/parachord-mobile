# Resolver Coordinator → `commonMain` Implementation Plan (#210, full unification)

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` (or `superpowers:subagent-driven-development`) to implement this plan task-by-task.

**Goal:** Collapse the duplicated resolver *orchestration* (`app/.../resolver/ResolverManager.kt` and `shared/iosMain/.../IosResolverCoordinator.kt`) into one `commonMain` `ResolverCoordinator`, with the genuinely platform-specific async behind an injected `ResolverRuntime`, so a resolution-logic change lives in ONE place and both platforms inherit it — **with resolve/rank/play behavior unchanged on both platforms.**

**Architecture:** A shared `ResolverCoordinator` owns the fan-out, the native **Spotify** branch (already shared via `SpotifyClient.searchTrack`), gating (via the already-shared `ResolverGating` helpers), re-score, and ranking. Each platform supplies a `ResolverRuntime` that resolves its *own* native non-Spotify resolvers (Android: Apple Music via MusicKit+iTunes, SoundCloud, local files; iOS: Apple Music via catalog HTTP) and its `.axe` resolvers (Android: `PluginManager.resolve`; iOS: the JavaScriptCore unique-key polling workaround). Android keeps its richer `resolveWithHints` wrapper *on top of* the shared coordinator.

**Tech Stack:** Kotlin Multiplatform (`commonMain` + `iosMain` + `:app` androidMain), Koin DI (Android) / `IosContainer` manual DI (iOS), Ktor, kotlinx.serialization, kotlin.test.

**Prereq already landed (a3ab1fc):** `ResolverGating.axeResolverIds(...)`, `ResolverGating.rescore(...)`, and `selectBestMatch(...)` are shared and called by both coordinators. This plan builds on them.

---

## Design decisions (review these BEFORE writing code)

**D1 — Two return contracts, two methods.** Android `resolve()` returns *scored-but-unranked* sources (the 0.60 floor + `selectRanked` run downstream at call sites via `ResolverScoring.selectBest`). iOS `resolveSources()` returns *ranked + floored*. The coordinator exposes BOTH:
- `suspend fun resolveScored(query, targetTitle?, targetArtist?, album?, excludeResolvers): List<ResolvedSource>` — fan out + re-score, **no** ranking/floor. Android's `resolve()` delegates here.
- `suspend fun resolveRanked(query, targetTitle?, targetArtist?, album?, preferredResolver?): List<ResolvedSource>` — `scoring.selectRanked(resolveScored(...))`. iOS's `resolveSources(artist, title, album)` delegates here with `query = "$artist $title"`, `targetTitle = title`, `targetArtist = artist`.

**D2 — `ResolverRuntime` owns native non-Spotify resolution AND `.axe` execution.** The native-vs-`.axe` split differs per platform and the AM mechanism can't be shared (Android's MusicKit bridge is WebView-bound). So:
```kotlin
interface ResolverRuntime {
    /** Native resolver ids this platform resolves itself, EXCLUDING spotify
     *  (the coordinator owns spotify). Android: {applemusic, soundcloud, localfiles}; iOS: {applemusic}. */
    val nativeResolverIds: Set<String>
    /** Resolve one native id. Returns null on no-match / not-available (token absent etc.). */
    suspend fun resolveNative(resolverId: String, query: String, targetTitle: String?, targetArtist: String?): ResolvedSource?
    /** Resolve one .axe id (Android: PluginManager.resolve; iOS: JSC polling). */
    suspend fun resolveAxe(resolverId: String, query: String, targetTitle: String?, targetArtist: String?): ResolvedSource?
}
```
The coordinator fans out: spotify (itself) + each `nativeResolverIds` id that passes the active/exclude gate (→ `resolveNative`) + each `ResolverGating.axeResolverIds(...)` id (→ `resolveAxe`).

**D3 — Unified gate is behavior-preserving (VERIFY the invariants in Task 1).** Coordinator gates spotify + natives with `isActive(id) = (active.isEmpty() || id in active) && id !in excludeResolvers`. `.axe` ids come from `ResolverGating.axeResolverIds` (which additionally applies `id !in disabled`). This preserves both platforms IFF:
- **(I1)** iOS removes a resolver from `active_resolvers` when it's disabled (so a disabled native/spotify is already excluded by the `id in active` check) — confirmed in `SettingsViewModel.setResolverEnabled` (`activeResolvers.remove(id)`), re-verify.
- **(I2)** iOS `active_resolvers` is always non-empty (seeded to all resolver ids), so `id in active` is the real gate there — confirmed (`activeResolvers = Set(PCServices.resolvers.map{$0.id})`), re-verify.
- **(I3)** Android never populates `active_resolvers` (so `active.isEmpty()` is always true on Android → natives gate on exclude only, matching today) — confirmed (`setActiveResolvers` is never called in `app/`), re-verify.
If any invariant is false, STOP and revisit — do **not** add `id !in disabled` to the native gate blindly (it would change Android behavior when a native is in `disabled`).

**D4 — Apple Music stays entirely in the runtime.** Android: `resolveAppleMusic` (MusicKit Tier 1 + iTunes Tier 2, both already on `selectBestMatch`). iOS: `resolveAppleMusicNative` (catalog HTTP, already on `selectBestMatch`). These move verbatim into the respective `ResolverRuntime` impls.

**D5 — Android's `resolveWithHints` stays in `ResolverManager`.** It's Android-only richness (Spotify ID verify, the 429-keep-the-badge fallback, cascade-artwork merge, `excludeResolvers`). It calls `coordinator.resolveScored(..., excludeResolvers)` instead of the old private `resolve()`. iOS's `resolveSingle` (additive re-resolution) stays in the iOS layer, calling a new `coordinator.resolveSingle(resolverId, ...)` shared helper OR keeping its own thin dispatch — see Task 6.

**D6 — Finding 1 (converge Android onto `active_resolvers`) is OUT OF SCOPE here.** It changes user-visible Android settings behavior (the Android resolver toggle must start *writing* `active_resolvers` + `disabled`), needs its own verification, and D3 is explicitly designed to NOT depend on it. Track it as a separate ticket.

---

## Invariants & baseline (do first)

### Task 0: Verify the D3 invariants + green baseline

**Files:** read-only.

**Step 1:** Confirm I1/I2/I3:
- `grep -rn "setActiveResolvers" app/src/main/java` → expect **no calls** (I3).
- iOS `SettingsView.swift` `setResolverEnabled` removes from `activeResolvers` on disable (I1) and `activeResolvers` is seeded to all ids (I2).

**Step 2:** Establish green baseline:
```
./gradlew :shared:testDebugUnitTest
./gradlew :app:compileDebugKotlin
./gradlew :shared:compileKotlinIosSimulatorArm64
(cd iosApp && xcodebuild -project Parachord.xcodeproj -scheme Parachord -destination 'id=3D28327F-8567-4FDA-97D1-FAD2F5BEDCB5' -configuration Debug build)
```
Expected: all green. If `:shared` tests fail, fix before proceeding.

**Step 3:** No commit (read-only).

---

## Tasks

### Task 1: Define `ResolverRuntime` + `ResolverCoordinator` skeleton (shared, TDD)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/resolver/ResolverRuntime.kt`
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/resolver/ResolverCoordinator.kt`
- Create: `shared/src/commonTest/kotlin/com/parachord/shared/resolver/ResolverCoordinatorTest.kt`

**Step 1 — failing test.** Write `ResolverCoordinatorTest` with a fake `ResolverRuntime` (in-memory map of `resolverId → ResolvedSource`) and a fake-friendly `SpotifyClient`/`ResolverScoring`. Assert:
- `resolveScored` fans out spotify + natives + axe and returns the union, re-scored (0.95 both-match, 0.50 single-axis), **unranked** (sub-floor 0.50 sources still present).
- `resolveRanked` applies `selectRanked` (sub-floor dropped, priority-ordered).
- `excludeResolvers` skips the named resolver (no call to runtime for it).
- A native id NOT in `runtime.nativeResolverIds` is never resolved as native.

> NOTE: `SpotifyClient` is a concrete Ktor class. If it can't be faked cleanly, inject the Spotify branch as a `suspend (query) -> ResolvedSource?` lambda into the coordinator (same lambda-forwarding pattern used across `shared/`), and the Android/iOS bindings close over `spotifyClient.searchTrack` + token gate. Prefer the lambda — it keeps the coordinator unit-testable and sidesteps faking Ktor. Decide here in Task 1.

**Step 2:** Run `:shared:testDebugUnitTest --tests "*ResolverCoordinatorTest*"` → FAIL (types don't exist).

**Step 3 — implement.** `ResolverRuntime` per D2. `ResolverCoordinator`:
```kotlin
class ResolverCoordinator(
    private val runtime: ResolverRuntime,
    private val scoring: ResolverScoring,
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val resolveSpotify: suspend (query: String) -> ResolvedSource?,   // null when off/no-token (see NOTE)
) {
    suspend fun resolveScored(query, targetTitle?, targetArtist?, album?, excludeResolvers = emptySet()): List<ResolvedSource> = coroutineScope {
        val active = settingsStore.getActiveResolvers()
        val disabled = settingsStore.getDisabledPlugins()
        fun isActive(id) = (active.isEmpty() || id in active) && id !in excludeResolvers
        val tasks = buildList {
            if (isActive("spotify")) add(async { resolveSpotify(query) })
            for (id in runtime.nativeResolverIds) if (isActive(id)) add(async { runtime.resolveNative(id, query, targetTitle, targetArtist) })
            for (id in ResolverGating.axeResolverIds(pluginManager.plugins.value, active, disabled, runtime.nativeResolverIds + "spotify")) {
                if (id !in excludeResolvers) add(async { runtime.resolveAxe(id, query, targetTitle, targetArtist) })
            }
        }
        var results = tasks.mapNotNull { it.await() }
        if (targetTitle != null && targetArtist != null) results = ResolverGating.rescore(results, targetTitle, targetArtist)
        results
    }
    suspend fun resolveRanked(query, targetTitle?, targetArtist?, album?, preferredResolver? = null): List<ResolvedSource> =
        scoring.selectRanked(resolveScored(query, targetTitle, targetArtist, album), preferredResolver)
}
```

**Step 4:** Run the test → PASS. **Step 5:** `git commit` — "Resolver (#210): shared ResolverCoordinator + ResolverRuntime (TDD)".

---

### Task 2: Android `ResolverRuntime` impl (extract native + axe bodies)

**Files:**
- Create: `app/src/main/java/com/parachord/android/resolver/AndroidResolverRuntime.kt`
- Modify: `app/.../resolver/ResolverManager.kt`

**Step 1:** Create `AndroidResolverRuntime(spotifyClient, settingsStore, oAuthManager, okHttpClient, json, musicKitBridge, trackDao)` implementing `ResolverRuntime`:
- `nativeResolverIds = setOf("applemusic", "soundcloud", "localfiles")`.
- `resolveNative(id, query, tt, ta)`: `when(id) { "applemusic" -> resolveAppleMusic(query, tt, ta); "soundcloud" -> resolveSoundCloud(query); "localfiles" -> resolveLocalFile(tt, ta); else -> null }`.
- `resolveAxe(id, query, tt, ta)` = the body of `resolveViaPlugin`.
- **Move** `resolveAppleMusic`, `resolveViaiTunes`, `resolveSoundCloud`, `searchSoundCloudTrack`, `resolveLocalFile`, `resolveViaPlugin`, `SoundCloudAuthException`, and the `ITunes*`/`Sc*`/`AxeResolveResult` private data classes from `ResolverManager` into this file **verbatim** (they already use `selectBestMatch`). Keep `resolveSpotify` + `verifySpotifyTrack` in `ResolverManager` (hints) — but also expose a spotify-search lambda for the coordinator (Task 4).

**Step 2:** `./gradlew :app:compileDebugKotlin` → expect errors in `ResolverManager` referencing the moved methods (fixed in Task 4). It's fine to leave red between Task 2–4 locally; do NOT commit red. (If you prefer green-per-commit, fold Tasks 2–4 into one commit.)

**Step 3:** No standalone commit (see Task 4).

---

### Task 3: iOS `ResolverRuntime` impl

**Files:**
- Create: `shared/src/iosMain/kotlin/com/parachord/shared/ios/IosResolverRuntime.kt`
- Modify: `shared/iosMain/.../IosResolverCoordinator.kt`

**Step 1:** Create `IosResolverRuntime(jsRuntime, settingsStore, httpClient, appleMusicDeveloperToken)` implementing `ResolverRuntime`:
- `nativeResolverIds = setOf("applemusic")`.
- `resolveNative(id, query, tt, ta)`: `if (id == "applemusic") resolveAppleMusicNative(ta ?: "", tt ?: "") else null`. (Catalog search uses artist/title; pass `targetArtist`/`targetTitle`.)
- `resolveAxe(id, query, tt, ta)` = the body of `resolveOne` (JSC unique-key polling) — pass `ta`/`tt` as artist/title.
- **Move** `resolveOne`, `resolveAppleMusicNative`, `jsEsc`, `AxeResolveResult`, `callCounter`, the `POLL_*` consts into this file verbatim.

**Step 2:** `./gradlew :shared:compileKotlinIosSimulatorArm64` → expect errors in `IosResolverCoordinator` (fixed in Task 5). Don't commit red.

---

### Task 4: Reduce Android `ResolverManager` to coordinator + hints

**Files:** Modify `app/.../resolver/ResolverManager.kt`, `app/.../di/AndroidModule.kt`.

**Step 1:** `ResolverManager` constructor now takes `coordinator: ResolverCoordinator` (+ keeps `spotifyClient`, `settingsStore` for `verifySpotifyTrack`). Replace the private `resolve()` body with `coordinator.resolveScored(query, targetTitle, targetArtist, album=null, excludeResolvers)`. Keep `resolveWithHints` exactly as-is except it calls the new `resolve()` (which now delegates). Keep `resolveSpotify`/`verifySpotifyTrack` (hints path).

**Step 2:** `AndroidModule`: bind `AndroidResolverRuntime`, bind `ResolverCoordinator` with `resolveSpotify = { q -> <token gate + spotifyClient.searchTrack(q) with the same try/catch as the old resolveSpotify> }`, update `ResolverManager` binding.

**Step 3:** `./gradlew :app:compileDebugKotlin` → green. **Step 4:** `git commit` — "Resolver (#210): Android ResolverManager → shared coordinator + AndroidResolverRuntime".

---

### Task 5: Reduce iOS `IosResolverCoordinator` to coordinator + runtime

**Files:** Modify `shared/iosMain/.../IosResolverCoordinator.kt`, `shared/iosMain/.../IosContainer.kt`.

**Step 1:** `IosResolverCoordinator.resolveSources(artist, title, album)` → `coordinator.resolveRanked(query="$artist $title", targetTitle=title, targetArtist=artist, album)`. `resolveSingle` → `coordinator.resolveSingle(...)` (Task 6) or keep a thin local dispatch that calls `runtime.resolveNative`/`resolveAxe` + `scoreConfidence` + floor.

**Step 2:** `IosContainer`: build `IosResolverRuntime`, build `ResolverCoordinator(runtime, scoring, settingsStore, pluginManager, resolveSpotify = { q -> if (settingsStore.getSpotifyAccessToken()==null) null else runCatching { spotifyClient.searchTrack(q) }.getOrNull() })`. Wire `IosResolverCoordinator` to delegate.

**Step 3:** `./gradlew :shared:compileKotlinIosSimulatorArm64` → green. **Step 4:** `git commit` — "Resolver (#210): iOS coordinator → shared coordinator + IosResolverRuntime".

---

### Task 6: Shared `resolveSingle` (additive re-resolution) — optional consolidation

**Files:** Modify `ResolverCoordinator.kt`, both runtime callers, `ResolverCoordinatorTest.kt`.

**Step 1 — failing test:** `resolveSingle(resolverId, query, targetTitle, targetArtist, album)` resolves exactly one resolver (spotify via lambda, native via `runtime.resolveNative`, else `runtime.resolveAxe`), re-scores, returns it only if `!noMatch && confidence >= MIN_CONFIDENCE_THRESHOLD` else null. (Mirrors iOS `resolveSingle` lines 307–337.)

**Step 2–4:** Implement, run test → PASS, point iOS `IosResolverCoordinator.resolveSingle` at it. Android has no `resolveSingle` caller today — skip wiring there. **Step 5:** `git commit`.

---

### Task 7: Full build + regression sweep + docs

**Step 1:** All green:
```
./gradlew :shared:testDebugUnitTest && ./gradlew :app:compileDebugKotlin && ./gradlew :shared:compileKotlinIosSimulatorArm64
(cd iosApp && xcodebuild ... build)
```

**Step 2 — DEVICE regression (the real acceptance gate; cannot be unit-tested).** On BOTH Android and iOS, verify behavior is UNCHANGED:
- Resolver **badges** appear correctly on a tracklist (right icons, right confidence dimming).
- **Disabled-resolver gate:** disabling a resolver removes it from resolution (iOS; Android per its current model).
- **Additive re-resolution:** enabling a resolver after a track is cached resolves just it and merges (iOS `resolverEnabled` path).
- **Apple Music match:** a track whose correct AM match isn't the first catalog hit still resolves to AM (both platforms).
- **0.60 floor:** a wrong-song streaming result (single-axis) shows no badge and doesn't win playback; a correct local file does.
- **Spotify hints:** Spotify badge persists during a 429 (Android `resolveWithHints` 429-keep-badge path) — regression-sensitive, exercise if possible.

**Step 3:** Update `CLAUDE.md` resolver sections + the #210 / `iosApp/AGENTS.md` references to point at `ResolverCoordinator`/`ResolverRuntime`. Note Finding 1 as the remaining Android convergence.

**Step 4:** `git commit`, then `superpowers:finishing-a-development-branch`.

---

## Out of scope (separate tickets)
- **Finding 1:** converge Android onto `active_resolvers` (Android resolver toggle must write `active_resolvers` + `disabled`; then the native gate can add `id !in disabled` uniformly). User-visible settings change — own ticket + own verification.
- Visibility-scoped resolution (#177) — unrelated.

## Rollback
Each task is its own commit; revert the offending commit. Tasks 2–4 (Android) and 3+5 (iOS) are the risky pair — if device regression surfaces, revert that platform's coordinator-wiring commit and the platform falls back to its pre-#210 standalone coordinator while the other platform stays migrated (the shared `ResolverCoordinator` is additive until a platform's wiring commit lands).
