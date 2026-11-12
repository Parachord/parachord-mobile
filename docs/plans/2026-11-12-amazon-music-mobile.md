# Amazon Music on mobile: plan

Ticket: #443 (research). Desktop reference: Parachord/parachord `docs/architecture/amazon-music.md`, `plugins-src/amazonmusic/` (resolver 1.10.0), `amazon-web-player.js`, `amazon-music-logic.js`, `amazon-dmls.js`, `main-process/amazon-telemetry.js`, `src/renderer/lib/widevine-player.js`, `widevine-quality.js`. Read 2026-11-12.

## Summary

- **Android can do all of it**: sign-in, search and resolve, albums, playlist import, artist photos, recommendations and playback. Playback goes through **ExoPlayer** (DASH + Widevine), not a hidden Amazon web player.
- **iOS: discovery for sure; playback depends on FairPlay.** Desktop plays through Widevine, and iOS has no Widevine: AVPlayer and WKWebView only do FairPlay, so FairPlay is **required** for any iOS playback. Whether it's **possible** is spike S6: if music.amazon.com plays in Safari, Amazon's web backend already serves FairPlay to some client, and S6 finds out what that client asks for.
- **One spike decides whether this is feasible**: S2, capturing Amazon's own GraphQL request from inside a mobile web view. Desktop does this at the network layer (Electron `webRequest`), which mobile web views don't have.
- **Amazon's logic moves into the plugin; the hosts become thin.** Today desktop keeps almost all of it in host code (`amazon-music-logic.js`), and `amazonmusic.axe` is a shell over `window.electron.amazonMusic`. Step 0 is a desktop refactor (Parachord/parachord#1356): the plugin takes over every Amazon-specific step, and each host supplies a few generic primitives: a web session it can load, capture and fetch in; sign-in; and a DRM playback engine. Then a fix to Amazon's parsing reaches all three apps through the marketplace, and mobile ports none of it to Kotlin (CLAUDE.md, Common Mistakes #6 and the extensibility principle).

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

## Architecture: Amazon logic in the plugin, primitives in the host

```
  amazonmusic.axe  (all Amazon-specific logic: search URLs, which GraphQL request to capture,
                    template patching, response parsing, pagination, breaker, sign-in walls,
                    cookie state, plan, dmls/config/telemetry requests, licence-denial reasons)
        │ window.parachordHost.webSession.*      window.parachordHost.drm (host-owned playback)
        ▼
  Desktop:  Electron WebContentsView + webRequest + net     Shaka + castLabs Widevine
  Android:  WebView + document-start capture + Ktor          ExoPlayer DASH + Widevine
  iOS:      WKWebView + WKUserScript capture + Ktor           AVPlayer + FairPlay (only if S6)
        shared/commonMain: the host contracts below, the quality policy (from
        widevine-quality.js: tiers, family order, spatial opt-in, family-failure table),
        and the per-session limits; no Amazon parsing.
```

**Host primitives** (names provisional; desktop's refactor settles them, and they're generic so another web-only service could use them):

| Primitive | Does | Desktop today |
|---|---|---|
| `webSession.load(id, url, { capture: { urlPrefix }, timeoutMs })` → `{ finalUrl, captures: [{ url, method, headers, body }] }` | Loads a page in a persistent, hidden session and returns the requests it fired to the given prefix | `ensureView` + `attachGqlCapture` |
| `webSession.pageFetch(id, request)` → `{ status, text }` | `fetch` from inside the session's page (its origin, cookies, CORS) | `GQL_FETCH_SCRIPT` replay |
| `webSession.fetch(id, request)` → `{ status, headers, text }` | A request outside the page carrying the session's cookies | `amazon-dmls.js` `net` calls |
| `webSession.state(id)` → `{ url, cookieNames }` | What the plugin needs to judge sign-in and walls | `isSignedIn` inputs |
| `webSession.signIn(id, url, { done })` | Shows the session in a visible window until the plugin's `done({ url, cookieNames, stableTicks })` says so, or the person closes it | `openLogin` |
| `webSession.clear(id)` | Sign out: drop the session's cookies and storage (only its own cookie domains on Android, whose WebViews share one cookie jar) | `signOut` |
| `drm.play(track)`: the host calls the plugin's `getPlaybackManifest(track, config)` → `{ manifest, type: "dash", drm: "widevine"\|"fairplay", context }` and `relayLicense(challengeB64, context)` → licence; it reports `onPlaybackEvent(event, track)` back so the plugin sends telemetry | Host-owned playback with plugin-supplied manifest and licence | `widevine-bridge.js` + `amazon-dmls.js` + `amazon-telemetry.js` |

The plugin keeps desktop's behaviour: replay-first templates (24 h), one page flow at a time, the breaker, the plan gate and the prompt rules. Each host keeps only what's physically per-platform: the web view, the capture hook, the cookie store, the player and the quality-to-track-selection mapping.

**Sign-in: the plugin decides, the host shows and stores.** The plugin never sees a password or a cookie value.

| The plugin decides | The host does |
|---|---|
| The page that starts sign-in (`https://music.amazon.com/`) | Shows it in a visible window (Android Activity, iOS sheet) sharing the hidden session's cookies |
| When sign-in is finished: on `music.amazon.com`, past every wall (`/ap/*`, 2FA, the profile picker at `/identity/who-is-listening`), steady for 2 ticks, with an auth cookie present (desktop's `signInLanded`) | Each second, reports `{ url, cookieNames, stableTicks }` to the plugin's `done()`; closes the window when it answers yes. Closing it first ends the flow as not signed in |
| Signed in or not: which cookie names count (`at-main`, `sess-at-main`, `at-acb`) | `webSession.state()`: the current URL and cookie **names** only |
| A page load that hit a wall mid-session throws `not-signed-in` | Returns each load's final URL |
| Sign-out: the final telemetry event, then clear the session | `webSession.clear()` |
| The status text ("Signed in", "Sign in", "Prime: no playback") | A generic Sign in / Sign out row drawn from the plugin's status |

- **The cookies reach Amazon only through the host.** The host attaches them to `webSession.fetch` (`config.json`, the `dmls` manifest and licence, telemetry). The person types the password, does 2FA and picks the profile on Amazon's own pages.
- **When to show the sign-in window.** Desktop's rules (`src/renderer/lib/amazon-signin-prompt.js`) are: background lookups never prompt; retry quietly before prompting; 10 minutes of quiet after a decline. They move into the plugin as a pure method. The host calls it with what only the app knows: whether the person is looking at the app, whether the action was explicit, and when they last declined. The plugin answers prompt, retry quietly, or skip.
- **Cookie isolation differs per platform.** Desktop gives Amazon its own partition (`persist:amazon-music`), so sign-out wipes it whole.
  - **iOS** can match it with a dedicated `WKWebsiteDataStore(forIdentifier:)` (iOS 17+).
  - **Android** has one cookie jar (`CookieManager`) for every WebView in the app, shared with Apple Music's MusicKit page. So Android's `webSession.clear()` must delete only that session's own domains (`amazon.com`, `music.amazon.com` and their subdomains), never `removeAllCookies`, or signing out of Amazon would also sign you out of Apple Music.
  - The session therefore declares its cookie domains (`webSession.open(id, { cookieDomains })`). `state()` reports only those domains' cookie names, and `clear()` removes only them.

**Resolver-loader prerequisite.** `resolver-loader.js` builds each plugin method separately (`new Function('return ' + fnString)()`), so methods share nothing, and today every helper is copied into each method that uses it. Moving about 60 helpers into the plugin needs a plugin-level library evaluated once and handed to every method (for example `implementation._lib`, exposed as `this.lib`). Mobile's two copies of `resolver-loader.js` take the same change; a test already keeps them identical to desktop's.

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

0. **Desktop refactor first** (Parachord/parachord#1356): the resolver-loader's shared plugin library, the generic `webSession` / `drm` primitives, and Amazon's logic moved from `amazon-music-logic.js`, `amazon-web-player.js`, `amazon-dmls.js` and `amazon-telemetry.js` into `amazonmusic.axe`, with desktop's tests moved to test the plugin. Desktop behaviour unchanged. Mobile work starts once it lands; the spikes can run before.
1. **Shared host contracts.** `shared/commonMain`: the `webSession` / `drm` contracts each platform implements, the per-session limits desktop's bridge enforces (manifests per minute, licence relays per session), and the quality policy ported from `widevine-quality.js` with its test vectors. Run `:shared:iosSimulatorArm64Test`. No Amazon parsing here.
2. **Android discovery.**
   - `AmazonWebSession`: one hidden WebView (not attached to a window, `LAYER_TYPE_NONE`, like MusicKit). Page flows run one at a time; replays run in parallel; a template is replayed first, else the page flow; the view parks after 30 s; page loads time out at 30 s; the circuit breaker applies.
   - Replays use the unique-key poll for `evaluateJavascript` (CLAUDE.md: a bare async IIFE returns `{}`).
   - A sign-in Activity hosts a visible WebView with the same cookies, closing when the plugin's `done` says so. It's not a Custom Tab, because the app must read cookies and the URL.
   - The `window.parachordHost.webSession` functions go in `bootstrap.html`, forwarding to `NativeBridge`.
   - The prompt rules (background searches never prompt; a quiet retry first; 10 minutes of quiet after a decline) come with the plugin.
3. **Android playback.**
   - A generic `DrmPlaybackHandler` (not Amazon-specific; a `SourceHandler` producing an ExoPlayer `MediaItem` with DRM) for any plugin with host-owned DRM playback: `DashMediaSource` from the plugin's `getPlaybackManifest`; `DefaultDrmSessionManager` with a `MediaDrmCallback` that hands challenges to the plugin's `relayLicense` (the plugin sends the cert pre-charge and checks the plan before any manifest).
   - Player events go to the plugin's `onPlaybackEvent`, which sends Amazon's telemetry.
   - Licence denials and decode failures feed the shared family-failure table and fall back mid-track. Which denial means what (drop FLAC on `CUSTOMER_NOT_ELIGIBLE`, fail on a certificate denial) is the plugin's answer.
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
   - Discovery: the same plugin over iOS's `webSession` (WKWebView, `WKHTTPCookieStore`): playlist import from `music.amazon.com` URLs, album lookup, artist photos and recommendations.
   - Playback only if S6 passes: an `AVPlayer` engine with an `AVContentKeySession` relaying SPC/CKC through the plugin's `relayLicense`, the plugin asking dmls for FairPlay instead of Widevine.
   - Until then, Amazon is never a playback source on iOS: hidden from badges and picking, as for a Prime plan.
6. **Plugin gate and docs.**
   - Today `capabilities.mobile: false` hides the plugin everywhere. We need a per-platform switch so Android loads it and iOS loads it for discovery only.
   - Proposal for desktop (agreed; Parachord/parachord#1355): the plugin declares the host primitives it needs (`requires: { hostBridges: ["webSession"], playback: ["widevine", "fairplay"] }`), and each host reports what it supplies. A host missing the bridge hides the plugin. A host that has the bridge but can't play (iOS) keeps discovery and leaves Amazon out of playback.
   - Then add `minAppVersion.android` / `.ios` and drop `mobile: false`.
   - A plugin that's visible without its bridge would throw `not-ready` on every resolve, which #566 would retry indefinitely.
   - Update CLAUDE.md (a new "Amazon Music" section; resolver order; volume table), `iosApp/AGENTS.md` and the README.

## Risks

- **Capture (S2)** is the biggest unknown. Desktop found that a page-level `fetch` wrapper installs too late. Android's document-start script may win that race, but nobody has checked.
- **Licence on Android devices (S3).** Amazon rejects development Widevine certificates. Pixels carry production device certificates, but whether Amazon's licence policy accepts a non-Amazon Android client is untested.
- **Amazon may change anything without notice.** Desktop already treats every hash, header and response shape as captured rather than hard-coded, and keeps a circuit breaker. Mobile must do the same.
- **Account standing.** This uses Amazon's private web API, as desktop does. Telemetry must be accurate.
- **No concurrent-stream handling** exists on desktop to copy ("playing on another device").
- **Effort.** Desktop refactor: about a week (desktop's side, with its tests). Shared host contracts and quality policy: 1–2 days. Android web session and discovery: 3 days. Android DRM playback: 3–4 days. Settings/UI: 2 days. iOS discovery: 2 days. Spikes, in parallel with the refactor: 2–3 days.

## Decisions (2026-11-12)

1. **Test account:** theli-ua has Music Unlimited and runs the spikes that need it.
2. **iOS:** run S6 before deciding; FairPlay is required for iOS playback, and S6 says whether Amazon offers it.
3. **Plugin gate:** propose `requires.hostBridges` to desktop.
4. **Playback Quality:** add desktop's global setting on mobile, even though only Amazon reads it at first.
5. **Amazon logic lives in the plugin**, not in each host: desktop refactors first (Parachord/parachord#1356).
