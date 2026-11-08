# MusicKit SDK for Android: spike

**Date:** 2026-11-08. **Branch:** `spike/musickit-android-sdk` (throwaway; no PR to main; the default engine is unchanged).
**Device:** Pixel 9a, Android 17, cellular (LTE), no Apple Music app installed. US Apple Music subscription.
**SDK:** `Android-MusicKit-SDK-1.1.4.zip` (`MusicKitAuth-release-1.1.5.aar`, `MediaPlayback-release-1.1.4.aar`), listed on Apple's downloads page on October 30, 2026; its CHANGELOG dates 1.1.4 to 2026-05-07.

## Question

Android plays Apple Music through MusicKit JS in a hidden WebView (`MusicKitWebBridge`, `AppleMusicPlaybackHandler`). That choice was made in March 2026 to match desktop; Apple's native SDK was never tried. The WebView engine needs many background-survival rules (CLAUDE.md, "Apple Music State Polling & Auto-Advance" and "External Playback Background Survival"). Could Apple's SDK replace it, or run beside it?

## Answer in one paragraph

The SDK works, and better than expected: **it played with the Music User Token MusicKit JS already stores, with no Apple Music app installed**, which removes the biggest cost the background listed. It started tracks a little faster (median 2.2 s against 2.6 s) and more evenly, and it survived 14 minutes of screen-off deep Doze with two clean auto-advances. But MusicKit JS passed the same Doze run too, neither engine can apply volume offsets, and Apple's licence keeps the AARs out of git and out of public CI. **Recommendation: stay on MusicKit JS for now.** The SDK is the stronger engine on paper (audio in our own process, no WebView, no Chromium focus juggling), and is worth revisiting if the WebView engine keeps failing in the background or a private-CI reading of the licence is cleared. Details and the conditions are at the end.

## Results

