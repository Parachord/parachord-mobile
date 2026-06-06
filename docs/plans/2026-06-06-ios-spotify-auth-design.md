# iOS Spotify — Auth + Resolution + Playback — Design

**Date:** 2026-06-06
**Status:** Validated, ready for implementation planning
**Goal:** Connect Spotify on iOS (OAuth) → Spotify search results appear in
resolution → Spotify tracks play via Spotify Connect.

---

## Guiding principle (settled during brainstorming)

**iOS should not diverge from Android.** Android resolves Spotify with a
*native* Kotlin path (`ResolverManager.resolveSpotify` → `searchSpotifyTrack`
→ shared `SpotifyClient`), not the `.axe` — the `spotify.axe` is loaded but
dormant (`it.id !in nativeResolverIds`). `searchSpotifyTrack` is already pure
and uses only the shared `SpotifyClient`, so the right move is to **lift it
into shared and have BOTH platforms call it** — not to inject a token into
`spotify.axe` on iOS. The shared auth layer then serves resolution *and*
playback with one wiring.

Source-by-source nuance (not a blanket "iOS = Android"): Spotify goes
shared-native (it needs auth, `SpotifyClient` handles it cleanly). iOS Apple
Music resolution stays on the `.axe` (the no-auth iTunes-Search path that
already works on iOS and that Android doesn't use). Use shared-native where
clean, `.axe` where it's the pragmatic working path.

---

## What already exists (don't rebuild)

- iOS OAuth flow: `IosOAuthManager.authorize(.spotify(clientId:))` → `code` +
  PKCE `codeVerifier` + `state` (ASWebAuthenticationSession). Complete.
- Shared token storage: `SettingsStore` has `spotify_access_token` /
  `spotify_refresh_token` / `…_expires_at` (Keychain-backed `SecureTokenStore`).
- Shared `SpotifyClient(httpClient, tokens: AuthTokenProvider, …)` with
  `search()`, `getDevices()`, `startPlayback()`.
- Shared `HttpClient` with `OAuthRefreshPlugin` (401 → `OAuthTokenRefresher`).
- Auth interfaces: `AuthTokenProvider.tokenFor(realm)/invalidate(realm)`,
  `OAuthTokenRefresher.refresh(realm)`, `AuthRealm` enum, `AuthCredential.BearerToken`.
- `IosSpotifyConnect`: `wakeSpotify()` (`spotify://` deep link),
  `refreshInstalled()`, `play(uri:)` stub, `canPlay = false`.

---

## Section 1 — OAuth completion + token layer

- **Token exchange** (`IosSpotifyAuth`, new): take `OAuthResult` → POST
  `accounts.spotify.com/api/token` PKCE public-client grant (no secret):
  `grant_type=authorization_code, code, redirect_uri, client_id, code_verifier`
  → `access_token` / `refresh_token` / `expires_in`. Persist via
  `SettingsStore.setSpotifyTokens(...)`.
- **Real auth provider + refresher** (replace iOS `NoAuthTokenProvider` /
  `NoOpTokenRefresher`):
  - `SpotifyAuthTokenProvider: AuthTokenProvider` — `tokenFor(.spotify)` returns
    stored access token as `BearerToken` (refresh first if expired);
    `invalidate(.spotify)` clears it.
  - `SpotifyTokenRefresher: OAuthTokenRefresher` — `refresh(.spotify)` POSTs
    `grant_type=refresh_token` + `client_id`, saves + returns the new token.
    The HttpClient's `OAuthRefreshPlugin` calls this on 401.
- **Client ID**: `AppConfig.spotifyClientId`, sourced on iOS from Info.plist
  (`SpotifyClientID`), defaulting to the project's public Parachord client_id
  (PKCE client_ids aren't secret). **Prerequisite (user):** register redirect
  URI `parachord://auth/callback/spotify` on that Spotify app.
- **`SpotifyClient` in `IosContainer`** — constructed with the real
  `SpotifyAuthTokenProvider` so resolution + playback authenticate.

## Section 2 — Shared resolution + Settings UI

- **Move `searchTrack()` into shared `SpotifyClient`**:
  `suspend fun searchTrack(query): ResolvedSource?` (the ~20-line
  `searchSpotifyTrack` body — `search()` + pick first `isPlayable != false` +
  map to `ResolvedSource`).
  - **Android** `resolveSpotify` → `spotifyClient.searchTrack(query)` (replace
    local copy; identical behavior, just relocated — low risk).
  - **iOS** `IosResolverCoordinator`: drop `"spotify"` from the `.axe`
    `STREAMING_RESOLVERS`; add a native branch calling
    `spotifyClient.searchTrack(query)`, re-scored via `scoreConfidence` like the
    other sources. `spotify.axe` dormant on iOS. No JS token injection.
- **Settings "Connect Spotify"** (`SettingsView`, OAuth-driven, mirrors the
  Last.fm/LB pattern):
  - Disconnected → button → `IosOAuthManager.authorize` → exchange → store.
  - Connected (observe `getSpotifyAccessTokenFlow()` non-null) → "Connected" +
    Disconnect (clear tokens + `invalidate(.spotify)`).
  - Errors (cancel / state-mismatch / exchange failure) inline; `OAuthError`
    already models them.

## Section 3 — Connect playback

`IosSpotifyConnect.play(uri:)` (replace stub), inject `spotifyClient`,
`canPlay = (spotifyToken != nil)`. Single-pass (no compounding retries), iOS's
one wake path:

1. `getDevices()`. Usable device exists → step 3.
2. No device → `wakeSpotify()` (`spotify://` foregrounds Spotify — iOS's only
   option, briefly jarring) → poll `getDevices()` at 300 ms up to ~10 s.
3. `pickDevice`: prefer active, else first non-`restricted`; filter Free-account
   restricted devices. None → `false` → router falls through.
4. `startPlayback(SpPlaybackRequest(uris: [uri]), deviceId:)` — **with
   `device_id`, NEVER a separate `transferPlayback` first** (CLAUDE.md: transfer
   causes an audible blip; `startPlayback` activates inactive devices). Single
   502 retry.
5. `true` on 2xx; `false` on no-device / `403` (non-Premium) / error.

Router's `spotify` branch already gates on `canPlay` + `spotifyUri` → lights up
automatically.

**Edges:** non-Premium → 403 → false ("Premium required"); 429 → `SpotifyClient`
cooldown; token expiry → 401-refresh plugin; no Spotify app → `canOpenURL`
false → wake no-ops → no device → fall through.

---

## Verification

- Build green on simulator.
- **Auth + resolution: testable with ANY Spotify account** — connect, then
  Spotify badges/sources appear on tracks.
- **Playback: needs Premium + the Spotify app on the device** (on-device test).
- Android regression check: Spotify resolution still works after `resolveSpotify`
  calls the shared `searchTrack` (run the app, confirm Spotify badges).

## Scope / YAGNI

- One wake path (no Android-style media-button broadcast — no iOS equivalent).
- `pickDevice` kept simple (no synthetic "this device" / `Build.MODEL` match).
- Device-selection logic could move to shared later; stays in `IosSpotifyConnect`
  for v1.
- SoundCloud stays dormant (closed API).

---

## Files

- Shared: `api/SpotifyClient.kt` (`searchTrack`), `config/AppConfig.kt`
  (client_id already a field).
- `iosMain`: `IosSpotifyAuth` (new, token exchange), `SpotifyAuthTokenProvider`
  + `SpotifyTokenRefresher` (new), `IosContainer` (wire providers + SpotifyClient
  + expose connect/disconnect + `resolveSpotifyNative`), `IosResolverCoordinator`
  (native spotify branch).
- Swift: `IosSpotifyConnect.play` (ContentView.swift), `SettingsView` (Connect
  Spotify row), Info.plist (`SpotifyClientID`).
- Android: `resolver/ResolverManager.kt` (`resolveSpotify` → shared `searchTrack`).
