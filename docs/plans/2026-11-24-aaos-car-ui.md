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

## Round 2 — the launcher, the media card, art, and the parked full app

User-reported issues and their fixes (all verified on the API-35 playstore emulator):

- **Cover art everywhere** — `CalArt` (a Koin single over the app's Coil `ImageLoader`): playlist mosaics in Library, recommendation covers in For You, search rows (own URL, else the session's art map by art key), the queue's. Bitmaps cross the bridge to the host as `CarIcon`s — a `file:///` URL can't (the host's uid can't read our storage). `Row.Builder.setImage` throws on null → `setImageWhenReady`; rows render from what's cached and invalidate when a load lands.
- **The home-screen media card opened the full app** — two causes, both fixed:
  1. The launcher entry: the manifest now ships a `PhoneLauncher` activity-alias (the full app, phones' only icon) and CarAppActivity `enabled="false"`; `CarLauncherMode` (every process start, on cars with the templates host) disables the alias and enables CarAppActivity. The media card's tap-out resolves the launcher intent → the drive app.
  2. The opt-in: `PlaybackService` declares `androidx.car.app.launchable = true` (the system radio app declares the same key, `false`). Without it the launcher logs "Skipping MBS ... belonging to non media template app" — found by reading the launcher's dex. `CarAppActivity` also answers `android.car.intent.action.MEDIA_TEMPLATE`.
- **Search had no way out** — the system Back pops the pushed screen (verified); the SearchTemplate and the queue's header carry a `Action.BACK` start action as the visible affordance. Re-launching the app from the icon pops the stack to root (home).
- **No way to open the full app (parked)** — the Library tab's "Full app" section: "Open Parachord" row starts `MainActivity` from `carContext.applicationContext` with `FLAG_ACTIVITY_NEW_TASK`. Two traps found on the emulator: the CarContext targets the host's **virtual display** (a direct `startActivity` dies with `SecurityException ... launchDisplayId=13`), and without NEW_TASK it crashes ("outside of an Activity context"). Back returns to the drive app (same task). While driving, AAOS's own distraction rules block MainActivity (it declares no `distractionOptimized`).
- **No way back from the full app** — system Back returns to CarAppActivity; the car's launcher icon opens the drive app (the flip).
- **The media-playback intent and the dark screen** — `MediaPlaybackTemplate` rendered an empty dark surface when nothing had ever played, and a stuck dark screen survived icon re-launches. The show-media-playback intent now opens Now Playing only once something has played (`state.currentTrack != null`); any other re-launch intent pops the stack to root.
- **The library invalidate loop** — with zero playlists the home screen re-loaded the library on every `onGetTemplate` (a 1 Hz setTemplate loop). Fixed with a `libraryLoaded` flag + an ON_RESUME refresh (a playlist made in the full app shows on return); the queue screen observes only content-key changes (the position ticks don't re-render it).

Also: `minCarApiLevel` must be **application-level** meta-data (the host's `AppInfo.create` reads it from the Context — not from the service, the activity, or the metadata holder service); tabs require icons (`Tab.Builder.setIcon`); the TabTemplate requires `setHeaderAction(Action.APP_ICON)`.

## Round 3 — Now Playing access, the queue, and the media card's art

- **No way into Now Playing** — the car's media card taps out with a plain launcher intent (not `ACTION_SHOW_MEDIA_PLAYBACK`, which only the host itself would send), so nothing in the drive app ever reached the playback view. The home now has a fourth **Now Playing** tab (after For You, before Search) that pushes the `MediaPlaybackTemplate` — guarded by "something has played", the same rule as the show-playback intent, because an idle session renders an empty dark surface. The queue stays one tap further, on the playback view's header.
- **The home-screen media card had no album art** — the AAOS media card (and the CAL playback view) read the session's *compat bitmap*, which only `MediaMetadata.artworkData` becomes; a `artworkUri` URI alone renders no art. The service now embeds the playing item's artwork as PNG bytes: the artwork the notification already fetches is re-encoded and published through `sessionArtworkData` (a `MutableStateFlow<url, bytes>`) into the `queueSnapshotJob` combine, and the timeline's current item carries it when the fetched URL is the item's (`toAutoMediaItem(artworkData = …)`). The fetch self-corrects: `createNotification` reads the effective artwork URL from the player's metadata — the same URL the timeline item carries.

## Round 4 — Shuffle and Spinoff on Now Playing

The playback view's header carries the extra Now Playing controls (the header is the reliable place — the CAL 1.8.0-rc01 `MediaPlaybackTemplate.Builder` has no action strip, and the host's playback view does not render the session's custom actions):

- **Shuffle** — `playbackController.toggleShuffle()` through the bridge; disabled (never hidden) during a spinoff or Listen Along by the shared `shuffleAllowed`. Two drawables: `ic_auto_shuffle` (the phone's Shuffle glyph, `ParachordIcons.Shuffle`, hand-translated to a vector) and `ic_auto_shuffle_active` (the glyph cut out of a filled disc — the same treatment as `ic_auto_spinoff_active`). Both verified rendering on the emulator.
- **Spinoff** — the shared `carNowPlayingButtons` rule decides the button (hidden when nothing similar exists, the fork in a disc while it runs, a tap while the pool builds ignored), and the click goes through the bridge's `toggleSpinoff` (the media button's loading rule).
- **Three end actions accepted** — Spinoff + Shuffle + Queue render together with no host validation error (the two-action cap is an Android Auto projection guideline, not enforced by the automotive host; verified by forcing all three on the emulator).

The screen re-renders when the button state changes (an observer on `PlaybackStateHolder.state`, distinct by shuffle/spinoff/context-type, like the queue screen's).

## Round 5 — the session artwork, actually delivered

Round 3 embedded the artwork bytes into the session metadata, but nothing rendered: the fetch never ran. The bytes were fed by the *notification's* artwork path — `createNotification` (which runs on notification updates only) and the state observer (which returns early unless external playback holds the foreground). During external playback with the app visible, neither fires, so `sessionArtworkData` stayed null and the media card and the playback view kept the flat placeholder.

The fix: a dedicated observer that fetches whenever the playing track's effective artwork URL changes — `combine(stateHolder.state, trackResolverCache.trackArtwork)` → `effectiveArtwork`, the same function the timeline's current item uses, so the timeline's attach (which matches the fetched URL against the item's) always matches by construction. `collectLatest` cancels superseded fetches; a URL change mid-track (the resolved source's art landing) refetches and re-attaches. The bitmap is re-encoded as a 256px JPEG at 85 (~15–25 KB — PNG at 100 was needless binder risk). `fetchArtworkBitmap` also learned `content://` and `file://` (the ContentResolver — a `URLConnection` throws on them), so scanned local files' albumart reaches the car too.

Verified live on the emulator (instrumented build): `fetched bitmap=true bytes=22986 → attach bytes=22986` for the CAA cover, then a URL change to Amazon's CDN mid-track refetched and re-attached (12828 bytes), and `dumpsys media_session`'s metadata grew from 12 keys to 13 (the compat album-art bitmap). The notification-path writers were restored to plain notification-field assignments — they no longer touch the session flow.

## Known limitations (v1)

- Not tested on a device (the EX90): the drive-mode gate on the full app (AAOS blocks non-`distractionOptimized` activities while moving — the emulator is a user build, no VHAL injection), real audio playback, and the actual media card tap while playing.
- Cover-art rendering is compile- and mechanism-verified (the Coil load pattern is `composeMosaicAndroid`'s), but was not visually confirmed on the emulator: the fresh install has no library content, no connected services, and the search screen's on-screen keyboard couldn't be typed on reliably (it belongs to the host's remote surface — `input text`/`input keyevent` don't reach it).
- `CarIcon` for tabs uses placeholder drawables (launcher icon, heart, android search icon) — should be proper desktop icons.
