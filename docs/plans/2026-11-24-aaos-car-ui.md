# Android Automotive (AAOS) car UI — 2026-11-24

A minimal first-party UI for Parachord running on a car's own Android (Android
Automotive / Google built-in, e.g. a Volvo EX90), sideloaded as the same APK
that installs on phones. Until now the app only supported Android Auto
(projected from the phone): the shared `CarBrowse` tree + `MediaLibraryService`
in `PlaybackService`.

## Decisions (user-confirmed)

- **One APK, same app.** The app detects AAOS at runtime
  (`PackageManager.FEATURE_AUTOMOTIVE`) and swaps its root to the car UI; a
  phone install is unchanged.
- **System keyboard** for search (the car's own IME, whatever it allows in
  drive mode), not an app-rendered keyboard.
- **Every recommendation entry is playable on tap** — track, album (its
  tracks), artist (top songs), playlist, weekly, listener (their top tracks).
  No album/artist detail views exist in the car UI.

## Surface

`ParachordApp` → `CarRoot` (on AAOS) instead of `ParachordAppContent`.
`CarRoot` holds two states:

- **Car home** (`CarHomeScreen`): top bar with app name, a **search** button
  and a **settings gear**; the body is a **Library section first** (a
  Collection Radio tile + the library's saved playlists as tiles; plays go
  through the shared `HomeQueues`), then the shared recommendations page
  (`RecommendationsRepository.page` → `recommendationPageLayout`, previews
  only, no See-all, no source chips); the bottom is a car-scaled
  now-playing bar (the phone `MiniPlayer`'s shape: art, title/artist, heart,
  play/pause, next) that opens Now Playing. Not-configured / failed / empty
  states mirror the phone page's copy; NotConfigured's "Open settings" is
  parked-only like the gear.
- **Full UI (parked only)**: the whole `ParachordAppContent` (start route
  Settings) under a close bar that returns to the car home.

**Theme: the app runs always dark on a car** (Android Auto's template is dark;
  our player surfaces are) — forced in `ParachordApp`'s theme decision.

Car screens below home:

- **Now Playing** (`CarNowPlayingScreen`): Android-Auto-style — large art,
  title/artist, seek slider + times, prev / play-pause (FillAccent disc) /
  next, shuffle (the shared `shuffleAllowed` rule disables it during a
  spinoff or Listen Along), and the shared `carNowPlayingButtons` row (heart +
  Spinoff). A **queue button sits in the corner** and opens the queue.
- **Queue** (`CarQueueScreen`): the current track + Up Next
  (`PlaybackState.upNext`); a row tap is `playFromQueue(index)` and that is
  all it does (no reorder/remove/clear). Suspended dim (spinoff /
  listen-along) mirrors `QueueSheet`.
- **Search** (`CarSearchScreen` + `CarSearchViewModel`): a text field on the
  system IME; queries run through the shared `SearchSession` (the phone
  search's engine — library tracks/albums + remote tracks/albums/artists),
  with all results **merged into one list** (song/album/artist rows, kind +
  source badges; the same song once, the library copy first). **A song tap
  replaces the queue with that one track** (`playTrack` under a `"search"`
  context), an album plays its tracks, an artist their top songs, and it opens
  Now Playing.

## Driving state

`CarDrivingState` (app, Koin single) reaches `android.car`'s
`CarUxRestrictionsManager` **through reflection** (`android.car` isn't in the
public SDK; `CarDrivingStateMonitor` is system-only): `Car.createCar` →
`getCarManager("uxrestriction")` → current restrictions + a listener proxy.
The register method's name differs across AAOS builds — `registerListener`
(on the API-35 car image, verified against its `android.car.jar`) or
`registerCarUxRestrictionsListener` — so it tries both. `createCar` uses the
API-30+ two-arg timeout form, falling back to one-arg on older builds.
`isRequiresDistractionOptimization` ≈ moving. It **fails open** (not
restricted) when the car service is unavailable — the gate is a convenience,
not a safety interlock. The gear and the NotConfigured button are disabled
while restricted.

While restricted, the drive UI follows the driver-distraction rules: the
search field is off (typing is not allowed in motion — the car's voice
assistant searches through the shared MediaLibrarySession instead) and the
queue caps at 8 jump targets with a "Park to see the full queue" line.

## Reuse (the "shared code" check)

- `RecommendationsViewModel` is used verbatim by the car home (its play
  methods, `cardShown`, `weeklyCovers`, `messages`, `retry`).
- `NowPlayingViewModel` drives Now Playing and the queue; `MainViewModel`
  drives the bar (playback state, `trackArtwork`, heart, on-tour) and the
  full UI.
- Cards are the existing `RecAlbumCard` / `RecArtistCard` / `RecPlaylistCard`
  / `RecWeeklyCard` / `ListenerCard` / `ParachordAlbumCard` at a wider car
  width; `carNowPlayingButtons` picks the custom buttons as on Auto.
- Search reuses `streamingResolverSearch` (the DJ tools' search surface).
- iOS has no AAOS counterpart (CarPlay draws from the same shared models —
  no new shared decision logic was needed; everything the car UI decides with
  already lives in shared code).

## Known limitations (v1)

- While the car home is up, deep links go through the same `DeepLinkViewModel`
  the phone app feeds, so play links play; links that only *navigate* (an
  artist/album/page link) are dropped — their nav events find no nav host. Links
  that ask first still ask (the shared confirmation dialog).
- The drive-mode gate blocks *entering* the full UI and closes it by itself
  when the car starts moving.
- Card/row text sizes are the shared phone components' (13sp on cards); on
  low-density car displays they read small.
- `PlaybackNotice` action buttons ("Open in SoundCloud") show as plain toasts
  on the car screens.
- Sign-in assumes a browser exists on the car (Chrome Beta / the OEM's link
  viewer): `OAuthManager.launchCustomTab` now falls back to a plain
  `ACTION_VIEW` when no Custom Tab provider resolves, and the manifest queries
  for browsers.

## Manifest

`<uses-feature android:name="android.hardware.type.automotive"
android:required="false"/>` + a browser `<queries>` intent for the fallback.
The Android Auto descriptor and the browse tree are untouched.
