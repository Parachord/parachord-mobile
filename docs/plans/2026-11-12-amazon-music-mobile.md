# Amazon Music on mobile: plan

Ticket: #443 (research). Desktop reference: Parachord/parachord `docs/architecture/amazon-music.md`, `plugins-src/amazonmusic/` (resolver 1.10.0), `amazon-web-player.js`, `amazon-music-logic.js`, `amazon-dmls.js`, `main-process/amazon-telemetry.js`, `src/renderer/lib/widevine-player.js`, `widevine-quality.js`. Read 2026-11-12.

## Summary

- **Android can do all of it**: sign-in, search and resolve, albums, playlist import, artist photos, recommendations and playback. Playback goes through **ExoPlayer** (DASH + Widevine), not a hidden Amazon web player.
- **iOS: discovery for sure; playback depends on FairPlay.** Desktop plays through Widevine, and iOS has no Widevine: AVPlayer and WKWebView only do FairPlay, so FairPlay is **required** for any iOS playback. Whether it's **possible** is spike S6: if music.amazon.com plays in Safari, Amazon's web backend already serves FairPlay to some client, and S6 finds out what that client asks for.
- **One spike decides whether this is feasible**: S2, capturing Amazon's own GraphQL request from inside a mobile web view. Desktop does this at the network layer (Electron `webRequest`), which mobile web views don't have.
- **The plugin runs unchanged.** Each app supplies the host bridge it calls (`window.electron.amazonMusic.*`). The pure logic desktop keeps in `amazon-music-logic.js` moves to `shared/commonMain`, with desktop's test vectors.

## How desktop does it today

The ticket describes an older design. Desktop deleted it at resolver 1.7.0: there's no `/tracks/{asin}?do=play`, no hidden player page and no injected control scripts. What desktop runs now:

1. **A hidden "search view"**: a web page on `music.amazon.com` in a persistent cookie partition, with a Chrome user agent, parked off screen at 1100x800. It loads Amazon pages such as `/search/<q>/songs`, `/albums/<ASIN>` and `/playlists/<id>`. It also captures the persisted-query GraphQL request each page fires to `gql.music.amazon.com`: the URL, the body (`operationName`, `variables`, `extensions.persistedQuery.sha256Hash`) and the headers (`x-api-key`, `csrf-token`, `csrf-rnd`, `csrf-ts`, `x-amzn-*`).
   - It replays that request with `fetch` from inside the page, with patched variables.
   - A captured search becomes a **template**, fresh for 24 h, so later searches don't load a page.
   - A circuit breaker opens for 10 min after 3 structural failures.
   - Playlists paginate by passing `pageInfo.token` as `afterCursor`.
2. **A visible sign-in window** in the same partition. Sign-in only counts once the URL sits on `music.amazon.com` past every wall: `/ap/*` and the profile picker at `/identity/who-is-listening`. "Signed in" is read from cookie names (`at-main`, `sess-at-main`, `at-acb`).
3. **Playback** has no Amazon page at all.
   - The main process reads `GET https://music.amazon.com/config.json` (customerId, marketplace, territory, siteRegion, deviceId, `tier`).
   - It asks `POST https://music.amazon.com/<NA|EU|FE>/api/dmls/` for a DASH manifest (`X-Amz-Target: ...DigitalMusicLocatorServiceExternal.getDashManifestsV2`, `Content-Encoding: amz-1.0`, the csrf headers and the cookie).
   - Shaka plays it on the app's audio element. Widevine licence challenges are relayed through the same endpoint (`getLicenseForPlaybackV2`), after one empty "cert" challenge per session.
   - Amazon **telemetry** events are required (`/telemetry/v1/events`); desktop notes plays must be reported.
4. **Plan gate.** Only Music Unlimited plays (`tier` matches `/^UNLIMITED/i`). Prime and unknown plans keep discovery but lose playback: no badge, never picked.
5. **Quality.** A global Playback Quality setting has four tiers (Max, High ≤500k, Standard ≤200k, Data saver ≤100k), an Adaptive toggle and "Prefer Dolby Atmos". Families are FLAC, Opus, AAC, plus E-AC-3 when opted in and supported. A licence denial or decode failure drops that family and falls back mid-track.

Section 9 of the desktop map lists which pieces are pure: almost all of `amazon-music-logic.js` and the policy half of `widevine-quality.js`.

## Architecture on mobile

```
                        amazonmusic.axe (unchanged)
                          │ window.electron.amazonMusic.*   ← host bridge, defined by each app's JS bootstrap
                          ▼
  Android: bootstrap.html shim → NativeBridge → AmazonMusicHost (app)
  iOS:     IosJsRuntime shim   → Swift callables → IosAmazonMusicHost
                          │
          shared/commonMain/amazon/  (pure logic, ported with desktop vectors)
            AmazonMusicLogic: URLs, walls, cookie state, sign-in landed, GraphQL request
              matchers, template patching, response → track/album/playlist/recs parsers,
              pagination, breaker, plan, dmls bodies, region, licence-denial summary,
              telemetry builders
            AmazonQualityPolicy: tiers, family order, spatial opt-in, family-failure table
            AmazonDmlsClient (Ktor): config.json, manifest, licence relay, telemetry —
              cookie + csrf supplied by the platform
                          │
  Android only: AmazonWebSession (hidden WebView: page loads, GraphQL capture + in-page replay,
                sign-in Activity), AmazonMusicPlaybackHandler (ExoPlayer DASH + DRM)
```

