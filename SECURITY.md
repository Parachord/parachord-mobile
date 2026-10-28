# Security Policy

This policy covers both apps in this repo: Parachord for **Android** and **iOS**, and the shared Kotlin Multiplatform core they run on.

## Reporting Vulnerabilities

Please report vulnerabilities privately through a [GitHub security advisory](https://github.com/Parachord/parachord-mobile/security/advisories/new). Don't open a public issue.

Please include:
- Description of the vulnerability
- Steps to reproduce
- Affected platform (Android, iOS or both) and files/components if known
- Your assessment of the impact

We'll acknowledge receipt within 48 hours and aim to resolve critical issues within 7 days.

## Security Model

### Credentials

OAuth tokens and bring-your-own API keys go through the shared `SecureTokenStore`:
- **Android:** `EncryptedSharedPreferences`, backed by an Android Keystore AES-256-GCM key.
- **iOS:** the Keychain (`kSecAttrAccessibleAfterFirstUnlock`).

Non-sensitive preferences (theme, sort order, queue state) use SharedPreferences on Android and NSUserDefaults on iOS.

On Android, cloud backup of all app data is disabled (`android:allowBackup="false"`, plus `dataExtractionRules` that exclude every directory).

### Client Secrets (Open Source Notice)

The following credentials are compiled into the build (Android `BuildConfig` from `local.properties`; iOS `Info.plist` from `Secrets.xcconfig`). They are **not confidential** in this open-source project:

- `LASTFM_API_KEY` / `LASTFM_SHARED_SECRET`: Last.fm API signing. Effectively a public client credential for an OSS project. If Last.fm's Terms of Service require confidentiality, we'll migrate to per-user credentials.
- `SPOTIFY_CLIENT_ID` (Android): Spotify OAuth public client identifier (PKCE flow, no client secret). On iOS each user enters their own client ID in Settings.
- `SOUNDCLOUD_CLIENT_ID` / `SOUNDCLOUD_CLIENT_SECRET` (Android): SoundCloud OAuth. PKCE mitigates the secret's exposure; functionally a public client.
- `APPLE_MUSIC_DEVELOPER_TOKEN`: the Apple Music developer JWT, generated at build time from the team's MusicKit key and valid for at most 180 days. The MusicKit private key itself is never committed or bundled.
- `ACHORDION_BEARER_TOKEN`: shared app token for submitting track links to Achordion.
- `TICKETMASTER_API_KEY` / `SEATGEEK_CLIENT_ID` (Android): fallback concert-discovery keys. Users can enter their own in Settings. Rate-limited, no access to sensitive data.

**Do not add new secrets to the build expecting confidentiality.** Use per-user credentials (like the AI providers do) for anything that needs to stay private.

### Plugin System (.axe)

Plugins are JavaScript files downloaded from the [parachord-plugins](https://github.com/Parachord/parachord-plugins) GitHub repository over HTTPS. Android runs them in a headless WebView (`JsBridge`), and iOS in JavaScriptCore. The runtime enforces:

- **Namespaced storage:** plugins can only read and write their own prefixed keys (`plugin.<id>.` on Android; `plugin.` / `parachord.` on iOS). App tokens and API keys live in the secure token store, which plugins can't reach.
- **Path validation:** plugin filenames must match `^[A-Za-z0-9_.-]+\.axe$`, with canonical-path containment checks.
- **Size limits:** manifest ≤ 1 MiB, individual plugins ≤ 5 MiB.
- **Manifest ID validation:** plugin IDs must match `^[A-Za-z0-9_-]+$`.

**Not yet enforced (deferred):**
- A host allowlist on plugin `fetch` (plugins can currently make HTTPS requests to any host)
- Plugin signing (HTTPS transport integrity is the current defense; repo access controls prevent supply-chain compromise)
- `file://` origin hardening on the Android plugin WebView

See issue [#107](https://github.com/Parachord/parachord-mobile/issues/107) for the plugin sandbox roadmap.

### Network Security

- **Android:** cleartext (HTTP) traffic is denied app-wide via `network_security_config.xml` and `android:usesCleartextTraffic="false"`.
- **iOS:** App Transport Security is left at its HTTPS-only default, with no exceptions.
- All API endpoints use HTTPS.
- Every request carries a `Parachord/<version>` User-Agent with a reachable contact URL, as MusicBrainz and ListenBrainz require.
- Hosted XSPF imports are HTTPS-only and reject private and loopback hosts, re-checked on every poll (SSRF). XML parsing disables external entities (XXE).

### OAuth

- Spotify and SoundCloud use **PKCE** (SHA-256 code challenge, 32 bytes of secure randomness).
- All flows include a cryptographically random **state parameter** for CSRF protection (192 bits on Android). On Android, pending flows (code verifier and state) are kept in encrypted storage so they survive the app being killed between the browser launch and the callback.
- On Android, the OAuth redirect is handled by a dedicated `OAuthRedirectActivity` (not `MainActivity`), so Chrome Custom Tabs close automatically.
- On iOS, sign-in runs in `ASWebAuthenticationSession`, which isolates the web session from the app.

### WebView Hardening (Android)

- The MusicKit WebView (`MusicKitWebBridge`) has a URL allowlist via `shouldOverrideUrlLoading`. Only Apple-related hosts and the app's asset-loader origin are permitted.
- The Apple ID sign-in popup WebView has the same allowlist.
- The `RESOURCE_PROTECTED_MEDIA_ID` (DRM) permission is gated on the requesting origin.
- All JS string interpolation in `evaluate()` uses Base64 encoding to prevent injection.

iOS plays Apple Music through the native MusicKit framework, so it has no MusicKit WebView.

### MediaSession (Android)

- `PlaybackService.onGetSession` validates connecting packages. Only system packages, our own app and trusted controllers are allowed.

## Completed Security Review

A comprehensive security review of the Android app and shared core was completed in April 2026. All Critical, High and Medium findings have been closed, except the plugin sandbox work tracked in [#107](https://github.com/Parachord/parachord-mobile/issues/107).
