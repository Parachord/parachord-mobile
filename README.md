# Parachord for Android

A unified music player that brings Spotify, Apple Music, SoundCloud, Bandcamp, and your local files into one queue. Parachord resolves every track across your available sources and plays from the best match based on a configurable resolver priority.

Android companion to the [Parachord desktop app](https://github.com/Parachord/parachord) — same resolver pipeline, same plugin system, same playlist sync and very close to feature parity. 

## Install

**Beta testers** — join the [internal testing track](https://groups.google.com/g/parachord-testers) to get automatic updates through the Play Store.

or

**Sideload** — grab the signed APK from the [latest release](https://github.com/Parachord/parachord-mobile/releases/latest) and install directly. Requires "Install unknown apps" permission for your browser or Files app.

## Screenshots

<table>
  <tr>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/home.png" alt="Home screen" width="240"><br>
      <sub><b>Home</b> — playlists, recent listens, Discover tiles</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/sidebar.png" alt="Sidebar" width="240"><br>
      <sub><b>Sidebar</b> — friends with live listening status</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/fab.png" alt="FAB menu" width="240"><br>
      <sub><b>Quick actions</b> — DJ chat, create/import playlist, add friend</sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/now-playing.png" alt="Now Playing" width="240"><br>
      <sub><b>Now Playing</b> — resolver chip shows active source</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/queue.png" alt="Up Next queue" width="240"><br>
      <sub><b>Queue</b> — mixed Spotify + Apple Music rows</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/shuffleupagus.png" alt="Shuffleupagus AI DJ" width="240"><br>
      <sub><b>Shuffleupagus</b> — natural-language DJ (ChatGPT / Claude / Gemini)</sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/fresh-drops.png" alt="Fresh Drops" width="240"><br>
      <sub><b>Fresh Drops</b> — new releases from artists you follow</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/critical-darlings.png" alt="Critical Darlings" width="240"><br>
      <sub><b>Critical Darlings</b> — top-rated albums with AI blurbs</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/recommendations.png" alt="Recommendations" width="240"><br>
      <sub><b>Recommendations</b> — ListenBrainz + Last.fm blended feed</sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/charts.png" alt="Pop of the Tops charts" width="240"><br>
      <sub><b>Pop of the Tops</b> — global charts</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/concerts.png" alt="Concerts" width="240"><br>
      <sub><b>Concerts</b> — Ticketmaster + SeatGeek, library-aware</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/friend-history.png" alt="Friend history" width="240"><br>
      <sub><b>Friends</b> — their Last.fm top albums at a glance</sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/playlists.png" alt="Playlists" width="240"><br>
      <sub><b>Playlists</b> — hosted XSPF + Spotify-synced side by side</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/artist.png" alt="Artist page" width="240"><br>
      <sub><b>Artist</b> — discography with release-type filter</sub>
    </td>
    <td align="center" valign="top" width="33%">
      <img src="docs/screenshots/sync.png" alt="Sync wizard" width="240"><br>
      <sub><b>Sync wizard</b> — pick what to pull from Spotify</sub>
    </td>
  </tr>
</table>

## Features

### Unified playback
- **Spotify Connect** — controls Spotify on your phone or any Connect-capable device (Premium required). Uses the Web API directly; no Spotify App Remote SDK.
- **Apple Music** — via MusicKit JS inside a hidden WebView. Subscription required.
- **SoundCloud** — native ExoPlayer streaming, no browser redirect.
- **Local files** — with automatic artwork extraction and online enrichment for tracks missing embedded art.
- **Bandcamp** — resolves tracks and opens in the browser for playback (matches desktop).
- **Direct HTTP streams** — anything with a URL, via ExoPlayer.

One queue across all sources. Tap any track and Parachord figures out where to play it from.

### Resolver pipeline
Two-tier scoring — priority-first, confidence-second. A Spotify result at 70% confidence beats SoundCloud at 95% when Spotify is ranked higher. Sub-threshold matches (< 60% confidence) are filtered so wrong-song results never play.

Default order: `spotify > applemusic > bandcamp > soundcloud > localfiles > youtube`. User-configurable.

### Playlist sync
- **Spotify bi-directional sync** — import your playlists, saved tracks, albums, and followed artists. Locally-created playlists push up.
- **Hosted XSPF** — import any `.xspf` URL (e.g. a radio station's "recently played" feed) and Parachord polls every 5 minutes to keep local + Spotify in lockstep.
- **Three-layer dedup** prevents duplicate remote playlists on re-sync.

Apple Music sync is on the roadmap ([#15](https://github.com/Parachord/parachord-mobile/issues/15)); playback works today.

### Discovery
- **Recommendations** — personalized albums and artists from Last.fm + ListenBrainz, plus an AI-generated feed (your own ChatGPT/Claude/Gemini key).
- **Fresh Drops** and **Critical Darlings** — curated new releases.
- **Pop of the Tops** — global chart albums.
- **Weekly Jams** and **Weekly Exploration** — the last four weeks of each, pulled automatically from ListenBrainz.
- **On Tour** — teal dot next to the artist name when they're playing near you. Filter radius configurable.
- **Friends** — see what your Last.fm / ListenBrainz contacts have been playing.
- **Concerts** — Ticketmaster + SeatGeek search, location-filtered.

### AI features (BYOK)
- **DJ chat (Shuffleupagus)** — natural-language control: "play something like Beach House but more upbeat", "queue the new Waxahatchee album", "skip to Sugar".
- **Album and artist recommendations** grounded in your actual listening history.
- **Providers** — ChatGPT, Claude, Gemini. Ollama is .axe-pluggable but disabled on mobile (needs a local server).

### Scrobbling
- **ListenBrainz**, **Last.fm**, and **Libre.fm**.
- **MBID enrichment** — every track gets MusicBrainz identifiers in the background via ListenBrainz's MBID Mapper (~4ms lookup, 90-day disk cache). Scrobble payloads include `recording_mbid`, `artist_mbids`, `release_mbid`.

### Sharing
- **Smart links** via `go.parachord.com` — share a track, album, playlist, or artist and recipients get a rich Open Graph preview with per-service listen buttons (Spotify, Apple Music, SoundCloud). Works regardless of the recipient's platform.
- Deeplink fallback to `parachord.com/go` when the smart-link API is unreachable.

### .axe plugin system
Parachord Android runs the same 19 plugins as desktop — bundled as `.axe` files, hot-reloadable from the `parachord-plugins` GitHub repo over a 24-hour debounce. Plugin types: content resolvers, AI providers, meta-services (Last.fm, MusicBrainz, Discogs, Wikipedia), scrobblers, concert services. YouTube and Ollama are filtered out on mobile via `capabilities.mobile: false`.

Native Kotlin resolvers (Spotify, Apple Music, SoundCloud, local files) take priority over the `.axe` equivalents for speed.

### Background resilience
External playback (Apple Music, Spotify Connect) survives screen-off, Doze mode, and Android's foreground-service killer. Silent 1s WAV loops on ExoPlayer keep `MediaSessionService` happy while DRM audio flows through MusicKit or Spotify. See CLAUDE.md "External Playback Background Survival" for the rationale.

## Requirements

- Android 8.0+ (API 26)
- **Spotify Premium** for Spotify playback (free tier can browse but can't stream via Web API)
- **Apple Music subscription** for Apple Music playback
- Your own API keys for AI features (ChatGPT / Claude / Gemini) — set in Settings

## Setup (development)

### Prerequisites
- Android Studio Ladybug (2024.2) or newer
- JDK 17
- Android SDK 35

### API keys
Copy the example config and fill in your keys:

```bash
cp local.properties.example local.properties
```

| Key | Required | Get it from |
|-----|----------|-------------|
| `LASTFM_API_KEY` | Yes | https://www.last.fm/api/account/create |
| `LASTFM_SHARED_SECRET` | Yes | Same as above |
| `SPOTIFY_CLIENT_ID` | Yes | https://developer.spotify.com/dashboard |
| `SOUNDCLOUD_CLIENT_ID` | Optional | https://soundcloud.com/you/apps |
| `SOUNDCLOUD_CLIENT_SECRET` | Optional | Same as above |
| `APPLE_MUSIC_DEVELOPER_TOKEN` | Optional | Apple Developer Account (MusicKit JS) |

AI provider keys (ChatGPT / Claude / Gemini / Ticketmaster / SeatGeek) are configured per-user in Settings, not at build time — keeping them out of the open-source build config.

### Build and install

```bash
# Build + install debug APK on connected device
./gradlew installDebug

# Force-stop the old process so Android picks up the new code
adb shell am force-stop com.parachord.android.debug

# Run unit tests
./gradlew :app:testDebugUnitTest
```

Release builds require a keystore — the CI workflow handles signing and publishing. See [docs/play-store-publishing.md](docs/play-store-publishing.md) for details.

### Cutting a release

```bash
./scripts/release-version.sh 0.4.0-beta.3
git push && git push origin v0.4.0-beta.3
```

Tag-driven: the `v*` push triggers the release workflow, which signs the APK + AAB, attaches both to a GitHub Release, and uploads the AAB to the Play Console internal track. Prerelease version names (hyphen in the semver) auto-route to `internal` regardless of the track flag.

## Architecture

The app mirrors the [desktop Parachord app's](https://github.com/Parachord/parachord) architecture, adapted for Android idioms. Detailed design notes live in [CLAUDE.md](CLAUDE.md).

### Tech stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin (Multiplatform-ready — `:shared` module) |
| UI | Jetpack Compose + Material 3 |
| Playback | Media3 / ExoPlayer, Spotify Web API (Connect), MusicKit JS (WebView) |
| Database | SQLDelight (replaced Room, April 2026) |
| DI | Koin (replaced Hilt, April 2026) |
| Networking | OkHttp + Retrofit (app), Ktor (shared module) |
| Images | Coil |
| Preferences | DataStore (non-sensitive), EncryptedSharedPreferences via `SecureTokenStore` (OAuth tokens, API keys — AES-256-GCM backed by Android Keystore) |
| Plugins | WebView-hosted JS via `JsBridge` → `resolver-loader.js` (shared with desktop) |

### Project layout

```
app/src/main/java/com/parachord/android/
├── ai/              # AI DJ service (ChatGPT, Claude, Gemini, Ollama wrapper)
├── auth/            # OAuth flows (Spotify, Last.fm, Apple Music, SoundCloud)
├── bridge/          # JS runtime wrapper for .axe plugins
├── data/
│   ├── api/         # Retrofit clients (Spotify, Last.fm, MusicBrainz, ListenBrainz, Ticketmaster, SeatGeek)
│   ├── db/          # SQLDelight-backed DAOs + bridge typealiases to shared models
│   ├── metadata/    # Cascading metadata providers, MBID enrichment, image enrichment
│   ├── repository/  # Library, concerts, recommendations, weekly playlists
│   ├── scanner/     # Local media file scanner
│   └── store/       # DataStore prefs, EncryptedSharedPreferences wrapper
├── deeplink/        # Spotify / Apple Music / parachord:// URL routing
├── playback/
│   ├── handlers/    # SpotifyPlaybackHandler, AppleMusicPlaybackHandler, SoundCloudPlaybackHandler, MusicKitWebBridge
│   ├── scrobbler/   # ListenBrainz, Last.fm, Libre.fm, .axe scrobbler wrapper
│   ├── PlaybackController.kt
│   ├── PlaybackService.kt
│   └── QueueManager.kt
├── playlist/        # Hosted XSPF poller + scheduler
├── resolver/        # Track resolution pipeline and scoring
├── share/           # Smart links + share sheet
├── sync/            # Playlist / library sync (Spotify; Apple Music planned)
├── ui/              # Compose UI — screens, components, navigation, theme
└── widget/          # Home screen mini player widget

shared/src/commonMain/kotlin/com/parachord/shared/
├── model/           # Track, Album, Artist, Playlist, etc.
├── api/             # Ktor clients for cross-platform use
├── db/              # SQLDelight-generated entities + queries
├── plugin/          # PluginManager, JsRuntime interface
└── platform/        # expect/actual (Log, randomUUID, time)
```

### Key design patterns

- **Metadata cascade** — MusicBrainz (IDs, discography, tracklists) → Last.fm (images, bios, tags) → Spotify (album art, preview URLs). Later providers fill gaps from earlier.
- **Resolver scoring** — priority-first (user-configurable), confidence-second (0.0–1.0 tiebreaker), with a 0.60 confidence floor to filter wrong-song matches.
- **On-the-fly resolution** — tracks from external sources (AI recommendations, weekly playlists, DJ chat) carry only title/artist/album. Resolved in the background via `TrackResolverCache`; `PlaybackController` falls back to inline resolution for tracks that weren't pre-resolved.
- **Three-layer playlist dedup** on sync — ID link, `spotifyId` field, name match. Prevents duplicate remote playlists across reconnect cycles.
- **Background playback survival** — ExoPlayer plays silent audio on loop during external playback so `MediaSessionService` never demotes from foreground. Overrides `onUpdateNotification` and `pauseAllPlayersAndStopSelf` to keep Media3 from fighting us.

For the full list of "lessons learned the hard way," see [CLAUDE.md](CLAUDE.md).

## KMP shared module

The `:shared` module holds platform-agnostic code destined for a future iOS port: models, business logic (resolver scoring, metadata service, queue manager), Ktor API clients, SQLDelight schema, plugin runtime interface. iOS-specific playback, UI, and platform glue will live in a future `iosApp/` target.

Android-side files that moved to shared become typealiases in their original packages so imports keep working across the app — see `data/db/entity/`, `resolver/`, `data/metadata/`, etc.

## Security

Full security review completed April 2026; see [SECURITY.md](SECURITY.md) and `.claude/plans/logical-munching-waffle.md`. Highlights:
- Network security config rejects cleartext traffic app-wide.
- Release builds fail if `CI_KEYSTORE_PATH` is unset — no debug-keystore fallback.
- XSPF imports block SSRF via HTTPS-only + literal-host check on every poll (not just at import, in case DNS changes).
- OAuth flows use PKCE + state parameter; per-flow verifier isolation.
- OAuth tokens and API keys stored in `EncryptedSharedPreferences` (AES-256-GCM, Android Keystore backed) — never in plain DataStore.
- `.claude/` and `play-service-account.json` are gitignored.

Plugin sandboxing ([#107](https://github.com/Parachord/parachord-mobile/issues/107)) — the `.axe` fetch allowlist and storage namespacing — is deferred pending a broader plugin SDK revision shared with desktop.

## CI

GitHub Actions runs on every push / PR / tag. See [`.github/workflows/build.yml`](.github/workflows/build.yml).

- **Branch push** → build signed release APK + AAB, upload as 14-day artifacts.
- **Tag push (`v*`)** → everything above, plus:
  - Publish a GitHub Release with the APK + AAB attached.
  - Upload the AAB to Play Console internal testing track (when `PLAY_SERVICE_ACCOUNT_JSON` secret is present).

Required repository secrets:
- Build: `LASTFM_API_KEY`, `LASTFM_SHARED_SECRET`, `SPOTIFY_CLIENT_ID`, `SOUNDCLOUD_CLIENT_ID`, `SOUNDCLOUD_CLIENT_SECRET`, `APPLE_MUSIC_DEVELOPER_TOKEN`
- Signing: `CI_KEYSTORE_BASE64`, `CI_KEYSTORE_PASSWORD`
- Play Store upload: `PLAY_SERVICE_ACCOUNT_JSON`

## License

[MIT](LICENSE) — same license as the [desktop app](https://github.com/Parachord/parachord).