- **Plugin unchanged.** The bridge method names, arguments and result shapes are desktop's (`searchTracks`, `searchAlbums`, `lookupAlbum`, `lookupPlaylist`, `searchArtistImage`, `getRecommendations`, `expandRecommendationSection`, `getStatus`, `openLogin`, `signOut`), and so are their `reason` codes. This is the host-hook pattern #440 used for Apple Music, applied to the namespace the plugin already calls. It's host glue, not a reimplementation of the plugin (Common Mistakes #6).
- **Playback is host-owned** on desktop too (`capabilities.widevine`; the plugin's `play()` returns false). Android routes `amazonmusic` sources to a new handler, the way Bandcamp has its own engine.
- **Network calls.** Desktop's GraphQL replays run from inside the page, where Amazon's own origin, cookies and CORS hold. Mobile keeps that. Desktop's dmls and telemetry calls run outside the page with an explicit cookie header; on mobile those go through a shared Ktor client, with the cookie read from the web view's cookie store (`CookieManager` / `WKHTTPCookieStore`).

## Spikes first (a Music Unlimited account needed)

The Parachord team has no Unlimited account; theli-ua does, and runs the account-dependent spikes (all but parts of S1 and S2, which a Prime account can check). Results go on #443.

| # | Question | Pass when |
|---|---|---|
| S1 | In a hidden-capable `WebView` (Chrome UA, persistent cookies), can a person sign in, pass 2FA and the profile picker, and land on `music.amazon.com`? (A tester says Firefox for Android works; a WebView is not Firefox.) | Cookies `at-main`/`sess-at-main` present and a `/search/...` page renders results |
| S2 | **Can we capture the page's own `SpecSearchSeeMorePage` request (body + headers)?** Try in order: (a) `WebViewCompat.addDocumentStartJavaScript` wrapping `fetch` and `XMLHttpRequest` (runs before page scripts, unlike Electron's isolated preload, which lost the race); (b) `shouldInterceptRequest` for headers + a document-start hook for the body; (c) as a last resort, read the APQ hash from Amazon's JS bundle. | A captured template that, replayed from the page with a new `searchText`, returns `data.specificationSearch` |
| S3 | Does `config.json` + a dmls manifest + a licence relayed through a custom `MediaDrmCallback` play in ExoPlayer on a Pixel (Widevine L1)? Also try an L3 emulator. | Audio plays; note any `denialReason` (`UNTRUSTED_DEVICE_CERTIFICATE` / `DEVELOPMENT_CERTIFICATE_NOT_ALLOWED` would end the playback half) |
| S4 | Does Amazon accept our telemetry events (200, not 401)? | 200 for `streamingInitiated` / `trackStreamed` |
| S5 | Does playback keep going with the screen off and in Android Auto? It's ExoPlayer, so the normal foreground service applies; check DRM session renewal and licence relay with the screen off. | 10 min screen-off, and a full album |
| S6 | (iOS) **Is FairPlay possible?** First, does music.amazon.com play in Safari (macOS, then iOS)? If it does, record its requests with Safari's Web Inspector: the `dmls` manifest call (its `contentProtectionList`, `musicDashVersionList`, `deviceTypeId`) and the licence call (`DrmType`, certificate fetch). Then replay that manifest request from a test harness and play the result in `AVPlayer` with an `AVContentKeySession` relaying SPC/CKC through dmls. | A FairPlay HLS/DASH manifest and a CKC; AVPlayer plays it. If Safari can't play Amazon Music, FairPlay isn't offered to web clients and iOS stays discovery-only |

**Go / no-go.**
- S1 + S2 must pass for anything to ship, since discovery needs them.
- S3 + S4 must pass for playback.
- If S2 fails, stop: hard-coding Amazon's GraphQL hashes would break on every Amazon redeploy, and desktop deliberately never does it.

## Phases (after the spikes)

1. **Shared logic.** Port the pure functions from `amazon-music-logic.js` and the policy half of `widevine-quality.js` to `shared/commonMain/.../amazon/`. Port the vectors from desktop's `tests/resolver/amazon-music-logic.test.js`, `amazon-gql-capture.test.js`, `amazon-web-player-template.test.js`, `amazon-telemetry.test.js` and `widevine-quality.test.js` as the cross-platform contract. Run `:shared:iosSimulatorArm64Test` (regexes, Unicode). Skip desktop's dead player-era helpers (map, section 9).
2. **Android discovery.**
   - `AmazonWebSession`: one hidden WebView (not attached to a window, `LAYER_TYPE_NONE`, like MusicKit). Page flows run one at a time; replays run in parallel; a template is replayed first, else the page flow; the view parks after 30 s; page loads time out at 30 s; the circuit breaker applies.
   - Replays use the unique-key poll for `evaluateJavascript` (CLAUDE.md: a bare async IIFE returns `{}`).
   - A sign-in Activity hosts a visible WebView with the same cookies, finishing with the shared `signInLanded`. It's not a Custom Tab, because the app must read cookies and the URL.
   - The `window.electron.amazonMusic` shim goes in `bootstrap.html`.
   - Desktop's sign-in prompt rules apply: background searches never prompt; a quiet retry before a prompt; 10 minutes of quiet after a decline.
3. **Android playback.**
   - `AmazonMusicPlaybackHandler` (a `SourceHandler` producing an ExoPlayer `MediaItem` with DRM): `DashMediaSource` from the dmls manifest (substitute ASIN honored); `DefaultDrmSessionManager` with an `AmazonLicenseCallback` that sends the cert pre-charge, then relays challenges.
   - The plan gate runs before any manifest request.
   - Telemetry hangs off the player's events.
   - Licence denials and decode failures feed the shared family-failure table and fall back mid-track: drop FLAC on `CUSTOMER_NOT_ELIGIBLE`; fail the source on a certificate denial.
   - Errors map to `PlayFailure` (`START_FAILED`, or a refused source so another plays).
   - Quality maps to ExoPlayer track selection: max bitrate per tier, preferred families, spatial opt-in, adaptive or locked.
   - Volume offset 0 dB, like other full-quality streams.
   - Because it's ExoPlayer, none of the external-engine foreground rules apply. It behaves like a local track (the #614 foreground keep at a track's end covers it).
