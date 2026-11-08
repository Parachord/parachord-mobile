# MusicKit SDK for Android: spike

**Status:** in progress. The license question is answered. The device measurements haven't run yet (see "What the device run needs").
**Branch:** `spike/musickit-android-sdk`. Throwaway; no PR to main, and the default engine is unchanged.

## Question

Android plays Apple Music through MusicKit JS in a hidden WebView (`MusicKitWebBridge`, `AppleMusicPlaybackHandler`). That choice was made in March 2026 to match desktop; Apple's native SDK was never tried. The WebView engine needs many background-survival rules (CLAUDE.md, "Apple Music State Polling & Auto-Advance" and "External Playback Background Survival"). Could Apple's SDK replace it, or run beside it?

## License: can the AARs be committed or fetched in CI?

**No to committing them. CI can't fetch them from Apple either.**

- **How Apple ships it.** A zip on the Apple Developer downloads page (`developer.apple.com/download/all/?q=android%20musickit`), behind an Apple Developer sign-in. The current file is `Android-MusicKit-SDK-1.1.4.zip` (23.88 MB, listed October 30, 2026). It isn't on Maven or any public URL, so CI has nothing it can download without a developer account's session.
- **What governs it.** The Apple Developer Program License Agreement (DPLA). §2.6: "You agree not to rent, lease, lend, upload to or host on any website or server, sell, redistribute, or sublicense the Apple Software … in whole or in part, or to enable others to do so." Committing the AARs to a public GitHub repo is uploading them to a website for others to take. The parts of the DPLA read for this spike have no clause that permits sharing the SDK files themselves, and none that mentions Android. Shipping the SDK compiled into an APK is the use Apple offers it for, and other apps on Google Play do it.
- **What other open-source Android apps do.** All the ones found keep the AARs out of git and tell builders to download them: p2o51/Yoin's README says "Apple doesn't allow redistributing it, so it isn't in this repository"; deveshrai/Foldman's NOTICE says the SDK is "used under the Apple Developer Program License Agreement" and "not part of this repository's source"; lladlam/MeloX-Android and dverba/OrchardDrive do the same. Foldman and MeloX reach the SDK by reflection so the app builds without it; this spike does too.
- **CI options, none clean.** (1) A private CI secret or private artifact store holding the AARs, fetched at build time. That is still hosting Apple Software on a server; DPLA §2.9 lets a "Service Provider" act on the developer's behalf, which arguably covers a private build runner, but that's a reading, not something the agreement says. (2) Build release APKs that include the SDK only on a developer's machine. (3) Leave the SDK out of CI builds: the engine then exists only in locally built APKs.
- **Not read:** any separate license file inside the zip itself (it hasn't been downloaded yet). If it has one, it may say more than the DPLA; check it before deciding.

Also binding if the SDK ships (DPLA §3.3.6(D), which covers the MusicKit APIs and MusicKit JS alike): full songs only, user-initiated playback with standard controls, no downloading or modifying MusicKit content, follow the Apple Music Identity Guidelines. The WebView engine is under the same rules today.

## What's built on the branch

- `MusicKitSdkPlaybackHandler` (debug builds only): plays an `appleMusicId` through the SDK's local controller (`MediaPlayerControllerFactory.createLocalController` + `CatalogPlaybackQueueItemProvider`, or `LibraryPlaybackQueueItemProvider` for an `i.`/`l.` id), by reflection. It's an `EmbedPlaybackHandler`, so the controller's engine-agnostic loop drives it (silent ExoPlayer loop, foreground service, position mirror, `onFinished` advance). The MusicKit JS path is untouched.
- Token: the SDK's own sign-in (Settings → Developer → "Sign in through the Apple Music app") if done, else the Music User Token MusicKit JS already saved.
- Toggle: Settings → Developer → "Use Apple's Android SDK for Apple Music". Off by default; `BuildConfig.DEBUG` only.
- AARs: `app/libs/musickit/` (gitignored), `debugImplementation(fileTree(...))`. Without them the app builds and the toggle row reports "missing".
- Measurement: both engines log `START_LATENCY engine=sdk|js id=… outcome=… ms=…` (tag `MusicKitSdkSpike`): time from the handler's `play()` to the first moved position.

## Results

| Check | MusicKit JS (WebView) | Apple SDK 1.1.4 |
|---|---|---|
| Tap → audio (`START_LATENCY`, median of 5) | pending | pending (forum: ~2 s, FB25452140) |
| 3-track queue, screen off 10+ min, auto-advance | pending | pending |
| Android Auto: Now Playing + controls | pending | pending |
| Volume (resolver dB offset) | no effect (CLAUDE.md: DRM audio bypasses `music.volume`) | pending (does the controller have `setVolume`?) |
| Library-only `i.` id | not reached: hint paths only accept all-digit ids (`isAppleMusicCatalogId`) | pending (forum: crash, FB25452189) |
| Needs Apple Music app installed + signed in | no | yes |

## What the device run needs

1. `Android-MusicKit-SDK-1.1.4.zip` downloaded (Apple Developer sign-in), its two AARs copied into `app/libs/musickit/`.
2. A Pixel on USB with the Apple Music Android app installed and signed in to a subscribed account.
3. Then: `./gradlew installDebug`, turn the toggle on, sign in, and run the checks above against the toggle off.

## Recommendation

Pending the device run.
