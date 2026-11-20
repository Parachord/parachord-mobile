# Recommendations: iOS Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace iOS's Recommendations screen (the source picker over Tracks and Artists) with desktop's page, as PR 2 built it for Android. That means:
- Tracks, Albums, Artists, Playlists and Listeners, with a filter pill per source;
- Listener profiles you can follow;
- a playlist view for recommended service playlists.

**Architecture:** Every rule is already shared, from PRs 1 and 2:
- `RecommendationsRepository.page`;
- `RecommendationPageView.kt`, which holds `pageStatus`, `sectionViews`, `rowView`, the skeleton rules, `activeSourceFilter` and `releaseYear`;
- `RecommendationQueues.kt`, `RecommendationCopy.kt` and `RecommendationImages.kt`;
- the profile rules in `FriendsRepository`: `unsavedFriendId` and `profileFriend`.

This PR adds an iosMain bridge, so SwiftUI never handles nested Kotlin types. The bridge flattens the page into simple DTOs that already have those rules applied, and exposes the play, queue and follow actions as `IosContainer` calls. SwiftUI only draws.

**Tech Stack:** Kotlin Multiplatform (`shared/iosMain`, `iosTest`), SwiftUI (iOS 17+, `@Observable`), `FlowWatcher`, a hand-maintained `project.pbxproj`.

**Spec:** `docs/superpowers/specs/2026-11-19-home-and-recommendations-design.md`, sections "Recommendations — the design" → "Listeners" and "The page (both apps)".

**Android reference:** `docs/superpowers/plans/2026-11-19-recommendations-android.md`, plus `app/.../ui/screens/discover/RecommendationsScreen.kt`, `RecommendationCards.kt`, `ui/screens/playlists/ServicePlaylistScreen.kt` and `ui/screens/friends/FriendDetail*.kt`. **Match Android's behaviour exactly.** The user's device rulings on Android carry over:
- a tap on a recommended playlist opens a view;
- "See all" opens a grid that fills the width;
- no Shuffleupagus reason text on cards.

**Branch:** `feat/recommendations-ios`, cut from `feat/recommendations-android`'s head once PR 2's final-review fixes are in, in a new worktree `.worktrees/recs-ios` (copy `local.properties` and `iosApp/Parachord/Secrets.xcconfig` in).

## Global Constraints

- **No rule in Swift.** If SwiftUI needs a decision the shared files don't make, add it to shared commonMain (a test in commonTest), never in Swift. iosMain holds only the bridge: DTO flattening and container calls.
- **Read the current shared API first.** PR 2's final review may have renamed things. Read the shared files before writing the bridge, and use what's there.
- **KMP→Swift rules** (`iosApp/AGENTS.md`):
  - Flatten nested and sealed types to DTOs.
  - No Swift-facing property named `description`, `hash` or `debugDescription`.
  - Primitive `Double` bridges directly.
  - Collect Flows with `FlowWatcher`.
  - Guard bridged suspends: an uncaught exception from a bridged suspend aborts the process, so catch inside Kotlin and return an error value.
- **Project file.** Every new Swift file needs FOUR `project.pbxproj` entries with `AA00…` ids. Verify with `grep -c "Foo.swift" iosApp/Parachord.xcodeproj/project.pbxproj` = 4.
- **Design system.**
  - Components: `PCSectionHeader` (sub size for category rows), `PCFilterChips`, `PCIcon`, `PCServiceLogo`, `PCHexagon`.
  - Colours: `ResolverColor.solid(id)` for every source; `PC.fillAccent` for filled pills and buttons and the Shuffleupagus pill. White text never sits on the theme accent. No `.tint()` / `.accentColor()` globally, and no colour literals.
  - Scales: type 11 · 12 · 13 · 14 · 16 · 20…, 4-point spacing, radius 6 for art and 10 for cards.
