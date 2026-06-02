# KMP Migration Plan: Parachord Android → Kotlin Multiplatform

## Context

The Android app (191 files, ~51K lines) needs to share ~73% of its code with a future iOS app. The .axe plugin system is already KMP-ready (`JsRuntime` interface). This plan migrates the existing `parachord-mobile` repo in-place to a KMP project with a `:shared` module, keeping Android shipping at every step.

**Approach:** Start with Phases 0-1 (project structure + models), evaluate, then iterate sprint-by-sprint (1-2 phases per sprint). Android keeps shipping features between migration phases.

**Target code sharing: ~73%** (all business logic, data layer, networking, AI, resolver, plugin system)
**Stays platform-specific:** Compose UI (82 files), PlaybackService/MediaSession, MediaScanner, Android widgets, JsBridge, NetworkMonitor

> **Current status (April 2026, last verified 2026-04-29):**
> - **Phases 0–7: ✅ complete.** Platform abstractions, models, API client infrastructure, SQLDelight schema, Koin DI, resolver scoring, metadata service, AI models/tools, plugin system, deep link parsing, config.
> - **Phase 8 (Coil 2 → Coil 3): ⏳ pending.** Optional — only relevant for sharing Compose Multiplatform UI. Not blocking iOS; the shared business logic doesn't reference Coil. The single remaining Coil call lives in `composeMosaicAndroid` (the platform-specific 2x2 mosaic composite forwarded into `ImageEnrichmentService` via lambda).
> - **Phase 9A (TrackEntity → Track): ✅ complete.** `typealias TrackEntity = com.parachord.shared.model.Track`. All app code operates on the shared model.
> - **Phase 9B (SettingsStore → multiplatform-settings): ✅ complete.** `SettingsStore` lives in `shared/.../settings/` backed by `KvStore` (multiplatform-settings) + `SecureTokenStore`. Android `KvStoreFactory` wraps `SharedPreferences`; iOS `KvStoreFactory` wraps `NSUserDefaults`. The DataStore→KvStore migration runs once on first launch.
> - **Phase 9C (QueueManager → shared): ✅ complete.**
> - **Phase 9D (Room → SQLDelight actual): ✅ complete.** Room fully removed.
> - **Phase 9E (Retrofit → Ktor actual): ✅ complete.** Retrofit dropped from `app/build.gradle.kts`. All API clients live in `shared/.../api/` as Ktor `HttpClient` callers (Spotify, Last.fm, MusicBrainz, AppleMusic, Ticketmaster, SeatGeek, ListenBrainz). The shared client has 60s `requestTimeoutMillis` (covers AI generation), `OAuthRefreshPlugin` for 401-driven token refresh, `User-Agent` injection.
> - **Repositories: ✅ all 9 in shared.** Charts, Concerts, CriticalDarlings, FreshDrops, Friends, History, Library, Recommendations, WeeklyPlaylists. App-side files are typealias shims.
> - **Sync engine: ✅ shared.** `SyncEngine`, `SpotifySyncProvider`, `AppleMusicSyncProvider`, multi-provider iteration, three-layer dedup, four-piece mirror propagation rules — all `commonMain`.
> - **Metadata enrichment: ✅ shared.** `MbidEnrichmentService` (Keychain/cacheRead/cacheWrite lambdas), `ImageEnrichmentService` (mosaic via `composeMosaic` lambda).
> - **AI orchestration: ✅ shared.** `AiChatService`, `AiRecommendationService`, `ChatContextProvider`, `ChatCardEnricher`, `AiChatProvider` interface, `DjToolDefinitions`.
> - **AI providers: ✅ shared (Ktor).** `ChatGptProvider`, `ClaudeProvider`, `GeminiProvider` ported from OkHttp to the shared Ktor client. Identical system prompts, JSON parsing, tool-call protocols on both platforms.
> - **`DjToolExecutor`: ✅ shared interface.** Single-method `execute(name, args): Map<String, Any?>`. The Android concrete class implements it; iOS provides its own implementation that dispatches into AVPlayer / SPTAppRemote / MusicKit.
> - **iOS actuals: ✅ all 8 written + verified.** `Log`, `randomUUID`, `currentTimeMillis`, `DriverFactory`, `HttpClientFactory`, `PluginFileAccess`, `KvStoreFactory`, `IosSecureTokenStore` (Keychain via multiplatform-settings).
> - **Build verification:** `iosArm64`, `iosSimulatorArm64`, `androidDebug`, all unit tests — green.
>
> **What stays in `:app` (genuinely Android-only):** all Compose UI (~82 files), Android `ViewModel`s + `viewModelScope`, Media3 stack (`PlaybackController`, `PlaybackService`, `PlaybackRouter`, all handlers, `MusicKitWebBridge`), `JsBridge` (WebView), `MediaScanner` (ContentResolver), `NetworkMonitor` (ConnectivityManager), `OAuthManager` + `OAuthRedirectActivity` (Custom Tabs), `PluginSyncService` (WorkManager), the Android `DjToolExecutor` concrete class (dispatches to `PlaybackController`).
>
> **What's left for iOS:** the Swift app shell — UI (SwiftUI), navigation (`NavigationStack`), `@Observable` ViewModels, playback adapters (AVPlayer + SPTAppRemote + MusicKit), OAuth via `ASWebAuthenticationSession`, a Swift class implementing `DjToolExecutor` to dispatch DJ tool calls. The shared module is feature-complete for that work — `import shared` and call into every repository, AI service, sync engine, etc.

---

## Phase 0: Project Structure Setup

### Scope
Add the KMP Gradle plugin infrastructure and create the `:shared` module with empty source sets. The existing `:app` module continues building unchanged.

### Gradle Changes

**`settings.gradle.kts`** — add shared module:
```kotlin
include(":app")
include(":shared")
```

**`build.gradle.kts` (root)** — add KMP plugin:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false  // NEW
}
```

**`gradle/libs.versions.toml`** — add KMP-related entries:
```toml
[versions]
ktor = "3.1.1"
sqldelight = "2.0.2"
koin = "4.0.2"
koin-compose = "4.0.2"
coil3 = "3.1.0"
multiplatform-settings = "1.3.0"
kmp-uuid = "0.9.0"

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```

**`shared/build.gradle.kts`** — new file:
```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)  // androidTarget needs this
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilations.all {
            compilerOptions.configure {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // Platform-specific deps added per phase
        }
        iosMain.dependencies {
            // Platform-specific deps added per phase
        }
    }
}

