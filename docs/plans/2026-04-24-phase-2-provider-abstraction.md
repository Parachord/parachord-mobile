# Multi-Provider Sync — Phase 2 Execution Plan (Provider Abstraction)

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task, committing after each task passes.
>
> Parent plan: [2026-04-22-multi-provider-sync-correctness.md](2026-04-22-multi-provider-sync-correctness.md). Phase 1 (data model) landed in commits `06c396d`–`5f4634a`. Phases 3–6 expand just-in-time once Phase 2 lands.

**Goal:** Introduce a provider-agnostic `SyncProvider` interface in the shared module, refactor `SpotifySyncProvider` to implement it, and rewire `SyncEngine` to take `List<SyncProvider>` — while keeping every observable behavior identical to today's single-provider Spotify sync.

**Architecture:** Lift the cross-provider models (`SyncedTrack`, `SyncedAlbum`, `SyncedArtist`, `SyncedPlaylist`, `RemoteCreated`) and the new `SyncProvider` / `ProviderFeatures` / `SnapshotKind` / `DeleteResult` types into `shared/.../sync/`. `SpotifySyncProvider` keeps every method body unchanged, just gains `override` keywords and declares its `features` block. `SyncEngine` constructor changes from `spotifyProvider: SpotifySyncProvider` to `providers: List<SyncProvider>`, but the `syncPlaylists`/`syncTracks`/`syncAlbums`/`syncArtists` bodies still pick the one Spotify provider out of the list and behave identically. Multi-provider iteration is Phase 3.

**Tech Stack:** Kotlin, Kotlin Multiplatform (`:shared` module), Koin DI, JUnit 4, MockK.

---

## Task 1: Define `SyncProvider` interface + capability types in shared

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncProvider.kt`
- Create: `app/src/test/java/com/parachord/shared/sync/SyncProviderShapeTest.kt`

This task creates the **interface only** — no implementations yet, no model relocations yet. That's Tasks 2 and 3.

### Step 1: Write the failing test

```kotlin
// File: app/src/test/java/com/parachord/shared/sync/SyncProviderShapeTest.kt
package com.parachord.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test that the shared SyncProvider types compile and carry the
 * expected shape. The full conformance test for SpotifySyncProvider's
 * features lands in a later Phase 2 task.
 */
class SyncProviderShapeTest {
    @Test
    fun `ProviderFeatures captures all five capability flags`() {
        val features = ProviderFeatures(
            snapshots = SnapshotKind.Opaque,
            supportsFollow = true,
            supportsPlaylistDelete = true,
            supportsPlaylistRename = true,
            supportsTrackReplace = true,
        )
        assertEquals(SnapshotKind.Opaque, features.snapshots)
        assertEquals(true, features.supportsFollow)
    }

    @Test
    fun `SnapshotKind exposes Opaque, DateString, None`() {
        // Compile-time check: enum has all three variants.
        val all = SnapshotKind.values().toList()
        assertEquals(3, all.size)
        assert(SnapshotKind.Opaque in all)
        assert(SnapshotKind.DateString in all)
        assert(SnapshotKind.None in all)
    }

    @Test
    fun `DeleteResult sealed hierarchy compiles`() {
        val success: DeleteResult = DeleteResult.Success
        val unsupported: DeleteResult = DeleteResult.Unsupported(401)
        val failed: DeleteResult = DeleteResult.Failed(RuntimeException("boom"))
        // No assertion needed — if these don't compile, the type isn't right.
        listOf(success, unsupported, failed).forEach { _ -> /* exhaustive */ }
    }
}
```

### Step 2: Run — expect FAIL (compile error)

```
./gradlew :app:testDebugUnitTest --tests "com.parachord.shared.sync.SyncProviderShapeTest"
```

Expected: compile error — none of these types exist yet.

### Step 3: Create the interface + supporting types

`shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncProvider.kt`:

```kotlin
package com.parachord.shared.sync