| Check | MusicKit JS (WebView) | Apple SDK 1.1.4 |
|---|---|---|
| Time to audio, median (`START_LATENCY`) | **2.6 s** (10 plays, 2.3–5.1 s) | **2.2 s** (9 plays, 2.0–3.0 s) |
| Time to audio, mean | 3.1 s | 2.3 s |
| First play after switching engine | ~5 s, both times | no penalty |
| 3-track queue, screen off, forced deep Doze, ~14 min | **Passed**: 2 auto-advances, stopped at queue end | **Passed**: 2 auto-advances (2.1 s and 2.2 s gaps), stopped at queue end |
| Media-session controls (what Android Auto drives) | pause, resume, skip all correct | pause and skip correct; **resume played audio but Parachord's state stayed "paused"** (spike wiring bug, see below) |
| Android Auto head unit | not tested (see below) | not tested |
| Volume / per-resolver dB offset | no effect (known: DRM audio bypasses `music.volume`) | **no public API**; the SDK sets its own `AudioTrack` volume internally |
| Library-only `i.` id | not reached: the hint paths only accept all-digit ids (`isAppleMusicCatalogId`, #265) | not tested; forum 838633 reports a crash (FB25452189) |
| Needs the Apple Music app | no | **no for playback** with a MusicKit JS token; yes only for Apple's own sign-in flow |
| Audio focus | Chromium's `AudioFocusDelegate` | the SDK requests it itself (`controller.b`) |
| Where audio plays | Chromium renderer, Widevine | our process: an `AudioTrack` (44.1 kHz stereo) fed by the SDK's native decoder |
| APK size | baseline 74.5 MB (debug) | +16 MB (90.5 MB); about 8 MB of it is arm64 native code, the rest armeabi-v7a and Java |

How it was measured:
- **Start time.** Both handlers log `START_LATENCY engine=… ms=…`, the time from the handler's `play()` to the first moved position. Plays were triggered over adb (`com.parachord.spike.PLAY`), 14 s apart, with the app in front and the screen on. Each engine played the same ten catalog tracks, split into two sets swapped between engines, so neither benefits from a warm cache. One SDK reading (55 ms) was dropped as a leftover from an earlier run that had that track loaded.
- **Screen off.** The script started a 3-track queue with the app in front, then simulated unplugging, turned the screen off, forced deep Doze (`dumpsys deviceidle force-idle`), and sampled the media session and audio players every minute for 15 minutes. One run per engine. The WebView engine's past failures were intermittent, so one clean run doesn't show they're gone; a multi-hour soak would.
- **Controls.** `cmd media_session dispatch pause|play|next` against Parachord's media session, the same session Android Auto drives.

### Things that went wrong, and what they mean

- **Android 17 blocks audio focus from the background, for both engines.** One run of plays fired while the phone had dozed off. The system ignored every focus request (`AS.HardeningEnforcer: AudioHardening focus request … ignored`) and refused the foreground service start, so neither engine produced audio. That's the platform, not either engine: a play has to start from the foreground or from a running foreground service. Auto-advance with the screen off worked because the service was already running.
- **The SDK reports the previous track ending when a new queue loads.** `onItemEnded` fires for the old item (sometimes with its real position) right after `prepare()`. The spike first treated that as "finished", which made the controller skip ahead and stop the new track. The handler now accepts only its own track, after its audio started. Any real integration needs the same filter.
- **After `stop()`, `prepare(play=true)` can stay PAUSED.** `stop()` gives up audio focus and the next prepare didn't request it again (no focus request in `dumpsys audio`); the SDK sat paused. The handler calls `play()` once if it's still paused 2.5 s after prepare, and logs `KICK`. Once plays started from the foreground, no `KICK` was needed. It may be the focus hardening above rather than an SDK bug; it wasn't isolated.
- **Resume through the media session.** The SDK resumed (paused → playing, decoder restarted, position kept advancing), but Parachord's mirrored state stayed at "paused" with a frozen position, and the silent ExoPlayer loop didn't restart. Reproduced twice. MusicKit JS reported resume correctly. This is in how the spike handler rides the controller's embed-handler path (`EmbedPlaybackHandler` + `startEmbedStatePolling`), not an SDK limit; a real engine would take its state from the SDK listener. Left unresolved (time-boxed).
- **Android Auto head unit didn't project.** The desktop head unit installed in this Mac's Android SDK is from March 2022 (protocol 1.7) and the phone runs Android Auto 18.0: it connected over adb and stayed on "Waiting for phone". A newer head unit (SDK manager) is needed to check the car UI. Both engines go through the same media session, so the car UI itself isn't engine-specific; the control results above are the part that is.

## The token: no Apple Music app needed

Apple's usage guide ("Providing an Already-Obtained MUT", and "Sourcing the MUT from elsewhere" in the playback guide) says the `TokenProvider` is source-agnostic and names link.apple.com as an example. On the device, the token MusicKit JS saves (`SettingsStore.getAppleMusicUserToken`) drove `createLocalController` and played every catalog track, with `getAppleMusicStoreFrontId()` returning null and the Apple Music app not installed. The playback AAR doesn't check for the app (its only `getPackageInfo` call reads our own package); only `MusicKitAuth`'s sign-in hands off to it.

So an SDK engine could keep Parachord's current MusicKit JS sign-in and need only the playback AAR. Not checked: a non-US storefront with a null storefront id; token expiry and `refreshTokenAsync` with a web-issued token.

## License: can the AARs be committed or fetched in CI?

**No to committing them. CI can't fetch them from Apple either.**

- **How Apple ships it.** A zip on the Apple Developer downloads page (`developer.apple.com/download/all/?q=android%20musickit`), behind an Apple Developer sign-in. Not on Maven or any public URL. No click-through terms appeared when downloading it.
- **No licence file in the zip.** It holds a README, CHANGELOG, usage guides, API docs, a sample app and the two AARs. Nothing grants or limits redistribution, so the Apple Developer Program License Agreement (DPLA) is the governing text.
- **The DPLA.** §2.6: "You agree not to rent, lease, lend, upload to or host on any website or server, sell, redistribute, or sublicense the Apple Software … in whole or in part, or to enable others to do so." Committing the AARs to a public GitHub repo is uploading them to a website for others to take. The parts read for this spike have no clause permitting sharing the SDK files themselves and none mentioning Android. Shipping the SDK compiled into an APK is the use Apple offers it for, and other apps on Google Play do it.
- **Other open-source Android apps** keep the AARs out of git and tell builders to download them: p2o51/Yoin's README says Apple doesn't allow redistributing it; deveshrai/Foldman's NOTICE says the SDK is used under the DPLA and isn't part of the repository; lladlam/MeloX-Android and dverba/OrchardDrive do the same. Foldman and MeloX reach the SDK by reflection; this spike does too.
- **CI.** Parachord's release builds come from GitHub Actions, which can't download from Apple. The options: (1) the AARs as a private CI secret or artifact, fetched at build time. That's still hosting Apple Software on a server; §2.9 lets a "Service Provider" act on the developer's behalf, which arguably covers a private runner, but it's a reading, not something the agreement says. (2) Release builds made on a developer's machine. (3) CI builds without the SDK. Decide this before building a production engine.

Also binding if the SDK ships (DPLA §3.3.6(D), covering the MusicKit APIs and MusicKit JS alike): full songs only, user-initiated playback with standard controls, no downloading or modifying MusicKit content, the Apple Music Identity Guidelines. The WebView engine is under the same rules today.

## SDK facts worth knowing before building on it

- **API (1.1.4).** `MediaPlayerControllerFactory.createLocalController(context, handler, tokenProvider)`; `TokenProvider` has three getters, including the new `getAppleMusicStoreFrontId()`. `CatalogPlaybackQueueItemProvider` / `LibraryPlaybackQueueItemProvider` / `StationPlaybackQueueItemProvider`, `prepare(provider, playWhenReady)`, a 13-method `Listener`. There's a real queue (`addQueueItems`, `moveQueueItemWithId`), so gapless or pre-queued next tracks are possible, unlike today's one-track-per-play model.
- **No volume, no media session, no notification.** Parachord's silent-ExoPlayer foreground service and `MediaSession` would stay. The SDK does take audio focus itself, so ExoPlayer's `handleAudioFocus=false` during external playback still applies.
- **Manifest.** `MusicKitAuth` declares `android:allowBackup="true"`, which conflicts with Parachord's `false` (SECURITY.md C5); the spike overrides it back in `app/src/debug/AndroidManifest.xml`. It also adds `musicsdk://` and its own activities. `MediaPlayback` adds two play-activity reporting services (`ReportingService`, a `JobService`) and `WAKE_LOCK`.
- **Release cadence.** 1.1.2 in November 2021, then nothing until 1.1.3 in April 2026. Open bugs on the forum (2 s start, library ids) are still open.

## What's on the branch

- `MusicKitSdkPlaybackHandler` (debug builds only): plays an `appleMusicId` through the SDK's local controller by reflection, so the app builds without the AARs. It's an `EmbedPlaybackHandler`, so the controller's engine-agnostic embed loop drives it; the MusicKit JS path is untouched apart from a `START_LATENCY` log.
- Toggle: Settings → Developer → "Use Apple's Android SDK for Apple Music" (off by default), plus "Sign in through the Apple Music app" for Apple's own sign-in (not exercised: the Apple Music app wasn't installed and wasn't needed).
- adb triggers (debug only): `com.parachord.spike.ENGINE` (`--es engine sdk|js`), `PLAY` (`--es tracks 'id|title|artist;…'`), `STOREFRONT`, `STATUS`; tag `MusicKitSdkSpike`.
- `-PspikeApp` builds as `com.parachord.android.spike`, so it installs beside the regular debug build.
- AARs: `app/libs/musickit/` (gitignored), `debugImplementation(fileTree(...))`, plus `appcompat` for the SDK's sign-in activity.

To rerun: download the zip, copy both AARs into `app/libs/musickit/`, `./gradlew installDebug -PspikeApp`, sign in to Apple Music in the app, then use the adb triggers.

## Recommendation

**Stay on MusicKit JS as the only engine for now; don't add the SDK as a second engine yet.**

- What the SDK measurably bought here is modest: about 0.4 s off the median start and no slow first plays. It matched, not beat, MusicKit JS on screen-off survival in this test, and neither can apply volume.
- What it costs is a second Apple Music engine to maintain (the background rules don't go away while MusicKit JS remains), a closed-source binary with slow releases and open bugs, a manifest override, about 8 MB per ABI, and an unresolved CI and licence question.
- The "only when the Apple Music app is installed" option from the brief doesn't apply: the app isn't needed.

Revisit, and consider **replacing** MusicKit JS rather than adding a second engine, if:
1. the WebView engine keeps failing in the background in real use. The SDK's design (in-process audio, no WebView renderer, no Chromium focus delegate) removes the causes behind most of the CLAUDE.md background rules, which a longer soak test should confirm; or
2. a private-CI reading of DPLA §2.6/§2.9 is cleared, so release builds can include it.

If either happens, the next steps are a multi-hour screen-off soak on both engines, state taken from the SDK listener (fixing the resume mirror), a newer desktop head unit for Android Auto, and the token-refresh and non-US-storefront checks.
