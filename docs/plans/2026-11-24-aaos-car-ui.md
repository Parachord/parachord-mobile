# Android Automotive (AAOS) drive UI — CAL media templates

The car drive UI is **Android for Cars App Library (CAL) media templates**: the car's template host (`android.software.car.templates_host.media`) renders every screen and enforces driver-distraction compliance by construction. Our Media3 `MediaLibraryService` (shared with Android Auto) feeds the playback state and the Assistant.

## The adaptive APK

- **AAOS with the templates host** (Android 14+ cars — the EX90, modern Google built-in): the CAL drive UI renders.
- **Older AAOS** (Android 10–13): the car's own Media Center renders the shared `CarBrowse` tree.
- **Phones**: the phone app unchanged.
- `uses-feature android.software.car.templates_host.media required="false"` — the car picks what it has.

## The screens (`app/.../car/cal/`)

- **Home** (`CalHomeScreen`): `TabTemplate` with three tabs.
  - **Library**: Collection Radio + the saved playlists (plays through the shared `HomeQueues`).
  - **For You**: the shared recommendations page as `SectionedItemTemplate` sections.
  - **Search**: opens the `SearchTemplate`.
- **Search** (`CalSearchScreen`): the host's keyboard (its own distraction compliance) through the shared `SearchSession` + `mergeSearchResults`; a song tap replaces the queue with that track, an album plays its tracks, an artist their top songs.
- **Now Playing** (`CalNowPlayingScreen`): a `MediaPlaybackTemplate` — the host renders the controls from the registered session token; no playback UI of ours to build. Its header carries a Queue button.
- **Queue** (`CalQueueScreen`): a `SectionedItemTemplate` — current track + Up Next; tap = `playFromQueue`, nothing else.

## The wiring

- `CalCarAppService` (category `MEDIA`) + `CalMediaSession` (returns the home screen; handles `ACTION_SHOW_MEDIA_PLAYBACK` from the car's home media card).
- `CalPlaybackBridge` (Koin singleton): every template tap plays through it (the shared `HomeQueues`, `CatalogQueues`, `CarBrowse.playRequest`, `PlaybackController` calls posted to the main thread). Registers the Media3 session compat token with `MediaPlaybackManager` so the host's `MediaPlaybackTemplate` and the car's media card stay in sync.
- `PlaybackService.sessionCompatToken` (a companion var, set when the session is built) is what the bridge reads.

## Manifest

- `MEDIA_TEMPLATES` permission; `minCarApiLevel 8` as **application-level** meta-data (the host reads it from there — NOT from the service, the activity, or the metadata holder service).
- `automotive_app_desc.xml`: `<uses name="template"/>` beside `<uses name="media"/>`.
- CAL 1.8.0-rc01 (the first with `CATEGORY_MEDIA_APP`/`MEDIA_TEMPLATES`/`ACTION_SHOW_MEDIA_PLAYBACK`); no 1.8.0 stable exists yet.
- `app-automotive` (CarAppActivity) wants minSdk 29; our minSdk is 26, but car templates only launch on API 29+ cars — manifest override.

## What replaced

The hand-drawn Compose drive UI (CarRoot, CarHomeScreen, CarNowPlayingBar/Screen, CarQueueScreen, CarSearchScreen, CarHomeLibraryViewModel, CarEnvironment, CarDrivingState and the drive-mode rules) is **removed** — the CAL host provides all of that by construction (its keyboard, its list caps, its rendering). The parked-only gear/full-UI is gone: the CAL drive UI IS the car surface; the full phone app still runs on the car via the launcher.

## Known limitations (v1)

- Verified rendering on the API-35 playstore emulator (arm64): the templates host accepted the manifest, rendered the TabTemplate home (screen has content, brightness ~18 dark UI with bright pixels = icons/text). Not yet interactively tested.
- Not tested on a device.
- `CarIcon` for tabs uses placeholder drawables (launcher icon, heart, android search icon) — should be proper desktop icons.
- Recommendations sections have no covers (text-only rows in the SectionedItemTemplate v1).
