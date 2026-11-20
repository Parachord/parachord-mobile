# Home: shared rules (PR 4 of 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port desktop's Home page model, playlist freshness, history mixes, setup / getting-started / banner models, the concerts-near-you filter and the in-app chat origin into `shared/commonMain`, wired on both apps, so PRs 5 and 6 only draw the page.

**Architecture:** Pure functions in a new `shared/.../home/` package, each a port of one desktop module with desktop's test vectors. Two stateful pieces: playlist freshness, stamped inside `PlaylistTrackDao` (the one place every playlist-track write passes), and `HistoryMixesRepository`, built like `RecommendationsRepository` (per-provider cache, stale-while-refresh, generations). Both apps wire the repository and the new columns; no screen changes in this PR.

**Tech Stack:** Kotlin Multiplatform (commonMain), SQLDelight, kotlinx.serialization, kotlinx-datetime, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-19-home-and-recommendations-design.md` ("Home — the design", "Order and PRs" item 4).

**Desktop source of truth** (read with `git -C /Users/jherskowitz/Development/parachord/parachord-desktop show origin/main:<path>`):
- `src/renderer/lib/home-model.js` + `tests/renderer/home-model.test.js`
- `src/renderer/lib/time-ago.js` + `tests/renderer/time-ago.test.js`
- `playlist-freshness.js` + `tests/main-process/playlist-freshness.test.js`
- `src/renderer/lib/home-setup.js`, `home-banners.js`, `getting-started.js` + their tests in `tests/renderer/`
- `protocol-url.js` (`buildChatPromptUrl`, `shouldSendPendingChatPrompt`, `shouldDropPendingChatPrompt`) + `tests/protocol/chat-origin.test.js`
- `src/renderer/app.js` ~L35730 (`homeConcerts`) and ~L28125–28345 (history-mix host code)

## Global Constraints

- All logic in `shared/src/commonMain/kotlin/com/parachord/shared/home/` unless a task names another file; no JVM-only APIs (CLAUDE.md "KMP / shared/commonMain rules").
- Every test file in `shared/src/commonTest/...`; backtick test names contain no `,` `.` `;` `:` `/` `<` `>` `[` `]` `\` (use ` — `).
- Run `./gradlew :shared:jvmTest` and `./gradlew :shared:iosSimulatorArm64Test` before each commit that touches regex, Unicode or string folding (Tasks 1, 2, 3, 6).
- Every gradle command from the worktree: `cd /Users/jherskowitz/Development/parachord/parachord-android/.worktrees/home-shared && ./gradlew …`.
- Desktop wording verbatim: `"just now"`, `"Nm ago"`, `"Nh ago"`, `"Nd ago"`, `"Nmo ago"`, `"Ny ago"`; `"Δ N"`; `"this morning"` / `"this afternoon"` / `"this evening"` / `"yesterday"` / `"N days ago"`; `"<title> picked up N new track(s) <when>"`; `"Refreshed <timeAgo>"`; `"Make me a playlist for right now"`; the four Ask phrasings.
- Desktop numbers verbatim: badge window 7 days; `freshestPlaylists` limit 6; `recentLoves` default 50; `NAME_CAP` 40; artist-name length cap 80 in `homeTopArtists`; `HOME_RECOMMENDATIONS_THROTTLE_MS` 600 000; `HOME_MIX_COVER_ATTEMPTS` 3; `HOME_MIX_COVER_RETRY_MS` 60 000; history-mix fresh 1 h, stale 7 d, provider timeout 20 s; chat prompt cap 500; concerts near you max 4.
- New settings keys: `home_getting_started` (JSON `{active, played, skipped}`), `home_setup_dismissed` (CSV set). New cache file: `history_mixes_cache.json`.
- `RelativeTime` is untouched; `TimeAgo` is a separate object.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A sync pull that rebuilds a playlist row** (`PlaylistDao.insert`, which is `INSERT OR REPLACE`) must keep `tracksChangedAt` / `lastChangeAdded`; otherwise every sync resets Home's order to `lastModified`. Test in Task 1.
2. **A delete-then-insert rewrite with the same tracks** (hosted XSPF poll, sync pull) must not stamp; two separate DAO calls would read "empty → N tracks, N added". Task 1 converts those call sites to `replaceAll` and tests that a same-tracks `replaceAll` doesn't stamp.
3. **A changed ListenBrainz username** must never show the old account's history mixes (cache keyed to account). Test in Task 4.
4. **A Home load that overlaps a newer one** must not overwrite the newer mixes (latest generation paints). Test in Task 4.
5. **A prompt name with bidi / zero-width characters or > 40 characters** must come out clean, since Ask skips the confirmation. Covered by porting desktop's `promptName` vectors in Task 3, plus one added Kotlin case for a surrogate pair at the 40-char cut.

---

### Task 1: Playlist freshness columns and stamping

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/parachord/shared/db/Playlist.sq` (CREATE gains `tracksChangedAt INTEGER`, `lastChangeAdded INTEGER NOT NULL DEFAULT 0`; new query `stampTrackChange: UPDATE playlists SET tracksChangedAt = ?, lastChangeAdded = ? WHERE id = ?;` and `getTrackStamp: SELECT tracksChangedAt, lastChangeAdded FROM playlists WHERE id = ?;`)
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/model/Playlist.kt` (`val tracksChangedAt: Long? = null`, `val lastChangeAdded: Int = 0`)
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/db/dao/PlaylistDao.kt` (map the two columns; `insert` keeps the stored stamp)
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/db/dao/PlaylistTrackDao.kt` (stamp on `insertAll`, `replaceAll`, `deleteTrack`)
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/home/PlaylistFreshness.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (bind-time `ALTER TABLE` for both columns, try/catch like `writable`)
- Modify: `shared/src/iosMain/kotlin/com/parachord/shared/db/DriverFactory.ios.kt` (`addColumnIfMissing` for both)
- Modify (delete+insert → `replaceAll`): `app/.../playlist/HostedPlaylistPoller.kt`, `app/.../ui/screens/playlists/PlaylistDetailViewModel.kt`, `shared/.../playlist/HostedXspfService.kt`, `shared/.../sync/SyncEngine.kt` (the `deleteByPlaylistId(x)` immediately followed by `insertAll(...)` for the same playlist), `shared/src/iosMain/.../ios/IosContainer.kt` (same pattern)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/home/PlaylistFreshnessTest.kt`, `shared/src/commonTest/kotlin/com/parachord/shared/db/PlaylistTrackStampTest.kt` (in-memory driver, as other DAO tests in commonTest/jvmTest do — follow whichever test source set the existing DAO tests use)