/**
 * Cross-platform sync provider interface. Spotify, Apple Music, Tidal,
 * and any future provider implements this. SyncEngine never branches on
 * `provider.id`; it dispatches on `provider.features` and lets each
 * provider declare its own capability surface.
 *
 * See parent plan `docs/plans/2026-04-22-multi-provider-sync-correctness.md`
 * for the design rationale, propagation invariants, and per-provider
 * idiosyncrasies this interface abstracts.
 */
interface SyncProvider {
    /** Stable identifier ("spotify", "applemusic", future "tidal"). */
    val id: String

    /** Human-readable label shown in settings + wizard ("Spotify", "Apple Music"). */
    val displayName: String

    /** Capability flags. SyncEngine routes on these, never on `id`. */
    val features: ProviderFeatures
}

/**
 * Per-provider capability declarations. Adding a new provider means
 * implementing SyncProvider with its own ProviderFeatures — no changes
 * to SyncEngine required.
 */
data class ProviderFeatures(
    /** What kind of snapshot/change-token this provider's playlists carry. */
    val snapshots: SnapshotKind,
    /** Whether the provider supports a follow/unfollow API for artists. Apple Music = false. */
    val supportsFollow: Boolean,
    /** Whether the provider supports playlist deletion via API. Apple Music = false. */
    val supportsPlaylistDelete: Boolean,
    /** Whether the provider supports playlist rename via API. Apple Music = false. */
    val supportsPlaylistRename: Boolean,
    /** Whether full-replace PUT on tracks is reliable; if false, push degrades to append-only after first failure. */
    val supportsTrackReplace: Boolean,
)

enum class SnapshotKind {
    /** Provider returns a stable opaque token (Spotify `snapshot_id`). String-equality compare. */
    Opaque,
    /** Provider returns a date/version string (Apple `lastModifiedDate`). String-equality compare; refetch on mismatch. */
    DateString,
    /** Provider has no snapshot. SyncEngine falls back to always-pull. Costlier — 1 extra API call per playlist per sync. */
    None,
}

/**
 * Result of a [SyncProvider.deletePlaylist] call. Provider implementations
 * must NEVER throw on documented-unsupported responses (e.g. Apple's 401
 * on DELETE) — return [Unsupported] instead so the caller can surface
 * "remove manually in the {provider}" UX.
 */
sealed class DeleteResult {
    object Success : DeleteResult()
    data class Unsupported(val status: Int) : DeleteResult()
    data class Failed(val error: Throwable) : DeleteResult()
}

/**
 * Returned by [SyncProvider.createPlaylist] — the newly-created remote
 * playlist's external ID and (if the provider supports snapshots) the
 * initial snapshot token.
 */
data class RemoteCreated(
    val externalId: String,
    val snapshotId: String?,
)
```

### Step 4: Run — expect PASS

```
./gradlew :app:testDebugUnitTest --tests "com.parachord.shared.sync.SyncProviderShapeTest"
```

Expected: 3 tests pass.

### Step 5: Full-suite regression

```
./gradlew :app:testDebugUnitTest
```

### Step 6: Commit

```
git add shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncProvider.kt \
        app/src/test/java/com/parachord/shared/sync/SyncProviderShapeTest.kt
git commit -m "Add SyncProvider interface + ProviderFeatures + SnapshotKind + DeleteResult"
```

---

## Task 2: Lift `SyncedTrack` / `SyncedAlbum` / `SyncedArtist` / `SyncedPlaylist` into shared

`SpotifySyncProvider` currently nests four data classes that aren't Spotify-specific in shape — they hold cross-provider sync metadata. Move them to the shared module so other providers (and the interface) can reference them without depending on the Spotify class.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncedModels.kt`
- Modify: `app/src/main/java/com/parachord/android/sync/SpotifySyncProvider.kt` (remove nested classes; replace with typealiases for source compat)
- Modify: `app/src/main/java/com/parachord/android/sync/SyncEngine.kt` (imports)
- Modify: `app/src/main/java/com/parachord/android/ui/screens/sync/SyncViewModel.kt` (imports)
- Modify: any other call site grep finds — likely none beyond the two above

### Step 1: Identify call sites

Run:
```
grep -rn "SpotifySyncProvider\.Synced\(Track\|Album\|Artist\|Playlist\)" /Users/jherskowitz/Development/parachord/parachord-mobile/app/src /Users/jherskowitz/Development/parachord/parachord-mobile/shared/src
```