- **Copy** comes from `RecommendationCopy`, verbatim, with no Swift string literals for page text.
- **Resolution:** each song row resolves on `.onAppear` through `IosTrackResolverCache.resolve(_:order:)` and is dropped on `.onDisappear` with `leave(artist:title:)`. Never resolve the whole list.
- **Shown cards:** album and artist cards call the bridge's `cardShown` on `.onAppear`, which drives release dates and image fills.
- **Sizes:**
  - Collapsed scrollers: songs 5, cards 12, listeners 12; cards 140 pt wide, hexagon 120 pt.
  - The expanded grid has `max(2, floor((availableWidth + 12) / (104 + 12)))` columns, with cards sized to fill the width.
- **Builds:**
  - xcodebuild with absolute paths only (AGENTS.md "Build / run / screenshot workflow"), simulator `iPhone 17 Pro`, OS 26.5.
  - Shared tests: `./gradlew :shared:iosSimulatorArm64Test` and `:shared:testDebugUnitTest`.
- **Docs in the same PR:**
  - `iosApp/AGENTS.md`: the Recommendations section and the "Where iOS shows these" list near L278;
  - the CLAUDE.md "Recommendations come from the plugins" paragraph (drop "iOS still reads the legacy flows");
  - the README.

## Review Focus

1. **A plugin call that throws while Swift awaits it** (a follow while offline, a weekly lookup failing): the app shows the shared message and doesn't crash. Bridged suspends must not throw into Swift. Pinned by Task 1's `bridge calls return a message instead of throwing` test.
2. **Leaving and reopening the page:** the source pill and the open rows reset (session state), the page shows its cached sections at once, and no second load starts while one runs. Pinned by Task 3's model test notes and Task 6's simulator check.
3. **A Listener already followed elsewhere:** the profile shows the saved friend with no Follow button. The shared `profileFriend` does this; Task 4 must call it rather than `getFriend` + transient fetch. Pinned by Task 4's step.
4. **The legacy flows' removal:** `recommendedTracksFlow`, `recommendedArtistsFlow` and `loadRecommended*` may still have other iOS callers (Concerts' artist seed uses recommended artists). Task 1 greps before removing any, and keeps any that are still used.
5. **Dark mode and Dynamic Type:** cards keep their height with the year line blank, and titles truncate to one line. Task 6's simulator check runs in both appearances.

---

### Task 1: The iosMain bridge — page DTOs and actions

**Files:**
- Create: `shared/src/iosMain/kotlin/com/parachord/shared/ios/IosRecommendationsPage.kt`
- Modify: `shared/src/iosMain/kotlin/com/parachord/shared/ios/IosContainer.kt` (the Recommendations section ~L1946-2030 and L2191-2195)
- Test: `shared/src/iosTest/kotlin/com/parachord/shared/ios/IosRecommendationsPageTest.kt`

**Interfaces:**
- Consumes (shared, from PRs 1–2):
  - `RecommendationPageState`, `pageStatus`, `sectionViews`, `rowView`, `albumsSlotIndex`, `pendingSkeleton`, `listenersSkeleton`, `activeSourceFilter`, `releaseYear`, `upcomingLabel`, `itemSourceName`, `similarityBadge`;
  - `songQueue`, `weeklyContext`, `servicePlaylistQueue`, `servicePlaylistTracks`, `listenerPlay`, `achordionUserUrl`, `showsAchordionLink`, `resolverDisplayName`, `unsavedFriendId`, `profileFriend`.