4. **Settings and UI (Android).**
   - An Amazon Music resolver panel: Sign in / Sign out, status, and the plan message when blocked ("Prime: no playback", "Plan not confirmed").
   - A Playback Quality card using desktop's keys (`playback_quality`, `playback_abr`, `playback_spatial`) and copy.
   - Resolver order and colour from desktop (`#25D1DA`; `amazonmusic` third in the default order).
   - A blocked plan removes Amazon from playback and badges but keeps discovery (desktop's `playback-limits`).
   - Car: same browse tree. An Amazon track in the car plays like any ExoPlayer track.
5. **iOS** (if S2 also passes in `WKWebView`, using a `WKUserScript` at document start).
   - Discovery: same shim and shared logic: playlist import from `music.amazon.com` URLs, album lookup, artist photos and recommendations.
   - Playback only if S6 passes: an `AVPlayer` engine with an `AVContentKeySession` relaying FairPlay through the shared `AmazonDmlsClient` (its request bodies take the protection type as a parameter, so Android and iOS share one client). Same plan gate, telemetry and quality policy.
   - Until then, Amazon is never a playback source on iOS: hidden from badges and picking, as for a Prime plan.
6. **Plugin gate and docs.**
   - Today `capabilities.mobile: false` hides the plugin everywhere. We need a per-platform switch so Android loads it and iOS loads it for discovery only.
   - Proposal for desktop (agreed; Parachord/parachord#1355): the plugin declares the host bridge it needs (`requires: { hostBridges: ["amazonMusic"], playback: "widevine" }`), and each host reports what it supplies. A host missing the bridge hides the plugin. A host that has the bridge but can't play (iOS) keeps discovery and leaves Amazon out of playback.
   - Then add `minAppVersion.android` / `.ios` and drop `mobile: false`.
   - A plugin that's visible without its bridge would throw `not-ready` on every resolve, which #566 would retry indefinitely.
   - Update CLAUDE.md (a new "Amazon Music" section; resolver order; volume table), `iosApp/AGENTS.md` and the README.

## Risks

- **Capture (S2)** is the biggest unknown. Desktop found that a page-level `fetch` wrapper installs too late. Android's document-start script may win that race, but nobody has checked.
- **Licence on Android devices (S3).** Amazon rejects development Widevine certificates. Pixels carry production device certificates, but whether Amazon's licence policy accepts a non-Amazon Android client is untested.
- **Amazon may change anything without notice.** Desktop already treats every hash, header and response shape as captured rather than hard-coded, and keeps a circuit breaker. Mobile must do the same.
- **Account standing.** This uses Amazon's private web API, as desktop does. Telemetry must be accurate.
- **No concurrent-stream handling** exists on desktop to copy ("playing on another device").
- **Effort.** Shared logic: about 2–3 days, plus tests. Android discovery: 3–4 days. Android playback and quality: 4–5 days. Settings/UI: 2 days. iOS discovery: 2–3 days. The spikes come first: 2–3 days.

## Decisions (2026-11-12)

1. **Test account:** theli-ua has Music Unlimited and runs the spikes that need it.
2. **iOS:** run S6 before deciding; FairPlay is required for iOS playback, and S6 says whether Amazon offers it.
3. **Plugin gate:** propose `requires.hostBridges` to desktop.
4. **Playback Quality:** add desktop's global setting on mobile, even though only Amazon reads it at first.