Expected sites: `SyncEngine.kt` (~5 references), `SyncViewModel.kt` (~2 references). Any others must also be updated in this commit.

### Step 2: Create the shared file

Read `SpotifySyncProvider.kt` lines 28–55 to copy the data class definitions verbatim (signatures matter — the implementer must not change field names/types). Move them into:

`shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncedModels.kt`:

```kotlin
package com.parachord.shared.sync

// Imports for any model types these data classes reference (e.g. Playlist,
// PlaylistTrack, Album, Artist, Track) — the shared model package is
// `com.parachord.shared.model.*`. Match the exact field types from
// SpotifySyncProvider's nested definitions.

data class SyncedTrack( /* fields from SpotifySyncProvider.SyncedTrack */ )
data class SyncedAlbum( /* fields from SpotifySyncProvider.SyncedAlbum */ )
data class SyncedArtist( /* fields from SpotifySyncProvider.SyncedArtist */ )
data class SyncedPlaylist( /* fields from SpotifySyncProvider.SyncedPlaylist */ )
```

If any field type lives only in `app/` (Android-only), DO NOT move that data class — flag it. The expectation is that all four use `:shared` module types only.

### Step 3: Drop nested classes from `SpotifySyncProvider`, add typealiases for source compat

Inside `SpotifySyncProvider`'s class body, remove the four `data class` declarations. For source-compatibility with code that references them as `SpotifySyncProvider.SyncedTrack`, add typealiases to the **companion object**:

```kotlin
companion object {
    private const val TAG = "SpotifySyncProvider"
    const val PROVIDER_ID = "spotify"

    // Re-exported for source compatibility while call sites migrate to
    // the shared types. Phase 2 Task 3 collapses this when it implements
    // SyncProvider — these will be deleted then.
    typealias SyncedTrack = com.parachord.shared.sync.SyncedTrack
    typealias SyncedAlbum = com.parachord.shared.sync.SyncedAlbum
    typealias SyncedArtist = com.parachord.shared.sync.SyncedArtist
    typealias SyncedPlaylist = com.parachord.shared.sync.SyncedPlaylist
}
```

Kotlin requires typealiases at top-level or inside an object — they're fine in a companion object. Existing references to `SpotifySyncProvider.SyncedTrack` continue to compile.

### Step 4: Update import statements in call sites

For `SyncEngine.kt` and `SyncViewModel.kt`, replace `SpotifySyncProvider.SyncedTrack` references with the shared type via either:
- Add `import com.parachord.shared.sync.SyncedTrack` and use the unqualified name, OR
- Leave the qualified `SpotifySyncProvider.SyncedTrack` references as-is — the typealias keeps them working.

The safer choice for minimal-diff: leave call sites alone, let the typealias bridge. Don't touch `SyncEngine`'s ~10 references unless required for compilation.

### Step 5: Run full suite

```
./gradlew :app:testDebugUnitTest
```

Expected: all 308 existing tests still pass. No new tests this task — the relocation is structural only.

### Step 6: Commit

```
git add shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncedModels.kt \
        app/src/main/java/com/parachord/android/sync/SpotifySyncProvider.kt
git commit -m "Move sync model data classes to shared module with source-compat typealiases"
```

---

## Task 3: Refactor `SpotifySyncProvider` to implement `SyncProvider`

Wire the existing class to the interface from Task 1 — no behavior changes.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/sync/SpotifySyncProvider.kt`
- Create: `app/src/test/java/com/parachord/android/sync/SpotifySyncProviderConformanceTest.kt`

### Step 1: Write the failing conformance test

```kotlin
// File: app/src/test/java/com/parachord/android/sync/SpotifySyncProviderConformanceTest.kt
package com.parachord.android.sync

import com.parachord.shared.sync.ProviderFeatures
import com.parachord.shared.sync.SnapshotKind
import com.parachord.shared.sync.SyncProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies SpotifySyncProvider satisfies the SyncProvider interface and
 * declares its capability flags correctly. SyncEngine treats Spotify as
 * snapshot-aware (Opaque token) with full follow/delete/rename/replace
 * support — the contract this test pins.
 */
