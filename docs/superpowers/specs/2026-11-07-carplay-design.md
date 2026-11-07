# CarPlay

Status: design, not yet planned. Date: 2026-11-07.

## Why

Parachord runs on Android Auto: a library to browse, a Now Playing screen with the heart and Spinoff, and the playing queue. iOS has nothing in the car. The person asked for a CarPlay app that gives a similar experience and shares as much code with Android Auto as possible.

## Goals

- A CarPlay audio app with the same library as Android Auto, in the same order and with the same limits:
  - **Playlists**, most recently updated first. A playlist opens to a **Shuffle** row, then its tracks, capped at 500.
  - **Loved Songs**: the 100 most recently added collection tracks. Tapping one plays the list from that song.
  - **Collection Radio**: shuffles the whole collection.
- Now Playing with play/pause, skip, the scrubber and artwork, plus the **heart** and **Spinoff** buttons, shown by the same rules as Android Auto (`autoMediaButtons`: no buttons with nothing playing; Spinoff hidden when no similar tracks were found, "End Spinoff" while one runs).
- **Up Next** on the Now Playing screen lists the queue Android Auto shows; tapping a song plays it from there.
- When a track can't play, the car shows the same message Android Auto gets (for example "Open Spotify on your phone to play this").
- Playback started from the car works with the phone locked and Parachord never opened on the phone since it launched.

## Non-goals

- **Siri voice play** ("Hey Siri, play Roads in Parachord"). CarPlay gives audio apps no search screen; voice goes through a Siri intents extension. A follow-up (the person's decision).
- Search. Android Auto has it; CarPlay can't, as above.
- Anything Android Auto doesn't have (recently played, recommendations, albums, artists).
- Shipping CarPlay to users. That needs Apple's CarPlay audio entitlement, which the owner requests from Apple.

## Anti-goals

- Two copies of the car logic. The menu, its ids, caps and what each tap plays are written once.
- Changing what Android Auto users see. Moving its logic to shared code is behavior-preserving.
- Regressing the phone's lock screen or Control Center while making Now Playing work for the car.

## How it works

### Shared car logic (`shared/commonMain`, package `com.parachord.shared.car`)

Android Auto's portable pieces, now top-level functions in `PlaybackService.kt` and `AutoMediaButtons.kt`, move here, and both platforms call them:

- **The tree.** Node ids keep Android's exact strings, so nothing changes for Auto: `parachord_root`, `browse:playlists`, `browse:loved`, `action:collection-radio`, `playlist:<id>`, `playlist-shuffle:<id>`, `playlist-track:<position>:<playlistId>`, `loved-track:<id>`, plus the parser for `playlist-track` ids.
- **Children of a node** as a platform-neutral item (id, title, subtitle, artwork URL, browsable or playable), loaded through the shared DAOs, with the caps (500 playlist tracks, 100 loved songs), the sort orders and the "Shuffle" row. The playlist subtitle is `ownerName ?: "Parachord"`.
- **What a tap plays**: from an item id to the tracks, the start index and whether to shuffle, matching today's `dispatchBrowseTreeAction` table. Loved songs and search results keep the current shuffle setting; playlists don't shuffle; Shuffle and Collection Radio do.
- **The now-playing buttons**: `autoMediaButtons` and its enum move as they are.

Android keeps only Media3: building `MediaItem`s, the FileProvider artwork bridge and URI grants, the silence placeholder, search (Android-only), the forwarding player and queue timeline. Existing Android tests move with the code they test, or keep testing it through the shared functions.

### iOS app startup (before any CarPlay screen)

CarPlay can launch the app while the phone is locked, with no phone screen ever shown. Today several things playback needs only start when the phone's main screen appears (`RootGate.task`, `ContentView.onAppear` and `.task`):

- the JavaScriptCore plugin host (`nativeBridgeInstaller`, `loadPluginsAndList`), without which nothing resolves;
- creating `AppPlayback.shared`;
- `SpinoffController.bind`, `ListenAlongController.bind`, `bindChatPlayback`;
- `restoreQueue()` and resolving the current track's artwork.

These move into one idempotent app-level start, called from both the phone scene and the CarPlay scene, whichever connects first. The phone's screens keep their own UI-only work (banners, sheets, sync timers).

### Now Playing for every service

CarPlay's Now Playing screen shows `MPNowPlayingInfoCenter` and sends play/pause/skip/seek through `MPRemoteCommandCenter`. Today the info is set only by the local-file player (with no artwork), MusicKit publishes its own, SoundCloud and Bandcamp publish through WebKit, and play/pause/seek are bound to the local-file player rather than the coordinator. The builder checks each service (AVPlayer, Apple Music, Spotify Connect, SoundCloud, Bandcamp) on CarPlay and makes the info and the commands follow whatever is playing, including artwork, without breaking what the lock screen shows today. `iosApp/AGENTS.md` records why each service publishes the way it does; read it first.

### The CarPlay screens (Swift, in the iOS app target)

- A `CPTemplateApplicationScene` delegate, declared in the scene manifest.
- **Root:** one `CPListTemplate` with the three rows, which renders the shared tree directly (my call; a tab bar would need a second structure).
- Lists show what the shared tree returns. Long lists are clipped to `CPListTemplate.maximumItemCount` when that is below the shared cap. Artwork loads asynchronously from http(s) and `file://` mosaic URLs, with a placeholder until it arrives.
- **Now Playing:** `CPNowPlayingTemplate.shared` with two `CPNowPlayingImageButton`s (heart, Spinoff) kept in step with the collection and spinoff state, and Up Next enabled.
- **Errors:** a `CPAlertTemplate` with the coordinator's message and an OK button.
- The heart and Spinoff call the same code as the phone: the container's collection add/remove and `SpinoffController.shared.toggle()`.

### Services that need the phone screen

"In the car" means a CarPlay scene is connected (my call).

- **SoundCloud and Bandcamp** play inside a hidden web view attached to the phone's key window. With only the car connected there's no window, so the source counts as refused and the next source plays (my call). With the phone's window present they play as today.
- **Spotify's Now Playing (the person's decision).** Parachord's audio session mixes with others during Spotify playback so it doesn't interrupt Spotify, so iOS treats Spotify as the playing app. In the car, a Spotify track shows Spotify's Now Playing: no heart, Spinoff or Up Next, and Next skips inside Spotify. Parachord's queue still advances when the track ends. Every other service shows Parachord's Now Playing. Check it in a real car once the entitlement is granted, and revisit if it's bad.
- **Spotify that can't start (my calls, from the review):**
  - In the car, no wake attempt that opens the Spotify app: a phone the driver isn't looking at must not switch apps, and a locked phone can't. If Spotify's on-phone device isn't already listed, the source fails at once.
  - The device picker is never shown from the car. Only the phone itself counts as a Spotify device there; a saved remote device (a speaker at home) isn't used, since sound from somewhere else is worse than falling back.
  - With another source, it plays. With none, playback stops on the track with Android's message, "Open Spotify on your phone to play this" (moved to shared code), shown as the car's alert. It doesn't skip through a Spotify-only playlist.
