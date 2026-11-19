# Recommendations: shared (PR 1 of 6) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port desktop's current Recommendations data layer to `shared/commonMain` and add the plugin glue, so PR 2 (Android) and PR 3 (iOS) only have to draw the page. Today's screens keep working unchanged.

**Architecture:**
- A rewritten `RecommendationModel.kt` (pure, desktop's `recommendation-model.js`), a new `ReleaseDate.kt` (`release-date.js`), and a shared `pluginIsOn` gate.
- `RecommendationsRepository` is rewritten around a page state: per-provider cache, paging, Shuffleupagus, release dates and Listeners.
- `PluginManager` gains the loader calls desktop added.
- The repository's existing `getRecommendedTracks` / `getRecommendedArtists` flows stay, now derived from the page's Tracks and Artists rows, so both apps build and behave as before until PRs 2 and 3.

**Tech Stack:** Kotlin Multiplatform (commonMain), kotlinx.serialization, Ktor (MockEngine in tests), SQLDelight unchanged, JS resolver-loader (WebView / JavaScriptCore), Koin (Android), `IosContainer` (iOS).

**Spec:** `docs/superpowers/specs/2026-11-19-home-and-recommendations-design.md` (sections "Order and PRs" item 1 and "Recommendations — the design"). Desktop reference: `Parachord/parachord` `origin/main` at `7acc0759`; read files with `git -C /Users/jherskowitz/Development/parachord/parachord-desktop show origin/main:<path>`.

**Worktree:** `/Users/jherskowitz/Development/parachord/parachord-android/.worktrees/home-recs`, branch `docs/home-recs-design` (rename to `feat/recommendations-shared` before the first code commit: `git branch -m feat/recommendations-shared`). Every gradle command starts with `cd <worktree> &&` (`CLAUDE.md` → "Working in a git worktree").

## Global Constraints

- All logic goes in `shared/src/commonMain`. No JVM-only APIs (`CLAUDE.md` → "KMP / shared/commonMain rules"): `currentTimeMillis()`, no `toSortedMap`, no `ConcurrentHashMap`, no `Dispatchers.IO`, and no `,` in backtick test names in `commonTest`.
- Any regex, Unicode or normalisation code (`slug`, `releaseDateKey`, `searchTitle`) must pass `./gradlew :shared:iosSimulatorArm64Test`. Kotlin/Native rejects `\p{N}`; use `LETTER_OR_NUMBER_CLASS`.
- Both `resolver-loader.js` copies (`app/src/main/assets/js/` and `iosApp/Parachord/Resources/`) stay byte-identical. New methods are copied verbatim from desktop's `resolver-loader.js` (L539–625).
- Async plugin calls go through `PluginManager.pollAsync` (the unique-key poll). A plugin that throws is a failure, never an empty answer.
- **Desktop's numbers:**
  - provider timeout 20 s;
  - recommendations fresh 1 h, stale-but-shown 7 days;
  - similar users fresh 7 days, `shape = 3`;
  - release dates kept 7 days, newest 1500;
  - `EXPANSION_PAGE_CAP = 10`.
- **Copy strings** are desktop's exact text and live in shared code (`UiMessages` or the model).
- **Never cache a failure as "nothing".** A failure keeps the previous answer; `notReady` drops that provider's.
- **Docs ride with the change:**
  - `CLAUDE.md`'s "Recommendations come from the plugins" paragraph;
  - the ListenBrainz Weekly Playlists section ("weekly playlists now also come from the ListenBrainz plugin; Home's carousel stays until PR 5/6").

## Review Focus

1. **A provider that's switched off or not connected is never asked.** Example: Apple Music on Android with no Music User Token. Test in Task 3 (`pluginIsOn`) and Task 8 (`asks only providers that are on`).
2. **A failed or timed-out provider keeps its last good sections.** It neither empties the page nor writes an empty cache entry. Test in Task 8.
3. **Exploration and Jams for "This Week" both have no url and the same name.** They must stay two cards, keyed by `weekly.id`. Test in Task 5.
4. **People to leave out of Listeners:** the user themself, someone already followed, someone the user removed, and the same person sent twice (any case). Test in Task 9.
5. **Album titles with store decorations ("(Deluxe Edition)", " - EP"),** and non-ASCII artists ("Sigur Rós"), must find their release date. A non-match gets no badge. Test in Task 6 (ported vectors plus one accent vector).

---

## File Structure

| File | Responsibility |
|---|---|
| `shared/.../recommendations/RecommendationModel.kt` | **Rewrite.** Item and section types, normalise, categories, keys, merge, `buildRecommendationPage`, filters, `friendsRow`, `shuffleupagusSection`, `homeRecommendationAlbums`. |
| `shared/.../recommendations/RecommendationCategories.kt` | **New.** Category tables, `categoryFor`, `artistRowTitle`, `CATEGORY_RANK` (split out to keep the model file readable). |
| `shared/.../recommendations/ReleaseDate.kt` | **New.** `releaseDateKey`, `searchTitle`, `pickReleaseGroupDate`, `upcomingLabel`, and the `ReleaseDates` cache. |
| `shared/.../plugin/PluginGate.kt` | **New.** `pluginIsOn`, `needsAccount`, `PluginNotReadyException`. |
| `shared/.../plugin/PluginManager.kt` | **Modify.** `similarUsersResolvers`, `getSimilarUsers`, `expandRecommendationSection`, `historyMixResolvers`, `getHistoryMixes`; `notReady` mapping. |
| `shared/.../plugin/MetaServicePluginConfig.kt` | **Modify.** Add Last.fm `apiKey`. |
| `shared/.../settings/SettingsStore.kt` | **Modify.** `recommendations_off_resolvers` getter and setter. |
| `shared/.../api/MusicBrainzClient.kt` | **Modify.** `searchReleaseGroups(title, artist)`. |
| `shared/.../api/AppleMusicCatalog.kt` | **Modify.** `personalRecommendations(limit, userToken)`. |
| `shared/.../repository/RecommendationsRepository.kt` | **Rewrite.** Page state, provider cache, paging, Shuffleupagus, release dates, legacy flows. |
| `shared/.../repository/SimilarUsersStore.kt` | **New.** Listeners fetch and cache. |
| `shared/.../repository/FriendsRepository.kt` | **Modify.** `follow(service, username)`, `listenerTopTracks(service, username)`. |
| `app/src/main/assets/js/resolver-loader.js`, `iosApp/Parachord/Resources/resolver-loader.js` | **Modify.** Desktop's four loader methods. |
| `app/src/main/assets/js/bootstrap.html`, `shared/src/iosMain/.../IosJsRuntime.kt` (`BOOTSTRAP_JS`), iOS `JsPolyfills` | **Modify.** The `appleMusicPersonalRecommendations` hook. |
| `app/src/main/assets/plugins/{lastfm,listenbrainz,applemusic}.axe`, `iosApp/Parachord/Resources/axe-plugins/` (same three) | **Refresh** to marketplace versions. |
| `app/.../di/AndroidModule.kt`, `shared/src/iosMain/.../ios/IosContainer.kt` | **Modify.** Wiring. |

---

### Task 1: Loader methods and `PluginManager` calls

**Files:**
- Modify: both `resolver-loader.js` copies, `shared/.../plugin/PluginManager.kt`
- Create: `shared/.../plugin/PluginGate.kt` (only `PluginNotReadyException` in this task)
- Test: `app/src/test/java/com/parachord/android/plugin/PluginRecommendationsTest.kt` (extend)

**Interfaces:**
- Produces:
  - `class PluginNotReadyException(message: String) : IllegalStateException(message)`
  - `suspend fun PluginManager.similarUsersResolvers(): List<Pair<String, String>>`
  - `suspend fun PluginManager.getSimilarUsers(resolverId: String, configOverrideJson: String): String` — the raw JSON array
  - `suspend fun PluginManager.expandRecommendationSection(resolverId: String, sectionId: String, cursor: String?): String?` — the raw `{items,nextCursor}`, or null
  - `suspend fun PluginManager.historyMixResolvers(): List<Pair<String, String>>`
  - `suspend fun PluginManager.getHistoryMixes(resolverId: String): String`
  - `getRecommendations`, `getSimilarUsers` and `getHistoryMixes` throw `PluginNotReadyException` when the plugin's error has `notReady: true`, and `IllegalStateException` for any other throw or a timeout.
- Each call passes the plugin's stored config. `getSimilarUsers` passes `configOverrideJson` (the caller merges the stored config with `excludeUsernames`).

- [ ] **Step 1: Write the failing tests.** In `PluginRecommendationsTest`:
  - `both resolver-loader copies carry desktop's similar-users and history-mix calls`: the two files are equal, and each contains `getSimilarUsersResolvers()`, `async getSimilarUsers(resolverId, configOverride = null)`, `getHistoryMixResolvers()` and `async getHistoryMixes(resolverId, configOverride = null)`.
  - `a notReady error from a plugin call is a PluginNotReadyException`: with the existing fake `JsRuntime` answering the poll key with `error:notReady:not-signed-in`, `getRecommendations("applemusic")` throws `PluginNotReadyException`.
  - `any other plugin error is a plain failure`: the same with `error:boom` throws `IllegalStateException` and not `PluginNotReadyException`.
- [ ] **Step 2: Run them; expect them to fail.** Run: `cd <worktree> && ./gradlew :app:testDebugUnitTest --tests '*PluginRecommendationsTest*'`. Expected: FAIL (methods missing).
- [ ] **Step 3: Copy the methods.** Copy desktop's four loader methods verbatim into both copies, after `getRecommendations`.
- [ ] **Step 4: Implement the `PluginManager` calls.**
  - The poll expression catches `notReady`. Wrap each loader call as `(async()=>{try{return await <call>}catch(e){throw new Error((e&&e.notReady?'notReady:':'')+(e&&e.message||e))}})()`. Kept as an IIFE expression: `pollAsync` splices it into `var result = …`.
  - `pollAsync` maps an `error:notReady:` prefix to `PluginNotReadyException` when `throwOnFailure`.
  - Use `RECOMMENDATION_POLL_ATTEMPTS` (20 s) for all of them.
- [ ] **Step 5: Run the tests; expect PASS.**
- [ ] **Step 6: Commit.** `feat(plugins): similar users, history mixes and section paging through the loader`

### Task 2: Refresh the bundled plugins

**Files:**
- Modify: `app/src/main/assets/plugins/{lastfm,listenbrainz,applemusic}.axe`, `iosApp/Parachord/Resources/axe-plugins/{same}`
- Test: `PluginRecommendationsTest`

**Interfaces:**
- Produces: bundled Last.fm ≥ 1.2.15 and ListenBrainz ≥ 1.6.12 with `capabilities.friends`, `similarUsers` and `recommendations`; ListenBrainz also `historyMixes`; Apple Music ≥ 1.5.7.

- [ ] **Step 1: Write the failing test.** `the bundled Last_fm and ListenBrainz plugins offer Listeners on both apps`. For each app's copy, parse the manifest and assert:
  - `capabilities.friends == true` and `capabilities.similarUsers == true`;
  - the version is ≥ the floor above.
- [ ] **Step 2: Run it; expect it to fail** (the bundled copies are 1.2.8 and 1.6.7).
- [ ] **Step 3: Refresh the copies.**
  - Run: `cd <worktree> && MARKET_DIR=/Users/jherskowitz/Development/parachord/parachord-plugins node .github/scripts/sync-bundled-plugins.js`.
  - First `git -C /Users/jherskowitz/Development/parachord/parachord-plugins fetch && git -C … checkout origin/main -- .`, or point `MARKET_DIR` at a fresh clone.
  - The script updates only bundled plugins and never downgrades.
  - Check the iOS copies match the Android ones byte for byte (`cmp`).
- [ ] **Step 4: Run the plugin tests; expect PASS.** Run: `./gradlew :app:testDebugUnitTest --tests '*Plugin*'`.
- [ ] **Step 5: Commit.** `chore(plugins): bundle Last.fm 1.2.15, ListenBrainz 1.6.12, Apple Music 1.5.7`

### Task 3: The plugin gate, the off-list and Last.fm's key

**Files:**
- Modify: `shared/.../plugin/PluginGate.kt`, `SettingsStore.kt`, `MetaServicePluginConfig.kt`
- Test: create `shared/src/commonTest/kotlin/com/parachord/shared/plugin/PluginGateTest.kt`; extend the existing `MetaServicePluginConfig` test if there is one, else add `MetaServicePluginConfigTest.kt`

**Interfaces:**
- Produces:
  - `fun needsAccount(plugin: PluginManager.PluginInfo): Boolean` — `requiresAuth`. Add `authType` to `PluginInfo` if the manifest parse has it, else `requiresAuth` only.
  - `fun pluginIsOn(id: String, isMetaService: Boolean, needsAccount: Boolean, connected: Boolean, activeResolvers: Collection<String>, disabledPlugins: Set<String>, disabledMetaProviders: Set<String>): Boolean`. Rules, in order:
    1. in `disabledPlugins` or `disabledMetaProviders` → false;
    2. a meta-service → true;
    3. `activeResolvers` not empty → `id in activeResolvers`;
    4. otherwise → `!needsAccount || connected`.
  - `suspend fun SettingsStore.getRecommendationsOffResolvers(): Set<String>` and `setRecommendationsOffResolvers(ids: Set<String>)`, key `recommendations_off_resolvers` (a CSV like the other set settings), default empty.
  - `metaServicePluginConfigs` adds `"apiKey"` from `AppConfig.lastFmApiKey` to Last.fm's config, only when non-blank.

- [ ] **Step 1: Write the failing tests** in `PluginGateTest`:
  - `a disabled plugin is off`: in `disabledPlugins` → false, even when active and connected.
  - `iOS: the active list decides`: active `["spotify"]`, id `applemusic`, connected → false; id `spotify` → true.
  - `Android: an account plugin needs a connection`: active empty, `needsAccount`, not connected → false; connected → true.
  - `a plugin with no account is on when nothing disables it`.
  - `a meta-service is on unless disabled`.
  - `Last_fm's config carries the API key` and `not when there is none`.
- [ ] **Step 2: Run them; expect them to fail.** Run: `./gradlew :shared:testDebugUnitTest --tests '*PluginGateTest*' --tests '*MetaServicePluginConfig*'`.
- [ ] **Step 3: Implement** the three pieces with the signatures above.
- [ ] **Step 4: Run the tests; expect PASS.**
- [ ] **Step 5: Commit.** `feat(recommendations): one shared rule for which plugins are on`

### Task 4: The model, part 1 — items, categories, keys, merge

**Files:**
- Rewrite: `shared/.../recommendations/RecommendationModel.kt` (keep `slug` and `trackSlug`)
- Create: `RecommendationCategories.kt`
- Test: rewrite `shared/src/commonTest/kotlin/com/parachord/shared/recommendations/RecommendationModelTest.kt`

**Interfaces:**
- Consumes: `slug`, `trackSlug`, `RecommendationType` (existing).
- Produces:
  - `data class WeeklyInfo(val id: String, val contextType: String, val label: String, val weekLabel: String, val date: String?)`
  - `data class RecommendationItem(type, id, name, artist?, imageUrl?, url?, reason?, mbid?, isrc?, duration?, releaseDate?, weekly: WeeklyInfo?, category: String?, seedArtist: String?, resolverId: String?, sourceIds: List<String>, derived: Boolean, blendIndex: Int?)`
  - `data class RecommendationSection(id?, title?, description?, category?, seedArtist?, nextCursor?, items)`
  - `normalizeRecommendationItem(raw: JsonElement?, resolverId: String?): RecommendationItem?`
  - `normalizeRecommendationSections(raw: JsonElement?, resolverId: String, resolverName: String): List<RecommendationSection>`
  - `object RecommendationCategories { PLAYLIST, ALBUM tables (id → title); fun validFor(type, id): Boolean; fun categoryFor(type, item, sectionCategory): String?; fun artistRowTitle(seeds: List<String>): String; CATEGORY_RANK }`
  - `recommendationItemKey(item): String`, `providerIdKey(item): String?`, `mergeOwned(into: MutableList<RecommendationItem>, incoming: List<RecommendationItem>)`, `interleave(lists: List<List<T>>): List<T>`, `deriveArtistsFromSongs(items)`

- [ ] **Step 1: Write the failing tests.** Port desktop's `tests/renderer/recommendation-model.test.js` describe blocks, one Kotlin test per desktop `test(...)`, with the same inputs and expectations:
  - `normalizeRecommendationItem`, `… releaseDate`, `… weekly field`;
  - `normalizeRecommendationSections`;
  - `deriveArtistsFromSongs`;
  - `dedupeItems / recommendationItemKey`;
  - `playlist categories: contract and helpers`, `album categories: tables and helpers`;
  - `one item per provider id`.

  Names use ` — ` instead of `,`. Skip `blockedItemPick`: mobile has no blocklist; note that in the file header.
- [ ] **Step 2: Run them; expect them to fail to compile.** Run: `./gradlew :shared:testDebugUnitTest --tests '*RecommendationModelTest*'`.
- [ ] **Step 3: Implement.** Port the matching functions from desktop's `src/renderer/lib/recommendation-model.js` (L39–332). Copy strings and category titles are desktop's exact text.
- [ ] **Step 4: Run the tests; expect PASS.** Then run `./gradlew :shared:iosSimulatorArm64Test --tests '*RecommendationModelTest*'`.
- [ ] **Step 5: Commit.** `feat(recommendations): desktop's item model, categories and merge`

### Task 5: The model, part 2 — the page

**Files:**
- Modify: `RecommendationModel.kt`
- Test: `RecommendationModelTest.kt`

**Interfaces:**
- Consumes: Task 4's types and helpers.
- Produces:
  - `data class ProviderRecommendations(val resolverId: String, val resolverName: String, val sections: List<RecommendationSection>, val canExpand: Boolean)`
  - `data class ExpandedSection(val items: List<RecommendationItem>, val nextCursor: String?, val exhausted: Boolean)` keyed by `pagingKey = "$resolverId|${section.id ?: section.title}"`
  - `data class PageableSection(resolverId, resolverName, sectionId, title, category, pagingKey, nextCursor)`
  - `data class RecommendationRow(key, type, category, title, blended, sources: List<PageSource>, items, pageable: List<PageableSection>, canLoadMore)`
  - `data class RecommendationPageSection(type: RecommendationType?, title, rows: List<RecommendationRow>)`. The Listeners section uses `type = null` and row key `friends:similar`.
  - `data class PageSource(id, name, count)`
  - `data class SimilarUser(username, similarity: Double, avatarUrl?, topArtists: List<String>)`
  - `data class FriendExclusions(friendServices: Set<String>, friendKeys: Set<String>, selfKeys: Set<String>)`
  - `buildRecommendationPage(providers: List<ProviderRecommendations>, shuffleupagus: RecommendationSection?, expanded: Map<String, ExpandedSection>, similarUsers: Map<String, List<SimilarUser>>, exclude: FriendExclusions, excludePlaylistIds: Set<String>): List<RecommendationPageSection>`
  - `pageSources(sections): List<PageSource>`, `filterRowBySource(row, sourceId: String?): RecommendationRow`
  - `friendsRow(similarUsers, exclude): RecommendationRow?`
  - `shuffleupagusSection(albums: List<AiAlbumSuggestion>, artists: List<AiArtistSuggestion>): RecommendationSection?`
  - `homeRecommendationAlbums(sections, aiAlbums: List<AiAlbumSuggestion>): List<RecommendationItem>` (with `sourceLabel` as `resolverName`), `filterBySource(items, id)`, `albumsNeedingArt(albums, perSource = 6)`

- [ ] **Step 1: Write the failing tests.** Port these describe blocks whole:
  - `buildRecommendationPage`, including `Friends` and `filters and flatten`;
  - `shuffleupagusSection`;
  - `playlist category rows`, `album category rows`;
  - `homeRecommendationAlbums`.

  Leave out `weeklyHomeEntries` and `the legacy flat builder`: Home's weekly mirror isn't used on mobile.

  Add these Review Focus tests:
  - `This Week's Exploration and Jams stay two cards`: two playlist items named "This Week" with no url, `weekly.id` `a` and `b`, contextTypes exploration and jam. Expect two items, Exploration first.
  - `a history mix id is dropped after the newest-week pick`: `excludePlaylistIds = {"b"}` drops Jams and keeps Exploration.
- [ ] **Step 2: Run them; expect them to fail.**
- [ ] **Step 3: Implement.** Port `buildRecommendationPage`, `categoryRows`, `friendsRow`, `pageSources`, `filterRowBySource`, `shuffleupagusSection` and `homeRecommendationAlbums` (desktop L402–894). Section titles: "Tracks", "Albums", "Artists", "Playlists", "Listeners".
- [ ] **Step 4: Run the JVM tests and `:shared:iosSimulatorArm64Test` for the class; expect PASS.**
- [ ] **Step 5: Commit.** `feat(recommendations): desktop's blended page, category rows and Listeners row`

### Task 6: Release dates

**Files:**
- Create: `shared/.../recommendations/ReleaseDate.kt`
- Modify: `MusicBrainzClient.kt`
- Test: create `shared/src/commonTest/.../recommendations/ReleaseDateTest.kt`

**Interfaces:**
- Produces:
  - `releaseDateKey(artist: String?, title: String?): String`
  - `searchTitle(title: String): String`
  - `releaseGroupQuery(title: String, artist: String): String` — the Lucene query `releasegroup:"T" AND artist:"A"`, with `"` and `\` escaped
  - `pickReleaseGroupDate(groups: List<MbReleaseGroup>, title: String, artist: String): String?`
  - `upcomingLabel(date: String?, today: LocalDate): String?`
  - `suspend fun MusicBrainzClient.searchReleaseGroups(query: String, limit: Int = 5): List<MbReleaseGroup>`, through the existing gate. Add `MbReleaseGroup(id, title, primaryType, firstReleaseDate, artistCredit: List<name>)` serialisable types.
  - `class ReleaseDates(load: suspend () -> String?, save: suspend (String) -> Unit, lookup: suspend (title, artist) -> String?, nowMs: () -> Long)`:
    - `suspend fun dateFor(item: RecommendationItem): String?` — the provider's `releaseDate` first, else the cache, else null;
    - `suspend fun requestLookups(items: List<RecommendationItem>)` — one lookup per key in flight, in order;
    - `val changes: Flow<Unit>`;
    - TTL 7 days, keep the newest 1500, a failure stores nothing.

- [ ] **Step 1: Write the failing tests.**
  - Port every `test(...)` in desktop's `tests/renderer/release-date.test.js`, with the same inputs.
  - Add `an accented artist still matches`: "Sigur Rós" against credit "Sigur Ros" → a date.
  - Add `a failed lookup stores nothing and asks again later`: on a `ReleaseDates` with a throwing `lookup`, a second `requestLookups` calls it again.
- [ ] **Step 2: Run them; expect them to fail.**
- [ ] **Step 3: Implement.** Port `release-date.js`. Month names use en-US "Jan"…"Dec", hard-coded, not the locale.
- [ ] **Step 4: Run the JVM tests and `:shared:iosSimulatorArm64Test --tests '*ReleaseDateTest*'`; expect PASS.**
- [ ] **Step 5: Commit.** `feat(recommendations): Coming badges and release years from MusicBrainz`

### Task 7: Apple Music's personal recommendations hook

**Files:**
- Modify: `AppleMusicCatalog.kt`, `app/src/main/assets/js/bootstrap.html`, `app/.../bridge/NativeBridge.kt`, `shared/src/iosMain/.../IosJsRuntime.kt` (`BOOTSTRAP_JS`), iOS `JsPolyfills.installNativeBridge` (in `iosApp/Parachord/ContentView.swift`) and `IosContainer`
- Test: create `shared/src/commonTest/.../api/AppleMusicPersonalRecommendationsTest.kt` (MockEngine)

**Interfaces:**
- Produces:
  - `suspend fun AppleMusicCatalog.personalRecommendations(limit: Int, userToken: String?): PersonalRecommendationsResult`, with:
    - `sealed interface PersonalRecommendationsResult { data class Ok(val body: String); data class NotReady(val code: String); data class Failed(val status: Int) }`;
    - `GET https://api.music.apple.com/v1/me/recommendations?limit=N` with N clamped to 1–30, the `Authorization: Bearer <dev>` and `Music-User-Token` headers;
    - no developer token → `NotReady("no-developer-token")`; no user token → `NotReady("not-signed-in")`; 401/403 → `NotReady("token-refused")`; another non-2xx → `Failed`.
  - JS hook `window.appleMusicPersonalRecommendations(limit)` on both runtimes. It calls `NativeBridge.appleMusicPersonalRecommendations(callbackId, limit)`. The answer resolves the body as parsed JSON; `NotReady` rejects with an `Error` that has `notReady = true` and `code`; `Failed` rejects plainly. Same callback shape as `appleMusicCatalogGet`.
  - The Music User Token is read on the native side; it never enters JS.

- [ ] **Step 1: Write the failing tests:**
  - `sends both tokens and the clamped limit`: `limit = 99` → `limit=30`.
  - `no user token is not ready`.
  - `a 401 is not ready — token refused`.
  - `a 500 is a failure`.
  - `a 200 hands back the body`.
- [ ] **Step 2: Run them; expect them to fail.**
- [ ] **Step 3: Implement** the catalog call, then the two hosts. Mirror `appleMusicCatalogGet`'s existing wiring line for line, through the shared `MusicKitCatalogLimiter`.
- [ ] **Step 4: Run the shared tests; expect PASS.** Then `./gradlew :app:compileDebugKotlin`, and the iOS build command from `iosApp/AGENTS.md` (absolute `-project` path); expect no errors.
- [ ] **Step 5: Commit.** `feat(applemusic): the host hook for the plugin's personal recommendations`

### Task 8: The repository

**Files:**
- Rewrite: `shared/.../repository/RecommendationsRepository.kt`
- Test: rewrite `shared/src/commonTest/.../repository/RecommendationsRepositoryTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 3, 5 and 6, plus `AiRecommendationService` (`getCachedRecommendations`, `loadRecommendations`, `hasEnabledProvider`).
- Produces:
  - `interface RecommendationSource`:
    - `providers(): List<RecommendationProvider>`, where `RecommendationProvider(id, name, isMetaService, needsAccount, canExpand)`;
    - `fetch(id): String` — throws `PluginNotReadyException` / `IllegalStateException`;
    - `expand(id, sectionId, cursor): String?`.

    `PluginRecommendationSource(pluginManager)` implements it.
  - Constructor `RecommendationsRepository(source, settingsStore, metadataService, aiRecommendationService, releaseDates: ReleaseDates, similarUsers: SimilarUsersStore, providerIsOn: suspend (RecommendationProvider) -> Boolean, cacheRead, cacheWrite, nowMs)`. The platform builds `providerIsOn` from `pluginIsOn` with its own settings and connection state.
  - `val page: StateFlow<RecommendationPageState>`, where `RecommendationPageState(sections, sources: List<PageSource>, loading: Boolean, pendingProviders: Set<String>, aiLoading: Boolean, error: RecommendationsError?, hero: String)`:
    - `RecommendationsError = NotConfigured | Message(text)`;
    - `hero` is "Personalized picks for {lb, else lastfm username}", else "Personalized picks from your services".
  - `suspend fun load(force: Boolean = false)`
  - `suspend fun openRow(rowKey: String)` — pages every pageable section of that row until exhausted or capped
  - `fun excludePlaylistIds(ids: Set<String>)` — for PR 4
  - **Kept for today's screens:** `getRecommendedTracks(): Flow<Resource<List<RecommendedTrack>>>`, `getRecommendedArtists(): Flow<Resource<List<RecommendedArtist>>>`, `cachedTracksList`, `cachedArtistsList`. These are now derived from the page's Tracks and Artists rows, with the same `Resource` semantics as today, and are removed in PRs 2/3.
- **Rules:** the spec's "Fetch, cache and refresh" subsection, every bullet.
  - Providers: `providerIsOn` and not in `getRecommendationsOffResolvers()`; Last.fm / ListenBrainz only with a username.
  - Per provider: 20 s; fresh 1 h; stale 7 d; account key; empty never served from cache but replaces the entry; failure keeps; not-ready drops; load generation; force clears expansions; `not_configured`.
  - Paging: one per key, cap 10, no-advance, `lastPageTypes`.
  - Shuffleupagus pending flags.
  - Disk cache.

- [ ] **Step 1: Write the failing tests,** with a fake `RecommendationSource`, a fake clock and an in-memory cache:
  - `asks only providers that are on and not switched off for recommendations`;
  - `a provider that fails keeps its last sections`;
  - `a not-ready provider loses its cached sections`;
  - `an empty answer replaces the cache but is not served from it next time`;
  - `fresh for an hour — then shown while it refreshes for 7 days`;
  - `a cached answer for another account is not shown`;
  - `only the latest load updates the page`;
  - `opening a row pages until the provider runs out — at most 10 pages`;
  - `a cursor that doesn't move ends paging`;
  - `not configured with no usernames and no providers on`;
  - `the old tracks and artists flows still come from the page`;
  - `Shuffleupagus picks land under Based on your listening`.
- [ ] **Step 2: Run them; expect them to fail.**
- [ ] **Step 3: Implement.** Port desktop's `refreshMergedRecommendations` / `loadRecommendations` / `loadMoreRecommendationSection` (`app.js` L27622–28440) behaviour, without UI.
- [ ] **Step 4: Run the shared tests; expect PASS.**
- [ ] **Step 5: Commit.** `feat(recommendations): desktop's page data, caching and paging, shared`

### Task 9: Listeners and Follow

**Files:**
- Create: `shared/.../repository/SimilarUsersStore.kt`
- Modify: `FriendsRepository.kt`, `RecommendationsRepository.kt` (consume the store)
- Test: create `SimilarUsersStoreTest.kt`; extend the existing friends test (or create `FriendsFollowTest.kt`)

**Interfaces:**
- Produces:
  - `class SimilarUsersStore(source: RecommendationSource-like (similarUsersResolvers/getSimilarUsers), friends: suspend () -> FriendExclusions, configFor: suspend (id) -> String?, providerIsOn, load, save, nowMs)`:
    - `suspend fun usersFor(force: Boolean): Map<String, List<SimilarUser>>`;
    - providers have both `similarUsers` and `friends` capabilities;
    - 7 d fresh; an empty answer counts as fresh; failure keeps; `shape = 3` (an older shape is shown and refetched once);
    - one fetch in flight per (provider, account), shared;
    - no username → not asked;
    - config = stored config + `excludeUsernames` (followed and hidden names on that service).
  - `suspend fun FriendsRepository.follow(service: String, username: String): Resource<Friend>` — the same path as `addFriend`, with an explicit service.
  - `suspend fun FriendsRepository.listenerTopTracks(service: String, username: String): List<Track>` — the 7-day top 10, else 1 month; throws on failure.
  - `UiMessages`:
    - `LISTENER_NO_TOP_TRACKS = "%s has no recent top tracks"`;
    - `LISTENER_TOP_TRACKS_FAILED = "Couldn’t load %s’s top tracks. Try again in a moment."` (curly apostrophes, as desktop);
    - `COULDNT_REACH_SERVICE = "Couldn't reach %s"`.

- [ ] **Step 1: Write the failing tests:**
  - `an empty answer is cached as fresh`;
  - `a failure keeps the old list and stores nothing`;
  - `two callers share one fetch`;
  - `followed — removed — and yourself are excluded — in any case` (Review Focus 4, through `buildRecommendationPage`);
  - `excludeUsernames reaches the plugin`;
  - `follow adds the friend on the given service`;
  - `top tracks fall back to a month`.
- [ ] **Step 2: Run them; expect them to fail.**
- [ ] **Step 3: Implement.** Port desktop's `fetchSimilarUsers` (~L27963) and `playListenerTopTracks` (~L30535).
- [ ] **Step 4: Run them; expect PASS.**
- [ ] **Step 5: Commit.** `feat(recommendations): Listeners, with Follow`

### Task 10: Wiring, docs and the full check

**Files:**
- Modify: `app/.../di/AndroidModule.kt`, `shared/src/iosMain/.../ios/IosContainer.kt`, `CLAUDE.md`, `iosApp/AGENTS.md` (only if it describes the repository)

**Interfaces:**
- Consumes: every task above.
- Produces:
  - **Android** `providerIsOn`: `pluginIsOn(id, isMetaService = plugin.type == "meta-service", needsAccount, connected = <the existing per-service connection check: Spotify token, Apple Music MUT, SoundCloud token; true for others>, activeResolvers, disabledPlugins, disabledMetaProviders)`.
  - **iOS:** the same call with its settings. `connected` is true; `active_resolvers` already reflects connections.
  - `ReleaseDates` and `SimilarUsersStore` disk caches (`release_dates_cache.json`, `similar_users_cache.json`) through the existing `cacheRead` / `cacheWrite` pattern.

- [ ] **Step 1: Wire both containers.** Use explicit `single { … }` (Common Mistakes #37) for every new class with a defaulted or lambda parameter.
- [ ] **Step 2: Run the full checks.**
  - Run: `cd <worktree> && ./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest :shared:iosSimulatorArm64Test`. Expected: BUILD SUCCESSFUL, including `KoinModuleVerifyTest`.
  - Then build iOS with the `iosApp/AGENTS.md` command. Expected: no errors.
- [ ] **Step 3: Check on the Pixel.** `./gradlew installDebug` on the Pixel (`ANDROID_SERIAL=53111JEBF07835`), force-stop, open Recommendations. The old screen shows tracks and artists as before; logcat shows the new providers asked (`adb logcat | grep -i recommend`).
- [ ] **Step 4: Update the docs.**
  - Rewrite `CLAUDE.md`'s "Recommendations come from the plugins" paragraph to what's true now: providers by `pluginIsOn` and the off-list, the page model, release dates, Listeners, the caches.
  - Add the Weekly Playlists note.
- [ ] **Step 5: Commit, push and open the PR.** Commit `docs + wiring: shared recommendations page on both apps`, then push and open the PR referencing #645 / #672 / #673 / #707 (off-list only) and the spec.