android {
    namespace = "com.parachord.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

**`app/build.gradle.kts`** — add shared dependency:
```kotlin
dependencies {
    implementation(project(":shared"))
    // ... existing deps unchanged
}
```

### Directory Structure Created
```
shared/
  src/
    commonMain/kotlin/com/parachord/shared/
    androidMain/kotlin/com/parachord/shared/
    iosMain/kotlin/com/parachord/shared/
```

### Verification
- `./gradlew :shared:build` compiles (empty module)
- `./gradlew :app:assembleDebug` still works unchanged
- No existing code moved yet

### Files Changed: 4 new/modified
### Effort: Small (1-2 hours)

---

## Phase 1: Models + Serialization (Lowest Risk)

### Scope
Move all pure data classes, sealed classes, enums, and utility types to `shared/commonMain`. These have zero Android dependencies -- just `kotlinx.serialization` which is already multiplatform.

### Files to Move (~30 files)

**From `data/db/entity/` -> `shared/commonMain/.../model/`** (9 files)
Strip Room annotations (`@Entity`, `@PrimaryKey`) and create plain data classes. The Room entities in `androidMain` become thin wrappers or typealias mappings.

| Current File | New Location | Notes |
|---|---|---|
| `TrackEntity.kt` | `shared/.../model/Track.kt` | Remove `@Entity`/`@PrimaryKey`, keep `availableResolvers()` method |
| `AlbumEntity.kt` | `shared/.../model/Album.kt` | Remove Room annotations |
| `ArtistEntity.kt` | `shared/.../model/Artist.kt` | Remove Room annotations |
| `PlaylistEntity.kt` | `shared/.../model/Playlist.kt` | Remove Room annotations |
| `PlaylistTrackEntity.kt` | `shared/.../model/PlaylistTrack.kt` | Remove Room annotations |
| `FriendEntity.kt` | `shared/.../model/Friend.kt` | Remove Room annotations |
| `ChatMessageEntity.kt` | `shared/.../model/ChatMessage.kt` | Remove Room annotations |
| `SearchHistoryEntity.kt` | `shared/.../model/SearchHistory.kt` | Remove Room annotations |
| `SyncSourceEntity.kt` | `shared/.../model/SyncSource.kt` | Remove Room annotations |

**From `data/repository/` -> `shared/.../model/`** (4 model files)
| `Resource.kt` | `shared/.../model/Resource.kt` | Already pure Kotlin |
| `ChartsModels.kt` | `shared/.../model/ChartsModels.kt` | Already pure Kotlin |
| `HistoryModels.kt` | `shared/.../model/HistoryModels.kt` | Already pure Kotlin |
| `RecommendationModels.kt` | `shared/.../model/RecommendationModels.kt` | Already pure Kotlin |

**From `ai/` -> `shared/.../ai/`** (5 type files)
| `AiChatProvider.kt` (types only) | `shared/.../ai/AiModels.kt` | ChatRole, ChatMessage, ToolCall, AiChatResponse, AiProviderConfig, AiProviderInfo, DjToolDefinition |

**From `resolver/` -> `shared/.../resolver/`** (3 type files)
| `ResolverManager.kt` (types only) | `shared/.../resolver/ResolverModels.kt` | ResolverInfo, ResolvedSource, normalizeStr(), scoreConfidence() |

**From `playback/` -> `shared/.../playback/`** (3 type files)
| `PlaybackState.kt` | `shared/.../playback/PlaybackState.kt` | Already pure data classes, RepeatMode enum |
| `PlaybackContext.kt` | `shared/.../playback/PlaybackContext.kt` | Pure data class |
| `QueueState.kt` | `shared/.../playback/QueueState.kt` | Pure data class |

**From `plugin/` -> `shared/.../plugin/`** (1 file)
| `PluginManager.kt` (PluginInfo only) | `shared/.../plugin/PluginInfo.kt` | Extract `PluginInfo` data class |

**From `data/metadata/` -> `shared/.../metadata/`** (1 file)
| `MetadataModels.kt` | `shared/.../metadata/MetadataModels.kt` | Pure data classes |

**From `data/api/` -> `shared/.../api/`** (API response models, ~15 model files embedded in API files)
All `@Serializable` response model data classes from each API file. The Retrofit interface annotations stay behind; the models move to shared.

| Source | Shared Location | Model Count |
|---|---|---|
| `SpotifyApi.kt` models | `shared/.../api/SpotifyModels.kt` | ~30 data classes |
| `LastFmApi.kt` models | `shared/.../api/LastFmModels.kt` | ~25 data classes |
| `ListenBrainzApi.kt` models | `shared/.../api/ListenBrainzModels.kt` | ~20 data classes |
| `MusicBrainzApi.kt` models | `shared/.../api/MusicBrainzModels.kt` | ~10 data classes |
| `TicketmasterApi.kt` models | `shared/.../api/TicketmasterModels.kt` | ~8 data classes |
| `SeatGeekApi.kt` models | `shared/.../api/SeatGeekModels.kt` | ~6 data classes |
| `AppleMusicApi.kt` models | `shared/.../api/AppleMusicModels.kt` | ~3 data classes |

### Approach
1. Create the shared model classes in `commonMain` (no Room annotations, no Android imports)
2. Update the Room entities in `:app` to either:
   - Import from shared and add Room annotations via a wrapper, OR
   - Keep Room entities as thin mappers that convert to/from shared models
3. Update all imports in `:app` to reference shared models

### What Breaks
- Room entities reference shared models instead of being self-contained
- Need mapper functions between Room entities and shared models
- All `import com.parachord.android.data.db.entity.*` change to shared imports

### Verification
- `./gradlew :shared:build` compiles with all models
- `./gradlew :app:assembleDebug` still builds
- Run existing unit tests (20 test files) -- all pass
- Launch app, verify library loads, playback works

### Files Changed: ~30 new in shared, ~80+ import updates in app
### Effort: Medium (1-2 days)

---

## Phase 2: API Clients (Retrofit -> Ktor)

### Scope
Replace 7 Retrofit API interfaces + OkHttp with Ktor multiplatform HTTP client. The API response models already moved to shared in Phase 1.

### Ktor Client Architecture

**`shared/commonMain/.../api/HttpClientFactory.kt`** — expect/actual for platform engines:
```kotlin
// commonMain
expect fun createHttpClient(json: Json): HttpClient

// androidMain — uses OkHttp engine (familiar, proven)
actual fun createHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(json) }
    install(Logging) { level = LogLevel.HEADERS }
}

// iosMain — uses Darwin engine
actual fun createHttpClient(json: Json): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) { json(json) }
    install(Logging) { level = LogLevel.HEADERS }
}
```

### Files to Migrate (8 API files -> 8 Ktor client files)

| Retrofit Interface | Ktor Client Class | Lines | Complexity |
|---|---|---|---|
| `SpotifyApi.kt` (interface) | `shared/.../api/SpotifyClient.kt` | ~240 | High — many endpoints, auth headers |
| `LastFmApi.kt` (interface) | `shared/.../api/LastFmClient.kt` | ~200 | High — signed requests, XML quirks |
| `ListenBrainzApi.kt` (interface) | `shared/.../api/ListenBrainzClient.kt` | ~150 | Medium |
| `MusicBrainzApi.kt` (interface) | `shared/.../api/MusicBrainzClient.kt` | ~80 | Low — read-only queries |
| `TicketmasterApi.kt` (interface) | `shared/.../api/TicketmasterClient.kt` | ~60 | Low |
| `SeatGeekApi.kt` (interface) | `shared/.../api/SeatGeekClient.kt` | ~40 | Low |
| `AppleMusicApi.kt` (interface) | `shared/.../api/AppleMusicClient.kt` | ~30 | Low |
| `GeoLocationService.kt` | `shared/.../api/GeoLocationClient.kt` | ~80 | Medium — OkHttp direct calls |

### Gradle Changes (shared/build.gradle.kts)
```kotlin
commonMain.dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
}
androidMain.dependencies {
    implementation(libs.ktor.client.okhttp)
}
iosMain.dependencies {
    implementation(libs.ktor.client.darwin)
}
```

### Migration Pattern (per API)
1. Create Ktor client class in shared that takes `HttpClient` as constructor param
2. Translate each `@GET`/`@POST`/`@PUT`/`@DELETE` to `client.get{}`/`client.post{}` calls
3. Map `@Header("Authorization")` to `header("Authorization", token)` in request builder
4. Map `@Query` params to `parameter("key", value)`
5. Map `@Path` params to string interpolation in URL
6. Map `@Body` to `setBody(obj)` with `contentType(ContentType.Application.Json)`
7. Map `Response<Unit>` to `HttpResponse` with status code checking

### OAuthManager Migration
`auth/OAuthManager.kt` uses OkHttp directly for token exchange. This moves to shared with Ktor:
- Token refresh (Spotify, SoundCloud) -> Ktor POST with form body
- Last.fm session exchange -> Ktor GET
- The `launchCustomTab()` and PKCE generation stay in `androidMain` (platform-specific browser launch)

Split into:
- `shared/.../auth/OAuthTokenService.kt` — token exchange/refresh logic (Ktor)
- `app/.../auth/OAuthManager.kt` — Android-specific browser launch, deep link handling

### What Breaks
- `ApiModule.kt` (Hilt) references Retrofit builders — update to provide Ktor clients
- `ResolverManager.kt` uses OkHttp directly for iTunes/SoundCloud — update to use Ktor clients
- All code that calls `spotifyApi.search(...)` changes to `spotifyClient.search(...)`

### Verification
- All API calls work (Spotify search, Last.fm scrobble, MusicBrainz lookup, etc.)
- OAuth flows still work (Spotify, SoundCloud, Last.fm)
- Run app and test: search, resolve, scrobble, library sync, concerts, recommendations

### Files Changed: ~8 new Ktor clients, ~15 files updated for imports
### Effort: Large (3-5 days) — most complex networking migration

---

## Phase 3: Database (Room -> SQLDelight)

### Scope
Replace Room (9 entities, 9 DAOs, 8 migrations, 258 schema version) with SQLDelight for cross-platform database access.

### SQLDelight Setup

**`shared/build.gradle.kts`** — add SQLDelight plugin:
```kotlin
plugins {
    id("app.cash.sqldelight") version "2.0.2"
}

sqldelight {
    databases {
        create("ParachordDatabase") {
            packageName.set("com.parachord.shared.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}
```

### .sq Files to Create (in `shared/src/commonMain/sqldelight/`)

| Room Entity | .sq File | Key Queries |
|---|---|---|
| `TrackEntity` | `Track.sq` | getAll, getById, getByAlbumId, search, insert, update, delete, findLocalFile, backfillResolverIds, backfillMbids |
| `AlbumEntity` | `Album.sq` | getAll, getById, search, getByTitleAndArtist, insert, delete |
| `ArtistEntity` | `Artist.sq` | getAll, insert, delete, existsByName, deleteByName |
| `PlaylistEntity` | `Playlist.sq` | getAll, getById, insert, update, delete, backfillLastModified |
| `PlaylistTrackEntity` | `PlaylistTrack.sq` | getByPlaylistId, insert, delete, replaceAll, getMaxPosition |
| `FriendEntity` | `Friend.sq` | getAll, insert, update, delete |
| `ChatMessageEntity` | `ChatMessage.sq` | getByProvider, insert, deleteOlderThan, clearByProvider, clearAll |
| `SearchHistoryEntity` | `SearchHistory.sq` | getAll, insert, delete, clearAll |
| `SyncSourceEntity` | `SyncSource.sq` | getByItemId, insert, delete |

### Migration Strategy
SQLDelight doesn't use version numbers like Room. For existing Android users upgrading:

1. Create SQLDelight `.sqm` migration files matching the Room v12 schema
2. On Android, use `AndroidSqliteDriver` which wraps the existing SQLite database
3. The v12 schema becomes SQLDelight's "version 1" (baseline schema)
4. Room's 8 migrations (v4->v12) are already applied on existing installs -- SQLDelight starts fresh from v12 schema
5. New iOS installations start from the v12 schema directly

### Platform Drivers
```kotlin
// commonMain — expect
expect fun createDatabaseDriver(context: Any?): SqlDriver

// androidMain
actual fun createDatabaseDriver(context: Any?): SqlDriver =
    AndroidSqliteDriver(
        ParachordDatabase.Schema,
        context as Context,
        "parachord.db"
    )

// iosMain
actual fun createDatabaseDriver(context: Any?): SqlDriver =
    NativeSqliteDriver(ParachordDatabase.Schema, "parachord.db")
```

### DAO -> SQLDelight Query Pattern
Room DAOs return `Flow<List<Entity>>`. SQLDelight returns `Query<Entity>` which can be converted to Flow via `.asFlow().mapToList()`.

Example transformation:
```kotlin
// Room DAO
@Query("SELECT * FROM tracks ORDER BY addedAt DESC")
fun getAll(): Flow<List<TrackEntity>>

// SQLDelight .sq
getAll:
SELECT * FROM tracks ORDER BY addedAt DESC;

// Kotlin wrapper (in shared)
fun getAllTracks(): Flow<List<Track>> =
    database.trackQueries.getAll().asFlow().mapToList(Dispatchers.IO)
```

### What Breaks
- `DatabaseModule.kt` (Hilt) provides DAOs from Room database — replaced with SQLDelight queries
- `AppModule.kt` provides `ParachordDatabase` — replaced with SQLDelight driver factory
- All DAO injection sites change to SQLDelight query wrappers
- `LibraryRepository` and all 13 repositories update their DAO references

### Data Migration for Existing Users
Critical: existing Android users have a Room database at version 12. SQLDelight must open this same database file without data loss.

Approach:
- Use `AndroidSqliteDriver` with `callback` parameter to handle version migration
- Set SQLDelight schema version = 12 to match Room's current version
- All existing data preserved -- same tables, same columns, same file

### Verification
- Fresh install: database creates correctly, all CRUD operations work
- Existing install: upgrade preserves all tracks, albums, playlists, chat history
- Flow-based reactive queries still update UI in real time
- Run all 20 existing unit tests

### Files Changed: ~9 .sq files, ~10 Kotlin wrappers, ~20 files updated
### Effort: Very Large (5-7 days) — most complex phase, data integrity critical

---

## Phase 4: DI (Hilt -> Koin)

### Scope
Replace Hilt dependency injection with Koin, which supports KMP. This affects 4 Hilt modules, 23 `@HiltViewModel` classes, and ~83 `@Inject` constructor sites.

### Migration Order
DI migration should happen AFTER Phases 2 and 3 because the DI modules reference API clients and database objects that change in those phases.

### Koin Module Definitions

**`shared/.../di/SharedModule.kt`** — shared dependencies:
```kotlin
val sharedModule = module {
    // JSON
    single { Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; coerceInputValues = true } }

    // HTTP Client
    single { createHttpClient(get()) }

    // API Clients (from Phase 2)
    single { SpotifyClient(get()) }
    single { LastFmClient(get()) }
    single { ListenBrainzClient(get()) }
    single { MusicBrainzClient(get()) }
    single { TicketmasterClient(get()) }
    single { SeatGeekClient(get()) }
    single { AppleMusicClient(get()) }

    // Database (from Phase 3)
    single { createDatabaseDriver(get()) }
    single { ParachordDatabase(get()) }

    // Repositories (from Phase 5)
    single { LibraryRepository(get(), get(), get(), get(), get(), get(), get()) }
    // ... other repositories
}
```

**`app/.../di/AndroidModule.kt`** — Android-specific:
```kotlin
val androidModule = module {
    // Platform context
    single<Context> { androidContext() }

    // Settings (DataStore)
    single { SettingsStore(get()) }

    // PlaybackController, PlaybackService bindings
    single { PlaybackController(get(), ...) }

    // Network monitor
    single { NetworkMonitor(get()) }

    // ViewModels
    viewModel { HomeViewModel(get(), get(), ...) }
    viewModel { LibraryViewModel(get(), get(), ...) }
    viewModel { SearchViewModel(get(), get(), ...) }
    // ... 23 ViewModels total
}
```

### Hilt -> Koin Translation

| Hilt Pattern | Koin Equivalent |
|---|---|
| `@Singleton class Foo @Inject constructor(bar: Bar)` | `single { Foo(get()) }` |
| `@HiltViewModel class FooVM @Inject constructor(bar: Bar) : ViewModel()` | `viewModel { FooVM(get()) }` |
| `@Module @InstallIn(SingletonComponent::class)` | `module { }` |
| `@Provides fun provideFoo(): Foo` | `single { Foo() }` |
| `@Binds @IntoSet fun bindFoo(impl: FooImpl): Foo` | `single<Set<Foo>> { setOf(get<FooImpl>()) }` |
| `hiltViewModel<FooVM>()` in Compose | `koinViewModel<FooVM>()` |
| `@AndroidEntryPoint` | Remove (Koin doesn't need it) |

### Application Class Change
```kotlin
// Before (Hilt)
@HiltAndroidApp
class ParachordApplication : Application()

// After (Koin)
class ParachordApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ParachordApplication)
            modules(sharedModule, androidModule)
        }
    }
}
```

### Files Modified

| File | Change |
|---|---|
| `app/ParachordApplication.kt` | Remove `@HiltAndroidApp`, add Koin startup |
| `app/ApiModule.kt` | Delete (replaced by `sharedModule`) |
| `app/AppModule.kt` | Delete (split into `sharedModule` + `androidModule`) |
| `app/DatabaseModule.kt` | Delete (merged into `sharedModule`) |
| `app/ScrobblerModule.kt` | Delete (merged into `sharedModule`) |
| `ui/MainActivity.kt` | Remove `@AndroidEntryPoint`, `@Inject` fields |
| `playback/PlaybackService.kt` | Remove `@AndroidEntryPoint`, `@Inject` fields, use `inject()` |
| All 23 ViewModels | Remove `@HiltViewModel`, `@Inject`, use Koin constructor injection |
| All 82 Compose screens | Replace `hiltViewModel()` with `koinViewModel()` |

### Gradle Changes
Remove from `app/build.gradle.kts`:
```kotlin
// Remove
alias(libs.plugins.ksp)    // only if no other KSP users remain
alias(libs.plugins.hilt)
implementation(libs.hilt.android)
ksp(libs.hilt.compiler)
implementation(libs.hilt.navigation.compose)
implementation(libs.hilt.work)
ksp(libs.hilt.androidx.compiler)
```

Add:
```kotlin
implementation(libs.koin.android)
implementation(libs.koin.compose)
```

### What Breaks
- Every `@Inject constructor` becomes plain constructor with Koin `get()` wiring
- Every `hiltViewModel<>()` in Compose becomes `koinViewModel<>()`
- WorkManager integration: `@HiltWorker` -> manual Koin injection in Worker
- `PlaybackService` field injection -> Koin `inject()` delegate

### Verification
- App launches without DI crashes
- All 23 ViewModels resolve correctly
- PlaybackService starts and receives media commands
- WorkManager sync jobs execute
- All navigation works (each screen gets its ViewModel)

### Files Changed: ~110 files modified (4 deleted, 2 new, all VMs + screens updated)
### Effort: Large (3-4 days) — many files but mechanical changes

---

## Phase 5: Repositories + Business Logic

### Scope
Move 13 repositories, resolver pipeline, AI services, scrobblers, and metadata services to `shared/commonMain`. These depend on API clients (Phase 2) and database (Phase 3) being already migrated.

### Files to Move

**Repositories (13 files, ~3,500 lines):**

| Repository | Android Dependencies | Migration Notes |
|---|---|---|
| `LibraryRepository.kt` | DAOs (now SQLDelight) | Direct move, update DAO refs |
| `ChartsRepository.kt` | LastFmApi, SpotifyApi | Direct move, update to Ktor clients |
| `HistoryRepository.kt` | LastFmApi, ListenBrainzApi | Direct move |
| `FriendsRepository.kt` | LastFmApi, ListenBrainzApi | Direct move |
| `RecommendationsRepository.kt` | SpotifyApi, LastFmApi | Direct move |
| `FreshDropsRepository.kt` | SpotifyApi, LastFmApi | Direct move |
| `CriticalDarlingsRepository.kt` | SpotifyApi, MusicBrainzApi | Direct move |
| `WeeklyPlaylistsRepository.kt` | SpotifyApi, LastFmApi | Direct move |
| `ConcertsRepository.kt` | TicketmasterApi, SeatGeekApi | Direct move |

**Resolver pipeline (3 files, ~800 lines):**

| File | Android Dependencies | Migration Notes |
|---|---|---|
| `ResolverManager.kt` | SpotifyApi, OkHttp, `android.util.Log` | Replace `Log` with expect/actual logger, OkHttp calls become Ktor |
| `ResolverScoring.kt` | SettingsStore | Direct move (SettingsStore interface in shared) |
| `TrackResolverCache.kt` | None | Direct move (pure in-memory cache) |

**AI services (7 files, ~1,500 lines):**

| File | Android Dependencies | Migration Notes |
|---|---|---|
| `AiChatService.kt` | ChatMessageDao (now SQLDelight) | Direct move |
| `AiRecommendationService.kt` | None significant | Direct move |
| `ChatContextProvider.kt` | SettingsStore, DAOs | Direct move |
| `ChatCardEnricher.kt` | ResolverManager | Direct move |
| `providers/ClaudeProvider.kt` | OkHttp | Replace with Ktor |
| `providers/ChatGptProvider.kt` | OkHttp | Replace with Ktor |
| `providers/GeminiProvider.kt` | OkHttp | Replace with Ktor |
| `tools/DjToolDefinitions.kt` | None | Direct move |
| `tools/DjToolExecutor.kt` | PlaybackController, LibraryRepository | Move, reference via interface |

**Scrobblers (5 files, ~600 lines):**

| File | Android Dependencies | Migration Notes |
|---|---|---|
| `Scrobbler.kt` (interface) | TrackEntity (now shared model) | Direct move |
| `ScrobbleManager.kt` | SettingsStore | Direct move |
| `LastFmScrobbler.kt` | LastFmApi, OkHttp | Replace OkHttp with Ktor |
| `ListenBrainzScrobbler.kt` | ListenBrainzApi | Direct move |
| `LibreFmScrobbler.kt` | LastFmApi variant | Direct move |
| `AxeScrobbler.kt` | PluginManager | Direct move |

**Metadata services (8 files, ~1,200 lines):**

| File | Android Dependencies | Migration Notes |
|---|---|---|
| `MetadataService.kt` | None significant | Direct move |
| `MetadataProvider.kt` (interface) | None | Direct move |
| `LastFmProvider.kt` | LastFmApi | Direct move |
| `SpotifyProvider.kt` | SpotifyApi | Direct move |
| `MusicBrainzProvider.kt` | MusicBrainzApi | Direct move |
| `WikipediaProvider.kt` | OkHttp | Replace with Ktor |
| `DiscogsProvider.kt` | OkHttp | Replace with Ktor |
| `ImageEnrichmentService.kt` | ML Kit (face detection) | Keep in androidMain, define interface in commonMain |
| `MbidEnrichmentService.kt` | ListenBrainzApi | Direct move |

**Other shared logic:**

| File | Migration Notes |
|---|---|
| `playlist/XspfParser.kt` | Pure Kotlin XML parsing, direct move |
| `playlist/PlaylistImportManager.kt` | May reference ContentResolver -- split if needed |
| `deeplink/DeepLinkHandler.kt` | URL parsing logic to shared, Android Intent handling stays |
| `deeplink/ExternalLinkResolver.kt` | Direct move |
| `data/store/SettingsStore.kt` | See Phase 7 (expect/actual) |

### Platform Abstractions Needed

**Logging** — `android.util.Log` used in ~30 files:
```kotlin
// commonMain
expect object Log {
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
}

// androidMain
actual object Log {
    actual fun d(tag: String, msg: String) = android.util.Log.d(tag, msg)
    // ...
}
```

**UUID generation** — `java.util.UUID.randomUUID()` used in several repositories:
```kotlin
// commonMain
expect fun randomUUID(): String
// androidMain
actual fun randomUUID(): String = java.util.UUID.randomUUID().toString()
// iosMain
actual fun randomUUID(): String = platform.Foundation.NSUUID().UUIDString
```

**System.currentTimeMillis():**
```kotlin
// commonMain
expect fun currentTimeMillis(): Long
// androidMain
actual fun currentTimeMillis(): Long = System.currentTimeMillis()
// iosMain
actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
```

### What Breaks
- All repository consumers (ViewModels) need import updates
- ResolverManager loses `android.util.Log` -- needs expect/actual
- AI providers lose OkHttp -- replaced with Ktor in Phase 2
- ImageEnrichmentService uses ML Kit (Android-only) -- stays in androidMain with interface in commonMain

### Verification
- All repository operations work (add/remove tracks, albums, playlists)
- Resolver pipeline resolves tracks from all sources
- AI chat works with all providers (ChatGPT, Claude, Gemini)
- Scrobbling works (Last.fm, ListenBrainz, Libre.fm)
- Metadata enrichment works (album art, artist info, MBIDs)
- All 20 unit tests pass (many test these exact components)

### Files Changed: ~45 files moved to shared, ~50 files updated in app
### Effort: Very Large (5-7 days) — large scope but mostly mechanical

---

## Phase 6: Plugin System

### Scope
Move `JsRuntime` (interface), `PluginManager`, and `PluginSyncService` to `shared/commonMain`. `JsBridge` (WebView implementation) stays in `androidMain`.

### Architecture

```
shared/commonMain/
  plugin/
    JsRuntime.kt          -- interface (already KMP-ready, move as-is)
    PluginManager.kt       -- core logic
    PluginSyncService.kt   -- marketplace sync

shared/androidMain/
  plugin/
    AndroidJsRuntime.kt   -- wraps existing JsBridge (WebView-based)

shared/iosMain/
  plugin/
    IosJsRuntime.kt       -- stub/empty for now (JavaScriptCore in future)
```

### PluginManager Changes
Currently depends on:
- `android.content.Context` (for `assets.list()`, `filesDir`)
- `android.util.Base64`
- `JsBridge` (concrete class instead of `JsRuntime` interface)

Fix:
1. Replace `Context` dependency with `expect/actual` for file access:
```kotlin
// commonMain
expect class PluginFileAccess {
    fun listBundledPlugins(): List<String>
    fun readBundledPlugin(filename: String): String
    fun listCachedPlugins(): List<String>
    fun readCachedPlugin(filename: String): String
    fun writeCachedPlugin(filename: String, content: String)
}
```
2. Replace `android.util.Base64` with `kotlin.io.encoding.Base64` (Kotlin stdlib)
3. Change `JsBridge` type to `JsRuntime` interface (the TODO in the current code)

### What Breaks
- PluginManager's `context.assets.list()` / `context.filesDir` calls
- `android.util.Base64.encodeToString()` call in plugin loading

### Verification
- All .axe plugins load on Android (bandcamp, youtube, etc.)
- Plugin resolve works (resolve a track via bandcamp.axe)
- Plugin sync service downloads updates from marketplace
- PluginManager hot-reload works after marketplace sync

### Files Changed: 3 moved to shared, 2 new expect/actual files
### Effort: Medium (1-2 days)

---

## Phase 7: Platform Abstractions

### Scope
Define expect/actual interfaces for platform-specific services: Settings (DataStore), PlaybackEngine, NetworkMonitor, MediaScanner, AuthManager.

### Settings (DataStore -> multiplatform-settings)

**Option A: multiplatform-settings library**
Use `com.russhwolf:multiplatform-settings` which wraps SharedPreferences (Android) and NSUserDefaults (iOS).

**Option B: expect/actual wrapper**
Define a `Settings` interface in commonMain that mirrors SettingsStore's public API:

```kotlin
// commonMain
interface PlatformSettings {
    fun getString(key: String): Flow<String?>
    suspend fun setString(key: String, value: String)
    fun getBoolean(key: String): Flow<Boolean?>
    suspend fun setBoolean(key: String, value: Boolean)
    // ... etc
}

// androidMain — wraps DataStore<Preferences>
class AndroidSettings(private val dataStore: DataStore<Preferences>) : PlatformSettings { ... }
```

**Recommendation:** Option A (multiplatform-settings) is simpler and well-tested. The SettingsStore class moves to shared with a `Settings` backend:

```kotlin
// shared/commonMain
class SettingsStore(private val settings: Settings) {
    // All existing public API preserved
    // Internal implementation switches from DataStore keys to Settings keys
}
```

### PlaybackEngine
Playback is deeply Android-specific (Media3 ExoPlayer, MediaSession, foreground Service). This stays in `:app` but with a shared interface:

```kotlin
// commonMain
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    fun play(track: Track)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()
    fun setQueue(tracks: List<Track>)
}
```

QueueManager and QueuePersistence (pure logic, no Android APIs) move to shared. PlaybackController, PlaybackService, PlaybackRouter stay in `:app` as the Android `PlaybackEngine` implementation.

### NetworkMonitor
```kotlin
// commonMain
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}

// androidMain — wraps ConnectivityManager (existing code)
// iosMain — wraps NWPathMonitor
```

### MediaScanner
Stays entirely in `:app` (uses Android's `MediaStore` ContentProvider). Define a shared interface if iOS needs local file scanning later.

### What Breaks
- SettingsStore internals change from DataStore to multiplatform-settings
- All `BuildConfig.*` references need expect/actual (API keys come from different sources on iOS)

### BuildConfig Replacement
```kotlin
// commonMain
expect object AppConfig {
    val LASTFM_API_KEY: String
    val LASTFM_SHARED_SECRET: String
    val SPOTIFY_CLIENT_ID: String
    // ... etc
}

// androidMain
actual object AppConfig {
    actual val LASTFM_API_KEY: String get() = BuildConfig.LASTFM_API_KEY
    // ...
}
```

### Verification
- Settings persist correctly (theme, tokens, resolver order)
- Upgrade from DataStore: existing user settings preserved
- Network banner appears/disappears correctly
- Playback works through shared interface

### Files Changed: ~5 new interfaces, ~5 expect/actual pairs, ~3 files refactored
### Effort: Medium (2-3 days)

---

## Phase 8: Coil 2 -> Coil 3 (KMP Image Loading)

### Scope
Upgrade from Coil 2 (`io.coil-kt:coil-compose:2.7.0`) to Coil 3 (`io.coil-kt.coil3:coil-compose:3.x`) which has KMP support.

### Changes
Coil 3 has a different package structure but similar API:

```kotlin
// Coil 2
import coil.compose.AsyncImage
import coil.request.ImageRequest

// Coil 3
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
```

### Gradle Changes
```kotlin
// Remove
implementation(libs.coil.compose)  // io.coil-kt:coil-compose:2.7.0

// Add (in shared)
commonMain.dependencies {
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.ktor3)  // Uses Ktor for fetching
}
```

### Files Affected
All Compose files that use `AsyncImage` or `rememberAsyncImagePainter` (estimated ~25 files across UI components and screens):
- `components/AlbumArtCard.kt`
- `components/TrackRow.kt`
- `components/FaceAwareImage.kt`
- `components/MiniPlayer.kt`
- All screen files that display artwork

### FaceAwareImage Special Case
`FaceAwareImage.kt` uses ML Kit face detection for smart cropping. This is Android-only. Keep as Android-specific component, not in shared.

### Verification
- All album art, artist images, track artwork loads correctly
- Image caching works (no re-downloads on config changes)
- FaceAwareImage still works for artist images

### Files Changed: ~25 import updates, 1 Gradle change
### Effort: Small (half day) — mostly import changes

---

## Phase 9: Deferred Migration Tasks

These tasks were identified during Phases 5-7 as too large to tackle inline. They complete the migration to full code sharing but can be done incrementally without blocking iOS development.

### 9A: TrackEntity → Shared Track Model — ✅ DONE

**What landed:** Rather than the planned per-call-site migration, the work was absorbed into Phases 1 + 5 via the typealias-bridge pattern. `app/src/main/java/com/parachord/android/data/db/entity/TrackEntity.kt` is now:

```kotlin
package com.parachord.android.data.db.entity
import com.parachord.shared.model.Track
typealias TrackEntity = Track
```

Every call site that imports `TrackEntity` is operating on the shared `Track` data class under the hood. No mass-rename was needed; the typealias preserves source compatibility while the underlying type is shared.

**Why this works:** The shared `Track` model carries every field the app needs (title, artist, album, duration, artwork, addedAt, all resolver IDs, all MBIDs, source URL). The Room annotations that originally lived on `TrackEntity` were removable when Room was replaced by SQLDelight (Phase 9D) — SQLDelight maps query rows to `Track` via row-mapping functions in the wrapper DAOs (e.g., `TrackDao.Tracks.toTrack()`).

**Future cleanup (optional, not blocking iOS):** Remove the typealias and rename all `TrackEntity` usages to `Track` directly. Mechanical rename, no behavior change. Can happen any time — there's no rush since the typealias is free.

### 9B: SettingsStore → Multiplatform Settings
**Scope:** Migrate SettingsStore (78+ public methods, DataStore-backed) to use `com.russhwolf:multiplatform-settings` or a shared interface so iOS can provide its own NSUserDefaults backend.

**Why deferred:** SettingsStore is the most widely referenced service in the app. The current bridge approach (passing function params like `getResolverOrder: suspend () -> List<String>` to shared classes) works but doesn't fully share the settings logic.

**Approach:**
1. Add `multiplatform-settings` dependency to shared module
2. Create shared `SettingsStore` class using `Settings` backend
3. Android: provide `SharedPreferencesSettings` (backed by DataStore migration)
4. iOS: provide `NSUserDefaultsSettings`
5. Migrate each settings section incrementally (resolver prefs, auth tokens, AI config, etc.)

**Effort:** Medium-Large (2-4 days)

### 9C: QueueManager + QueuePersistence → Shared — ✅ DONE

**What landed:** `QueueManager` and `QueueSnapshot` now live in `shared/src/commonMain/kotlin/com/parachord/shared/playback/`. The original `app/.../playback/QueueManager.kt` is a typealias re-export:

```kotlin
@file:Suppress("unused")
package com.parachord.android.playback
typealias QueueSnapshot = com.parachord.shared.playback.QueueSnapshot
typealias QueueManager = com.parachord.shared.playback.QueueManager
```

JVM-runnable unit tests cover queue behavior (shuffle, repeat, advance, persistence). Same typealias bridge pattern as 9A.

`QueuePersistence` location to be verified during the iOS playback orchestrator extraction (Phase A of `2026-04-25-ios-playback-design.md`); if not already shared, it's a half-day move at that point.

### 9D: Room → SQLDelight Actual Migration — ✅ DONE

**What landed:** Room is fully removed from the app. `app/build.gradle.kts` has no `room-runtime` / `room-compiler` / `room-ktx` dependencies. The `app/.../data/db/dao/*Dao.kt` files are no longer Room `@Dao` *interfaces* — they are now wrapper *classes* around SQLDelight `*Queries` accessors. A representative sample:

```kotlin
// app/.../data/db/dao/TrackDao.kt (after migration)
class TrackDao(private val db: ParachordDb, private val driver: SqlDriver) {
    private val queries get() = db.trackQueries

    private fun Tracks.toTrack() = Track(
        id = id, title = title, artist = artist, /* ... full mapping ... */
    )

    fun getAll(): Flow<List<TrackEntity>> =
        queries.getAll().asFlow().mapToList(Dispatchers.IO).map { it.map { it.toTrack() } }
    // ...
}
```

All 11 DAO files (`TrackDao`, `AlbumDao`, `ArtistDao`, `PlaylistDao`, `PlaylistTrackDao`, `FriendDao`, `ChatMessageDao`, `SearchHistoryDao`, `SyncSourceDao`, `SyncPlaylistLinkDao`, `SyncPlaylistSourceDao`) follow this pattern. Consumer code (repositories, ViewModels) is unchanged because the `*Dao` class API matches the previous Room interface signatures.

**Data migration:** Existing Room SQLite databases are read by SQLDelight directly — same `parachord.db` file, same SQL schema (the SQLDelight `.sq` files were authored to match the final Room v12 schema). No one-time migration code was needed; SQLDelight just opened the existing file.

**Future cleanup (optional, not blocking iOS):** Move the wrapper DAO classes to `shared/.../data/db/dao/` so they can be consumed from iOS. Today they live in `app/` and reference `ParachordDb` (which is in shared) — moving them is a matter of relocation, not refactoring. Required if iOS sync wants to share the data-access layer; not required for iOS playback (iOS-side queue / track ops can call SQLDelight directly via the shared module without going through these wrappers).

### 9E: Retrofit → Ktor Actual Migration
**Scope:** Switch the app's API calls from Retrofit interfaces to the shared Ktor clients. Currently the app still uses Retrofit for all HTTP calls; the Ktor clients in shared are infrastructure-ready but not wired in.

**Why deferred:** Similar to 9D — touching every repository and API consumer. The Ktor clients need to match every Retrofit endpoint exactly.

**Approach:**
1. Switch one API at a time (e.g., MusicBrainzApi first since it's simplest)
2. Verify each endpoint returns identical data structures
3. Remove Retrofit interfaces and OkHttp dependency once fully migrated

**Effort:** Medium (3-5 days)

---

## Phase Ordering and Dependencies

```
Phase 0 (Structure)
    |
Phase 1 (Models) -------- no dependencies
    |
Phase 2 (Ktor) --------- depends on Phase 1 (models)
    |
Phase 3 (SQLDelight) ---- depends on Phase 1 (models)
    |
Phase 4 (Koin) ---------- depends on Phase 2 + Phase 3
    |
Phase 5 (Repositories) -- depends on Phase 2 + Phase 3 + Phase 4
    |
Phase 6 (Plugins) ------- depends on Phase 5 (PluginManager refs)
    |
Phase 7 (Abstractions) -- depends on Phase 5 (SettingsStore used by repos)
    |
Phase 8 (Coil 3) -------- independent, can run anytime after Phase 0
    |
Phase 9 (Deferred) ------ incremental, no single dependency
    9A (TrackEntity→Track) -- prerequisite for 9C
    9B (SettingsStore)     -- independent
    9C (QueueManager)      -- depends on 9A
    9D (Room→SQLDelight)   -- depends on 9A (uses Track model throughout)
    9E (Retrofit→Ktor)     -- independent
```

Phases 2 and 3 can run in parallel. Phase 8 can run anytime. Phase 9 tasks are independent of each other (except 9C depends on 9A) and can be done incrementally between feature work.

---

## Summary Table

> **About the effort estimates.** The historical entries (Phases 0–7) used human-developer-day estimates. The remaining-work entries (Phases 8 + 9) below now use AI-assisted-day estimates split into *active dev* (you + AI collaborator coding time) and *wallclock* (active dev + verification & bake). AI compresses mechanical work dramatically (Retrofit→Ktor, DAO wrappers, mass refactors) but does not compress real-device verification or multi-day soak periods. Treat active-dev as "calendar time of focused work sessions"; treat wallclock as "elapsed time from start to merge."

| Phase | Scope | Files Moved/New | Files Modified | Effort | Risk | Status |
|---|---|---|---|---|---|---|
| 0 | Project structure | 3 new | 2 | 1-2 hours | Very Low | ✅ Done |
| 1 | Models + serialization | ~30 new | ~80 | 1-2 days | Low | ✅ Done |
| 2 | Retrofit -> Ktor (infra) | ~10 new | ~15 | 3-5 days | Medium | ✅ Done |
| 3 | Room -> SQLDelight (infra) | ~20 new | ~20 | 5-7 days | High | ✅ Done |
| 4 | Hilt -> Koin | 2 new, 4 deleted | ~110 | 3-4 days | Medium | ✅ Done |
| 5 | Business logic → shared | ~12 new | ~25 | 5-7 days | Medium | ✅ Done |
| 6 | Plugin system | 5 new | ~8 | 1-2 days | Low | ✅ Done |
| 7 | Platform abstractions | 4 new | ~3 | 2-3 days | Medium | ✅ Done |
| 8 | Coil 2 -> 3 | 0 | ~25 | active 0.5d / wall 1d | Low | Pending |
| 9A | TrackEntity → Track | 1 typealias | (absorbed by 1+5) | — | — | ✅ Done (typealias bridge) |
| 9B | SettingsStore → KMP | ~3 new | ~5 | active 1–2d / wall 3–4d | Medium | Pending |
| 9C | QueueManager → shared | 1 moved | ~3 | — | — | ✅ Done |
| 9D | Room → SQLDelight (actual) | DAO wrappers | ~30 | — | — | ✅ Done (SQLDelight wrapper DAOs) |
| 9E.1 | Retrofit → Ktor (all 9 native API clients to commonMain) | ~9 Ktor clients | ~15 | active 1–2d / wall 2–3d | Medium | Pending |
| 9E.2 | NativeBridge.fetch transport convergence (plugins inherit shared HttpClient) | 0 (transport rewire) | ~3 | active 0.5–1d / wall 2–3d | High (19 plugins must keep working) | Pending |
| Sync | SyncEngine + providers → commonMain (depends on 9E.1; 9E.2 recommended first) | ~3 moved (SyncEngine, SpotifySyncProvider, AppleMusicSyncProvider) | ~10 | active 2–4d / wall 5–7d | High | Pending — separate design doc forthcoming |

## What Stays in `:app` (androidApp) — NOT Shared

These 82+ files remain Android-only:

- **UI layer (82 files):** All Compose screens, components, theme, icons, navigation
- **PlaybackService.kt** — Android foreground Service + MediaSession
- **PlaybackController.kt** — Media3 MediaController bridge
- **PlaybackRouter.kt** — ExoPlayer routing
- **SpotifyPlaybackHandler.kt** — Spotify App Remote SDK (Android AAR)
- **AppleMusicPlaybackHandler.kt** — MusicKit WebBridge (WebView)
- **SoundCloudPlaybackHandler.kt** — streaming handler
- **MusicKitWebBridge.kt** — WebView-based MusicKit JS
- **JsBridge.kt / NativeBridge.kt** — Android WebView JS runtime
- **MediaScanner.kt** — Android MediaStore scanner
- **NetworkMonitor.kt** — Android ConnectivityManager (impl moves to androidMain)
- **MainActivity.kt** — Android Activity
- **ParachordApplication.kt** — Application class
- **LibrarySyncWorker.kt** — Android WorkManager worker
- **SyncScheduler.kt** — WorkManager scheduling
- **MiniPlayerWidgetProvider/Updater** — Android AppWidget
- **FaceAwareImage.kt** — ML Kit face detection
- **HapticUtils.kt** — Android vibrator

## Rollback Strategy

Each phase is independently rollback-friendly:
- Phase 0: Delete `:shared` module, revert settings.gradle.kts
- Phase 1: Move model files back, revert imports
- Phase 2: Revert to Retrofit interfaces (keep shared models)
- Phase 3: Revert to Room (keep shared models)
- Phase 4: Revert to Hilt (re-add annotations)
- Phase 5-8: Move files back to `:app`

**Branch strategy:** Create a branch per phase (`kmp/phase-0-structure`, `kmp/phase-1-models`, etc.). Merge each to main only after verification passes.
