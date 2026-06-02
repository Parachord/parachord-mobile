# Security Policy

## Reporting Vulnerabilities

If you discover a security vulnerability in Parachord, please report it responsibly:

1. **Email:** security@parachord.app
2. **GitHub:** Open a [security advisory](https://github.com/Parachord/parachord-mobile/security/advisories/new)

Please include:
- Description of the vulnerability
- Steps to reproduce
- Affected files/components if known
- Your assessment of the impact

We'll acknowledge receipt within 48 hours and aim to resolve critical issues within 7 days.

## Security Model

### Credentials

Parachord stores OAuth tokens and BYO API keys in Android's `EncryptedSharedPreferences` (backed by Android Keystore AES-256-GCM). Non-sensitive preferences (theme, sort order, queue state) use standard Jetpack DataStore.

Cloud backup of all app data is disabled (`android:allowBackup="false"` + `dataExtractionRules` that exclude all directories).

### Client Secrets (Open Source Notice)

The following credentials are compiled into `BuildConfig` from `local.properties` and are **not confidential** in this open-source project:

- `LASTFM_API_KEY` / `LASTFM_SHARED_SECRET` — Last.fm API signing. Effectively a public client credential for an OSS project. If Last.fm's Terms of Service require confidentiality, we'll migrate to per-user BYO credentials.
- `SPOTIFY_CLIENT_ID` — Spotify OAuth public client identifier (PKCE flow, no client secret needed).
- `SOUNDCLOUD_CLIENT_ID` / `SOUNDCLOUD_CLIENT_SECRET` — SoundCloud OAuth. PKCE mitigates the client secret exposure; functionally a public client.
- `APPLE_MUSIC_DEVELOPER_TOKEN` — Apple Music JWT. Each user provides their own in Settings.
- `TICKETMASTER_API_KEY` / `SEATGEEK_CLIENT_ID` — Concert discovery API keys. Rate-limited, no sensitive data access.

**Do not add new secrets to `BuildConfig` expecting confidentiality.** Use per-user BYO credentials (like the AI providers do) for anything that needs to stay private.

### Plugin System (.axe)

Plugins are JavaScript files downloaded from the [parachord-plugins](https://github.com/Parachord/parachord-plugins) GitHub repository over HTTPS. The plugin runtime (`NativeBridge`) enforces:

- **Namespaced storage:** Plugins can only read/write keys prefixed with `plugin.<id>.`. App-level tokens and API keys are inaccessible.
- **Path validation:** Plugin filenames must match `^[A-Za-z0-9_.-]+\.axe$` with canonical-path containment checks.
- **Size limits:** Manifest ≤ 1 MiB, individual plugins ≤ 5 MiB.
- **Manifest ID validation:** Plugin IDs must match `^[A-Za-z0-9_-]+$`.

**Not yet enforced (deferred):**
- Host allowlist on `NativeBridge.fetch*` (plugins can currently make HTTP requests to any host)
- Plugin signing (HTTPS transport integrity is the current defense; repo access controls prevent supply-chain compromise)
- `file://` origin hardening on the plugin WebView (`JsBridge`)

See GitHub issue [#107](https://github.com/Parachord/parachord-mobile/issues/107) for the plugin sandbox roadmap.

### Network Security

- Cleartext (HTTP) traffic is denied system-wide via `network_security_config.xml` and `android:usesCleartextTraffic="false"`.
- All API endpoints use HTTPS.
- A global `User-Agent` interceptor identifies the app on every OkHttp request.

### OAuth

- Spotify and SoundCloud use **PKCE** (SHA-256 code challenge, 32 bytes of SecureRandom).
- All flows include a cryptographically-random **state parameter** (192 bits) for CSRF protection.
- Pending OAuth flows (code verifier + state) are persisted in EncryptedSharedPreferences to survive app-kill between Custom Tab launch and callback.
- The OAuth redirect is handled by a dedicated `OAuthRedirectActivity` (not `MainActivity`) to ensure Chrome Custom Tabs close automatically.

### WebView Hardening

- The MusicKit WebView (`MusicKitWebBridge`) has a URL allowlist via `shouldOverrideUrlLoading` — only Apple-related hosts and the app's asset-loader origin are permitted.
- The Apple ID auth popup WebView has the same allowlist.
- `RESOURCE_PROTECTED_MEDIA_ID` (DRM) permission is gated on the requesting origin.
- All JS string interpolation in `evaluate()` uses Base64 encoding to prevent injection.

### MediaSession

- `PlaybackService.onGetSession` validates connecting packages — only system packages, our own app, and trusted controllers are allowed.

## Completed Security Review

A comprehensive security review was completed in April 2026. The full review plan is tracked internally. All Critical, High, and Medium findings have been closed. See issues [#105](https://github.com/Parachord/parachord-mobile/issues/105)–[#108](https://github.com/Parachord/parachord-mobile/issues/108) for remaining work.
