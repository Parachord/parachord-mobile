# Recommendations: Android Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Android's Recommendations screen (Artists/Songs tabs, hardcoded chips) with desktop's current page: Tracks, Albums, Artists, Playlists and Listeners, a filter pill per source, and Listener profiles you can follow.

**Architecture:** PR #740 already gives the page model (`RecommendationsRepository.page: StateFlow<RecommendationPageState>`). This PR adds two shared files: the rules the screen applies on top of that state (row previews, toggles, tails, page status, desktop's copy) and the queues each card plays. iOS's PR 3 reuses both unchanged. Android gets a thin `RecommendationsViewModel`, a new screen with its cards, and a friend profile that can show someone who isn't followed yet.

**Tech Stack:** Kotlin Multiplatform (`shared/commonMain`, kotlin.test), Jetpack Compose + Material 3, Koin, Coil.

**Spec:** `docs/superpowers/specs/2026-11-19-home-and-recommendations-design.md`, sections "Recommendations — the design" → "Listeners" and "The page (both apps)". PR 1 is #740 (branch `feat/recommendations-shared`). This plan builds on it in `.worktrees/home-recs`, on a new branch `feat/recommendations-android` cut from #740's head (rebase onto main once #740 merges).

## Global Constraints

- All rules (what shows, in what order, with what words) go in `shared/commonMain`; Android only draws and wires. KMP rules from `CLAUDE.md` → "KMP / shared/commonMain rules" (no JVM APIs; no `,` in backtick test names).
- Desktop reference: `Parachord/parachord` `origin/main` at `7acc0759`, `src/renderer/components/recommendations-page.js` and `app.js` (`playListenerTopTracks` ~L30535, `achordionUserUrl` L1354). Where this plan and that code disagree, the code wins.
- Copy is desktop's, verbatim, except: Android has no Amazon Music (`mobile: false`), so the not-configured body drops "sign in to Amazon Music, ".
- Design system: `SectionHeader` (`size` sub for category rows), `FilterChips`, `ParachordIcon`, `serviceColor(id)` for every source colour, Shuffleupagus pill in `FillAccent`. No hardcoded service hexes. Rows scroll sideways (Common Mistakes #7).
- Every song row resolves through `ResolveWhileVisible` + `TrackResolverCache.resolveInBackground(listOf(track))` (visibility-scoped, #177); never submit the whole list.
- Album cards request release dates only once on screen (`RecommendationsRepository.requestReleaseDates`).
- Play actions go through `PlaybackController` with a `PlaybackContext`; nothing plays a source directly.
- Phone preview sizes (my call, recorded as rulings): songs 5 (desktop's `SONG_PREVIEW`), cards 12 per scroller, listeners 12. "See all" / "Show less" turns a scroller into a wrapped grid of everything.
- Docs in the same PR: `CLAUDE.md` "Recommendations come from the plugins" paragraph (its last sentence about "today's screens"), and the README feature list.

## Review Focus

1. **A source pill whose source leaves the page** (its provider stops answering after a refresh): the page falls back to All instead of an empty page. Pinned by Task 1's `activeSourceFilter` tests.
2. **The Listeners row while its lookups are out, with nothing cached:** a hexagon skeleton titled "Listeners", not a blank gap and not "No recommendations yet". Pinned by Task 1's `listenersPending` and `pageStatus` tests.
3. **Shuffleupagus still thinking** when Albums has category rows but no "Based on your listening" row: one skeleton row with that title right after "New for you" (else first), and 3 trailing skeleton cards on `type:album`, `type:artist` and `albums:personal`. This applies under All or the Shuffleupagus pill only. Pinned by Task 1's `sectionView` / `rowView` tests.
4. **Opening a Listener who is already followed** (followed on another device after the row was built): the profile shows the saved friend with no Follow button, not a duplicate. Pinned by Task 3's `profileFriend` test.
5. **A service playlist whose URL no plugin claims, or whose lookup fails:** a "Couldn't load {name}" message and nothing stops. Pinned by Task 2's `servicePlaylistQueue` tests.

---

### Task 1: Shared page-view rules and copy

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/recommendations/RecommendationPageView.kt`
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/recommendations/RecommendationCopy.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/repository/RecommendationPageState.kt` (add `listenersPending`)
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/repository/RecommendationsRepository.kt` (set it)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/recommendations/RecommendationPageViewTest.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/repository/RecommendationsRepositoryTest.kt` (one case)

**Interfaces:**
- Consumes (from #740): `RecommendationPageState`, `RecommendationPageSection`, `RecommendationRow`, `RecommendationItem`, `PageSource`, `ListenerItem`, `filterRowBySource`, `upcomingLabel(date, today)`, `SHUFFLEUPAGUS_ID`, `LISTENERS_ROW_KEY`.
- Produces:
  - `RecommendationCopy` (object). Constants:
    - `TITLE` "Recommendations";
    - `SUBLINE` "From your connected services and Shuffleupagus";
    - `NOT_CONFIGURED_TITLE` "Connect a music service";
    - `NOT_CONFIGURED_BODY` "Get personalized recommendations based on your listening history. Connect Last.fm or ListenBrainz, or enable an AI plug-in for Shuffleupagus suggestions.";
    - `CONNECT_LISTENBRAINZ` "Connect ListenBrainz", `CONNECT_LASTFM` "Connect Last.fm";
    - `FAILED_TITLE` "Couldn’t load recommendations";
    - `EMPTY_TITLE` "No recommendations yet", `EMPTY_BODY` "Your connected services had nothing to suggest. Listen a little more, or try again later.";
    - `EMPTY_SOURCE_TITLE` "Nothing from this source yet", `EMPTY_SOURCE_BODY` "This source has no recommendations right now. Pick All to see the others.";
    - `TRY_AGAIN` "Try again", `SEE_ALL` "See all", `SHOW_LESS` "Show less";
    - `LISTENERS` "Listeners", `ALBUMS` "Albums", `BASED_ON_LISTENING` "Based on your listening";
    - `VIEW_HISTORY` "View history", `FOLLOW` "Follow", `VIEW_ON_ACHORDION` "View on Achordion".

    Functions:
    - `listenerNoTracks(name)` = "$name has no recent top tracks";
    - `listenerTracksFailed(name)` = "Couldn’t load $name’s top tracks. Try again in a moment.";
    - `playing(name)` = "Playing $name";
    - `loading(name)` = "Loading $name…";
    - `couldntLoad(name)` = "Couldn’t load $name";
    - `couldntReach(service)` = "Couldn’t reach $service";
    - `similarityBadge(similarity: Double?): String?` = whole percent "NN%", null when absent.

    The apostrophes are U+2019 as desktop's.
  - In `RecommendationPageView.kt`:
    - Constants: `SONG_PREVIEW = 5`, `CARD_PREVIEW = 12`, `LISTENER_PREVIEW = 12`, `AI_TAIL = 3`, `AI_TAIL_ROWS = setOf("type:album", "type:artist", "albums:personal")`.
    - `fun activeSourceFilter(selected: String?, sources: List<PageSource>): String?` (null = All).
    - `data class RowView(val items: List<RecommendationItem>, val listeners: List<ListenerItem>, val showToggle: Boolean, val toggleLabel: String, val loadingTail: Boolean, val aiTail: Int)`.
    - `fun rowView(row: RecommendationRow, state: RecommendationPageState, filter: String?, expanded: Boolean): RowView`.
    - `data class SectionView(val section: RecommendationPageSection, val rows: List<RecommendationRow>, val personalPendingAt: Int?)`, where `personalPendingAt` is the index in `rows` where the "Based on your listening" skeleton row goes, null for none.
    - `fun sectionViews(state: RecommendationPageState, filter: String?): List<SectionView>`: content sections first, Listeners last, sections with no visible rows dropped.
    - `fun albumsSkeletonSlot(state: RecommendationPageState, filter: String?): Boolean`: desktop's `llmPlaceholder`.
    - `sealed class PageStatus { Loading; NotConfigured; Failed(val text: String); Empty(val filteringOne: Boolean); Content }` and `fun pageStatus(state: RecommendationPageState, filter: String?): PageStatus`.
    - `fun releaseYear(date: String?): String` (first 4 chars, else "").
    - `fun shuffleupagusReason(item: RecommendationItem): String?`: the reason when Shuffleupagus sent the item (`resolverId` or `sourceIds`) and `reason` isn't blank.
    - `fun itemSourceName(item: RecommendationItem, row: RecommendationRow): String`: the name in `row.sources` for `item.resolverId`, else "".
  - `RecommendationPageState.listenersPending: Boolean = false`.

The rules are a port of desktop's `renderSubsection`, `renderTypeSection` and the page's state chain (recommendations-page.js L597–L800). Port the conditions exactly: `showToggle = expanded || items.size > preview || row.canLoadMore`; `loadingTail` uses `state.loadingMore` + `state.paging[...].lastPageTypes` / `lastPageCategories` (with `"<type>:<category>"` for a category row); `aiTail` applies only with `state.aiLoading`, a row key in `AI_TAIL_ROWS`, and the filter null or `SHUFFLEUPAGUS_ID`; a row stays visible with no items while a page for it is loading under the filter. Collapsed `items` are the first `preview` of the filtered list; expanded are all. Listeners filter by `resolverId` / `sourceIds` like items. Desktop's `filteringOne` = filter non-null.

- [ ] **Step 1: Write the failing tests** in `RecommendationPageViewTest` (build rows with plain constructors):
  - `activeSourceFilter keeps a source on the page — falls back to All when it left`: `activeSourceFilter("lastfm", listOf(PageSource("lastfm","Last.fm")))` == "lastfm"; `activeSourceFilter("lastfm", listOf(PageSource("applemusic","Apple Music")))` == null; `activeSourceFilter(null, …)` == null.
  - `a song row previews 5 — See all when more`: a `type:song` row of 7 → `items.size == 5`, `showToggle`, `toggleLabel == "See all"`; expanded → 7 items, `"Show less"`.
  - `a card row with 3 items and no more pages has no toggle`: `showToggle == false`.
  - `canLoadMore shows the toggle even under the preview`.
  - `the filter keeps only that source's items`: items from `lastfm` and `listenbrainz`, filter `listenbrainz` → only those.
  - `loading tail only while a page for the row is in flight — and only if the last page brought its type`: `loadingMore = {"k"}`, `paging["k"].lastPageTypes = [SONG]` on a `type:album` expanded row with `pageable` key `k` → false; with `[ALBUM]` → true; collapsed → false.
  - `Shuffleupagus tail — 3 cards on album and artist rows under All or its pill only`: `aiLoading = true`, `type:album` → 3; filter `lastfm` → 0; filter `shuffleupagus` → 3; `type:song` → 0.
  - `personal skeleton goes after New for you in a categorized Albums section`: rows `albums:new`, `albums:editorial`, `aiLoading` → `personalPendingAt == 1`; no `albums:new` → 0; an existing `albums:personal` row → null; no category rows → null.
  - `albums skeleton slot when Shuffleupagus thinks and nothing album-like is on the page`: `aiLoading`, sections only songs → true; with a `type:album` section → false; filter `lastfm` → false.
  - `sections — content first, Listeners last, empty sections dropped`.
  - `pageStatus order`: `loading` with no sections → `Loading`; `error = NotConfigured` → `NotConfigured`; `error = Message("x")` → `Failed("x")`; no sections, not loading, no pending, not `listenersPending` → `Empty(false)`; same with filter "lastfm" → `Empty(true)`; `listenersPending` with nothing else → `Content`; sections present → `Content`.
  - `releaseYear`: "2026-11-21" → "2026"; null → "".
  - `shuffleupagusReason only for Shuffleupagus picks`: `resolverId = "shuffleupagus", reason = "peak"` → "peak"; `resolverId = "lastfm", reason = "x"` → null; `sourceIds = ["lastfm","shuffleupagus"], reason = "y"` → "y".
  - `similarityBadge rounds to a whole percent`: 0.876 → "88%", null → null.
- [ ] **Step 2: Add the repository case** in `RecommendationsRepositoryTest`: `listenersPending is true while similar users are fetched — false after`. Use the existing fakes with a similar-users provider whose lookup suspends on a `CompletableDeferred`; assert `page.value.listenersPending` is true during the lookup and false after it completes. It also ends false when the lookup throws.
- [ ] **Step 3: Run them to verify they fail.** Run: `./gradlew :shared:testDebugUnitTest --tests '*RecommendationPageViewTest*' --tests '*RecommendationsRepositoryTest*'`. Expected: compile failure (unresolved references).
- [ ] **Step 4: Implement** `RecommendationCopy.kt` and `RecommendationPageView.kt` with the signatures above. In `RecommendationsRepository.loadProviders`:
  - Set `listenersPending = true` in the same `_page.update` that sets `pendingProviders`, when any similar-users provider will be asked and the page has no Listeners section.
  - Set it false after the similar-users `launch` finishes, success or failure, only for the latest generation.
  - Also false wherever `pendingProviders` is cleared (L176, L368, L437).
- [ ] **Step 5: Run to verify they pass**, then the iOS run for the shared file: `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test --tests '*RecommendationPageViewTest*'`. Expected: PASS.
- [ ] **Step 6: Commit** `feat(recommendations): shared page-view rules and desktop's copy`.

### Task 2: Shared play queues for the page's cards

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/recommendations/RecommendationQueues.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/playlist/PlaylistUrlImporter.kt` (make the `ProtocolTrack` → `Track` mapping reusable)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/recommendations/RecommendationQueuesTest.kt`

**Interfaces:**
- Consumes: `CatalogQueue(tracks: List<Track>, context: PlaybackContext)` (`shared/.../playback/CatalogQueues.kt`), `PlaybackContext(type, name, id)`, `ResolvedProtocolPlay`, `ProviderPlaylistResolver.resolve(url)`, `FriendsRepository.listenerTopTracks(service, username): List<Track>` (throws on failure), `trackSlug`, `WeeklyInfo`.
- Produces:
  - `fun recommendedSongTrack(item: RecommendationItem): Track?`. It's null unless a song with an artist. Its fields:
    - `id = "rec-" + trackSlug(artist, name)`;
    - `duration` in ms (seconds × 1000, null when ≤ 0);
    - `recordingMbid` (valid MBIDs only) and `isrc` (`validateIsrc`);
    - `artworkUrl = imageUrl`.
  - `fun songQueue(rowItems: List<RecommendationItem>, index: Int, row: RecommendationRow): CatalogQueue?`: the tapped song and everything after it in the **filtered** list, with context `PlaybackContext("recommendations", row.title ?: "Recommendations", row.key)`.
  - `fun weeklyContext(weekly: WeeklyInfo): PlaybackContext` = `PlaybackContext(weekly.contextType, "${weekly.weekLabel}'s ${weekly.label}", weekly.id)`.
  - `suspend fun servicePlaylistQueue(item: RecommendationItem, resolve: suspend (String) -> ResolvedProtocolPlay?): CatalogQueue?`: null when `item.url` is null, when resolve returns null or no tracks, or when it throws (not a `CancellationException`). Context `PlaybackContext("playlist", item.name, item.url)`. Tracks come from the importer's mapping (`protocolPlayTrack`, below).
  - `fun protocolPlayTrack(t: ProtocolTrack, provider: String, index: Int): Track`: the renamed, now public `importedTrack`, with its callers updated.
  - `sealed class ListenerPlay { data class Play(val queue: CatalogQueue); data class Message(val text: String) }` and `suspend fun listenerPlay(service: String, username: String, name: String, topTracks: suspend (String, String) -> List<Track>): ListenerPlay`:
    - tracks → `Play` with context `PlaybackContext("friend", name, "$service:$username")`;
    - empty → `Message(RecommendationCopy.listenerNoTracks(name))`;
    - throws → `Message(RecommendationCopy.listenerTracksFailed(name))`.
  - `fun achordionUserUrl(username: String): String` = `"https://achordion.xyz/user/" + encodeURLPathPart(username)` (Ktor's `encodeURLPathPart`, matching desktop's `encodeURIComponent` for a path segment).

- [ ] **Step 1: Write the failing tests:**
  - `recommendedSongTrack converts seconds to ms and keeps only valid ids`: duration 215.0 → 215000; mbid "not-an-mbid" → null; a valid ISRC with lowercase letters → uppercased; artist null → returns null.
  - `songQueue starts at the tapped song of the filtered list`: 4 songs, index 2 → 2 tracks; context `("recommendations", "For you", "type:song")` for a row titled "For you"; untitled row → name "Recommendations".
  - `weeklyContext names the week`: `WeeklyInfo("abc","weekly-jam","Weekly Jams","Week of Nov 17")` → `PlaybackContext("weekly-jam", "Week of Nov 17's Weekly Jams", "abc")`.
  - `servicePlaylistQueue — no url, no tracks, a throw and null all give null`, and `— tracks keep their order with a playlist context`.
  - `listenerPlay — tracks play — none and failures say why`: three cases with the exact copy above.
  - `achordionUserUrl escapes the name`: "a b/c" → "https://achordion.xyz/user/a%20b%2Fc".
- [ ] **Step 2: Run to verify they fail.** `./gradlew :shared:testDebugUnitTest --tests '*RecommendationQueuesTest*'` → compile failure.
- [ ] **Step 3: Implement** the signatures above. The `importedTrack` rename keeps `PlaylistUrlImporterTest` green.
- [ ] **Step 4: Run** `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test --tests '*RecommendationQueuesTest*' --tests '*PlaylistUrlImporter*'`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(recommendations): shared queues for songs, weeklies, service playlists and listeners`.

### Task 3: A friend profile for someone not followed, with Follow

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/repository/FriendsRepository.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/screens/friends/FriendDetailViewModel.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/screens/friends/FriendDetailScreen.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/repository/FriendsUnsavedProfileTest.kt` (new; follow the setup of the existing `FriendsListenAlongPollTest`)

**Interfaces:**
- Consumes: `FriendsRepository.follow(service, username): Resource<Friend>`, `getFriendById`, `getFriendTopTracks/Albums/Artists(username, service, period)`, `getFriendRecentTracks(username, service)`.
- Produces:
  - `fun unsavedFriendId(service: String, username: String): String` = `"transient:$service:$username"`, and `fun parseUnsavedFriendId(id: String): Pair<String, String>?`. The username may contain `:`; split on the first two only.
  - `suspend fun FriendsRepository.profileFriend(id: String): Friend?`:
    - the saved friend for a saved id;
    - for a transient id, the saved friend with the same service and username (case-insensitive) when one exists;
    - else an unsaved `Friend` with `id = unsavedFriendId(…)`, `displayName = username`, no cached track, `transient = true`;
    - null for an unparseable id.
  - Android route: Listener cards navigate to `Routes.friendDetail(unsavedFriendId(service, username))` (existing `FRIEND_DETAIL`).

- [ ] **Step 1: Write the failing tests:**
  - `unsaved ids round-trip — a colon in the name survives`: `parseUnsavedFriendId(unsavedFriendId("lastfm","a:b"))` == ("lastfm","a:b"); `parseUnsavedFriendId("friend-1")` == null.
  - `profileFriend builds an unsaved friend`: `transient == true`, `username`, `service`, `displayName`.
  - `profileFriend returns the saved friend when already followed`: a saved "ListenBrainz/Alice" and id `transient:listenbrainz:alice` → the saved row (its id, `transient == false`).
- [ ] **Step 2: Run to verify they fail** (`:shared:testDebugUnitTest --tests '*FriendsUnsavedProfileTest*'`).
- [ ] **Step 3: Implement** the shared functions.
- [ ] **Step 4: Android profile.**
  - `FriendDetailViewModel` loads through `profileFriend(friendId)` and exposes `isFollowed: StateFlow<Boolean>` (`!friend.transient`).
  - `fun follow()` calls `friendsRepository.follow(service, username)`. On `Resource.Success` it swaps `_friend` to the saved friend; on `Resource.Error` it emits the message on a `messages: SharedFlow<String>` the screen shows as a Toast.
  - `togglePin` does nothing while unsaved.
  - `FriendDetailScreen`:
    - shows a filled `Follow` button (`RecommendationCopy.FOLLOW`, `FillAccent`, white text) in the header while not followed, in place of the pin control;
    - every tab's `ErrorState` shows `RecommendationCopy.couldntReach(<service display name: "Last.fm" / "ListenBrainz">)` with a Try again button (`RecommendationCopy.TRY_AGAIN`) that reloads that tab. Add a `retryTab(tab)` VM function if the tab's loader has none.
- [ ] **Step 5: Run** `./gradlew :shared:testDebugUnitTest --tests '*FriendsUnsavedProfileTest*' :app:compileDebugKotlin`. Expected: PASS, and the build compiles.
- [ ] **Step 6: Commit** `feat(friends): open and follow someone you don't follow yet`.

### Task 4: Android view model

**Files:**
- Rewrite: `app/src/main/java/com/parachord/android/ui/screens/discover/RecommendationsViewModel.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (the `viewModelOf` stays if the constructor resolves; otherwise an explicit `viewModel { … }`, per Common Mistakes #37)

**Interfaces:**
- Consumes: Task 1 (`activeSourceFilter`), Task 2 (all queue functions), `RecommendationsRepository` (`page`, `messages`, `load(force)`, `openRow(rowKey)`, `requestReleaseDates(items)`), `CatalogQueues`, `PlaybackController.playQueue(tracks, startIndex, context)` / `addToQueue`, `TrackResolverCache`, `WeeklyPlaylistsRepository.loadPlaylistTracks(id)`, the Koin-bound `ProviderPlaylistResolver`, `FriendsRepository.listenerTopTracks`, `LibraryRepository` (artist collection toggle).
- Produces (for Task 6):
  - `val page: StateFlow<RecommendationPageState>`;
  - `val sourceFilter: StateFlow<String?>`, the raw selection (the screen applies `activeSourceFilter`), and `fun setSourceFilter(id: String?)`;
  - `val expandedRows: StateFlow<Set<String>>` and `fun toggleRow(rowKey: String)`. Opening also calls `repository.openRow(rowKey)` in `viewModelScope`; closing only removes the key;
  - `val messages: SharedFlow<String>`: the repository's messages merged with this VM's own;
  - `fun refreshIfStale()` (calls `load(false)`) and `fun retry()` (calls `load(true)`). `init` calls `load(false)`;
  - `fun albumShown(item: RecommendationItem)`: batches album items for 300 ms, then calls `requestReleaseDates`;
  - `fun resolveVisible(track: Track): Job` (`trackResolverCache.resolveInBackground(listOf(track))`);
  - `val trackResolvers` and `trackResolverConfidences` from the cache;
  - `fun playSong(rowItems: List<RecommendationItem>, index: Int, row: RecommendationRow)`;
  - `fun playAlbum(item)` / `queueAlbum(item)` through `CatalogQueues.album(item.name, item.artist)`. These emit `RecommendationCopy.loading(name)` first and `couldntLoad(name)` on null;
  - `fun playArtistTopSongs(name)` / `queueArtistTopSongs(name)` / `toggleArtistCollection(name, imageUrl, inCollection)`. Keep today's bodies;
  - `fun playWeekly(item)` (`loadPlaylistTracks` then `playQueue` with `weeklyContext`, then emits `playing(name)`), and `fun weeklyCovers(id: String): StateFlow<List<String>>`, the first 4 distinct album covers of that week's tracks, loaded once per id;
  - `fun playServicePlaylist(item)` / `queueServicePlaylist(item)` (`servicePlaylistQueue`; null → `couldntLoad(item.name)`);
  - `fun playListener(item: ListenerItem)` (`listenerPlay`; `Message` → emit it);
  - `fun followListener(item: ListenerItem)` (`FriendsRepository.follow`, then `load(false)` so the row drops them; errors emitted).

- [ ] **Step 1: Rewrite the VM** with the members above. Remove the old `_allTracks` / `_allArtists` / `SourceCounts` code and its legacy repository calls; nothing on Android uses `getRecommendedTracks` / `getRecommendedArtists` after this (Home doesn't — confirm with `grep -rn "getRecommended" app/src/main`). Leave the repository's legacy flows alone: iOS still reads them until PR 3.
- [ ] **Step 2: Verify** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests '*KoinModuleVerifyTest*'`. Expected: compiles, and the Koin graph resolves.
- [ ] **Step 3: Commit** `feat(recommendations): Android view model over the shared page`.

### Task 5: Android cards and the shared hexagon

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/components/HexagonShape.kt` (moved from `LibraryScreen.kt` L517 and `HomeScreen.kt` L1241, which then import it)
- Create: `app/src/main/java/com/parachord/android/ui/screens/discover/RecommendationCards.kt`

**Interfaces:**
- Consumes: Task 1 (`releaseYear`, `upcomingLabel`, `shuffleupagusReason`, `itemSourceName`, `RecommendationCopy.similarityBadge`), `AlbumArtCard`, `ParachordIcon`, `serviceColor`, `FillAccent`, `ShimmerAlbumCard`, `hapticCombinedClickable`.
- Produces (stateless composables, all `modifier: Modifier = Modifier`):
  - `RecAlbumCard(item, year: String, comingLabel: String?, reason: String?, onOpen, onPlay, onLongPress)`;
  - `RecArtistCard(item, imageUrl: String?, reason: String?, onOpen, onLongPress)`;
  - `RecPlaylistCard(item, sourceName: String, onOpen, onLongPress)`;
  - `RecWeeklyCard(item, covers: List<String>, onOpen, onPlay)`;
  - `ListenerCard(item: ListenerItem, serviceName: String, onOpen, onPlay, onLongPress)`;
  - `RecCardSkeleton(lines: Int)` and `ListenerCardSkeleton()`.

Card shapes:
- **Width:** 140 dp for every card.
- **Album:** cover 140 dp, radius 6; title, artist, then the year line (always present, `" "` when blank). A "Coming …" pill over the cover's bottom-left in `FillAccent`, white 11 sp text. A reason badge (11 sp, `FillAccent` tint) under the year when `reason != null`. A round play button on the cover.
- **Weekly:** a 2×2 mosaic of `covers` (one cover fills it; none: a `ParachordIcon` placeholder) and `weekly.label` with `weekly.weekLabel` as the second line.
- **Playlist:** image and name, with `sourceName` as the second line.
- **Listener:** a hexagon avatar 120 dp (the initial on `serviceColor(service)` when no URL). The `similarityBadge` pill sits over its foot in `serviceColor(item.service)` with white 11 sp / 600 text. Then the name, `serviceName` (12 sp secondary), and `topArtists.joinToString(", ")` in 12 sp tertiary, maxLines 2, a fixed 2-line height.
- **Skeleton lines:** album 3, playlist 2, artist 1.

- [ ] **Step 1: Move `HexagonShape`** to the component, with both screens importing it. Verify `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 2: Write the cards** to the signatures above, with `@Preview`s for each card in light and dark.
- [ ] **Step 3: Verify** `./gradlew :app:compileDebugKotlin`. Expected: compiles.
- [ ] **Step 4: Commit** `feat(recommendations): Android cards`.

### Task 6: The Android page, navigation and docs

**Files:**
- Rewrite: `app/src/main/java/com/parachord/android/ui/screens/discover/RecommendationsScreen.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/navigation/Navigation.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/screens/settings/SettingsScreen.kt`
- Modify: `CLAUDE.md`, `README.md`

**Interfaces:**
- Consumes: Tasks 1, 4 and 5; `SectionHeader`, `FilterChips`, `TrackRow`, `ResolveWhileVisible`, `AlbumContextMenu`, `ArtistContextMenu`, `TrackContextMenuHost`.
- Produces:
  - Route `Routes.SETTINGS_WITH_PLUGIN = "settings?plugin={plugin}"` with `fun settings(pluginId: String?)`;
  - a `SettingsScreen(initialPluginId: String? = null)` parameter that opens that plugin's sheet once on first composition. Home's PRs reuse it.

Page layout, top to bottom:
1. **Hero.** `RecommendationCopy.TITLE`, then `page.hero`, then `SUBLINE`, in the Recommendations page band.
2. **Filter bar.** `FilterChips`: "All", then one chip per `page.sources`, in that order, with no counts. Shuffleupagus uses `FillAccent`, the others `serviceColor(id)`. Hidden on `NotConfigured`.
3. **Body by `pageStatus(page, filter)`** (`filter = activeSourceFilter(selected, page.sources)`):
   - **Loading:** skeleton sections.
   - **NotConfigured:** the `NOT_CONFIGURED_*` empty state with two buttons, ListenBrainz first, each navigating to `Routes.settings("listenbrainz" | "lastfm")`.
   - **Failed:** `FAILED_TITLE`, its `text`, and Try again → `retry()`.
   - **Empty:** `EMPTY_*` or `EMPTY_SOURCE_*` per `filteringOne`, with Try again.
   - **Content:** see below.

Content, for each `sectionViews(...)`:
- **Section title.** A `SectionHeader` with the section title when the section has category rows. A section without category rows shows only its one row, whose header is the row title.
- **Each row:**
  - A header: `SectionHeader(title = row.title ?: section.title, size = if (row.category != null) sub else section)`, with the `rowView.toggleLabel` action when `showToggle`.
  - **Songs:** the visible `TrackRow`s (each in `ResolveWhileVisible`, tap → `playSong(filteredItems, index, row)`, long-press via `TrackContextMenuHost`), plus 5 skeleton rows when `loadingTail`.
  - **Cards:** collapsed → a `LazyRow` of cards; expanded → a wrapped grid (`FlowRow`, 140 dp cards). Either way, plus `loadingTail` skeleton cards and `aiTail` skeleton cards.
  - Insert the "Based on your listening" skeleton row at `personalPendingAt`.
- **Card actions:**
  - Album: open `Routes.album`, play `playAlbum`, long-press `AlbumContextMenu` (play, queue, go to artist, collection).
  - Artist: open `Routes.artist`, long-press `ArtistContextMenu`.
  - Weekly: open `Routes.weeklyPlaylist(id, contextType)`, play `playWeekly`.
  - Playlist: tap → `playServicePlaylist`; long-press menu Play / Add to Queue.
  - Listener: open `Routes.friendDetail(unsavedFriendId(service, name))`, play `playListener`, long-press menu View history / Follow / View on Achordion (ListenBrainz only, opening `achordionUserUrl` with `ACTION_VIEW`).
- **Album release dates:** each album card calls `vm.albumShown(item)` in a `LaunchedEffect(item.id)`.
- **Skeletons below the content:**
  - The Albums skeleton section when `albumsSkeletonSlot`, placed after Songs (desktop's `llmSlot` placement).
  - A pending skeleton section when `page.pendingProviders` isn't empty and the filter is All.
  - A Listeners skeleton (title `LISTENERS`, 6 `ListenerCardSkeleton`s) when `page.listenersPending` and there's no Listeners section.

Behaviour:
- `messages` show as a Toast.
- `refreshIfStale()` runs on `ON_RESUME`, as today.
- The "now playing" highlight on a song row: `playbackContext.type == "recommendations" && playbackContext.id == row.key && currentTrack.id == track.id`.

- [ ] **Step 1: Add the settings route** and `initialPluginId` (opening `selectedPlugin` for that id in a `LaunchedEffect(Unit)`). Verify `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 2: Rewrite `RecommendationsScreen`** as above, deleting `SourceFilterBar`, `ArtistsTab`, `SongsTab`, `RecommendedArtistItem` and their hardcoded colours. Verify `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 3: Update docs.**
  - In `CLAUDE.md`'s "Recommendations come from the plugins" paragraph, replace the "**Today's screens**" sentence: Android draws the shared page, `RecommendationPageView.kt` and `RecommendationQueues.kt` hold the page's rules and queues, and iOS still reads the legacy flows until PR 3.
  - Add a line to the "Friend Follow / Unfollow" section: a Listener opens as an unsaved profile (`unsavedFriendId`, `profileFriend`) with Follow.
  - README feature list: Recommendations gains Tracks, Albums, Artists, Playlists and Listeners, with a filter per source.
- [ ] **Step 4: Commit** `feat(recommendations): desktop's Recommendations page on Android`.

### Task 6b: Open a recommended service playlist in a playlist view

Added after the user's device check: tapping a recommended playlist played it, and desktop opens it in its playlist view (`openServicePlaylist`, desktop `app.js` ~L35564). This supersedes Task 6's "Playlist: tap plays". Home's history mixes (PR 5) will open the same view.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/recommendations/RecommendationCopy.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/recommendations/RecommendationQueues.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/recommendations/RecommendationQueuesTest.kt`
- Create: `app/src/main/java/com/parachord/android/ui/screens/playlists/ServicePlaylistScreen.kt`
- Create: `app/src/main/java/com/parachord/android/ui/screens/playlists/ServicePlaylistViewModel.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/navigation/Navigation.kt`, `RecommendationsScreen.kt`, `AndroidModule.kt`

**Interfaces:**
- Consumes: `servicePlaylistQueue(item, resolve)`, `itemSourceName`, `PlaylistImportManager.importFromUrl(url)`, `ResolveWhileVisible`, `TrackRow`, `TrackContextMenuHost`.
- Produces:
  - Shared:
    - `RecommendationCopy.recommendedBy(sourceName: String)` = "Recommended by $sourceName", or "Recommended by the service" when blank (desktop: `sourceName || 'the service'`).
    - `RecommendationCopy.SAVE` "Save" and `RecommendationCopy.saved(name)` = "Saved $name".
    - `fun servicePlaylistTracks(play: ResolvedProtocolPlay): List<Track>`, so the view and `servicePlaylistQueue` map tracks one way.
  - Route: `Routes.SERVICE_PLAYLIST = "service_playlist?url={url}&name={name}&source={source}&image={image}"`, with `fun servicePlaylist(url: String, name: String, sourceName: String, imageUrl: String?)` (each value `Uri.encode`d).
  - `ServicePlaylistViewModel(savedStateHandle, …)`: `title`, `description` (`recommendedBy(source)`), `coverUrl`, `tracks: StateFlow<Resource<List<Track>>>` (Loading until the lookup lands, `Error(couldntLoad(name))` on null or failure), `playFrom(index)` (context `PlaybackContext("playlist", name, url)`), `save()` (imports through `PlaylistImportManager`, then emits a navigate-to-saved-playlist event with the new id), `resolveVisible(track)`, `retry()`.

Screen, in the same shape as `WeeklyPlaylistScreen`:
- **Opens at once.** Skeleton rows show until the tracks land.
- **Header.** The cover the card showed (else a mosaic of the first 4 track covers), the title, the description, a Play button (`FillAccent`) and Save.
- **Track rows.** Each resolves through `ResolveWhileVisible`, and a tap plays from that row.
- **Errors.** An error shows `couldntLoad(name)` with Try again.

The Recommendations page's playlist card **tap opens this view**; its long-press menu keeps Play / Add to Queue.

- [ ] **Step 1: Shared test first.** `servicePlaylistTracks keeps order and ids` and `recommendedBy falls back for a blank source`. Run them and see them fail, implement, then pass on JVM and `:shared:iosSimulatorArm64Test`. `servicePlaylistQueue` uses `servicePlaylistTracks`.
- [ ] **Step 2: The route, VM (Koin) and screen.** Wire the playlist card's tap to `Routes.servicePlaylist(item.url, item.name, itemSourceName(item, row), item.imageUrl or the filled image)`. Verify `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` (KoinModuleVerifyTest included).
- [ ] **Step 3: Docs.** In `CLAUDE.md`'s Recommendations paragraph, a recommended playlist opens in its own view with Save. Commit `feat(recommendations): open a recommended playlist in a playlist view`.

### Task 7: Device check

**Files:** none (fixes found here go back into the owning task's files).

- [ ] **Step 1: Install on the Pixel** (`ANDROID_SERIAL=53111JEBF07835`): `./gradlew installDebug`, then `adb shell am force-stop com.parachord.android.debug` and launch. Confirm the APK's mtime is newer than the last edit (CLAUDE.md worktree Rule 2).
- [ ] **Step 2: Walk the page.** Check each of these:
  - Sections fill: Last.fm and ListenBrainz songs and artists, Apple Music albums and playlists, Weekly Exploration / Jams cards, Listeners.
  - Each pill filters, and All restores.
  - See all opens a grid and pages on (a loading tail shows, then cards); Show less collapses.
  - A song plays with the rest queued.
  - A weekly card's Play starts "{week}'s Weekly …".
  - A Listener's Play plays their top tracks.
  - Tapping a Listener opens the unsaved profile; Follow saves them, and the row drops them on return.
  - Not-configured state: disconnect both scrobblers on a test install, check the two Connect buttons open those plugin sheets, and that the sheets have their Connect (#741).
- [ ] **Step 3: Logcat check.** No `FATAL`, and no repeated `RecommendationsRepo` failures.
- [ ] **Step 4: Screenshots.** Send the user screenshots of the page (All, one pill, an expanded row, a Listener profile).
