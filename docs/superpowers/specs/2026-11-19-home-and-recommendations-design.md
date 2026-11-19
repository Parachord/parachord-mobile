# Home and Recommendations: desktop's current pages on both apps

Issues: #645 (Recommendations), #672 / #673 (categories), #668 (Home), #707 (only its "recommendations off" rule).
Desktop reference: `Parachord/parachord` `origin/main` at `7acc0759` (2026-11-19). Where this document and that code disagree, the code wins; where the code and desktop's older spec (`docs/superpowers/specs/2026-11-12-home-redesign-design.md`) disagree, the code wins too.

## Intent

Desktop rebuilt both pages in November 2026:
- **Home** is about getting you playing something you love in one tap.
- **Recommendations** is one page that blends every service's picks by type, with a filter per source.

Both apps still show desktop's old versions. The user asked for both apps to match desktop **as it is today**, not the issues as written: desktop has moved past them (category rows, Listeners via plugin capabilities, the getting-started Home for new users, release years on album cards).

Success: someone moving between desktop, Android and iPhone finds the same sections, the same rules about what shows and in what order, and the same words. The phones lay the content out for a narrow screen.

## Goals

- Recommendations on both apps, as desktop's page:
  - Tracks, Albums, Artists, Playlists and Listeners, each one blended row (or category rows for albums and playlists).
  - A filter pill per source; Shuffleupagus blended in.
  - The newest weekly playlists, "Coming …" badges and release years on albums.
  - Listeners you can open, play and follow.
- Home on both apps, as desktop's page:
  - The announcement slot, a greeting header with Collection Radio and the "Finish setting up" pill.
  - Jump back in, Made from your listening (history mixes), Your playlists freshest first, and Albums you might love.
  - Fresh Drops / Critical Darlings (Pop of the Tops in Fresh Drops' place when that's empty), Concerts near you, and Ask Shuffleupagus.
  - The three-step getting-started layout for a new user.
- Every rule that decides what shows, in what order, with what words, lives once in `shared/commonMain`, ported from desktop's pure modules with their test vectors. Each platform only draws.
- The "Recommendations from X" off-list (`recommendations_off_resolvers`) is honoured from day one. Its switch UI comes with #707.

## Non-goals

- The rest of #707: "Play from X" and "Use metadata from X", and the switch UI itself.
- What's New (see Home → What's New).
- Desktop-only pieces: the browser-extension and Raycast setup items, the getting-started phone note (TestFlight / Play links), hover prefetch, and Amazon Music (`mobile: false`).
- New plugin behaviour. Plugins come from the marketplace; this work adds host glue only.
- Friend Activity, Recently Added, Continue Listening, the Discover grid, the separate AI artist suggestions, and the collection stats: desktop removed them from Home, and so do we. Friends stay in the side menu.

## Anti-goals (this would be a failure even if it works)

- **Two copies of a rule.** For example, an Android-side "newest week" pick and a different iOS one.
- **Caching a failed lookup as "nothing".** A failed Listeners or recommendations fetch hides the row for a week. Failures throw and keep the last good answer.
- **Hardwiring services into the page.** Sources, colours, Listener providers and history-mix providers come from plugin capabilities and manifests (`ServiceColors`), never a fixed list. The old Android Recommendations chips (hardcoded amber, orange and red) are exactly this.
- **Shipping a build where the Weekly playlists are gone from both pages.** See "Order".
- **Asking a provider the user switched off,** or one whose plugin isn't on.

## Constraints

