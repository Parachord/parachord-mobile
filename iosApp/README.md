# Parachord for iOS

The native SwiftUI app. It links the Kotlin Multiplatform `shared` module (`Shared.framework`) for everything that isn't UI or playback: resolvers, metadata, sync, scrobbling, AI, the plugin runtime and the database. Features, install links and the overall architecture are in the [root README](../README.md).

**Before changing anything here, read [AGENTS.md](AGENTS.md).** It holds the iOS-specific rules (KMP→Swift bridging, JavaScriptCore plugin hosting, the build and screenshot workflow) that each cost at least one failed build or shipped bug.

## Build

**Prerequisites:** Xcode 26 with the iOS 26 SDK, and JDK 17 (Gradle builds the shared framework).

1. Copy the secrets template and fill it in:
   ```bash
   cp iosApp/Parachord/Secrets.xcconfig.example iosApp/Parachord/Secrets.xcconfig
   ```
   It holds `LASTFM_API_KEY`, `LASTFM_SHARED_SECRET`, `APPLE_MUSIC_DEVELOPER_TOKEN` and `ACHORDION_BEARER_TOKEN`. The file is gitignored and optional: without it the app builds, and those features stay inert. Spotify is bring-your-own; users enter their client ID in Settings.
2. Open `Parachord.xcodeproj` and run the `Parachord` scheme.

Two build phases do extra work on every build:
- **Run gradle** runs `:shared:embedAndSignAppleFrameworkForXcode`, so edits to `shared/` are picked up without a separate Gradle step.
- **Embed Apple Music developer token** runs `scripts/apple-music-token.py` and stamps the token into the built `Info.plist`, rotating it before it expires when a MusicKit AuthKey is configured.

Headless build (use absolute paths; see AGENTS.md):

```bash
xcodebuild build \
  -project /path/to/parachord-mobile/iosApp/Parachord.xcodeproj \
  -scheme Parachord \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

The simulator can't play Apple Music, so test playback on a device.

## Tests

The shared logic is tested once in Kotlin and run on the iOS simulator:

```bash
./gradlew :shared:iosSimulatorArm64Test
```

Run it for any change to `shared/`, especially regex, Unicode or string handling: Kotlin/Native rejects some things the JVM accepts. CI runs it too (`.github/workflows/ios-tests.yml`).

## Release

`scripts/testflight.sh` archives the app and exports a signed `.ipa`. Add `--upload` to send it to App Store Connect, which needs an App Store Connect API key; see the script header. After the upload, add the build to the TestFlight groups in App Store Connect. An upload only reaches internal testers until the build is added to the external group.

## Layout

```
iosApp/
├── Parachord.xcodeproj/
├── Parachord/                 # The app (com.parachord.ios), iPhone + iPad
│   ├── ParachordApp.swift     # @main entry
│   ├── RootView.swift, Shell.swift      # Navigation shell
│   ├── *Screen.swift, *View.swift       # Screens (Home, Search, Collection, Now Playing, Chat, …)
│   ├── PlaybackRouter.swift   # Picks the engine: AVPlayer, MusicKit or Spotify Connect
│   ├── IosTrackResolverCache.swift      # Resolver cache + background resolution
│   ├── BackgroundSync.swift   # Background sync scheduling
│   ├── Theme.swift            # Desktop palette (purple accent)
│   └── Resources/             # Bundled .axe plugins (axe-plugins/) + resolver-loader.js
├── ShareExtension/            # Share a Spotify / Apple Music link into Parachord (com.parachord.ios.share)
└── scripts/                   # testflight.sh, embed-apple-music-token.sh
```

The Kotlin side of the iOS app (`IosContainer`, `IosResolverRuntime`, the JavaScriptCore runtime and the platform actuals) lives in `shared/src/iosMain/`.

## Deployment target

iOS 17.0, on iPhone and iPad.
