# Subsonic on mobile: plan

Ticket: #401. Desktop reference: Parachord/parachord `plugins-src/subsonic/` (resolver 0.3.2, `capabilities.mobile: false`), `src/renderer/hooks/use-playback-engine.js` (the `html5Stream` branch, ~L1908), `src/renderer/app.js` (`isHtml5StreamResolver`, `getResolverConfig`, the `isConfigured` check, ~L43723), `src/renderer/lib/playback-helpers.js` (`dropSubsonicSecrets`). Read 2026-11-12.

## Summary

- **Both apps can do all of it, including iOS.** Subsonic serves a plain audio stream (no DRM), so ExoPlayer and AVPlayer play it directly.
- **The plugin already holds all the Subsonic logic** (search3, resolve, album search and lookup, artist photos, token auth, server URL clean-up, the CJK search retry). Mobile ports none of it.
- **Desktop has no Subsonic-specific playback code either.** A plugin declares `capabilities.html5Stream`, the host asks its `getStreamUrl(track, config)` at play time and plays that URL. Mobile builds the same two generic pieces:
  - a host engine for `html5Stream` plugins;
  - a settings screen for plugins that declare `settings.configurable` fields.
- **Both pieces are generic.** They also serve Bandcamp's Subsonic collection streaming (#351) and any future self-hosted source.
- **Two decisions belong to you** (see the end): plain `http://` servers on a home network, and self-signed certificates. Both affect the app's network security settings.

No spike needs a paid account: Navidrome runs locally in Docker with a few audio files.

## How desktop does it

