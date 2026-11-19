# Amazon Music on mobile: plan

Ticket: #443 (research). Desktop reference: Parachord/parachord `plugins-src/amazonmusic/` (resolver 1.21.3), `docs/architecture/web-session.md`, `docs/architecture/amazon-music.md`, `docs/architecture/plugins.md` ("The plugin-supplied Widevine path", "Required host bridges"), `main-process/web-session.js`, `main-process/ipc/web-session-bridge.js`, `src/renderer/lib/widevine-player.js`, `widevine-quality.js`. First written 2026-11-12; updated 2026-11-19 for what desktop shipped and for spike S2's result.

## Status (2026-11-19)

- **Step 0 is done on desktop.** Amazon's logic moved into `amazonmusic.axe` (Parachord/parachord#1356, M1–M11, closed): search, albums, artist photos, playlist import, recommendations, the sign-in rules, the plan check, the DRM pair and Amazon's telemetry. The host modules (`amazon-web-player.js`, `amazon-music-logic.js`, `amazon-dmls.js`, `amazon-telemetry.js`) are deleted. A host supplies only the generic `webSession` bridge and a Widevine player.
- **The plugin gate is done on desktop.** Plugins declare `requires.hostBridges` (Parachord/parachord#1355, closed); `amazonmusic.axe` declares `["webSession"]`. It still carries `capabilities.mobile: false` and no `minAppVersion.android` / `.ios`.
- **Spike S2 passed on a Pixel 9a** (2026-11-19, a signed-in Amazon account without Music Unlimited). Details under "Spikes". The spike screen is on branch `spike/amazon-s2` (debug-only, not for merge).
- **Next:** S3 (does Amazon license a Pixel's Widevine through ExoPlayer?), which needs Music Unlimited.

## Summary

- **Android can do all of it**: sign-in, search and resolve, albums, playlist import, artist photos, recommendations and playback. Playback goes through **ExoPlayer** (DASH + Widevine), not a hidden Amazon web player.
- **iOS: discovery for sure; playback depends on FairPlay.** Desktop plays through Widevine, and iOS has no Widevine: AVPlayer and WKWebView only do FairPlay, so FairPlay is **required** for any iOS playback. The plugin's DRM pair asks Amazon for Widevine only (`DrmType: 'WIDEVINE'`), so iOS playback would also need a plugin change. Whether it's **possible** at all is spike S6.
- **The deciding spike for discovery, S2, passed**: a WebView can capture Amazon's own GraphQL request and replay it.
- **Amazon's logic lives in the plugin; the hosts are thin.** Mobile ports none of it to Kotlin (CLAUDE.md, Common Mistakes #6 and the extensibility principle). A fix to Amazon's parsing reaches all three apps through the marketplace.

## How desktop does it now (plugin 1.21.x)

The plugin drives one host web session (`amazon-music`) through `window.electron.webSession` and owns every Amazon-specific step:

1. **Discovery by capture and replay.** The plugin loads an Amazon page (`/search/<q>/songs`, `/albums/<ASIN>`, `/playlists/<id>`, the home page) with a capture window on `gql.music.amazon.com`. It finds the persisted-query request the page fired (`SpecSearchSeeMorePage`, `AlbumDetailPage`, the playlist and home queries), keeps it as a template (fresh 24 h), and replays it from inside the page with patched variables (`pageFetch`). Page flows run one at a time in two lanes (foreground first); replays run in parallel; a circuit breaker opens after repeated structural failures; our own replay traffic comes back flagged `echo` and never mints a template.
2. **Sign-in** in a visible window sharing the session. The plugin's pure `decide({ url, cookieNames, stableTicks })` says when it's finished: on `music.amazon.com`, past every wall (`/ap/*`, the profile picker at `/identity/who-is-listening`), with an auth cookie (`at-main`, `sess-at-main`, `at-acb`). "Signed in" is read from cookie names only. The session's `generation` changes on sign-out, and the plugin drops every cache minted under the old one.
3. **Playback** has no Amazon page. The plugin reads `config.json` through `webSession.fetch`, refuses any plan but Music Unlimited (`tier` `/^UNLIMITED/i`) before asking for a manifest, asks `POST /<NA|EU|FE>/api/dmls/` for a DASH manifest (`getDashManifestsV2`), and relays licence challenges through the same endpoint (`getLicenseForPlaybackV2`, after a cert pre-charge). The host's Widevine player (Shaka) plays the manifest and calls the plugin's `relayLicense` for each challenge. Player events go to the plugin's `handlePlayerEvent`, which sends Amazon's telemetry.
4. **Status.** `capabilities.status`: the plugin's `getStatus` gives signed in or not, the plan and why playback is blocked; the host draws a generic sign-in row from it. `signInPromptDecision` is the plugin's rule for when to show the sign-in window.
5. **Quality** is host policy (`widevine-quality.js`): the global Playback Quality tiers, Adaptive, and "Prefer Dolby Atmos"; a refused codec family falls back mid-track.

## What a mobile host supplies

### The `webSession` bridge (shipped names)

The plugin calls `window.electron.webSession.<method>('amazon-music', …)`. The `window.electron` namespace is hard-coded in the plugin, so a mobile host either exposes the same names or desktop renames the bridge to a host-neutral global first (preferred; a small plugin change). Contract: `docs/architecture/web-session.md`.

| Call | Returns | Android |
|---|---|---|
| `load(id, url, { timeoutMs, capture: { urlPrefixes, quietMs, budgetMs, doneWhen?, returnOnTimeout? } })` | `{ success, finalUrl, captures: [{ url, method, headers, body, echo? }], loadTimedOut? }` | One hidden WebView per session; a document-start script (`WebViewCompat.addDocumentStartJavaScript`) wraps `fetch` and `XMLHttpRequest` and records matching requests; the capture window ends on quiet, budget, or a `doneWhen` match; loads serialize in two lanes |
| `pageFetch(id, { url, method, headers, body })` | `{ success, status, text }` or `{ success, error }` (`busy`, `no-origin-page`, `page-off-host`) | `fetch` run inside the page via the unique-key poll (an async IIFE returns `{}` from `evaluateJavascript`); never throws; waits on the navigation gate |
| `fetch(id, request)` | `{ success, status, headers, text }` | Ktor (or OkHttp) with the session's cookies from `CookieManager`, exact-host allowlist re-checked on every redirect, set-cookie names only back |
| `state(id)` | `{ url, signInUrl, cookieNames, generation }` | The hidden view's URL, the sign-in screen's, cookie names for the session's cookie domains, and a counter bumped by `clear()` |
| `signIn(id, url, { decide })` | `{ success, signedIn }` | A visible Activity with a WebView sharing the cookies, ticking once a second into the plugin's `decide`; closing it first is not signed in |
| `clear(id)` | `{ success }` | Delete only the session's cookie domains (`amazon.com`, `music.amazon.com`, `www.amazon.com`), never `removeAllCookies`: Android has one cookie jar, shared with Apple Music's MusicKit page |

Host config, not plugin data: the cookie domains, the exact-host allowlist (`music.amazon.com`, `gql.music.amazon.com`), the sign-in title, a desktop Chrome user agent, and the budget caps (load ≤ 60 s, capture budget ≤ 30 s, quiet ≤ 10 s).

The host must also advertise the bridge (`window.__parachordHostBridges` includes `webSession`) and honour `requires.hostBridges` when loading plugins: a plugin whose bridge is missing is hidden, never loaded to throw `not-ready` on every resolve (#566 would retry it forever). Mobile reads neither today; the rule is desktop's `plugin-compat.js`, and the port belongs in shared `PluginCompat.kt`.

### The Widevine player

A generic `DrmPlaybackHandler` (any plugin with `capabilities.widevine` and the plugin pair), not Amazon-specific:
- Calls the plugin's `getPlaybackManifest(track, config)` (10 s budget) → `{ manifest, type: 'dash', drm: 'widevine', context, … }`, and plays the MPD text through ExoPlayer's `DashMediaSource`.
- `DefaultDrmSessionManager` with a `MediaDrmCallback` that hands each challenge (base64) to the plugin's `relayLicense(challengeB64, context)` and returns its licence. The context stays host-side and is passed back as is.
- Player lifecycle events go to the plugin's `handlePlayerEvent`.
- The error fields are load-bearing: `.reason` (`plan-ineligible` with `.planKey`, `not-signed-in`, `load-failed`, `playback-failed`), and on licences `.licenseDenied` and `.denialReason` (a per-content refusal drops that codec family and falls back). They map to `PlayFailure`.
- It's ExoPlayer, so the local-track rules apply (the #614 foreground keep, the car, resume at position); none of the external-engine foreground rules do.
- The plugin's `cdmAvailable()` asks `navigator.requestMediaKeySystemAccess('com.widevine.alpha')` in the JS runtime. On Android the plugin host is a WebView, which needs `RESOURCE_PROTECTED_MEDIA_ID` granted to answer yes; on iOS (JavaScriptCore, no `navigator`) it answers no, which is right.
- `appVersion()` reads `window.electron.updater.getVersion` for Amazon's music agent string and falls back to `dev`; a host should supply its version.

## Spikes (a Music Unlimited account needed for S3–S6)

| # | Question | Pass when | Result |
|---|---|---|---|
| S1 | In a WebView (desktop Chrome UA, persistent cookies), can a person sign in, pass 2FA and the profile picker, and land on `music.amazon.com`? | `at-main` / `sess-at-main` present and a search page renders results | **Passed (visible WebView), 2026-11-19, Pixel 9a.** Not yet tried from a hidden view's session handed to a visible sign-in screen |
| S2 | Can we capture the page's own `SpecSearchSeeMorePage` request (body + headers) and replay it with a new `searchText`? | A replay from the page returns `data.specificationSearch` | **Passed, 2026-11-19, Pixel 9a.** See below |
| S3 | Does `config.json` + a dmls manifest + a licence relayed through a `MediaDrmCallback` play in ExoPlayer on a Pixel (Widevine L1)? Also an L3 emulator. | Audio plays; note any `denialReason` | Not run (needs Unlimited). theli-ua reports music.amazon.com plays in Firefox for Android, which uses the phone's Widevine too: encouraging, not proof |
| S4 | Does Amazon accept our telemetry events? | 200 for `streamingInitiated` / `trackStreamed` | Not run |
| S5 | Does playback survive 10 min screen-off and a full album, and Android Auto? | — | Not run |
| S6 | (iOS) Does music.amazon.com play in Safari, and with which DRM? Record the dmls manifest and licence calls. | A FairPlay manifest and a CKC that AVPlayer plays | Not run. A pass also needs the plugin to ask for FairPlay |

**S2 details.** A debug-only Activity (`spike/amazon-s2`) loaded `music.amazon.com/search/<q>/songs` in a visible WebView with a desktop Chrome UA and a document-start script wrapping `fetch` and XHR:
- **The wrapper sees every GraphQL request** with its body and headers: `x-api-key`, `csrf-token`, `csrf-rnd`, `csrf-ts`, the `x-amzn-*` headers and the persisted-query hash. Desktop's page-level stub lost this race; Android's document-start script runs first. The page also installs its own `window.fetch` wrapper (a performance tracer) on top of ours, and it still calls ours.
- **The page posts to `https://gql.music.amazon.com` with no trailing slash.** Desktop's capture prefix is `https://gql.music.amazon.com/*`, so a mobile capture must match the host, not that prefix string, or it captures nothing.
- **Signed out, the page searches through another API** (`na.web.skill.music.a2z.com/api/searchCatalogTracks`), not GraphQL. The plugin requires sign-in anyway.
- **A cold first load went to Home.** The first `/search/…` load after a fresh start went through `/?searchTerm=…` and ended on Home with no search fired; the same URL on the warm page fired `SpecSearchSeeMorePage` (`specType: "track"`, `firstLimit: 30`). The web session should warm the page (load `music.amazon.com/`) before its first search, or the plugin counts the miss as structural.
- **The replay worked.** The captured request, with `searchText` changed and the captured headers, sent from the page with `credentials: 'include'`, returned 200 with `data.specificationSearch` and real results (for "khruangbin": "Lady and Man", "August 10", "White Gloves", "Maria También").
- **Not covered:** the album and artist tabs (`specType` `album` / `artist`), album lookup, playlist import pagination, recommendations, and a hidden (unattached) WebView. They ride the same mechanism.

**Go / no-go.** S1 + S2 pass, so discovery can ship on Android. S3 + S4 must pass for Android playback. iOS playback needs S6 and a plugin change.

## Phases

0. **Desktop refactor.** Done (Parachord/parachord#1356, #1355).
1. **Desktop follow-ups for mobile** (small; desktop tickets to file):
   - A host-neutral name for the bridge instead of `window.electron.webSession` (and a host-neutral version getter), or agree that mobile exposes `window.electron`.
   - `minAppVersion.android` once the Android host ships, and drop `capabilities.mobile: false` (`requires.hostBridges` does the hiding).
   - For iOS playback later: let the DRM pair ask for FairPlay.
2. **Shared host contracts.** `shared/commonMain`: the `requires.hostBridges` rule in `PluginCompat.kt` and `PluginManager` (hide a plugin whose bridge the platform lacks); the `webSession` contract and its limits (caps, exact-host allowlist, cookie domains); the per-session DRM limits (manifests per minute, 30 licence relays per session); the quality policy ported from `widevine-quality.js` with its vectors. Run `:shared:iosSimulatorArm64Test`. No Amazon parsing here.
3. **Android web session and discovery.** `WebSessionHost` (generic; Amazon registers one instance): the hidden WebView (not attached to a window, `LAYER_TYPE_NONE`, like MusicKit), the document-start capture script, the two-lane load chain, the navigation gate for `pageFetch`, echo flags, the idle park, the warm-up load, the sign-in Activity, scoped `clear()`. Expose it to the plugin runtime in `bootstrap.html` through `NativeBridge`, with the unique-key poll.
4. **Android playback.** The generic `DrmPlaybackHandler` above; Playback Quality mapped to ExoPlayer track selection; volume offset 0 dB.
5. **Settings and UI (Android).** A sign-in row and status drawn from the plugin's `getStatus` (generic, any `capabilities.status` plugin); the Playback Quality card with desktop's keys (`playback_quality`, `playback_abr`, `playback_spatial`); resolver colour `#25D1DA` and the order from desktop. A blocked plan keeps discovery and leaves Amazon out of playback and badges.
6. **iOS** (if S2 also passes in a `WKWebView` with a `WKUserScript` at document start): discovery only, until S6 and the plugin's FairPlay path. Amazon is never a playback source on iOS until then.
7. **Docs.** CLAUDE.md (an "Amazon Music" section; resolver order; volume table), `iosApp/AGENTS.md`, the README.

## Risks

- **Licence on Android devices (S3).** Amazon rejects development Widevine certificates. Pixels carry production certificates, but whether Amazon's licence policy accepts our client is untested.
- **Amazon may change anything without notice.** The plugin treats every hash, header and response shape as captured, not hard-coded, and keeps a breaker; the mobile host must not hard-code any of it either.
- **Account standing.** This uses Amazon's private web API, as desktop does. Telemetry must be accurate.
- **The bridge's `window.electron` name** ties the plugin to desktop's preload; see phase 1.
- **Effort** (after step 0): shared contracts and plugin gating 2 days; Android web session and discovery 3–4 days; Android DRM playback 3–4 days; settings/UI 2 days; iOS discovery 2 days.

## Decisions

2026-11-12:
1. **Test account:** theli-ua has Music Unlimited and runs the spikes that need it.
2. **iOS:** run S6 before deciding; FairPlay is required for iOS playback.
3. **Plugin gate:** propose `requires.hostBridges` to desktop (shipped as Parachord/parachord#1355).
4. **Playback Quality:** add desktop's global setting on mobile, even though only Amazon reads it at first.
5. **Amazon logic lives in the plugin**, not in each host (shipped as Parachord/parachord#1356).

2026-11-19:
6. **Discovery on Android is feasible** (S1, S2 passed). Playback waits on S3.