**Interfaces:**
- Produces:
  - `object PlaylistFreshness { fun trackKey(artist: String?, title: String?, id: String? = null): String; fun countAdded(prev: List<String>, next: List<String>): Int; fun stamp(prevKeys: List<String>?, nextKeys: List<String>, prevAt: Long?, now: Long): Stamp? }` with `data class Stamp(val tracksChangedAt: Long, val lastChangeAdded: Int)`. `stamp` returns null when the key lists are equal (order-aware); otherwise `Stamp(now, prevKeys?.let { countAdded(it, nextKeys) } ?: nextKeys.size)`.
  - `Playlist.tracksChangedAt: Long?`, `Playlist.lastChangeAdded: Int`.
  - `PlaylistTrackDao(db: ParachordDb, nowMs: () -> Long = ::currentTimeMillis)`.

- [ ] **Step 1: Write the failing pure tests** in `PlaylistFreshnessTest`: port every case from desktop's `tests/main-process/playlist-freshness.test.js` that applies to `trackKey`, `countAdded` and the changed / unchanged / reorder / duplicates decisions. Must include:
  - `trackKey("Sigur Rós", "Hoppípolla") == "sigur-ros-hoppipolla"` and `trackKey(null, null, "x") == "id:x"`, `trackKey(null, null, null) == ""`.
  - Reorder `[a,b]→[b,a]` stamps with `lastChangeAdded = 0`.
  - `[a]→[a,a]` stamps with `1` (copies count).
  - `[a,b]→[a,b]` returns null.
  - `prevKeys = null` (no earlier tracks) → added = `nextKeys.size`.
  `trackKey` uses the existing `slug` from `recommendations/RecommendationModel.kt` (same fold as desktop's `slugPart`).
- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests '*PlaylistFreshnessTest*'` — Expected: FAIL (unresolved `PlaylistFreshness`).
- [ ] **Step 3: Implement `PlaylistFreshness`.**
- [ ] **Step 4: Write the failing DAO tests** in `PlaylistTrackStampTest` (clock injected as a fixed `now`):
  - `insertAll` of 3 tracks into an empty playlist stamps `(now, 3)`.
  - `replaceAll` with the same titles/artists in the same order leaves the stamp unchanged (seed a stamp of `(1000, 2)` first).
  - `replaceAll` adding one new track stamps `(now, 1)`; a reorder stamps `(now, 0)`.
  - `deleteTrack` stamps `(now, 0)`.
  - `updateTrackArtwork` and `backfillResolverIds` don't stamp.
  - `PlaylistDao.insert(playlist.copy(tracksChangedAt = null, lastChangeAdded = 0))` over a row stamped `(1000, 2)` keeps `(1000, 2)`; an insert carrying a later non-null stamp writes it.
- [ ] **Step 5: Implement.** In each stamped DAO write, inside the existing `queries.transaction { }`: read the playlist's ordered keys (`getByPlaylistId`) before the write and after it, call `PlaylistFreshness.stamp`, and run `stampTrackChange` when it's non-null. `insertAll` groups by `playlistId`. `deleteByPlaylistId` never stamps (whole-playlist removal). `PlaylistDao.insert`: in one transaction read `getTrackStamp`, run the existing `INSERT OR REPLACE`, then re-apply the newer of stored and incoming stamp (desktop `stampAgainst`'s "keep the newer"). Add the bind-time column migrations on both platforms. Convert each delete-then-insert pair listed above to one `replaceAll`.
- [ ] **Step 6: Run** `./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test :app:testDebugUnitTest` — Expected: PASS. Then `/usr/bin/grep -rn -E "deleteByPlaylistId\(" shared/src app/src/main` and confirm every remaining call deletes a whole playlist (no `insertAll` for the same id follows it).
- [ ] **Step 7: Commit** `feat(shared): playlist freshness — tracksChangedAt and lastChangeAdded stamped on track changes`.

### Task 2: TimeAgo and the playlist half of HomeModel

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/format/TimeAgo.kt`
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/home/HomePlaylists.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/repository/LibraryRepository.kt:499` (`recentLoves`)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/format/TimeAgoTest.kt`, `shared/src/commonTest/kotlin/com/parachord/shared/home/HomePlaylistsTest.kt`, extend `shared/src/commonTest/kotlin/com/parachord/shared/repository/CollectionTrackTest.kt`

**Interfaces:**
- Consumes: `Playlist.tracksChangedAt`, `Playlist.lastChangeAdded` (Task 1).
- Produces:
  - `object TimeAgo { fun ago(atMs: Long, nowMs: Long = currentTimeMillis()): String }`
  - `fun playlistFreshness(p: Playlist): Long` — `tracksChangedAt ?: lastModified.takeIf { it > 0 } ?: createdAt.takeIf { it > 0 } ?: 0`
  - `fun freshestPlaylists(list: List<Playlist>, limit: Int = 6): List<Playlist>` (stable on ties)
  - `fun changeBadge(p: Playlist, nowMs: Long): String?`
  - `fun whenPhrase(atMs: Long, nowMs: Long, tz: TimeZone = TimeZone.currentSystemDefault()): String`, `fun whenPhraseShort(...)` (same params)
  - `fun freshChangeLine(p: Playlist, nowMs: Long, tz: TimeZone = …): String?` (uses `p.name` as desktop's `title`)
  - `data class PlaylistSubtitle(val refreshed: String, val since: String?)` and `fun playlistSubtitle(p: Playlist, nowMs: Long): PlaylistSubtitle` — `refreshed = "Refreshed ${TimeAgo.ago(freshness, now)}"`, `since = "Since <UTC year of createdAt>"` when `createdAt > 0` (the spec's two-line form of desktop's `" · since YYYY"`).
  - `recentLoves(tracks, limit)`: loves only, newest `addedAt` first; a love with `addedAt <= 0` goes last in input order.

- [ ] **Step 1: Write the failing tests.** Port every case from `time-ago.test.js` into `TimeAgoTest`, and every `describe` for `playlistFreshness`, `freshestPlaylists`, `changeBadge`, `whenPhrase`, `freshChangeLine`, `playlistSubtitle` and `recentLoves` from `home-model.test.js` into `HomePlaylistsTest` / `CollectionTrackTest`, mapping `title`→`name` and desktop's missing fields to null/0. Pass `TimeZone.UTC` where desktop's test relies on local hours, and choose instants whose local hour gives the phrasing under test.
- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests '*TimeAgoTest*' --tests '*HomePlaylistsTest*' --tests '*CollectionTrackTest*'` — Expected: FAIL.
- [ ] **Step 3: Implement.** `whenPhrase` counts calendar days in `tz` between the two instants (`daysUntil` on `LocalDate`); hour thresholds 12 and 18.
- [ ] **Step 4: Run** the same command, then `./gradlew :shared:iosSimulatorArm64Test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(shared): TimeAgo and Home's playlist rules`.

### Task 3: Jump back in, sections, Ask and the recommendations throttle

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/home/HomeModel.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/home/HomeModelTest.kt`

**Interfaces:**
- Consumes: Task 2's playlist functions; `PlaybackContext(type, name, id)`; `AlbumCatalog.normalizeAlbumTitle` / `normalizeArtistName`; `Track` (`album`, `artist`, `title`, `artworkUrl`, `addedAt`, release MBID field if `Track` has one — else `mbid = null`).
- Produces:
  - `data class ActiveContexts(val playing: PlaybackContext?, val queued: PlaybackContext?)`. Album contexts compare by `name` (album) + `id` (artist), the form `CatalogQueues.album` uses; there's no mbid on mobile's context, so the album match is by name only.
  - `sealed class JumpCandidate { Playlist(id, title) ; Mix(id) ; Loves ; Album(album, artist, mbid?) }` and `fun isActiveContext(c: JumpCandidate, active: ActiveContexts): Boolean` with context types `"playlist"`, `"mix"`, `"loves"`, `"album"`.
  - `data class LoveAlbum(val album: String, val artist: String, val trackTitle: String, val mbid: String?)`, `fun latestLoveAlbum(loves: List<Track>, active: ActiveContexts): LoveAlbum?`
  - `sealed class JumpTile { abstract val subtitle: String; Playlist(playlist), Loves(tracks), Album(LoveAlbum), Mix(HistoryMix) }`, `fun jumpBackInTiles(playlists: List<Playlist>, loves: List<Track>, mixes: List<HistoryMix>, active: ActiveContexts, nowMs: Long): List<JumpTile>` (≤ 4; subtitles verbatim from desktop, including `"Playlist · N new <whenPhraseShort>"`). `HistoryMix` is defined in Task 4 — define it in this task in `home/HistoryMixes.kt` with only its fields (`id`, `name`, `title`, `description`, `imageUrl`, `url`, `resolverId`, `resolverName`, `mosaic: List<String> = emptyList()`) so Task 4 adds the functions.
  - `data class HomeSections(banner, jump, importCard, mixes, playlists, albums, fresh, critical, concerts, ask: Boolean)` and `fun homeSections(...)` with desktop's inputs (lists as `List<*>`, `concertsLocation: String?`, `hasCollection: Boolean`).
  - `fun promptName(name: String?): String`, `fun exampleShufflePrompt(artists: List<String>, seed: Int): String`, `fun homeTopArtists(loves: List<Track>, limit: Int = 3): List<String>`
  - `fun <T> filterBySource(items: List<T>, sourceId: String, resolverId: (T) -> String?, sourceIds: (T) -> List<String>): List<T>` (`"All"` passes through), `fun <T> albumsNeedingArt(albums: List<T>, perSource: Int = 6, resolverId: …, sourceIds: …): List<T>`
  - `const val HOME_RECOMMENDATIONS_THROTTLE_MS = 600_000L`, `fun shouldLoadHomeRecommendations(loading: Boolean, loadStarted: Boolean, lastFullLoadAt: Long?, nowMs: Long): Boolean`, `fun shouldStampHomeRecommendationsLoad(fetched: Int, failed: Int, sectionCount: Int): Boolean`
  - `data class LovesMosaicAlbum(val artist: String, val album: String, val albumArt: String?)`, `fun lovesMosaicAlbums(loves: List<Track>, n: Int = 4): List<LovesMosaicAlbum>`

- [ ] **Step 1: Write the failing tests:** port every `describe` in `home-model.test.js` for the functions above. Add one Kotlin case: `promptName("a".repeat(39) + "🎸")` keeps the whole emoji or drops it, never half (desktop slices by code point; count code points, not `Char`s).
- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests '*HomeModelTest*'` — Expected: FAIL.
- [ ] **Step 3: Implement.** `promptName`'s unsafe class is desktop's `UNSAFE_CHARS` set, matched by code point ranges (U+0000–001F, U+007F–009F, U+061C, U+200B–200F, U+202A–202E, U+2066–2069) without a regex Unicode class. `exampleShufflePrompt` index is `((seed % 4) + 4) % 4`.
- [ ] **Step 4: Run** `./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(shared): Home model — jump back in, sections, Ask prompt, recommendations throttle`.

### Task 4: History mixes and the Recommendations exclusion

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/home/HistoryMixes.kt` (functions)
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/repository/HistoryMixesRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/repository/RecommendationSources.kt` (add `interface HistoryMixSource` and `class PluginHistoryMixSource(pluginManager)`)
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt`, `shared/src/iosMain/kotlin/com/parachord/shared/ios/IosContainer.kt` (bind the repository; explicit `single { }`; cache file `history_mixes_cache.json` via the same read/write helpers as `recommendations_cache.json`; `providerIsOn` = the same gate each platform passes `RecommendationsRepository`)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/home/HistoryMixesTest.kt`, `shared/src/commonTest/kotlin/com/parachord/shared/repository/HistoryMixesRepositoryTest.kt`

**Interfaces:**
- Consumes: `PluginManager.historyMixResolvers(): List<Pair<String,String>>`, `PluginManager.getHistoryMixes(id): String` (throws; `PluginNotReadyException` for notReady), `RecommendationProvider`, `pluginIsOn`, `RecommendationsRepository.excludePlaylistIds(Set<String>)`, `SettingsStore` usernames (as `RecommendationsRepository.accountFor` reads them).
- Produces:
  - `fun normalizeHistoryMixes(itemsJson: String, providerId: String, providerName: String): List<HistoryMix>`, `fun flattenHistoryMixes(entries: List<HistoryMixEntry>): List<HistoryMix>`, `data class HistoryMixEntry(resolverId, resolverName, items: List<HistoryMix>)`, `fun mixesNeedingMosaic(mixes, tried: Set<String>): List<HistoryMix>`, `fun withMixMosaics(mixes, covers: Map<String, List<String>>): List<HistoryMix>`, `const val HOME_MIX_COVER_ATTEMPTS = 3`, `const val HOME_MIX_COVER_RETRY_MS = 60_000L`.
  - `interface HistoryMixSource { suspend fun providers(): List<RecommendationProvider>; suspend fun fetch(providerId: String): String }`.
  - `class HistoryMixesRepository(source, settingsStore, providerIsOn: suspend (RecommendationProvider) -> Boolean, cacheRead, cacheWrite, mosaicCovers: suspend (HistoryMix) -> List<String>, onMixIds: (Set<String>) -> Unit, nowMs = …, scope = …)` with `val mixes: StateFlow<HistoryMixesState>` (`entries: List<HistoryMixEntry>`, `flat: List<HistoryMix>`, `loading: Boolean`), `suspend fun load(force: Boolean = false)`, and `companion { FRESH_MS = 3_600_000; STALE_MS = 604_800_000; PROVIDER_TIMEOUT_MS = 20_000 }`. `mosaicCovers` is the platform's track-cover lookup for a mix URL (the registry `lookupPlaylist`, first 4 distinct covers); `onMixIds` is wired to `RecommendationsRepository.excludePlaylistIds`.

- [ ] **Step 1: Write the failing model tests:** port `home-model.test.js`'s `normalizeHistoryMixes`, `flattenHistoryMixes`, `historyMixCacheEntry`, `historyMixEntriesFor`, `mixesNeedingMosaic`, `withMixMosaics` describes, plus the vectors in `tests/renderer/history-mixes-loader.test.js` that test pure behaviour.
- [ ] **Step 2: Write the failing repository tests** (fake source, fake clock, in-memory cache strings):
  - Fresh cache (< 1 h) for the same account is served without a fetch.
  - 1 h–7 d cache shows at once and is replaced when the fetch answers.
  - An empty answer is stored and not shown (state has no entry for that provider), and a later load fetches again.
  - A thrown failure keeps the cached entry; `PluginNotReadyException` drops it.
  - A cache entry saved for ListenBrainz user "a" isn't shown when the username is "b".
  - Two overlapping `load()` calls: the first finishes last; the state shows the second's entries.
  - A provider whose fetch exceeds 20 s doesn't block the others' entries.
  - A load within 10 min of a finished full load is skipped unless `force` (`shouldLoadHomeRecommendations` / `shouldStampHomeRecommendationsLoad` reused).
  - `onMixIds` receives every mix id after each paint.
  - A mix without `imageUrl` gets `mosaic` from `mosaicCovers`; a throwing lookup is retried after 60 s, at most 3 tries in all.
- [ ] **Step 3: Run** `./gradlew :shared:jvmTest --tests '*HistoryMixes*'` — Expected: FAIL.
- [ ] **Step 4: Implement** the functions and the repository (mirror `RecommendationsRepository`'s mutex / generation / `safely` / `recommendationsBackgroundScope` patterns), `PluginHistoryMixSource`, and the wiring on both apps. Weekly Jams now arrives as a history mix (ListenBrainz's `getHistoryMixes`), so the exclusion takes it off Recommendations' Playlists row while Weekly Exploration stays.
- [ ] **Step 5: Run** `./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test :app:testDebugUnitTest` (includes `KoinModuleVerifyTest`) and `cd iosApp && xcodebuild -scheme Parachord -destination 'platform=iOS Simulator,name=iPhone 17' build` — Expected: PASS / BUILD SUCCEEDED.
- [ ] **Step 6: Commit** `feat(shared): history mixes repository; Recommendations leaves mixes out`.

### Task 5: Setup, getting-started and banner models

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/home/GettingStarted.kt`, `HomeSetup.kt`, `HomeBanners.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/settings/SettingsStore.kt` (`getHomeGettingStarted(): GettingStartedState?`, `setHomeGettingStarted(GettingStartedState)`, `getHomeSetupDismissed(): Set<String>`, `setHomeSetupDismissed(Set<String>)`; keys `home_getting_started`, `home_setup_dismissed`)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/home/GettingStartedTest.kt`, `HomeSetupTest.kt`, `HomeBannersTest.kt`

**Interfaces:**
- Consumes: `PluginManager.PluginInfo` (`id`, `capabilities`, `requiresAuth`, `configFields`), existing `needsAccount(PluginInfo)`, `Announcement` (`AchordionClient.kt:282`).
- Produces:
  - `playsHere(p)`, `stepOnePlugins(plugins): StepOnePlugins(services, free, folder)`, `requiredSetting`, `settingsMissing(p, saved: Map<String,String>?)`, `serviceState(p, accounts: Map<String,Boolean>, setupNeeded: Map<String,Boolean>, playbackResolvers: List<String>, playbackLimits: Map<String, PlaybackLimit>): ServiceState(state: "connected"|"limited"|"connect"|"setup", label: String?)`, `serviceConnected`, `scrobblerConnected(usernames: Collection<String?>)`, `gettingStartedSteps(connected, folderAdded, hasMusic, scrobbling, played): GettingStartedStatus(steps, current, allDone, done)`, `@Serializable data class GettingStartedState(active, played, skipped)`, `fun initialGettingStarted(saved: GettingStartedState?, connectDone: Boolean, musicDone: Boolean): GettingStartedState`, `gettingStartedLeft(state, status)`, `showGettingStarted(state, status)`. `setupMissing`'s `isConfigured` branch maps to `PluginManager.isPluginConfigured` at the call site, not here.
  - `fun homeSetupItems(gettingStartedLeft: Boolean, dismissed: Set<String>): HomeSetup(items, done, total, mode)` — no browser-extension item and no What's New on mobile (spec "What's New (out of scope)"); `mode` is `"setup"` while an item is open, else `"none"`.
  - `data class HomeBannerItem(id, title, body, iconUrl, cta, severity)`, `fun homeBannerItems(announcements: List<Announcement>): List<HomeBannerItem>`, `fun bannerViewId(items): String?`.
- **The one deliberate divergence (spec):** `initialGettingStarted` with no saved state returns `active = !connectDone && !musicDone` (desktop uses its tutorial flag, which mobile lacks).

- [ ] **Step 1: Write the failing tests:** port `getting-started.test.js`, `home-setup.test.js` (minus browser-extension and What's New cases) and `home-banners.test.js` (minus the CSS-token check). Add: no saved state + a collection → inactive; no saved state + nothing connected and no music → active; saved state round-trips through `SettingsStore`.
- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests '*GettingStartedTest*' --tests '*HomeSetupTest*' --tests '*HomeBannersTest*'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :shared:jvmTest` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(shared): Home setup, getting-started and banner models`.

### Task 6: Concerts near you and the in-app chat origin

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/repository/ConcertsRepository.kt` (`ConcertEvent` gains `venueLatitude: Double? = null`, `venueLongitude: Double? = null`, filled from `TmVenue.location` (parse the strings) and `SgVenue.location`; dedupe/merge keeps the first non-null)
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/home/ConcertsNearYou.kt`
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/deeplink/ChatPrompt.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/ai/AiChatService.kt` (`setPendingChatPrompt(prompt, fromApp: Boolean = false)` — the app-origin flag lives only on this call)
- Modify: `app/src/main/java/com/parachord/android/deeplink/DeepLinkViewModel.kt` and iOS's chat link handler: unchanged behaviour for links (still confirm); add `handleInAppChatPrompt(prompt)` (Android) / `IosContainer.askShuffleupagus(prompt:)` (iOS) that queue with `fromApp = true` and open chat without confirmation
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/home/ConcertsNearYouTest.kt`, `shared/src/commonTest/kotlin/com/parachord/shared/deeplink/ChatPromptTest.kt`

**Interfaces:**
- Consumes: `SettingsStore.ConcertLocation(latitude, longitude, city, radiusMiles)`.
- Produces:
  - `fun haversineMiles(lat1, lon1, lat2, lon2): Double`; `fun concertsNearYou(events: List<ConcertEvent>, location: SettingsStore.ConcertLocation?, today: LocalDate, limit: Int = 4): List<ConcertEvent>` — empty when no location (no city and no coordinates); upcoming (`date.take(10) >= today`); within `radiusMiles` by haversine when both the location and the venue have coordinates, else `locCity` (first comma part of `city`, lowercased, trimmed) contains the venue's city or state; soonest first; ≤ 4.
  - `object ChatPrompt { const val MAX_CHARS = 500; fun url(prompt: String?): String; fun shouldSend(pending: String?, chatOpen: Boolean, providerId: String?, loading: Boolean): Boolean; fun shouldDrop(wasOpen: Boolean, chatOpen: Boolean): Boolean }`. `url` caps at 500 UTF-16 units, drops a trailing lone high surrogate, percent-encodes as `encodeURIComponent` does.

- [ ] **Step 1: Write the failing tests:** port `chat-origin.test.js`'s `buildChatPromptUrl`, surrogate boundary, `shouldSendPendingChatPrompt` and `shouldDropPendingChatPrompt` cases (decode with the shared deep-link parser to compare round-trips). For concerts: a past event is dropped; a venue 10 mi away with radius 50 is kept and one 80 mi away dropped; no venue coordinates → city match ("Austin, TX" location keeps a venue city "Austin"); no location → empty; 6 qualifying events → the 4 soonest in date order.
- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests '*ConcertsNearYouTest*' --tests '*ChatPromptTest*'` — Expected: FAIL.
- [ ] **Step 3: Implement**, including the venue coordinates on both mappers and the in-app entry points on both apps. A `parachord://chat?prompt=` URL from outside still goes through `buildConfirmation` (Android) and iOS's confirmation; only the direct call skips it.
- [ ] **Step 4: Run** `./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test :app:testDebugUnitTest` and the iOS `xcodebuild … build` from Task 4 — Expected: PASS / BUILD SUCCEEDED.
- [ ] **Step 5: Commit** `feat(shared): concerts near you and Ask's in-app chat origin`.

### Task 7: Docs

**Files:**
- Modify: `CLAUDE.md` (new sections "Home — the shared model" and "Playlist freshness" and "History mixes"; the Recommendations paragraph's history-mix exclusion sentence; the ListenBrainz Weekly Playlists section: Weekly Jams arrives as a history mix), `iosApp/AGENTS.md` (Home / Recommendations mentions), `README.md` feature list if it describes Home.

- [ ] **Step 1:** `grep -rn -i -E "weekly jams|history mix|recentLoves|tracksChangedAt|home" --include='*.md' . | grep -v docs/` and fix every place that now describes old behaviour; write what is true now (PRs 5 and 6 draw the page; say the screens don't use these rules yet).
- [ ] **Step 2: Commit** `docs: Home's shared model, playlist freshness and history mixes`.