1. **Settings.** The plugin's manifest declares `settings.authType: "userpass"` and three `configurable` fields: `serverUrl` (text), `username` (text), `password` (password). Desktop renders them in Settings → Resolvers and stores them in `meta_service_configs.subsonic`. `getResolverConfig('subsonic')` hands `{ serverUrl, username, password }` to the plugin. An enabled resolver whose plugin's `isConfigured(config)` answers false shows as needing setup.
2. **Search and resolve** run in the plugin: Subsonic `search3`, a containment match on artist and title, and token auth (`u`, `t = md5(password + salt)`, `s`, `v=1.16.1`, `c=parachord`). The password never appears in a URL. Results carry `subsonicId` and `subsonicServer`, plus `isrc` when the server has a valid one, but **no stream URL and no cover URL**.
3. **Playback** (`html5Stream`): at play time the host calls `getStreamUrl(track, config)`, which builds `/rest/stream.view?id=<subsonicId>&maxBitRate=0` with a fresh salt. The host plays it on the shared audio element, with Range requests for seeking. If no URL comes back, the queue skips the track while advancing; otherwise a "Not Configured" dialog shows. `dropSubsonicSecrets` strips any stream URL an older resolve stored on a track.
4. **Invariants** (from #401, keep them exact):
   - **Never persist a credentialed URL.** A token and salt pair works until the password changes. That rules out the track, its sources, the resolver cache, the saved queue, and anything synced, shared or logged.
   - **No cover URLs from the server**: `getCoverArt.view` needs the same auth. Covers come from the normal art pipeline.
   - **No stream-quality record**: the server's bitrate describes the stored file, and `stream.view` can transcode.
5. **Order and look:** `spotify, applemusic, amazonmusic, bandcamp, soundcloud, localfiles, subsonic, youtube`; colour `#14b8a6`; icon 📡.

## Gaps on mobile

| Gap | Today on mobile |
|---|---|
| The plugin is hidden | `capabilities.mobile: false` → `PluginManager` filters it out |
| Settings for plugin fields | No screen renders a plugin's `configurable` fields. Plugin config only comes from code: `SoundCloudAuth.pluginConfigJson` and `metaServicePluginConfigs` (Last.fm and ListenBrainz usernames) |
| A result with no `url` | Android's `resolveViaPlugin` drops a result with no `url`, `<resolverId>Url`, or known service id, so every Subsonic result would be dropped. iOS's `resolveOne` has the same check |
| An id to play by | `ResolvedSource` has a field for each service's id (Spotify, Apple Music, SoundCloud, `bandcampTrackId`) and none for a plugin's own track id |
| Host playback for `html5Stream` | Neither app routes a plugin source to a URL the plugin builds at play time |
| A failed search reads as a miss | The plugin's `search` returns `[]` on an HTTP error, an API error, or an unreachable server (`searchOnce` → `null` → `[]`), so leaving home Wi-Fi would record misses. #566's retry needs it to throw, as `soundcloud.axe` does since 1.3.4 (Parachord/parachord#1347) |
| Plain HTTP and home networks | Android denies cleartext app-wide (`network_security_config.xml`, security H9/M12); iOS has no ATS exceptions. Self-hosted servers are often `http://192.168.x.x:4533` or `http://navidrome.local:4533` |

## Design

### 1. Plugin settings screen (both apps; generic)
- The shared `PluginUserConfig` (`shared/.../plugin/`) reads each loaded plugin's `settings.configurable` and stores the values per plugin.
  - Text fields go in `SettingsStore` (KvStore key `plugin_config_<id>`).
  - `password` fields go in `SecureTokenStore` (Keystore / Keychain), never in plain prefs or backups.
- It feeds `{ field: value }` to the plugin through `resolverConfigProvider`, merged with the configs that exist today (`pluginConfigJson` for SoundCloud, `metaServicePluginConfigs`).
- Settings screens:
  - **Android:** a "Plugin settings" sheet from the resolver's row in Settings.
  - **iOS:** a matching screen in Settings → Plug-ins.
  - Both render text and password fields from the manifest's labels, descriptions and placeholders.
  - A "Test connection" button runs the plugin's `search("test", config)` and shows the result or the error.
- **Needs setup:** a resolver whose plugin's `isConfigured(config)` answers false shows "Set up" (desktop's rule), stays out of resolution, and shows no badge.
- Saving the config clears that resolver's remembered misses, so tracks are asked again (as when a resolver is enabled, #1).

### 2. Results that carry a plugin's own id (both apps)
- Add `ResolvedSource.pluginTrackId`, read from `<resolverId>Id` (`subsonicId`), the way `pluginServiceUrl` reads `<resolverId>Url`.
- When a result has no URL, its `url` becomes a non-secret placeholder, `<resolverId>:track:<id>`, so `resolveViaPlugin` / `resolveOne` keep it.
- The placeholder isn't playable on its own. It only names the track for the `html5Stream` engine, so it's safe in the resolver cache, the saved queue and sync.

### 3. The `html5Stream` engine (both apps; generic)
- **Routing.** A source whose resolver's plugin declares `capabilities.html5Stream` goes to the new engine, ahead of the `stream: false` check (as Bandcamp's engine does).
- **At play time** the engine calls the plugin's `getStreamUrl(track, config)`, using the unique-key poll on both runtimes. The `track` passed in carries `subsonicId` (from `pluginTrackId`).
- **Android:** a `PluginStreamHandler` (`SourceHandler`) gives ExoPlayer a `MediaItem` with that URL. It's ordinary ExoPlayer playback: seeking by Range, `STATE_ENDED` advance, the #614 foreground keep, and the car. The engine's own `startsAt` places a resumed track (#657).
- **iOS:** an `AVPlayer` item from the URL, through the existing AVPlayer engine (as local files and direct streams).
- **The URL never outlives the play.** Not on the `Track`, not in `PlaybackState`, not logged:
  - redact `t`, `s`, `p`, `token` query values in any log line;
  - on Android, check that Media3 doesn't hand the `MediaItem`'s URI to other controllers (Android Auto, the system media controls). If it does, play through a `ResolvingDataSource` that maps the placeholder to the real URL inside the player.
  - A play after a pause rebuilds nothing; a new play asks `getStreamUrl` again.
- **Failures** go through the shared rules:
  - no URL, or a 401/403 → `START_FAILED` with "Check Subsonic's settings";
  - a refused source → another source plays;
  - the server unreachable → `START_FAILED` with the connection message.
- **Volume offset** 0 dB.

### 4. Plugin changes (desktop, then marketplace)
- **Failed searches throw.** `search` and `searchAlbums` throw on an HTTP error, a Subsonic API error, or a failed request, with the status in the message. Unconfigured stays `[]`. Same change as `soundcloud.axe` 1.3.4. Every desktop caller already catches per resolver.
- Once both apps ship the engine and settings: drop `capabilities.mobile: false` and add `minAppVersion.android` / `.ios` at the versions that have them, so older apps keep hiding it. With per-platform loading (Parachord/parachord#1355) the plugin would declare `requires: { capabilities: ["html5Stream", "pluginSettings"] }` instead.

### 5. Network
See the decisions below. Whichever is chosen:
- Android 16+ and iOS 14+ gate access to the local network:
  - iOS needs `NSLocalNetworkUsageDescription`, and shows a prompt the first time.
  - Android 17's local-network permission needs checking on the Pixel.
- Remote access works through any HTTPS reverse proxy or a Tailscale address with a real certificate.

### 6. Order, look, docs
- Add `subsonic` to `CANONICAL_RESOLVER_ORDER` and the defaults (after `localfiles`, before `youtube`).
- Resolver colour `#14b8a6` and its icon in `ResolverIconRow` / iOS badges.
- Add it to the volume-offset table (0).
- Docs: CLAUDE.md (a "Plugin settings and html5Stream" section; the resolver order; the volume table), `iosApp/AGENTS.md`, README.

## Testing
- **Navidrome in Docker** on the Mac with a handful of tagged files (including a CJK title for the retry).
  - The emulator reaches it at `10.0.2.2:4533`, the iOS simulator at `localhost:4533`, and the Pixel at the Mac's LAN IP. The Pixel run exercises the cleartext and local-network path.
  - Add an HTTPS proxy in front for the HTTPS path.
- **Checks:**
  - setup and Test connection;
  - "Set up" state with fields missing;
  - search badges;
  - play, seek, end-of-track advance, and screen-off playback;
  - Android Auto;
  - a resumed track (#657);
  - the server stopped (retried, not a miss);
  - a wrong password (Subsonic error 40 → the settings message).
- **No credentials on disk:** grep the app's files (`track_sources_cache.json`, the saved queue, prefs, the database) and logcat for `t=` / `s=` after playing.
- **Unit tests:**
  - `PluginUserConfig`: the secure store for passwords; merging with existing configs;
  - the `pluginTrackId` / placeholder mapping;
  - the URL redaction;
  - routing by `html5Stream`.

## Effort
- Plugin change on desktop: half a day.
- Plugin settings (shared + both UIs): 2 days.
- Result plumbing and the `html5Stream` engine on both apps: 2 days.
- Network decisions and local-network permissions: half a day to a day.
- Testing on devices: 1 day.
- About a week in all.

## Decisions for you
1. **Plain `http://` servers.** Most home setups are `http://<LAN IP>:4533`.
   - **(a) Allow cleartext app-wide, and restore the guard in code (recommended).** The shared HTTP clients and NativeBridge's fetch refuse `http://` except to the host in a plugin's saved `serverUrl`. On iOS, `NSAllowsLocalNetworking` allows `.local` names and IP literals without opening arbitrary loads.
   - **(b) Require HTTPS.** No security change, but most LAN setups won't work.
   - **(c) Allow cleartext for local addresses only.** Android's network security config can't express IP ranges, so this means (a) with the code guard narrowed to private IPs and `.local`.
2. **Self-signed certificates** (common on home HTTPS). Trusting user-installed CAs (Android `src="user"` trust anchors) weakens every connection the app makes. Recommendation: don't. Point people at a real certificate (Let's Encrypt, Tailscale HTTPS) or plain HTTP on the LAN.