class SpotifySyncProviderConformanceTest {
    private val provider: SyncProvider = SpotifySyncProvider(
        spotifyApi = mockk(relaxed = true),
        secureTokenStore = mockk(relaxed = true),
    )

    @Test
    fun `id is spotify`() {
        assertEquals("spotify", provider.id)
    }

    @Test
    fun `displayName is human-readable Spotify`() {
        assertEquals("Spotify", provider.displayName)
    }

    @Test
    fun `features declare full Spotify capability`() {
        val expected = ProviderFeatures(
            snapshots = SnapshotKind.Opaque,
            supportsFollow = true,
            supportsPlaylistDelete = true,
            supportsPlaylistRename = true,
            supportsTrackReplace = true,
        )
        assertEquals(expected, provider.features)
    }

    @Test
    fun `is assignable to SyncProvider`() {
        // Compile-time check: the type system accepts SpotifySyncProvider as SyncProvider.
        assertTrue(provider is SyncProvider)
    }
}
```

The test uses `mockk(relaxed = true)` for constructor dependencies — features are static, no actual API calls fire.

If `SpotifySyncProvider`'s constructor takes more or different dependencies than `(spotifyApi, secureTokenStore)`, adapt the test setup to match. Read the current constructor signature first.

### Step 2: Run — expect FAIL

```
./gradlew :app:testDebugUnitTest --tests "com.parachord.android.sync.SpotifySyncProviderConformanceTest"
```

Expected: compile fails because `SpotifySyncProvider` doesn't yet implement `SyncProvider` and doesn't have `displayName`/`features`/`id` properties.

(Note: `PROVIDER_ID` already exists as a `companion object const`. The interface needs `id` as an instance property.)

### Step 3: Refactor the class

Modify `SpotifySyncProvider.kt`:

1. Add `: SyncProvider` to the class declaration:

   ```kotlin
   class SpotifySyncProvider constructor(
       /* existing constructor params unchanged */
   ) : SyncProvider {
   ```

2. Add the three required interface properties at the top of the class body:

   ```kotlin
   override val id: String = PROVIDER_ID
   override val displayName: String = "Spotify"
   override val features = ProviderFeatures(
       snapshots = SnapshotKind.Opaque,
       supportsFollow = true,
       supportsPlaylistDelete = true,
       supportsPlaylistRename = true,
       supportsTrackReplace = true,
   )
   ```

3. Add imports at the top of the file:

   ```kotlin
   import com.parachord.shared.sync.ProviderFeatures
   import com.parachord.shared.sync.SnapshotKind
   import com.parachord.shared.sync.SyncProvider
   ```

4. **Do NOT** add `override` to other methods (`fetchTracks`, `createPlaylistOnSpotify`, etc.). The interface from Task 1 only declares `id`/`displayName`/`features`. Method-level interface methods are deferred to a later task — adding them all now would force every method body to align with the spec'd signature in one giant commit, which we want to avoid.

5. **Do NOT** change any method names or signatures. `createPlaylistOnSpotify` stays as-is. Future tasks (or Phase 3) will harmonize the interface.

### Step 4: Run — expect PASS

```
./gradlew :app:testDebugUnitTest --tests "com.parachord.android.sync.SpotifySyncProviderConformanceTest"
```

All 4 tests pass.

### Step 5: Full-suite regression

```
./gradlew :app:testDebugUnitTest
```

### Step 6: Commit

```
git add app/src/main/java/com/parachord/android/sync/SpotifySyncProvider.kt \
        app/src/test/java/com/parachord/android/sync/SpotifySyncProviderConformanceTest.kt
git commit -m "SpotifySyncProvider implements SyncProvider with capability declaration"
```

---

## Task 4: Wire Koin to bind providers as `SyncProvider` and expose `List<SyncProvider>`

**Files:**
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt`

### Step 1: Find the existing Koin registration for `SpotifySyncProvider`

Grep:
```
grep -n "SpotifySyncProvider\|SyncEngine" /Users/jherskowitz/Development/parachord/parachord-mobile/app/src/main/java/com/parachord/android/di/AndroidModule.kt
```

Should be a `single { SpotifySyncProvider(...) }` and a `single { SyncEngine(spotifyProvider = get(), ...) }`.

### Step 2: Re-register `SpotifySyncProvider` to also bind `SyncProvider`

Replace the existing `single { SpotifySyncProvider(...) }` line with:

```kotlin
single { SpotifySyncProvider(get(), get()) } bind com.parachord.shared.sync.SyncProvider::class
```

(Adapt the constructor `get()` calls to match the actual constructor's params — leave them as they are today.)

The `bind` ensures Koin can resolve a `SyncProvider` reference to this single instance.

### Step 3: Add a `single` for `List<SyncProvider>`

After the `SpotifySyncProvider` registration:

```kotlin
single<List<com.parachord.shared.sync.SyncProvider>> { getAll() }
```

`getAll()` collects every Koin-registered binding of `SyncProvider` into a list. Today there's only one (Spotify); Phase 4 will add `AppleMusicSyncProvider` and `getAll()` will pick it up automatically.

### Step 4: Add the import (if needed)

At the top of `AndroidModule.kt`, add:

```kotlin
import org.koin.core.module.dsl.bind
```

If Koin's `bind` infix is already in scope from another module file, the import is redundant; check before adding.

### Step 5: Verify the registration compiles + resolves

A small test confirming Koin actually returns a non-empty list:

```kotlin
// File: app/src/test/java/com/parachord/android/di/SyncProviderRegistrationTest.kt
package com.parachord.android.di

import com.parachord.shared.sync.SyncProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProviderRegistrationTest {
    @Test
    fun `at least one SyncProvider is registered`() {
        // Existing tests already use a Koin test setup pattern — find it in
        // any existing test that uses startKoin / loadKoinModules. If no
        // such pattern exists, this test should construct the providers
        // directly without going through Koin (Koin tests in unit-test
        // sourceset are tricky on Android).
        //
        // Acceptable simplification: skip this test entirely if Koin
        // requires Android context to bootstrap. The AndroidModule code
        // change itself will fail at app startup (DI graph error) if the
        // bind is wrong.
    }
}
```

If a clean Koin unit-test bootstrap isn't available in this codebase, drop this test entirely — the registration is exercised on every app launch, which is sufficient.

### Step 6: Run full suite

```
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

`assembleDebug` is required this task — a Koin DI graph error doesn't surface in unit tests, only at app-launch time. The `assembleDebug` build doesn't run the DI graph, but it does verify everything compiles.

### Step 7: Commit

```
git add app/src/main/java/com/parachord/android/di/AndroidModule.kt
git commit -m "Bind SpotifySyncProvider as SyncProvider; expose List<SyncProvider> via Koin"
```

---

## Task 5: Update `SyncEngine` constructor to accept `List<SyncProvider>`

The biggest scope expansion of Phase 2 — but every method body stays identical. The constructor takes a list, the body picks the Spotify provider out of it. Multi-provider iteration is Phase 3.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/sync/SyncEngine.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (`SyncEngine` registration)
- Modify: any test that constructs `SyncEngine` (likely `SyncEngineTest.kt`)

### Step 1: Inspect the current constructor

Read `SyncEngine.kt` lines 1–60 to see the constructor. Look for `private val spotifyProvider: SpotifySyncProvider`.

### Step 2: Change the constructor signature

```kotlin
class SyncEngine(
    // existing constructor params...
    private val providers: List<SyncProvider>,
    // remove: private val spotifyProvider: SpotifySyncProvider,
) {
    // Internal accessor — Phase 3 generalizes the body to iterate `providers`.
    // For now, every method that needs Spotify pulls it from the list.
    private val spotifyProvider: SpotifySyncProvider
        get() = providers.first { it.id == "spotify" } as SpotifySyncProvider

    // ... rest of class unchanged ...
}
```

The cast to `SpotifySyncProvider` is intentional — this whole getter goes away in Phase 3 when method bodies start dispatching on `provider.features` directly. For Phase 2 the cast preserves zero behavior change.

If `providers` is ever empty (no Spotify registered), the `first` throws `NoSuchElementException`. That's correct behavior — an unconfigured app shouldn't reach SyncEngine. Don't add fallback handling.

### Step 3: Update Koin registration

In `AndroidModule.kt`, change the `SyncEngine` `single { ... }` to inject the list instead of the single provider:

```kotlin
single {
    SyncEngine(
        // existing get() calls for other deps...
        providers = get(),  // resolves to List<SyncProvider> via the registration from Task 4
    )
}
```

### Step 4: Update tests that construct `SyncEngine`

Find them:
```
grep -rn "SyncEngine(" /Users/jherskowitz/Development/parachord/parachord-mobile/app/src/test
```

For each, wrap the existing `spotifyProvider = mockk()` argument in `listOf(...)`:

```kotlin
val engine = SyncEngine(
    /* existing args */,
    providers = listOf(mockSpotifyProvider),
)
```

If a test uses `relaxed = true` mocks and never touches sync providers explicitly, just pass `providers = emptyList()` — the test isn't exercising the sync path, so Spotify-not-found is fine. Use judgment per test.

### Step 5: Add a new constructor smoke test

```kotlin
// Add to SyncEngineTest.kt
@Test
fun `accepts a list of providers and finds spotify by id`() {
    val mockSpotify = mockk<SpotifySyncProvider>(relaxed = true)
    every { mockSpotify.id } returns "spotify"
    val engine = SyncEngine(
        // ... other relaxed mocks for required deps ...
        providers = listOf(mockSpotify),
    )
    // Just constructing successfully is the assertion.
}
```

### Step 6: Run full suite

```
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

### Step 7: Commit

```
git add app/src/main/java/com/parachord/android/sync/SyncEngine.kt \
        app/src/main/java/com/parachord/android/di/AndroidModule.kt \
        app/src/test/java/com/parachord/android/sync/SyncEngineTest.kt
git commit -m "SyncEngine accepts List<SyncProvider>; preserves single-Spotify behavior"
```

---

## Task 6: Phase 2 wrap-up — install to device + plan-status update

**Files:**
- Modify: `docs/plans/2026-04-22-multi-provider-sync-correctness.md` (mark Phase 2 status)

### Step 1: Update parent plan

Add a status note at the top of the Phase 2 section:

```
> **Status:** ✅ Landed in commits ... through .... Provider abstraction
> in place; SyncEngine accepts a list of providers; behavior unchanged.
> Phase 3 (multi-provider propagation invariants) needs a separate
> just-in-time expansion before further work.
```

### Step 2: Install to device + smoke test

```
./gradlew installDebug
/Users/jherskowitz/Library/Android/sdk/platform-tools/adb shell am force-stop com.parachord.android.debug
```

Launch the app. Verify:
- Normal Spotify sync still works (no regressions)
- Hosted XSPF poller still polls
- DI graph resolves at startup (no Koin error in logcat)

### Step 3: Commit

```
git add docs/plans/2026-04-22-multi-provider-sync-correctness.md
git commit -m "Mark multi-provider sync Phase 2 complete"
```

---

## Verification for the end of Phase 2

- `./gradlew :app:testDebugUnitTest` — green (308+ tests, plus 4 new conformance + 3 shape).
- `./gradlew :app:assembleDebug` — succeeds.
- App installs and runs on device. Spotify sync round-trip works exactly as before.
- `SpotifySyncProvider` is `SyncProvider`. `SyncEngine` takes `List<SyncProvider>`. Apple Music still has no sync — that's Phase 4.
- No method body inside `SpotifySyncProvider` or `SyncEngine` changed in this phase. The interface gained members; the data model relocated; DI rewired; everything else is identical.
- `grep -rn "SpotifySyncProvider.PROVIDER_ID" app/src` still returns Phase 1's call sites — they're still valid (the const still exists). Phase 3 generalizes them to `provider.id`.

---

## Execution

Per `superpowers:subagent-driven-development`: dispatch one implementer per task, run two-stage reviews (spec compliance, then code quality), commit + push between tasks. Do not parallelize implementations.