- **Shared code.** All logic goes in `commonMain`, with the KMP rules in `CLAUDE.md`. Any regex or Unicode work (release-date title normalising, `promptName`'s control and bidi strip, slugging) must pass `:shared:iosSimulatorArm64Test`.
- **Plugin loader.** Both `resolver-loader.js` copies stay byte-identical (a test checks). The new loader methods are copied from desktop's verbatim: `getSimilarUsersResolvers`, `getSimilarUsers`, `getHistoryMixResolvers`, `getHistoryMixes`. Async plugin calls use the unique-key poll on both runtimes.
- **MusicBrainz lookups** for release dates go through the existing 1 req/s `MusicBrainzClient` gate, on the background lane, and only for albums on screen.
- **Design system.** Section titles use `SectionHeader` / `PCSectionHeader`, pills use `FilterChips` / `PCFilterChips`, icons come from `ParachordIcon` / `PCIcon`, and service colours from `ServiceColors`. Copy follows `docs/DESIGN_SYSTEM.md`. Rows scroll sideways on phones (Common Mistakes #7).
- **Database.** `playlists` gains `tracksChangedAt` and `lastChangeAdded`. The columns are added at bind time (Android `AndroidModule` ALTER in try/catch, iOS `DriverFactory.addColumnIfMissing`), as with earlier columns.
  - They are local only: never pushed to a service, and never compared by sync.

## Approaches considered

1. **Recommendations first, all the way through, then Home** (chosen). Each page lands complete on both apps:
   - Home's "Albums you might love" reuses the new recommendations model.
   - The Weekly playlists stay on today's Home until the new Home replaces it.
2. **All shared groundwork first, then both pages.** Nothing visible until the end, and model mistakes surface late.
3. **Both pages on one branch.** Fewest PRs, too large to review well.

## Order and PRs

Each PR leaves both apps working.

1. **Recommendations: shared.**
   - The loader methods, and the `PluginManager` calls: `similarUsersResolvers`, `getSimilarUsers` with a per-call config merge, `expandRecommendationSection`.
   - Last.fm's `apiKey` in `metaServicePluginConfigs`.
   - The Apple Music `appleMusicPersonalRecommendations` hook.
   - The rewritten `RecommendationModel`, `ReleaseDate`, and the repository: caches, paging, Listeners, the provider gate, Shuffleupagus input.
   - The `recommendations_off_resolvers` setting, and `FriendsRepository.follow(service, username)`.
   - The bundled `lastfm`, `listenbrainz` and `applemusic` plugin copies (Android `assets/plugins`, iOS `axe-plugins`) refreshed to the marketplace versions the pages rely on (Last.fm 1.2.15 and ListenBrainz 1.6.12 declare `friends`, `similarUsers` and the weekly sections; Apple Music 1.5.7).
   - Tests: ports of desktop's vectors.
2. **Recommendations: Android**, including the friend profile for someone not followed, with Follow.
3. **Recommendations: iOS**, the same.
4. **Home: shared.**
   - `HomeModel`, `timeAgo` with desktop's wording, playlist freshness (columns plus stamping), `HistoryMixesRepository`.
   - The setup / getting-started / banner models, the concerts-near-you filter, and the in-app chat origin.
   - Recommendations starts excluding history-mix playlist ids here, since that's when mixes exist.
5. **Home: Android.**
6. **Home: iOS.**

PRs 2 and 3 can run side by side once 1 merges, and so can 5 and 6 after 4.

**Weekly playlists:**
- After PR 1, they come from the ListenBrainz plugin's weekly sections on Recommendations. Today's Home still shows its own carousel (`WeeklyPlaylistsRepository`).
- PR 4 adds the history-mix exclusion: Weekly Jams moves to Home's "Made from your listening", and Weekly Exploration stays on Recommendations.
- PRs 5 and 6 remove the old carousel. `WeeklyPlaylistsRepository` is deleted once neither app uses it.

## Recommendations — the design

Source of truth:
- `src/renderer/lib/recommendation-model.js` and `tests/renderer/recommendation-model.test.js`
- `src/renderer/components/recommendations-page.js`
- `src/renderer/lib/release-date.js` and `tests/renderer/release-date.test.js`
- `src/renderer/lib/recommendations-empty.js`
- the orchestration in `src/renderer/app.js`: `recommendationProviders` ~L27559 through the paging effect ~L28440, plus `playListenerTopTracks` ~L30535.

### Shared model (`shared/.../recommendations/RecommendationModel.kt`, a rewrite)

A port of desktop's current module, function by function. Port the test file's vectors whole.
- **`normalizeRecommendationItem` / `normalizeRecommendationSections`.**
  - Items gain `category`, `weekly` (`id`, `contextType` weekly-jam | weekly-exploration, `label`, `weekLabel`, `date`), `releaseDate` (albums, `^\d{4}(-\d{2}(-\d{2})?)?$`) and a song's `track`.
  - Sections gain `category` and `seedArtist`.
- **Category tables:**
  - Playlists: personal "Made for you", artist, editorial "From the editors", community "From other listeners", other "More playlists".
  - Albums: new "New for you", personal "Based on your listening", artist, editorial, other "More albums".
  - Plus `categoryFor`, `artistRowTitle` ("Because you like A", "A and B", "A, B and C", "A, B, C and more", and "Because of artists you like" with no names).
- **Dedupe:**
  - `recommendationItemKey`: songs and albums `artist|name`; a playlist by url, else `weekly:<id>`, else name; an artist by name.
  - `providerIdKey`.
  - `mergeOwned`: the first provider keeps the item; later senders join `sourceIds`; Shuffleupagus's `reason` fills a missing one; the lower category rank wins.
- **`buildRecommendationPage`:**
  - Type sections in the order Tracks, Albums, Artists, Playlists, Listeners.
  - First pages are interleaved round-robin by provider, with Shuffleupagus last; paged items are appended after.
  - Albums get `blendIndex`.
  - Playlists keep only the newest week of each weekly type, ordered Exploration, Jams, then the rest; `excludePlaylistIds` is applied after that pick.
  - `categoryRows` splits albums and playlists into category rows when there are 2 or more categories.
  - Each row carries `sources`, `blended`, `pageable` and `canLoadMore`.
- **Listeners:** `friendsRow(similarUsers, exclude)` builds the row from the similar-users answers (see Listeners below).
- **Filter and blending:** `pageSources`, `filterRowBySource`, `shuffleupagusSection` (albums and artists with `category: personal`), `deriveArtistsFromSongs`.
- **`blockedItemPick`:** port it if mobile has the blocklist; otherwise leave it out and note it. *(Builder: check.)*
- **For Home:** `homeRecommendationAlbums`, plus `filterBySource` and `albumsNeedingArt`.

### Release dates (`shared/.../recommendations/ReleaseDate.kt`)

A port of `release-date.js` and its vectors:
- `releaseDateKey` and `searchTitle` (strip trailing brackets, " - EP" / " - Single", and edition suffixes, up to 4 times).
- The release-group query.
- `pickReleaseGroupDate`: bare titles equal; the artist in the credit; Album before EP before others.
- `upcomingLabel(date, today)`: "Coming Nov 21", "Coming Feb 3, 2027", "Coming Dec 2026", "Coming 2027", only after today.

How it runs:
- The provider's own `releaseDate` is used first.
- The cache lasts 7 days, keeps the newest 1500, and stores nothing on a failure.
- Lookups run on MusicBrainz's background lane, one per key in flight, only for album cards that come on screen.

### Fetch, cache and refresh (`RecommendationsRepository`, rewritten)

**What "on" means: one shared rule, `pluginIsOn`.** Desktop asks a plugin that is in `active_resolvers` or is a meta-service. Android never fills `active_resolvers` (it gates on connection state, `CLAUDE.md` → "Native-gating divergence"), so a literal port would ask Apple Music even when it isn't connected. So shared code gets one `pluginIsOn(id, …)` that each platform feeds:
- **Android:** not in `disabled_meta_providers`, and, for a plugin that needs an account (`requiresAuth` or a non-`none` `authType`), connected.
- **iOS:** in `active_resolvers`, or a meta-service not in `disabled_plugins`.

Recommendations, Listeners and history mixes all use it, and `not_configured` reads it too. Tests cover both feeds.

**Which providers are asked:**
- Plugins with `capabilities.recommendations` for which `pluginIsOn` holds.
- Minus `recommendations_off_resolvers` (shared `SettingsStore`, default empty).
- In the user's resolver order, with meta-services after.
- Last.fm and ListenBrainz only with a username, as now.

**`not_configured`:** no Last.fm or ListenBrainz username, no active non-meta provider, and no AI plugin with history sharing.

**Per provider:**
- Each provider has 20 s. A late answer is still applied and saved.
- Fresh for 1 hour; stale-but-shown for 7 days.
- Keyed to the account.
- An empty answer replaces the stored one but is never served from the cache.
- A failure keeps the cache; `notReady` drops it.
- A load generation counter means only the latest load paints. A forced refresh clears paged items.

**Paging:**
- One request in flight per `pagingKey`, at most 10 pages.
- An empty page, or a cursor that doesn't move, means exhausted.
- `lastPageTypes` / `lastPageCategories` stop a row's loading tail.
- A failure shows "Couldn't load more from {provider}".
- Opening a row ("See all") pages every pageable section of that row until exhausted.

**Shuffleupagus input:**
- The album and artist picks from `AiRecommendationService`, keeping its reason.
- While AI suggestions load, the album and artist rows show three trailing skeleton cards. If there are no Albums yet, an "Albums" skeleton shows; if Albums has category rows but none for "Based on your listening", a skeleton row with that title shows.

**History-mix exclusion:** from PR 4 (above).

**Saved to disk:** provider sections, similar users and release dates, replacing today's tracks / artists cache.

### Listeners

**Providers:**
- Plugins with both `similarUsers` and `friends` capabilities for which `pluginIsOn` holds, as desktop decides it. The refreshed bundled plugins declare `friends` (PR 1), so there's no fixed list of services.

**Fetch:**
- `getSimilarUsers(id, config + excludeUsernames)`, where `excludeUsernames` is the user's followed names and hidden friend keys on that service.
- Last.fm needs `apiKey` in its config (it answers `[]` without one).

**Cache:**
- 7 days per provider (`users`, `fetchedAt`, `account`, `shape = 3`).
- A successful empty answer is fresh.
- A failure stores nothing and keeps the old answer.
- An older shape is shown while it refetches once.
- One lookup in flight per provider and account, shared by callers.
- Not ready (no key or username) means it isn't asked at all.

**Row:**
- `friendsRow` drops people already followed, hidden, or yourself.
- Each service is sorted by its own similarity; the services alternate.

**Card:**
- A hexagon avatar (or the initial).
- A "{pct}%" badge in the service's colour.
- The name, the service name, and two lines of recent top artists.

**Actions:**
- **Open:** their profile, as someone not followed.
- **Play:** their 7-day top 10 tracks, else 1 month. Errors: "{name} has no recent top tracks" / "Couldn't load {name}'s top tracks. Try again in a moment."
- **Long-press:** View history, Follow, and View on Achordion for ListenBrainz. A followed person gets the friend menu.

**Follow:** a new shared `FriendsRepository.follow(service, username)`, the same path as Add Friend with an explicit service.

**The profile:**
- Android's `FriendDetailViewModel` must load an unsaved person (service + username), not only a saved friend id.
- iOS's `FriendProfileScreen` already falls back for unsaved people.
- Both show a Follow button for someone not followed.
- A tab that fails shows "Couldn't reach {service}" with Try again.

### The page (both apps)

**Hero:**
- "Recommendations", with "Personalized picks for {ListenBrainz username, else Last.fm username}" or "Personalized picks from your services".
- Sub-line: "From your connected services and Shuffleupagus".

**Filter bar:**
- `FilterChips`: All, then one pill per `pageSources` entry, in page order, with no counts.
- Shuffleupagus is in the fill accent; others in their plugin colour.
- Session state only. A source that leaves the page falls back to All. Hidden when not configured.

**Sections:** Tracks, Albums, Artists, Playlists, Listeners.
- Album and playlist category rows use sub-size titles under the plain section title.

**Phone layout:**
- Tracks: a list, showing 5 rows until "See all".
- Album, artist and playlist rows, and Listeners: horizontal scrollers.
- "See all" / "Show less" expands a card row into a wrapped grid. *(My call: desktop's preview is columns × 2 rows, which a phone doesn't have. The scroller is the preview, and the expanded grid is what desktop's open row shows.)*

**Cards:**
- **Album:** cover, title, artist, the release year (else a blank line), a "Coming …" pill on the cover, and a Shuffleupagus reason badge.
- **Playlist:** image, name, and the source name as its second line.
- **Weekly playlist:** a 2×2 mosaic of its covers and `weekly.label`.
  - Open shows it as today's ephemeral weekly playlist screen.
  - Play uses context `{type: contextType, name: "{weekLabel}'s {label}"}`.
- **Artist:** a reason badge where there is one.
- **Song:** a `TrackRow`. Play queues the rest of the filtered row, with context `{type: recommendations, name, rowKey}`.

**States:** desktop's copy exactly. Gather it in shared `UiMessages`.
- Skeletons.
- "Connect a music service", with "Connect ListenBrainz" / "Connect Last.fm" opening plugin settings.
- "Couldn't load recommendations" with Retry. Desktop's quirk: the "nothing yet" messages show under this title. Keep it, for parity.
- "No recommendations yet" / "Nothing from this source yet".

**Gone:** Android's Artists/Songs tabs and its hardcoded chips, and iOS's source picker.

## Home — the design

Source of truth:
- `src/renderer/lib/home-model.js` and `tests/renderer/home-model.test.js`
- `home-setup.js`, `home-banners.js`, `getting-started.js`, `time-ago.js`
- `src/renderer/components/home-page.js`, `home-sections.js`, `home-setup.js`, `home-getting-started.js`
- `playlist-freshness.js`
- the history-mix host code in `app.js` ~L28125–28345.

### Shared rules (`shared/.../home/`)

Ports, with desktop's vectors:
- **Playlists:** `playlistFreshness`, `freshestPlaylists(list, 6)`, `changeBadge` ("Δ N" within 7 days), `whenPhrase` / `whenPhraseShort`, `freshChangeLine`, `playlistSubtitle` (two lines: "Refreshed 2h ago" / "Since 2023").
- **Jump back in:** `isActiveContext`, `latestLoveAlbum`, `jumpBackInTiles`.
- **History mixes:** `normalizeHistoryMixes`, `flattenHistoryMixes`, the cache-entry helpers, `mixesNeedingMosaic`, `withMixMosaics`, `lovesMosaicAlbums`.
- **Visibility and Ask:** `homeSections`, `promptName`, `exampleShufflePrompt` (the 4 phrasings, seeded per visit), `homeTopArtists`.
- **Recommendations:** `shouldLoadHomeRecommendations` / `shouldStampHomeRecommendationsLoad` (the 10-minute throttle).
- **Time:** `TimeAgo` with desktop's wording ("just now", "5m ago", "3h ago", "2d ago", "4mo ago", "2y ago"). This is separate from the existing `RelativeTime`, which other screens keep.
- **Banner:** `homeBannerItems` / `bannerViewId` over the existing `AnnouncementsRepository`.
- **Setup:** `homeSetupItems` (mobile offers only `getting-started`; What's New is out of scope, below) and the whole `getting-started.js` (steps, show / hide, skip).
- **Concerts:** the near-you filter. Upcoming only; haversine within the radius when there are coordinates, else a city match; soonest first; 4.
- **Recent loves:** the existing shared `recentLoves`, made to sort a love with no date last, as desktop does.

### Playlist freshness (`PlaylistFreshness.kt`)

A port of `playlist-freshness.js`:
- `trackKey`: a slug of artist + title, falling back to `id:`.
- `stampAgainst(prev, next, now)`: an ordered key-list comparison, so a reorder counts. A changed list stamps `tracksChangedAt = now` and `lastChangeAdded` = the multiset difference.

**Where it runs:**
- One shared choke point that every playlist-track write passes through: `PlaylistTrackDao.replaceAll` and the insert paths, called by edits, reorders, imports, sync pulls and the hosted XSPF poll.
- Not for renames, sync-state stamps, or a re-poll with the same tracks.

**Old playlists:** a playlist saved before the columns falls back to `lastModified` until its tracks next change.

### History mixes (`HistoryMixesRepository`)

**Providers:**
- Plugins with `historyMixes` for which `pluginIsOn` holds. *(Desktop doesn't gate mixes by the recommendations switch.)*
- The account is the meta-service username, or `''`.

**Cache:**
- 1 hour fresh, 7 days stale-but-shown.
- An empty answer is stored but not served.
- A failure keeps the cache; `notReady` drops it.
- 20 s per provider.
- The 10-minute throttle.
- Only the latest load updates the screen.

**Mosaics:** a mix without art gets one from its tracks: looked up once per session, retried after 60 s, up to 3 tries.

**Apple Music:** its mixes need the same `appleMusicPersonalRecommendations` hook as PR 1.

**Amazon:** no `getHistoryMixes` anywhere yet, so nothing here.

### In-app chat origin

Ask sends `parachord://chat?prompt=` through a direct in-app call that skips the confirmation an outside link gets:
- The origin is set only by the app's own call, never by anything in the URL.
- Prompts are capped at 500 characters.
- A prompt queued while chat is closed is dropped.

Android's `DeepLinkViewModel.buildConfirmation` and iOS's handler keep confirming external links.

### The page (both apps)

**Hero:** desktop's slim Home band. **Then the announcement slot:** one at a time, as today.

**New user:** the getting-started layout replaces everything below.
- "Welcome to Parachord", three steps:
  1. **Connect where you listen.** Tiles come from the plugin registry (plays here, needs an account; "Your music folder" for local files on Android), each opening that plugin's settings.
  2. **Bring in your music.** "Import a playlist" / "Sync your library".
  3. **Start listening.**
- "Skip setup for now", which moves it to the pill.
- "Explore while you set up" with the Critical Darlings and Pop of the Tops halves.
- **When it shows:**
  - Desktop shows it when the tutorial isn't completed. Mobile has no tutorial flag, so with no saved state it starts active only when the connect and music steps are both not done, so existing users with music never see it. *(My call.)*
  - It's saved in `home_getting_started` (`active`, `played`, `skipped`).

**Otherwise, top to bottom:**
1. **Header.**
   - A greeting by time of day, and the fresh-change line ("Daily Brew picked up 12 new tracks this morning"), hidden with no badge.
   - "Play Collection Radio" (when there's a collection), through the shared `CarBrowse` Collection Radio request.
   - The pill: "Finish setting up · N of M" while getting-started was skipped and isn't done; hidden otherwise. Dismissals are saved in `home_setup_dismissed`.
2. **Jump back in.** A 2×2 grid of the up-to-4 tiles.
   - Tapping a tile opens it; its round Play button plays it.
   - Contexts: playlist `{type, id, name}`, loves `{type: loves, name: "Recent Loves"}`, album `{type: album, name, artist}`, mix `{type: mix, id, name}`.
   - The active check reads the playing context and the first queued track's context.
   - Empty with no collection and no playlists: the "Build your collection" import card, opening library sync settings.
3. **Made from your listening.** A horizontal row.
   - Recent Loves leads, under All only.
   - Then the mixes, with source pills when there are 2 or more sources. No See all.
4. **Your playlists, freshest first.** A horizontal row of 6.
   - The "Δ N" badge and the two-line subtitle.
   - The subtitle line "By last change, so a playlist that refreshes itself stays up front however old it is".
   - See all opens Playlists.
5. **Albums you might love.** `homeRecommendationAlbums` with source pills, release years, and reason badges for Shuffleupagus and the critics. See all opens Recommendations.
6. **Fresh Drops / Critical Darlings.** Two half-cards with their page bands and See all.
   - Stacked on a phone, 3 albums each.
   - Pop of the Tops takes Fresh Drops' place when that has nothing and isn't loading.
   - Each half keeps its skeleton in place while loading.
7. **Concerts near you.** Up to 4, horizontal.
   - A date badge in the Concerts teal.
   - "Tickets" is the only action: one link, or a list for several sellers; http(s) only.
   - Hidden with no location.
8. **Ask Shuffleupagus.**
   - The explanation, an editable field seeded with the local example (replaced only while untouched), and "Ask".
   - With no chat plugin: "Set up Shuffleupagus", opening plugin settings.

**Loading:** after first paint, and nothing waits on the network.
- Recommendations (throttled) and history mixes.
- Fresh Drops and Critical Darlings when not loaded.
- Concerts when a location is set.

**Removed from both Homes:** Continue Listening, Recently Added, the Discover grid, the Weekly carousels, Friend Activity, AI artist suggestions, collection stats, the old Recent Loves list, and iOS's welcome scan card (replaced by the getting-started layout).

### What's New (out of scope)

Desktop's pill also offers "What's new in v{version}", the release notes for the running version. Mobile's `RELEASE_NOTES.md` is written per Android release and iOS is versioned separately, so there's nothing yet that both apps could show. **Decision: leave What's New out of this work on both apps** and track it as its own issue (#739). The pill carries only getting-started, and `homeSetupItems` keeps its What's New input unset.

## Left to the builder

- Spacing, card widths and skeleton shapes at phone widths, within the design system's scales.
- How many cards each row loads before scrolling.
- Splitting PR 1 if it grows past a comfortable review (model + release dates, then repository + Listeners).
- The exact iOS / Android plumbing for the Apple Music hook, through the shared `AppleMusicCatalog` with the Music User Token.

## Docs to update in the same PRs

- `CLAUDE.md`'s "Recommendations come from the plugins" paragraph.
- The ListenBrainz Weekly Playlists section.
- `iosApp/AGENTS.md` where it describes Recommendations or Home.
- The README feature list.
- New sections for playlist freshness and history mixes.