- **Apple Music** uses the same mixing session. Whether MusicKit's in-app player still counts as Parachord's on CarPlay is part of the Now Playing audit.

### Sign-ins on a locked launch (the person's decision)

A launch from the car with the phone locked has to read the saved sign-ins from the Keychain. `IosSecureTokenStore`'s comment says its items are readable after first unlock, but `multiplatform-settings`' `KeychainSettings` appears to set no accessibility, which leaves the iOS default, readable only while unlocked. Confirm on a device first. If so, switch every token to `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and re-save the existing ones once at launch, so they stay encrypted, never leave the device, and are unreadable after a restart until the first unlock.

### The entitlement

The CarPlay audio entitlement (`com.apple.developer.carplay-audio`) must not be added to the device entitlements until Apple grants it, or device builds stop signing (as associated domains did). It goes in an entitlements file used only for simulator builds (my call), so Xcode's CarPlay simulator works now and device builds are unchanged.

## Constraints

- Shared logic in `shared/commonMain`; shared changes run `./gradlew :shared:iosSimulatorArm64Test` as well as the JVM tests (CLAUDE.md, KMP rules).
- iOS deployment target stays 17.0.
- New Xcode files follow `iosApp/AGENTS.md` (pbxproj entries, the `xcodeproj` gem for target-level changes).
- Android Auto behaves exactly as before; its tests keep passing.

## Verification

- Shared tree, play-request and button rules: commonTest, run on JVM and the iOS simulator.
- Android: existing Auto tests, plus a check on the Pixel that the media session still offers the same items and buttons.
- CarPlay: Xcode's CarPlay simulator window, with screenshots of the root, a playlist, Loved Songs, Now Playing with the buttons, Up Next and an error alert; playback started from the car with the phone screen locked.

## Left to the builder

- Exact Kotlin names and how the shared tree returns items to Swift (a flattened DTO is the iOS norm; see AGENTS.md on bridging).
- Placeholder artwork and button images (SF Symbols are fine).
- How long a CarPlay list waits before showing an empty-state row.

## Open risks

- How each music service behaves on CarPlay with the phone locked is untested; the Now Playing audit may find services that can't be driven from the car. Spotify can't be tested in the simulator at all (no Spotify app there).
- `CPListTemplate.maximumItemCount` is set by the car and may be well under 500.
- Apple may take weeks to grant the entitlement, so real-car testing waits on it.
