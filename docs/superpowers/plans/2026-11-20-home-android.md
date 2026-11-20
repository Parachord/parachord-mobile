# Home: Android (PR 5 of 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Android's Home with desktop's new Home page. The page is drawn from one shared session, so iOS (PR 6) draws the same state.

**Architecture:**
- A new shared `HomeSession` (`shared/.../home/HomeSession.kt`) gathers everything Home shows into one `HomeState`. It runs the rules PR 4 added (`HomeModel`, `HomePlaylists`, `GettingStarted`, `HomeSetup`, `HomeBanners`, `ConcertsNearYou`) and owns the loading: recommendations and history mixes on a throttle, plus Fresh Drops, Critical Darlings, charts and concerts.
- It reads its inputs through a `HomeSources` interface that each platform implements.
- Android's `HomeViewModel` becomes a thin wrapper around the session.
- `HomeScreen` is rebuilt as small section composables in `ui/screens/home/`.

**Tech Stack:** Kotlin Multiplatform (commonMain), coroutines/Flow, Jetpack Compose + Material 3, Koin.

**Spec:** `docs/superpowers/specs/2026-11-19-home-and-recommendations-design.md`, "Home — the design" → "The page (both apps)".

**Branch:** `feat/home-android`, stacked on `feat/home-shared` (PR #749). Rebase onto main once #749 merges.

**Desktop source of truth:** `git -C /Users/jherskowitz/Development/parachord/parachord-desktop show origin/main:<path>`:
- `src/renderer/components/home-page.js`
- `home-sections.js`
- `home-setup.js`
- `home-getting-started.js`
- `home-banner.js`
- `home-skeletons.js`

**Facts already gathered** (signatures, line numbers, desktop copy): `/private/tmp/claude-501/-Users-jherskowitz-Development-parachord-parachord-android/11673284-423d-472c-b459-e8427afba130/scratchpad/home-android-survey.md`. Copy the file into the SDD workspace at execution start; it's in a scratchpad that can be cleared.

## Global Constraints

- **Desktop copy, verbatim:**
  - "Good morning" (5–11), "Good afternoon" (12–17), "Good evening" (otherwise), using the local hour.
  - "Jump back in", "Made from your listening" / "From the services you've connected".
  - "Your playlists, freshest first" / "By last change, so a playlist that refreshes itself stays up front however old it is".
  - "Albums you might love", "Concerts near you" / "Artists from your collection playing soon", "Ask Shuffleupagus".
  - "Build your collection" / "Import your playlists and loved tracks from the services you use." / "Import".
  - "Play Collection Radio", "Finish setting up · {done} of {total}", "Recent Loves", "Your latest love" / "Your N latest loves".
  - "Tickets", "Venue TBA", "Set up Shuffleupagus".
  - "Couldn’t load Pop of the Tops" / "Check your connection, then try again." / "Try again".
  - The Ask explanation and caption strings from desktop's `home-sections.js`.
- **Counts:**
  - Each row shows 6 cards (`HOME_ROW_CARDS`). Each half shows 3 (`HOME_HALF_ROW_CARDS`). Concerts show 4.
  - Jump back in shows at most 4 tiles in a 2×2 grid on a phone.
  - The Ask prompt is capped at 500 characters.
- **Phone layout:** horizontal rows use `LazyRow` cards 140 dp wide (`RecCardWidth`). The Fresh Drops / Critical Darlings halves stack full width. There is a 16 dp side gutter. Stay on the design system's scales (`docs/DESIGN_SYSTEM.md`): type 11·12·13·14·16·20·24·28·48, 4-point spacing, radius 4·6·8·10·12·16.
- **Colours** come from `DesignTokens` (`Page.HOME`, `Page.FRESH_DROPS`, `Page.CRITICAL_DARLINGS`, `Page.POP_OF_THE_TOPS`, `Page.CONCERTS`) and `ServiceColors`. Use `FILL_ACCENT` for filled buttons. No colour literals.
- **Icons:** use `ParachordIcon` with `PageIcon` / `AppIcon` (desktop's set). No Material icons except the system actions `docs/DESIGN_SYSTEM.md` allows.
- **Resolution stays visibility-scoped.** Any track list uses `ResolveWhileVisible`.
- **Removed from Android Home:** Continue Listening, Recently added, the Discover grid, the Weekly carousels, Friend activity, AI artist suggestions, collection stats, the old Recent loves list, and the `hasLibrary == false` welcome/scan card.
- **Kept:** the Apple Music reauth, Spotify expiry and Spotify reauth banners. They sit above the announcement slot.
- **Shared-code rule:** anything iOS will also need goes in `shared/commonMain`; the app layer only draws. The KMP rules in `CLAUDE.md` apply.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A user with nothing:** no collection, no playlists, nothing connected. They get the getting-started layout, never an empty page or a crash. Covered by `HomeSessionTest` and checked on the device.
2. **A load that fails offline** (recommendations, mixes, Fresh Drops, charts, concerts) keeps the other sections and shows the Pop of the Tops error half. It is never a blank Home. Covered by `HomeSessionTest`.
3. **Ask in the app** goes through `DeepLinkViewModel.handleInAppChatPrompt`, which has no confirmation. A blank field or a 600-character entry sends nothing or a capped prompt respectively. The existing `DeepLinkViewModelChatOriginTest` covers blank and capped prompts. Task 6's field caps its input with `ChatPrompt.cap` and disables Ask while the trimmed text is blank.
4. **Tapping a tile that is already playing:** it isn't offered, because Jump back in excludes active contexts. A played loves or mix context must carry the type the rule checks (`loves`, `mix`). Covered by `HomeSessionTest`'s active-context case.
5. **Returning to Home quickly** doesn't reload recommendations or mixes within 10 minutes. It also doesn't re-seed an Ask field the user has edited. Covered by `HomeSessionTest`'s throttle case and the Ask field state.

---

### Task 1: Shared page helpers

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/home/HomePage.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/home/HomePageTest.kt`

**Interfaces:**
- Consumes: `ConcertEvent` (`repository/ConcertsRepository.kt`, including `ticketSources`, `venueName`, `city`, `state`, `country`, `date`); `CarBrowse` / `CarIds.COLLECTION_RADIO` (`car/CarBrowse.kt`).
- Produces:
  - `fun homeGreeting(hour: Int): String`.
  - `data class HomeHalves(val fresh: Boolean, val critical: Boolean, val charts: Boolean)` and `fun homeHalves(freshCount: Int, freshLoading: Boolean, criticalCount: Int, criticalLoading: Boolean, chartsCount: Int, chartsLoading: Boolean, chartsFailed: Boolean): HomeHalves`. This is desktop's rule:
    - `fresh = freshCount > 0 || freshLoading`
    - `critical = criticalCount > 0 || criticalLoading`
    - `charts = !fresh && (chartsCount > 0 || chartsLoading || chartsFailed)`
  - `fun concertDateBadge(date: String?): String?`. It reads `yyyy-MM-dd` as a local date and returns `"Nov 14"`, or null when the date is invalid.
  - `fun concertPlaceLine(e: ConcertEvent): String`. It joins city, state and country with ", " and omits blanks.
  - `fun concertVenue(e: ConcertEvent): String`. It returns the venue name, or `"Venue TBA"`.
  - `data class TicketLink(val label: String, val url: String, val source: String)` and `fun concertTickets(e: ConcertEvent): List<TicketLink>`. It includes http(s) URLs only. It uses `ticketSources`, else `ticketUrl` with label "Tickets". Duplicate URLs are dropped.
  - `object HomeCopy`. It holds every string in Global Constraints's copy list, plus `fun lovesSubtitle(n: Int): String` and `fun setupPill(done: Int, total: Int): String`.

- [ ] **Step 1: Write the failing tests.**
  - `homeGreeting`: 4→evening, 5→morning, 11→morning, 12→afternoon, 17→afternoon, 18→evening, 0→evening.
  - `homeHalves`: fresh with cards → `(true, *, false)`. Fresh empty and not loading with charts → charts true. Fresh empty, charts failed → charts true. Fresh loading → charts false.
  - `concertDateBadge("2026-11-14") == "Nov 14"`, and `"bad"` → null.
  - `concertPlaceLine` with a blank state → `"Austin, US"`.
  - `concertTickets`: drops `javascript:` and `http:` duplicates, keeps 2 sellers.
  - `HomeCopy.lovesSubtitle(1) == "Your latest love"`, `(3) == "Your 3 latest loves"`.
- [ ] **Step 2: Run** `cd /Users/jherskowitz/Development/parachord/parachord-android/.worktrees/home-android && ./gradlew :shared:testDebugUnitTest --tests '*HomePageTest*'`. Expected: FAIL (unresolved).
- [ ] **Step 3: Implement.** Copy the strings from desktop's components (see the survey's §C). Use the shared design-system copy rules: "…" not "...", and typographic apostrophes where desktop uses them.
- [ ] **Step 4: Run** the same command plus `./gradlew :shared:iosSimulatorArm64Test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(shared): Home page helpers — greeting, halves rule, concert card text, copy`.

### Task 2: Shared `HomeSession`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/home/HomeSession.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/home/HomeSessionTest.kt`

**Interfaces:**
- Consumes: Task 1, and PR 4's rules:
  - `jumpBackInTiles`, `homeSections`, `freshestPlaylists`, `changeBadge`, `playlistSubtitle`, `freshChangeLine`
  - `homeTopArtists`, `exampleShufflePrompt`, `lovesMosaicAlbums`, `filterBySource`, `albumsNeedingArt`
  - `shouldLoadHomeRecommendations`, `shouldStampHomeRecommendationsLoad`
  - `homeBannerItems`, `bannerViewId`, `homeSetupItems`
  - `gettingStartedSteps`, `initialGettingStarted`, `showGettingStarted`, `gettingStartedLeft`, `stepOnePlugins`, `serviceState`
  - `concertsNearYou`, `homeRecommendationAlbums` (`recommendations/RecommendationPage.kt`), `HistoryMixesState`, `recentLoves`

  Also `SettingsStore.get/setHomeGettingStarted`, `get/setHomeSetupDismissed`, `getConcertLocation`.
- Produces:
  - `interface HomeSources`. Each member is one input, as a `Flow` or a suspend call:
    - `playlists: Flow<List<Playlist>>`, `collectionTracks: Flow<List<Track>>`, `hasCollection: Flow<Boolean>`
    - `playingContext: Flow<PlaybackContext?>`, `currentTrack: Flow<Track?>`
    - `announcements: Flow<List<Announcement>>`
    - `historyMixes: StateFlow<HistoryMixesState>` and `suspend fun loadHistoryMixes(force: Boolean)`
    - `recommendationSections: Flow<List<RecommendationPageSection>?>`, `aiAlbums: Flow<List<AiAlbumSuggestion>?>`, `suspend fun loadRecommendations(force: Boolean): HomeRecsLoad`
    - `freshDrops: Flow<HomeFeed<FreshDrop>>`, `criticalDarlings: Flow<HomeFeed<CriticsPickAlbum>>`, `charts: Flow<HomeFeed<ChartAlbum>>`, `suspend fun loadFeeds(force: Boolean)`
    - `concerts: Flow<HomeFeed<ConcertEvent>>`, `suspend fun concertLocation(): SettingsStore.ConcertLocation?`, `suspend fun loadConcerts()`
    - `chatAvailable: Flow<Boolean?>`
    - `plugins: Flow<List<PluginManager.PluginInfo>>`, `accounts: Flow<Map<String, Boolean>>`, `playbackResolvers: Flow<List<String>>`, `folderAdded: Flow<Boolean>`, `scrobblerUsernames: Flow<List<String?>>`
    - `nowMs(): Long`, `hour(): Int`
  - `data class HomeFeed<T>(val items: List<T> = emptyList(), val loading: Boolean = false, val failed: Boolean = false)` and `data class HomeRecsLoad(val fetched: Int, val failed: Int, val sectionCount: Int)`.
  - `class HomeSession(sources: HomeSources, settings: SettingsStore, scope: CoroutineScope)`.
    - `val state: StateFlow<HomeState>`.
    - Functions:
      - `fun onVisible()` (the loads, under the throttle)
      - `fun retryCharts()`
      - `fun setMixSource(id: String)` / `fun setAlbumSource(id: String)` (`"All"` resets)
      - `fun skipSetup()` / `fun resumeSetup()`
      - `fun dismissSetupItem(id: String)`
      - `fun bannerShown(id: String)`
      - `fun reseedAskExample()`
  - `data class HomeState`. Each field is the drawable result:
    - `greeting`, `changeLine: String?`, `hasCollection`, `setup: HomeSetup?`, `banner: HomeBannerItem?`
    - `gettingStarted: GettingStartedView?`. Non-null means the page shows only the getting-started layout plus Explore.
    - `tiles: List<JumpTile>`, `showImportCard: Boolean`
    - `mixRow: List<HomeMixCard>` (the Recent Loves card leads under "All"), `mixSources: List<FilterChipSource>`, `mixSource: String`
    - `playlists: List<HomePlaylistCard>` (playlist, badge, subtitle)
    - `albums: List<RecommendationItem>`, `albumSources: List<FilterChipSource>`, `albumSource: String`, `albumsLoading: Boolean`
    - `halves: HomeHalves`, `fresh: HomeFeed<FreshDrop>`, `critical: HomeFeed<CriticsPickAlbum>`, `charts: HomeFeed<ChartAlbum>`
    - `concerts: List<ConcertEvent>`, `concertsLoading: Boolean`
    - `askExample: String`, `chatAvailable: Boolean?`
    - `sections: HomeSections`
  - The supporting data classes:
    - `GettingStartedView(status, services: List<Pair<PluginInfo, ServiceState>>, free: List<PluginInfo>, folder: PluginInfo?, hasMusic: Boolean)`
    - `HomeMixCard` (sealed: `Loves(tracks, mosaic: List<LovesMosaicAlbum>, subtitle)` or `Mix(mix)`)
    - `HomePlaylistCard(playlist, badge: String?, subtitle: PlaylistSubtitle)`
    - `FilterChipSource(id, label)`
- **Rules the session applies:**
  - `played`: the first non-null `currentTrack` while getting-started is active sets `played = true` and saves it.
  - Getting-started state: `initialGettingStarted(saved, connectDone, musicDone)` the first time, saved then. When every step is done it sets `active = false`.
  - The active check uses `ActiveContexts(playing = playingContext, queued = null)`. Mobile queue entries carry no context; that's the documented divergence.
  - Concerts: `concertsNearYou(...)`, shown only with a location.
  - The Ask example: `exampleShufflePrompt(homeTopArtists(loves), seed)`. The seed is picked once per session (per visit).

- [ ] **Step 1: Write the failing tests** with a fake `HomeSources`, a `TestScope` and a fake clock:
  - Nothing connected and no music: `gettingStarted != null`, `tiles` empty. After `skipSetup()`: `gettingStarted == null`, `setup?.items` contains `getting-started`, and the saved state has `skipped == true`.
  - A collection and a playlist with no saved state: `gettingStarted == null` (the existing-user rule).
  - Loves plus a playing context `{type:"loves"}`: no `JumpTile.Loves`.
  - Two `onVisible()` calls 5 minutes apart call `loadRecommendations` and `loadHistoryMixes` once. At 11 minutes they're called again. If the first load's `HomeRecsLoad` had `failed == fetched`, the second visit loads again.
  - Fresh drops failed and empty with charts failed: `halves.charts == true`, and the other sections still populate.
  - Mixes from two resolvers plus loves: under "All" `mixRow[0]` is `Loves` and `mixSources` has 2 entries. After `setMixSource("listenbrainz")` there is no `Loves` card and only that resolver's mixes show.
  - No concert location: `concerts` empty and `sections.concerts == false`.
- [ ] **Step 2: Run** `./gradlew :shared:testDebugUnitTest --tests '*HomeSessionTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Combine the flows into `state` with `combine` / `stateIn(scope)`.
  - The loads in `onVisible()` run in `scope.launch` with each failure caught and logged, and `CancellationException` rethrown.
  - `scope` must carry a `CoroutineExceptionHandler`; see the CLAUDE.md memory on uncaught exceptions aborting iOS.
- [ ] **Step 4: Run** `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(shared): HomeSession — Home's state and loading in one place for both apps`.

### Task 3: Android sources and `HomeViewModel`

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/home/AndroidHomeSources.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/screens/home/HomeViewModel.kt`. Rewrite it; keep the playlist-delete, sync-sheet and context-menu functions the new screen still uses.
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt`. Bind `AndroidHomeSources` with an explicit `single { }`. Keep `viewModelOf(::HomeViewModel)` only if its constructor has no defaulted parameters; otherwise use an explicit `viewModel { }`.
- Test: `app/src/test/java/com/parachord/android/ui/screens/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2's `HomeSources` / `HomeSession`. The Android data each source maps from:
  - `LibraryRepository` playlists and tracks; `PlaybackStateHolder` and the controller's current context.
  - `AnnouncementsRepository.visibleAnnouncements`; `HistoryMixesRepository`.
  - `RecommendationsRepository` page sections and the `AiShuffleupagusPicks` cached albums.
  - `FreshDropsRepository.getFreshDrops`, `CriticalDarlingsRepository.getCriticsPicks`, `ChartsRepository.getAppleMusicAlbums("us")`.
  - Concerts through `ConcertsRepository.getPersonalizedEvents` with `ConcertArtistSeedSource.gather()`, as `ConcertsViewModel.loadEvents` does. Load only when a location is saved.
  - The chat plugins check `HomeViewModel.checkAiPlugins` uses today; `PluginManager.plugins`.
  - Accounts from the same connection checks `androidPluginIsOn` uses (Spotify token, Apple Music MUT, SoundCloud token).
  - `folderAdded` = local-file tracks exist; scrobbler usernames from `SettingsStore`.
- Produces: `HomeViewModel.state: StateFlow<HomeState>`, plus:
  - `onVisible()`, `retryCharts()`, `setMixSource`, `setAlbumSource`, `skipSetup`, `resumeSetup`, `dismissSetupItem`, `bannerShown`
  - `playCollectionRadio()`. It plays `CarBrowse.playRequest(CarIds.COLLECTION_RADIO)` through `PlaybackController.playQueue(tracks, startIndex, shuffle = true)`, as `PlaybackService` does for the car.
  - `playTile(tile: JumpTile)`, `playMix(mix: HistoryMix)`, `playRecentLoves()`, `queueRecentLoves()`, `playPlaylist(id, name)`, `playAlbum(item: RecommendationItem)`, `playAlbumByName(title, artist)`, `queueAlbumByName(title, artist)`
  - `ask(prompt: String)`. It forwards to `DeepLinkViewModel.handleInAppChatPrompt`; it is passed in from the screen, since that VM is activity-scoped. See Task 4.
  - The existing `toastEvents`, `deletePlaylist`, `getDeletableMirrors`, banner dismissals and `resolveVisible`.
- **Contexts each play sets** (spec): playlist `{type:"playlist", id, name}`; loves `{type:"loves", name:"Recent Loves"}`; album `{type:"album", name, id=artist}` (as `CatalogQueues.album`); mix `{type:"mix", id, name}`. Mix tracks come from `servicePlaylistTracks` over the mix URL's `ResolvedProtocolPlay`.

- [ ] **Step 1: Write the failing test.** `HomeViewModelTest`, with a fake `HomeSources` plus mocked controller pieces. Assert:
  - `playRecentLoves()` calls `playQueue` with the loves and the context `PlaybackContext("loves", "Recent Loves")`;
  - `playCollectionRadio()` calls `playQueue(..., shuffle = true)`;
  - `onVisible()` reaches the session's loads once inside 10 minutes.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*HomeViewModelTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `AndroidHomeSources` and the VM. Delete the VM members only the removed sections used: `weekly*`, `friendActivity`, `collectionStats`, `*Preview`, `aiRecommendations`, `recentAlbums`, `scanProgress` / `hasLibrary`. Keep `WeeklyPlaylistsRepository` in DI; iOS still uses it until PR 6.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest`, which includes `KoinModuleVerifyTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): Home view model on the shared HomeSession`.

### Task 4: Page frame, header, banner and Jump back in

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/home/HomeScreen.kt`. Rewrite it as the frame: hero band, banners, then `LazyColumn` sections.
- Create, in `app/src/main/java/com/parachord/android/ui/screens/home/`:
  - `HomeHeader.kt` (greeting, change line, the "Finish setting up" pill with its sheet, "Play Collection Radio")
  - `HomeJumpBackIn.kt` (2×2 tiles 72 dp tall, art left, round Play on the art, the "Build your collection" card)
- Modify: `app/src/main/java/com/parachord/android/ui/navigation/Navigation.kt` and `app/src/main/java/com/parachord/android/ui/MainActivity.kt`. Add these `HomeScreen` callbacks:
  - `onOpenImportPlaylist` → MainActivity's existing `showImportPlaylistDialog`
  - `onOpenSettings(pluginId: String?)` → `Routes.settings(pluginId)`
  - `onOpenSearch` → `Routes.SEARCH`
  - `onOpenServicePlaylist(url, name, source)` → `Routes.servicePlaylist`
  - `onOpenConcerts` → `Routes.CONCERTS`
  - `onAsk(prompt)` → `deepLinkViewModel.handleInAppChatPrompt(prompt)`
  - `onOpenRecentLoves` → `Routes.collection(<songs tab index>)`, using the index `LibraryScreen` uses for songs

  Remove the callbacks only the deleted sections used: `onNavigateToFriend`, `onListenAlong`, `onNavigateToWeeklyPlaylist`.

**Interfaces:**
- Consumes: Task 3's VM state and functions; `SectionHeader`, `ParachordIcon`, `AlbumArtCard`, `AnnouncementBanner`, and the three kept reauth/expiry banners (moved as-is from today's `HomeScreen`).
- Produces: `HomeScreen(...)` with the callback list above, `HomeHeader(state, ...)` and `HomeJumpBackIn(state, ...)`. Later tasks add sections to `HomeScreen`'s `LazyColumn`.
- **Hero band:**
  - Full width, `Page.HOME` gradient, title "Home" in uppercase, light weight, 0.2em letter-spacing, white.
  - About 64 dp tall on a phone (desktop is 80 px).
  - It replaces the `TopAppBar` title. Keep the drawer menu button on it.
- **Tile behaviour:**
  - The tile body opens: playlist → `onNavigateToPlaylist`; loves → `onOpenRecentLoves`; album → `onNavigateToAlbum`; mix → `onOpenServicePlaylist`.
  - The Play button calls `playTile`.

- [ ] **Step 1:** There's no Compose UI test harness in this module; check with `/usr/bin/grep -rln "createComposeRule" app/src`. If none exists, verify by build and on the device in Step 4. If one exists, add a test that `HomeJumpBackIn` with 3 tiles draws 3 tile titles.
- [ ] **Step 2: Implement** the frame, header, pill sheet, banner slot (one banner at a time; `bannerShown` on first show) and Jump back in. The page waits on nothing: draw from the state, and call `viewModel.onVisible()` from `LaunchedEffect(Unit)` and on each `ON_RESUME`.
- [ ] **Step 3: Build** `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL. Check that the compiler warning paths contain `.worktrees/home-android`.
- [ ] **Step 4: Device check** (Pixel `ANDROID_SERIAL=53111JEBF07835`, adb at `/Users/jherskowitz/Library/Android/sdk/platform-tools/adb`):
  - `./gradlew installDebug`, then `adb shell am force-stop com.parachord.android.debug`, then launch.
  - Take a screenshot showing the hero, greeting, banner (if any) and Jump back in.
  - Don't tap or swipe while the user is using the phone; ask first.
- [ ] **Step 5: Commit** `feat(android): Home frame, header and Jump back in`.

### Task 5: Mixes, playlists and albums rows

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/home/HomeRows.kt`
- Modify: `HomeScreen.kt`. Add the three rows.

**Interfaces:**
- Consumes: Task 3's state (`mixRow`, `mixSources`, `playlists`, `albums`, `albumSources`); `FilterChips`; `RecAlbumCard`; `RecWeeklyCard`-style mosaic drawing (reuse its private `Cover` / mosaic code by making it `internal` in `RecommendationCards.kt` rather than copying it); `releaseYear`; `PlaylistContextMenu`; `AlbumContextMenu`; `DeletePlaylistDialog`; `PlaylistSyncChannelsSheet`.
- Produces: `HomeMixesRow`, `HomePlaylistsRow` and `HomeAlbumsRow` composables.
- **Rules:**
  - **Chips** show only when there are more than 1 source. The colour is `ServiceColors.color(id)`, or `FILL_ACCENT` for Shuffleupagus.
  - **Mixes row** has no See all.
  - **Mix cards:** the subtitle is the description. Tap opens the service playlist; Play calls `playMix`.
  - **Recent Loves card:** a 2×2 mosaic of the 4 newest loved album covers when there are 4, else 1 cover. Its subtitle comes from `HomeCopy.lovesSubtitle`. Tap opens Recent Loves; Play calls `playRecentLoves`.
  - **Playlists row:**
    - Cards carry the top-left "Δ N" badge and a two-line subtitle ("Refreshed …" / "Since YYYY").
    - See all opens Playlists.
    - Long-press keeps today's menu: Play, share, sync and delete with `DeletePlaylistDialog`.
  - **Albums row:**
    - Each card shows its release year.
    - Shuffleupagus picks show a reason badge: a small round mark at the cover's bottom-right. Tapping it shows `reason ?: "Picked by Shuffleupagus"` in a tooltip. The spec lists "reason badges for Shuffleupagus and the critics".
    - Album covers go through the Recommendations repository's `cardShown` / `requestReleaseDates` for the cards on screen.
    - See all opens Recommendations.

- [ ] **Step 1: Build** `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 2: Device check:** install, force-stop and launch. Take a screenshot of each row. Tap a mix card and confirm the service playlist view opens. Ask the user before any tap if they're using the phone.
- [ ] **Step 3: Commit** `feat(android): Home rows — made from your listening, freshest playlists, albums you might love`.

### Task 6: Fresh Drops / Critical Darlings halves, concerts and Ask

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/home/HomeLowerSections.kt`
- Modify: `HomeScreen.kt`. Add the halves, concerts and Ask.

**Interfaces:**
- Consumes:
  - From Task 3's state: `halves`, `fresh`, `critical`, `charts`, `concerts`, `askExample`, `chatAvailable`.
  - Task 1's `concertDateBadge`, `concertPlaceLine`, `concertVenue`, `concertTickets`, `HomeCopy`.
  - The routes `FRESH_DROPS`, `CRITICAL_DARLINGS`, `POP_OF_THE_TOPS` and `CONCERTS`.
- Produces: `HomeHalf(page: DesignTokens.Page, icon: PageIcon, cards, onSeeAll)`, `HomeConcertCard(event)` and `HomeAsk(example, chatAvailable, onAsk, onSetUp)`.
- **Halves:**
  - A page-gradient band with the uppercase title, the page icon and "See all", then 3 album cards.
  - Fresh Drops cards carry the release-type badge, coloured by `DesignTokens.releaseTypeColor`.
  - Critical Darlings cards carry the review badge.
  - A loading half shows `ShimmerAlbumCard` ×3. A failed charts half shows the error copy with "Try again" → `retryCharts()`.
  - The halves stack vertically.
- **Concert card:**
  - The artist photo is 72 dp. The date badge sits bottom-left in `Page.CONCERTS.fill` with white text.
  - Then the artist, the venue and the place line.
  - "Tickets": one link opens with `ACTION_VIEW`. Several open a dropdown listing each seller's label.
  - The card itself isn't clickable.
- **Ask:**
  - The heading has the Shuffleupagus mark, then the explanation text.
  - The `OutlinedTextField` is seeded with the example, shown muted until edited. It's capped at 500 characters with `ChatPrompt.cap`.
  - The button reads "Ask", with the caption below.
  - The field keeps its text across recompositions (`rememberSaveable`). The example replaces the field text only while the user hasn't edited it.
  - With `chatAvailable == false`: the explanation plus a "Set up Shuffleupagus" button → `onOpenSettings(null)`, which opens the plugins.

- [ ] **Step 1: Build** `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 2: Device check:**
  - Install, force-stop and launch. Scroll to the halves, concerts and Ask, and take a screenshot.
  - Type into Ask and tap Ask (with the user's OK). Chat opens with the prompt and no confirmation dialog.
- [ ] **Step 3: Commit** `feat(android): Home halves, concerts near you and Ask Shuffleupagus`.

### Task 7: Getting-started layout

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/home/HomeGettingStarted.kt`
- Modify: `HomeScreen.kt`. While `state.gettingStarted != null`, draw only the hero, banners, this layout and the Explore row.

**Interfaces:**
- Consumes: `GettingStartedView`, `skipSetup`, `playCollectionRadio`, and the callbacks `onOpenSettings(pluginId)`, `onOpenImportPlaylist`, `onOpenSearch`; `HomeHalf` from Task 6 for Explore (Critical Darlings + Pop of the Tops).
- Produces: `HomeGettingStarted(view, ...)`.
- **Copy** (desktop's `home-getting-started.js`, survey §C4):
  - "Welcome to Parachord", the intro line and "Skip setup for now".
  - The three steps' titles and Done / Next / Not yet.
  - Step 1:
    - Group "Services": tiles from `services` with `ServiceState`: "Connected", the limited label, "Add your <label>"/"Set up", or "Connect". Tap → `onOpenSettings(id)`.
    - Group "On already, no account needed": "Your music folder" (`folder`, tap → `onOpenSettings("localfiles")`) plus `free`.
    - The order note, with "Set the order" → `onOpenSettings(null)`. Leave out desktop's "Sound quality": mobile has no such setting.
  - Step 2: "Import a playlist" → `onOpenImportPlaylist`; "Sync your library" → `onOpenSettings(null)`.
  - Step 3: "Play Collection Radio" when there is a collection, and "Search".
  - Leave out desktop's phone note ("Take it with you…"). This is the phone.
  - "Explore while you set up" with its subline.
- The current step is open; any step header can be tapped to open it.

- [ ] **Step 1: Write a test**, if Task 4 found a Compose harness: the layout with a status `current == "music"` opens step 2. Otherwise rely on Task 2's session tests and the device check.
- [ ] **Step 2: Build** `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Device check.** Don't clear the user's data. Ask the user before showing the layout. To show it without wiping anything, temporarily save `home_getting_started` as `{"active":true}` through a debug-only Settings toggle if one exists. Otherwise ask the user to try it on a fresh install, or check in the emulator.
- [ ] **Step 4: Commit** `feat(android): Home's getting-started layout`.

### Task 8: Cleanup and docs

**Files:**
- Delete the composables and helpers in `HomeScreen.kt` / elsewhere that only the removed sections used:
  - `ContinueListeningCard`, `RecentAlbumCard`, `DiscoverGrid`, `DiscoverCard`
  - `WeeklyPlaylistsSection` and its card helpers, `FriendActivityRow`, `HomeMiniPlaybar`
  - `AiSuggestionsSection` and its cards, `NoAiPluginsCard`, `StatsRow`, `StatCard`, `SplashScreen`

  Check each is unused first with `/usr/bin/grep -E -rn`.
- Modify: `CLAUDE.md`:
  - "Home — the shared model": say the Android screen now draws it through `HomeSession`; iOS in PR 6.
  - The ListenBrainz Weekly Playlists section: Android's Home no longer has the Weekly carousel; Weekly Jams is a mix.
  - "Album Context Menus" / "Artist Context Menus": drop the Home screens that no longer exist from the lists.
  - Key Files.
- Modify: `README.md`'s Home feature line.

- [ ] **Step 1: Delete, then run** `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:assembleDebug`. Expected: PASS / BUILD SUCCESSFUL, with no unused-symbol errors.
- [ ] **Step 2: Update the docs.** Find stale mentions with `grep -rn -i -E "Continue Listening|Recently added|Friend activity|Discover grid|weekly carousel|HomeViewModel" --include='*.md' . | grep -v docs/` and fix each.
- [ ] **Step 3: Device check:** a full scroll of Home on the Pixel, plus one Jump back in Play.
- [ ] **Step 4: Commit** `chore(android): remove the old Home sections; docs for the new Home`.
