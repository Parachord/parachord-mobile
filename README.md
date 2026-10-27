# Parachord for Android and iOS

A unified music player that brings Spotify, Apple Music, SoundCloud, Bandcamp, and your local files into one queue. Parachord resolves every track across your available sources and plays from the best match based on a configurable resolver priority.

This repo holds both mobile companions to the [Parachord desktop app](https://github.com/Parachord/parachord): a native **Android** app (Jetpack Compose) and a native **iOS** app (SwiftUI). They share one Kotlin Multiplatform core. Both apps use the same resolver pipeline, plugin system and playlist sync as desktop, and the Android app is very close to feature parity.

## Install

### Android
- **Beta testers**: join the [internal testing track](https://groups.google.com/g/parachord-testers) to get automatic updates through the Play Store.
- **Sideload**: grab the signed APK from the [latest release](https://github.com/Parachord/parachord-mobile/releases/latest) and install it directly. You need to give your browser or Files app the "Install unknown apps" permission.

### iOS
- **TestFlight**: join the [public beta](https://testflight.apple.com/join/zXvg5zxK).

## Screenshots

These are from the Android app; the iOS app follows the same layout with native SwiftUI styling.

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
One queue across all sources. Tap any track and Parachord figures out where to play it from.

| Source | Android | iOS |
|--------|---------|-----|
| **Spotify** (Premium) | Spotify Connect via the Web API: controls Spotify on your phone or any Connect device | Same: Spotify Connect via the Web API |
| **Apple Music** (subscription) | MusicKit JS in a hidden WebView | Native MusicKit |
| **SoundCloud** | Native ExoPlayer streaming | AVPlayer |
| **Local files** | Device media library, with artwork extraction and online enrichment | Device music library |
| **Bandcamp** | Resolves tracks and opens them in the browser (as on desktop) | Same |
| **Direct HTTP streams** | ExoPlayer | AVPlayer |

### Resolver pipeline
Sources are scored priority first, confidence second. A Spotify result at 70% confidence beats SoundCloud at 95% when Spotify is ranked higher. Matches below 60% confidence are dropped, so a wrong song never plays. An exact ISRC or MusicBrainz recording match scores 100%, even when the names differ.

Default order: `spotify > applemusic > bandcamp > soundcloud > localfiles > youtube`. You can change it in settings.

### Playlist and library sync
- **Spotify, Apple Music and ListenBrainz.** Import playlists, saved tracks, albums and followed artists, and push locally created playlists to any of them. A playlist pulled from one service can mirror to the others.
- **Hosted XSPF.** Import any `.xspf` URL, such as a radio station's "recently played" feed. Parachord polls it every 5 minutes and keeps the local copy and its mirrors in step.
- **Three-layer dedup** prevents duplicate remote playlists on re-sync. Track removals are remembered, so a removed track isn't re-imported by the next pull.
- **New sync engine (preview).** An opt-in sync engine merges edits made on any service. Before you switch, it shows a dry-run preview of exactly what would change.

Apple Music's API can't delete or rename playlists or remove tracks from them, so those changes have to be made in the Apple Music app.

### Discovery
- **Recommendations**: personalized albums and artists from Last.fm and ListenBrainz, plus an AI-generated feed using your own ChatGPT, Claude or Gemini key.
- **Fresh Drops** and **Critical Darlings**: curated new releases.
- **Pop of the Tops**: global chart albums.
- **Weekly Jams** and **Weekly Exploration**: the last four weeks of each, pulled automatically from ListenBrainz.
- **On Tour**: a teal dot next to the artist name when they're playing near you. The radius is configurable.
- **Friends**: see what your Last.fm and ListenBrainz contacts have been playing, and **Listen Along** with them.
- **Spinoff**: start a radio of similar tracks from whatever is playing, without touching your queue.
- **Concerts**: Ticketmaster and SeatGeek search, filtered by location.

### AI features (bring your own key)
- **DJ chat (Shuffleupagus)**: natural-language control, such as "play something like Beach House but more upbeat", "queue the new Waxahatchee album" or "skip to Sugar".
- **Album and artist recommendations** grounded in your actual listening history.
- **Providers**: ChatGPT, Claude and Gemini. Ollama works as an `.axe` plugin on desktop but is disabled on mobile, because it needs a local server.

### Scrobbling
- **ListenBrainz**, **Last.fm** and **Libre.fm**, plus optional pushing of loved tracks to ListenBrainz and Last.fm.
- **MBID enrichment.** Tracks get MusicBrainz identifiers in the background from ListenBrainz's MBID Mapper. If the mapper is down, the lookup falls back to ListenBrainz's acr-lookup service, or to MusicBrainz by ISRC. Scrobbles include `recording_mbid`, `artist_mbids` and `release_mbid`.

### Sharing
- **Tracks, albums and artists** share as [Achordion](https://achordion.xyz) links. Recipients get a rich preview with per-service listen buttons (Spotify, Apple Music, SoundCloud), whatever platform they're on.
- **Playlists** share as smart links via `go.parachord.com`, falling back to a `parachord.com/go` deeplink if the smart-link service is unreachable.
- **iOS share extension**: send a Spotify or Apple Music link to Parachord from any app.

### .axe plugin system
Both apps run the same plugins as desktop. They're bundled as `.axe` files and updated at runtime from the [`parachord-plugins`](https://github.com/Parachord/parachord-plugins) marketplace, checked at most once a day. Android hosts them in a headless WebView and iOS in JavaScriptCore, and both run desktop's `resolver-loader.js` unchanged.

Plugin types: content resolvers, AI providers, meta-services (Last.fm, MusicBrainz, Discogs, Wikipedia), scrobblers and concert services. YouTube and Ollama are filtered out on mobile (`capabilities.mobile: false`). Native resolvers (Spotify, Apple Music, plus SoundCloud and local files on Android) take priority over their `.axe` equivalents for speed.

### Background resilience (Android)
External playback (Apple Music, Spotify Connect) survives screen-off, Doze mode and Android's foreground-service killer. A silent 1-second WAV loops on ExoPlayer, which keeps `MediaSessionService` happy while the DRM audio flows through MusicKit or Spotify. See [CLAUDE.md](CLAUDE.md), "External Playback Background Survival", for the rationale.

## Requirements

| | Android | iOS |
|---|---|---|
| OS | Android 8.0+ (API 26) | iOS 17.0+ (iPhone and iPad) |
| Spotify playback | Spotify Premium (the free tier can browse but can't be controlled through the Web API) | Same |
| Apple Music playback | Apple Music subscription | Same |
| AI features | Your own ChatGPT / Claude / Gemini key, set in Settings | Same |

## Setup (development)

### Android

**Prerequisites:** Android Studio Ladybug (2024.2) or newer, JDK 17, Android SDK 35.

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
| `APPLE_MUSIC_AUTHKEY_P8` | Optional | Path to your MusicKit AuthKey. The build mints the developer token and rotates it before it expires. |
| `APPLE_MUSIC_DEVELOPER_TOKEN` | Optional | Generated from the AuthKey above, or set by hand (`scripts/apple-music-token.py`) |
| `ACHORDION_BEARER_TOKEN` | Optional | Pre-warms Achordion's track-links cache on share and scrobble. Blank turns the submit off. |

AI provider keys (ChatGPT / Claude / Gemini) and concert-service keys (Ticketmaster / SeatGeek) are entered per user in Settings, not at build time, which keeps them out of the open-source build config.

```bash
# Build + install debug APK on connected device
./gradlew installDebug

# Force-stop the old process so Android picks up the new code
adb shell am force-stop com.parachord.android.debug

# Run unit tests (shared module + app)
./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest
```

Release builds require a keystore; the CI workflow handles signing and publishing. See [docs/play-store-publishing.md](docs/play-store-publishing.md).

### iOS

**Prerequisites:** Xcode 26 with the iOS 26 SDK, JDK 17 (Gradle builds the shared framework).

Copy the example secrets and fill in your keys:

```bash
cp iosApp/Parachord/Secrets.xcconfig.example iosApp/Parachord/Secrets.xcconfig
```

`Secrets.xcconfig` holds `LASTFM_API_KEY`, `LASTFM_SHARED_SECRET`, `APPLE_MUSIC_DEVELOPER_TOKEN` and `ACHORDION_BEARER_TOKEN`. It's gitignored, and the project still builds without it; Last.fm features are then inert until you add it. Spotify is bring-your-own: users enter their Spotify client ID in Settings.

Open `iosApp/Parachord.xcodeproj` and run the `Parachord` scheme. A build phase runs `:shared:embedAndSignAppleFrameworkForXcode` on every build, so changes to `shared/` are picked up automatically. To build headless:

```bash
xcodebuild build -project iosApp/Parachord.xcodeproj -scheme Parachord \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

Run the shared tests on the iOS simulator with `./gradlew :shared:iosSimulatorArm64Test`. Kotlin/Native catches bugs the JVM suite can't, such as unsupported regex classes. iOS-specific notes are in [iosApp/AGENTS.md](iosApp/AGENTS.md).

### Cutting a release

**Android** is tag-driven:

```bash
./scripts/release-version.sh 0.4.0-beta.3
git push && git push origin v0.4.0-beta.3
```

The `v*` push triggers the release workflow. It signs the APK and AAB, attaches both to a GitHub Release, and uploads the AAB to the Play Console internal track. Prerelease version names (a hyphen in the semver) always go to `internal`, whatever the track flag says.

**iOS** builds are archived locally in Xcode and uploaded to App Store Connect, then released to the TestFlight groups.

## Architecture

Both apps mirror the [desktop Parachord app's](https://github.com/Parachord/parachord) architecture, adapted to each platform's idioms. Detailed design notes live in [CLAUDE.md](CLAUDE.md) (Android + shared) and [iosApp/AGENTS.md](iosApp/AGENTS.md) (iOS).

### Tech stack

| Layer | Shared (`:shared`, KMP) | Android (`:app`) | iOS (`iosApp/`) |
|-------|-------------------------|------------------|-----------------|
| Language | Kotlin Multiplatform | Kotlin | Swift |
| UI | — | Jetpack Compose + Material 3 | SwiftUI |
| Playback | Queue, routing contracts | Media3 / ExoPlayer, Spotify Web API (Connect), MusicKit JS (WebView) | AVPlayer, Spotify Web API (Connect), MusicKit |
| Database | SQLDelight | SQLDelight Android driver | SQLDelight native driver |
| Networking | Ktor (all API clients) | OkHttp engine | Darwin engine |
| DI | Koin `sharedModule` | Koin | `IosContainer` |
| Preferences | `KvStore` + `SecureTokenStore` | SharedPreferences, EncryptedSharedPreferences (AES-256-GCM, Android Keystore) | NSUserDefaults, Keychain |
| Plugins | `PluginManager`, `JsRuntime` | WebView (`JsBridge`) | JavaScriptCore |
| Images | — | Coil | SwiftUI image cache |

### Project layout

```
shared/src/
├── commonMain/kotlin/com/parachord/shared/
│   ├── ai/          # DJ chat + recommendations: providers, tool loop, prompts
│   ├── api/         # Ktor clients (Spotify, Apple Music, Last.fm, MusicBrainz, ListenBrainz, Achordion, …)
│   ├── db/          # SQLDelight schema, queries, DAOs
│   ├── metadata/    # Metadata cascade, MBID + image enrichment
│   ├── model/       # Track, Album, Artist, Playlist, …
│   ├── playback/    # Queue, scrobbling
│   ├── plugin/      # PluginManager, JsRuntime interface, marketplace sync
│   ├── repository/  # Library, history, friends, charts, concerts, recommendations, …
│   ├── resolver/    # Resolver coordinator + scoring
│   ├── settings/    # SettingsStore
│   └── sync/        # Sync engine + Spotify / Apple Music / ListenBrainz providers
├── androidMain/     # Android actuals
└── iosMain/         # iOS actuals, IosContainer, IosResolverRuntime

app/src/main/java/com/parachord/android/
├── auth/            # OAuth flows (Spotify, Last.fm, SoundCloud)
├── bridge/          # WebView JS runtime for .axe plugins
├── data/            # Typealias shims to shared, local media scanner
├── playback/        # PlaybackController, PlaybackService, Spotify / Apple Music / SoundCloud handlers
├── resolver/        # ResolverManager, AndroidResolverRuntime, TrackResolverCache
├── ui/              # Compose screens, components, navigation, theme
└── widget/          # Home screen mini player widget

iosApp/
├── Parachord/       # SwiftUI app: screens, PlaybackRouter, MusicKit, background sync
└── ShareExtension/  # Share-to-Parachord extension
```

### Key design patterns

- **Metadata cascade.** MusicBrainz (IDs, discography, tracklists) → Last.fm (images, bios, tags) → Spotify (album art, preview URLs). Later providers fill gaps left by earlier ones. When none of them has an album's track list, the plugins' catalogs (Apple Music, Bandcamp) step in.
- **Resolver scoring.** Priority first (user-configurable), confidence second (a 0.0–1.0 tiebreaker), with a 0.60 confidence floor that filters out wrong-song matches.
- **On-the-fly resolution.** Tracks from external sources (AI recommendations, weekly playlists, DJ chat) carry only title, artist and album. They're resolved in the background as they scroll into view, and playback falls back to inline resolution for any that weren't.
- **Three-layer playlist dedup** on sync: ID link, the stored service ID, then name match. This prevents duplicate remote playlists across reconnect cycles.
- **Shared core, thin platforms.** Business logic lives once in `:shared`. Each app supplies UI, playback engines and platform glue. Android files that moved to shared became typealiases in their original packages, so imports keep working.

For the full list of "lessons learned the hard way", see [CLAUDE.md](CLAUDE.md).

## Security

A full security review was completed in April 2026; see [SECURITY.md](SECURITY.md). Highlights:
- The network security config rejects cleartext traffic app-wide.
- Release builds fail if `CI_KEYSTORE_PATH` is unset; there's no fallback to the debug keystore.
- XSPF imports block SSRF: HTTPS only, plus a literal-host check on every poll, not just at import, in case DNS changes.
- OAuth flows use PKCE and a state parameter, with a separate verifier per flow.
- OAuth tokens and API keys are stored in the Android Keystore-backed `EncryptedSharedPreferences` or the iOS Keychain, never in plain preferences.
- `.claude/`, `play-service-account.json`, `local.properties` and `Secrets.xcconfig` are gitignored.

Plugin sandboxing ([#107](https://github.com/Parachord/parachord-mobile/issues/107)), meaning the `.axe` fetch allowlist and storage namespacing, is deferred until a broader plugin SDK revision shared with desktop.

## CI

GitHub Actions workflows live in [`.github/workflows/`](.github/workflows):

- **`build.yml`**: on pushes to `main`, PRs and `v*` tags.
  - **Push or PR**: builds the signed Android release APK and AAB and uploads them as 14-day artifacts.
  - **Tag push (`v*`)**: does everything above, then publishes a GitHub Release with the APK and AAB attached, and uploads the AAB to the Play Console internal testing track (when the `PLAY_SERVICE_ACCOUNT_JSON` secret is present).
- **`android-tests.yml`**: the JVM unit test suites.
- **`ios-tests.yml`**: the shared tests on the iOS simulator, for changes to `shared/` or the build config.
- **`sync-plugins.yml`**: opens a PR when the marketplace has newer versions of the bundled `.axe` plugins.
- **`apple-music-token-expiry.yml`**: a weekly check that files an issue 30 days before the CI Apple Music developer token expires.

Required repository secrets:
- Build: `LASTFM_API_KEY`, `LASTFM_SHARED_SECRET`, `SPOTIFY_CLIENT_ID`, `SOUNDCLOUD_CLIENT_ID`, `SOUNDCLOUD_CLIENT_SECRET`, `APPLE_MUSIC_DEVELOPER_TOKEN`
- Signing: `CI_KEYSTORE_BASE64`, `CI_KEYSTORE_PASSWORD`
- Play Store upload: `PLAY_SERVICE_ACCOUNT_JSON`

## License

[MIT](LICENSE), the same license as the [desktop app](https://github.com/Parachord/parachord).