- Produces (Swift-facing; every class is a plain data class with primitive or list fields):
  - `IosRecPage(status: String, statusText: String?, filteringOne: Boolean, hero: String, sources: List<IosRecSource>, blocks: List<IosRecBlock>)`, where `status` is one of "loading", "notConfigured", "failed", "empty", "content".
  - `IosRecSource(id, name, colorId: String, isShuffleupagus: Boolean)`.
  - `IosRecBlock(kind: String, …)`, where `kind` is one of:
    - "sectionTitle";
    - "row" (`rowKey`, `title`, `subtitle: Boolean`, `type: String`, `toggleLabel: String?`, `expanded`, `items: List<IosRecItem>`, `listeners: List<IosRecListener>`, `loadingTail`, `aiTail: Int`);
    - "personalSkeleton", "albumsSkeleton", "pendingSkeleton", "listenersSkeleton".

    The blocks come in the order Android draws them.
  - `IosRecItem(id, type, name, artist: String?, imageUrl: String?, url: String?, year: String, comingLabel: String?, sourceName: String, weeklyId: String?, weeklyContextType: String?, weeklyLabel: String?, weeklyWeekLabel: String?)`.
  - `IosRecListener(id, name, service, serviceName, avatarUrl: String?, badge: String?, topArtists: String, showsAchordion: Boolean)`.
  - `fun iosRecommendationsPage(state: RecommendationPageState, filter: String?, expanded: Set<String>, today: LocalDate): IosRecPage` (pure).
  - `IosContainer` additions (each suspend catches and returns a value; nothing throws to Swift):
    - `fun watchRecommendationsPage(filter: …)`. A `Flow<IosRecPage>` collecting `recommendationsRepository.page` combined with two `MutableStateFlow`s the container holds, for the filter and the expanded set. Add `setRecommendationsFilter(id: String?)` and `toggleRecommendationsRow(rowKey: String)` (which also calls `openRow` on open). Both reset when `loadRecommendations` is called with `resetSession = true`.
    - `suspend fun loadRecommendations(force: Boolean, resetSession: Boolean)`.
    - `fun recommendationsCardShown(itemId: String)`. It finds the item in the current page and batches it 300 ms into `requestReleaseDates` and the image fill, as Android's VM `cardShown` does.
    - `suspend fun recommendationsSongQueue(rowKey: String, index: Int): CatalogQueue?`, over the row's filtered list.
    - `suspend fun recommendationsWeeklyQueue(weeklyId: String): IosRecQueueResult`.
    - `suspend fun recommendationsServicePlaylist(url: String): IosRecQueueResult`.
    - `suspend fun recommendationsListenerPlay(service: String, username: String): IosRecQueueResult`.
    - `suspend fun followListener(service: String, username: String): String?`: null on success, else the message. On success it reloads with `force = false`.
    - `suspend fun weeklyCovers(weeklyId: String): List<String>` (the first 4 distinct covers).

    `IosRecQueueResult(queue: CatalogQueue?, message: String?)` carries the shared copy (`couldntLoad`, `listenerNoTracks`, `listenerTracksFailed`, `playing`).
  - **Legacy removal.** Grep `iosApp/` for `recommendedTracksFlow`, `recommendedArtistsFlow`, `loadRecommendedTracks` and `loadRecommendedArtists`. Remove the ones only the old screen used. Keep any another screen still calls (e.g. Concerts' artist seed), and list them in the report.

- [ ] **Step 1: Write the failing tests** in `IosRecommendationsPageTest`. Build `RecommendationPageState` values with plain constructors, as Task 1 of the Android plan did.
  - `status follows pageStatus`: loading → "loading"; `NotConfigured` → "notConfigured"; `Message("x")` → "failed" with `statusText` "x"; nothing → "empty".
  - `blocks come in Android's order`: a Songs section, then an Albums section with `albums:new` and `albums:editorial`, with `aiLoading` → `["row:type:song", "sectionTitle:Albums", "row:albums:new", "personalSkeleton", "row:albums:editorial"]`. Render each block as `kind` + `":" + (rowKey ?: title)`.
  - `an album item carries year and coming label and its source name`: `releaseDate "2027-02-03"` with today 2026-11-19 → year "2027", `comingLabel` "Coming Feb 3, 2027"; `sourceName` from the row's sources.
  - `a weekly playlist item carries its weekly fields`.
  - `a listener carries its badge and service name and the Achordion flag`: similarity 0.876 on listenbrainz → "88%", "ListenBrainz", true; on lastfm → false.
  - `the filter and the expanded set apply`: a collapsed song row of 7 → 5 items with "See all"; expanded → 7 with "Show less"; filter "lastfm" keeps only Last.fm items.
  - `bridge calls return a message instead of throwing`: `listenerPlay` with a throwing `topTracks` → `IosRecQueueResult(queue = null, message = listenerTracksFailed(name))`. Test the pure helper that `IosContainer` calls, not the container.
- [ ] **Step 2: Run them to see them fail.** Run: `./gradlew :shared:iosSimulatorArm64Test --tests '*IosRecommendationsPageTest*'`. Expected: compile failure.
- [ ] **Step 3: Implement** the DTOs, `iosRecommendationsPage`, and the container additions. Put any pure helper the container needs, e.g. `recQueueResultOf(...)`, in `IosRecommendationsPage.kt` so it's testable.
- [ ] **Step 4: Run** `./gradlew :shared:iosSimulatorArm64Test --tests '*IosRecommendationsPageTest*' :shared:testDebugUnitTest`. Expected: PASS. Then build the app once so the framework compiles: the xcodebuild command from AGENTS.md, expecting `BUILD SUCCEEDED`. The old Swift screen may need its legacy calls left in place until Task 3.
- [ ] **Step 5: Commit** `feat(ios): Recommendations page bridge`.

### Task 2: SwiftUI cards

**Files:**
- Create: `iosApp/Parachord/RecommendationCards.swift` (+ 4 pbxproj entries)

**Interfaces:**
- Consumes: Task 1's `IosRecItem` and `IosRecListener`; `PCHexagon`, `PCIcon`, `ResolverColor`, `PC` tokens, and the existing art view the app uses for album covers (find it in `DetailScreens.swift` / `Shell.swift`).
- Produces: SwiftUI views, each with `width: CGFloat = 140`:
  - `RecAlbumCard(item:width:onOpen:onPlay:)`;
  - `RecArtistCard(item:width:onOpen:)`;
  - `RecPlaylistCard(item:width:onOpen:)`;
  - `RecWeeklyCard(item:covers:width:onOpen:onPlay:)`;
  - `ListenerCard(listener:width:onOpen:onPlay:)`;
  - `RecCardSkeleton(lines:width:)` and `ListenerCardSkeleton(width:)`.

  Long-press menus go on through `.contextMenu` at the call site (Task 3).

Shapes mirror Android's `RecommendationCards.kt` (read it):
- **Album:** the cover (radius 6) with the "Coming …" pill top-right (`PC.fillAccent`, white 11 pt) and a 32 pt `PC.fillAccent` play disc bottom-right. Then the title, the artist, and the year line, which is always present (non-breaking space when blank).
- **Artist:** a square 6-radius photo (desktop's `artist-card.js`), else its initial.
- **Weekly:** a 2×2 mosaic. One cover fills it, 2–3 repeat, none shows the music placeholder. Then the label and `weekLabel`.
- **Listener:** a `PCHexagon` avatar at 120/140 of the width, else the initial on `ResolverColor.solid(service)`. The badge pill sits at its foot in the service colour (white 11 pt semibold), and a 32 pt play disc at its top-right. Then the name, `serviceName` and `topArtists`: 2 lines, fixed height.

- [ ] **Step 1: Write the views,** with `#Preview`s in light and dark.
- [ ] **Step 2: Build** with the AGENTS.md xcodebuild command. Expected: `BUILD SUCCEEDED`, and the `grep -c` check gives 4.
- [ ] **Step 3: Commit** `feat(ios): Recommendations cards`.

### Task 3: The SwiftUI page

**Files:**
- Rewrite: `iosApp/Parachord/RecommendationsScreen.swift`
- Modify: `iosApp/Parachord/RootView.swift`, `SettingsView.swift` (open one plugin's settings), `HomeScreen.swift` (`PCRoute` / `pcRouteDestination` if new routes are needed)

**Interfaces:**
- Consumes: Task 1 (`watchRecommendationsPage`, the actions); Task 2 (the cards); `PCSectionHeader`, `PCFilterChips`, the existing song row with resolver badges (`ResolverBadgeRow`; find how `HistoryScreen` or `PopOfTheTopsScreen` draws a track row), `IosTrackResolverCache`, and the queue coordinator the app plays queues through (find how the old screen and `PopOfTheTopsScreen` start a queue with a context).
- Produces:
  - **Settings link.** RootView exposes an environment action, `PCOpenPluginSettings` (a closure `(String) -> Void`), that opens Settings with that service's config sheet showing. `SettingsView(initialService:)` selects the `configService` matching the id once, on appear.
  - **Profile route.** `PCRoute.friend(id: unsavedFriendId(service, username), username:, service:, name: username)` for Listener taps.

Page (same structure and copy as Android's `RecommendationsScreen.kt`; read it):
1. **Hero:** `page.hero`, then `RecommendationCopy.SUBLINE`, in the Recommendations page band. The title is the navigation title, as on Android.
2. **Filter:** `PCFilterChips`: All, then `sources`. Shuffleupagus uses `PC.fillAccent`, the others `ResolverColor.solid`. Hidden on "notConfigured".
3. **Body by `status`:**
   - **"loading":** skeleton sections.
   - **"notConfigured":** the title, the body, and the two Connect buttons, ListenBrainz first, each calling `PCOpenPluginSettings`.
   - **"failed":** `FAILED_TITLE`, `statusText`, and Try again.
   - **"empty":** the copy for `filteringOne`, with Try again.
   - **"content":** each block in order:
     - **sectionTitle:** a `PCSectionHeader`.
     - **row:** a header with `toggleLabel`. The body is either:
       - songs: rows with resolution on appear, a tap playing `recommendationsSongQueue`, and a context menu;
       - cards: collapsed, a horizontal `ScrollView`/`LazyHStack` at 140 pt; expanded, a `LazyVGrid` using the column rule.

       Either way, append the tails.
     - **Skeleton blocks:** drawn as skeletons.
4. **Card actions** (each a `.contextMenu` on long-press):
   - **Album:** open `.album`, play via `albumQueue`; menu Play / Add to Queue / Go to Artist.
   - **Artist:** open `.artist`; menu Play Top Songs / Add to Queue.
   - **Weekly:** open `.playlist(id: weeklyId, title:)`, the existing weekly view; play via `recommendationsWeeklyQueue`, with the toast "Playing …".
   - **Service playlist:** open `.servicePlaylist(...)` (Task 5); menu Play / Add to Queue.
   - **Listener:** open the profile; play via `recommendationsListenerPlay`; menu View history / Follow / View on Achordion (when `showsAchordion`, opening `achordionUserUrl` in Safari).
5. **Messages:** any message from a result shows as the app's existing toast (find how other screens show one).

The model is a `@MainActor @Observable final class RecommendationsModel`:
- It holds the `IosRecPage`, starts the watch on appear, and stops it on disappear.
- On appear it calls `loadRecommendations(force: false, resetSession: true)`.
- Pull to refresh calls `force: true`.
- No load runs if one is already running.

- [ ] **Step 1: The settings link.** Build: `BUILD SUCCEEDED`.
- [ ] **Step 2: Rewrite the screen and model.** Delete the old source picker, tabs and `RecommendedTrack` / `RecommendedArtist` views. Build: `BUILD SUCCEEDED`, and the pbxproj count is 4 for any new file.
- [ ] **Step 3: Commit** `feat(ios): desktop's Recommendations page on iOS`.

### Task 4: The friend profile for someone not followed

**Files:**
- Modify: `iosApp/Parachord/FriendProfileScreen.swift`
- Modify: `shared/src/iosMain/kotlin/com/parachord/shared/ios/IosContainer.kt` (expose `profileFriend` and `follow`, if Task 1 didn't)

**Interfaces:**
- Consumes: shared `FriendsRepository.profileFriend(id)`, `follow`, `RecommendationCopy.FOLLOW` / `couldntReach(serviceId)` / `TRY_AGAIN`, `resolverDisplayName`; the recent-tracks hidden-listening reporting Android's Task 3 added (`onHidesListening`).
- Produces:
  - `IosContainer.profileFriend(friendId: String): Friend?`, which catches.
  - `FriendProfileModel` loads through `profileFriend`. It replaces the `getFriend` → `fetchTransientFriendNowPlaying` fallback for the identity; keep the now-playing refresh for saved friends. It exposes `isFollowed` (`friend?.transient == false`) and `follow()`.

The view:
- shows a `PC.fillAccent` Follow button while not followed, in place of the pin;
- hides the pin while not followed;
- shows each tab's error as `couldntReach(service)` with Try again;
- shows the hidden-listening message for an unsaved Last.fm profile, as Android does.

- [ ] **Step 1: Implement.** Build: `BUILD SUCCEEDED`.
- [ ] **Step 2: Commit** `feat(ios): open and follow a Listener you don't follow yet`.

### Task 5: The service playlist view

**Files:**
- Create: `iosApp/Parachord/ServicePlaylistView.swift` (+ 4 pbxproj entries)
- Modify: `iosApp/Parachord/HomeScreen.swift` (`PCRoute.servicePlaylist(url: String, name: String, source: String, image: String?)` and its destination)

**Interfaces:**
- Consumes: `IosContainer.recommendationsServicePlaylist(url)` (Task 1); `IosContainer.importPlaylistFromUrl(url)` (existing, returns `XspfImportResult` with `playlistId`); `RecommendationCopy.recommendedBy` / `SAVE` / `saved` / `couldntSave` / `couldntLoad` / `PLAY`; `PCRoute.savedPlaylist(id:)`.
- Produces: `ServicePlaylistView(url:name:source:image:)`, the same behaviour as Android's `ServicePlaylistScreen`:
  - It opens at once with skeleton rows.
  - The header has the cover the card showed (else a 2×2 mosaic of the first 4 track covers), the name, `recommendedBy(source)`, Play (`PC.fillAccent`) and Save.
  - Rows resolve on appear; a tap plays from that row with context `("playlist", name, url)`.
  - An error shows `couldntLoad(name)` with Try again.
  - **Save:**
    - imports once: the button stays disabled after success and the saved id is remembered, so another tap opens it instead of importing again;
    - shows `saved(name)`, then pushes `.savedPlaylist(id:)`;
    - a failure shows `couldntSave(name)` and re-enables the button.

- [ ] **Step 1: Implement and wire** the Recommendations playlist card's tap to it. Build: `BUILD SUCCEEDED`, with the pbxproj count at 4.
- [ ] **Step 2: Commit** `feat(ios): open a recommended playlist in a playlist view`.

### Task 6: Simulator check and docs

**Files:** `iosApp/AGENTS.md`, `CLAUDE.md`, `README.md`. Fixes found go back into their task's files.

- [ ] **Step 1: Install and launch.** Use the AGENTS.md install and launch commands on `iPhone 17 Pro` (OS 26.5).
  - The simulator shares the Mac's loopback, but Recommendations needs only the user's saved services.
  - If the simulator has no Last.fm or ListenBrainz connection, check the "Connect a music service" state and its two buttons. Connected services can't be signed into without the user, so say what couldn't be checked.
- [ ] **Step 2: Walk the page** in light and dark, taking screenshots with `xcrun simctl io booted screenshot`:
  - the hero, pills and filtering;
  - See all → a grid 3 wide, and Show less;
  - a song plays with the rest queued;
  - an album opens;
  - a weekly card plays;
  - a recommended playlist opens its view, and Save saves once;
  - a Listener opens the unsaved profile, and Follow works.
- [ ] **Step 3: Docs.**
  - `iosApp/AGENTS.md`: describe the page and the bridge (`IosRecommendationsPage.kt`), and update the list near L278.
  - CLAUDE.md: the Recommendations paragraph now says both apps draw the shared page, with no legacy-flow sentence.
  - README feature list: iOS too.
- [ ] **Step 4: Commit** `docs: Recommendations on iOS`, and send the user the screenshots.
