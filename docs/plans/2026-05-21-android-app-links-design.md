# Android App Links for `https://parachord.com/*` — design

Date: 2026-05-21
Closes: #123 (sub-issue of master #122)

## Goal

Register `https://parachord.com/<verb>` URLs with Android so that taps from any source (Gmail, Messages, Chrome, Achordion `PlayOnHoverFab`, etc.) open the Parachord app directly on devices where it's installed, falling back to the website otherwise. No behavior change for the existing `parachord://` custom scheme.

## Scope

Three additive changes. None of them touch `parseParachord` itself or any existing intent filter — full backward compatibility.

### 1. Manifest filter (`AndroidManifest.xml`)

Add a second `<intent-filter android:autoVerify="true">` on `MainActivity`, alongside the existing `parachord://` filter. Scheme `https`, host `parachord.com`, one `<data android:path|pathPrefix=...>` per supported verb. The full list (mirrors the issue):

- Playback verbs: `/play`, `/play/`, `/listen-along`, `/import`, `/queue/`, `/control/`, `/shuffle/`, `/volume/`
- Navigation verbs: `/artist/`, `/album/`, `/library`, `/history`, `/friend/`, `/recommendations`, `/charts`, `/critics-picks`, `/playlists`, `/playlist/`, `/settings`, `/search`, `/chat`, `/home`

`autoVerify="true"` lives on the filter element, not the data tags — Android verifies the host once per filter. Existing passthrough filters for `open.spotify.com` + `music.apple.com` stay unchanged (those don't autoVerify because we don't own those hosts).

The filter belongs on `MainActivity`, NOT `OAuthRedirectActivity`. `OAuthRedirectActivity` is OAuth-only and uses `parachord://auth/...`; mixing HTTPS filters there would confuse Android's verifier.

### 2. `DeepLinkHandler.parseHttpUrl` extension

`parseHttpUrl` already has two branches (`open.spotify.com`, `music.apple.com`). Add a third for `parachord.com`. The new branch is a thin URL rewrite:

```kotlin
private fun parseParachordHttps(uri: Uri): DeepLinkAction {
    val segments = uri.pathSegments
    if (segments.isEmpty()) return DeepLinkAction.Unknown(uri.toString())
    val rebuilt = Uri.Builder()
        .scheme("parachord")
        .authority(segments.first())                            // first segment becomes host
        .apply { segments.drop(1).forEach { appendPath(it) } }  // rest becomes path
        .encodedQuery(uri.encodedQuery)                         // query carries verbatim
        .build()
    return parseParachord(rebuilt)
}
```

`https://parachord.com/play?artist=X&title=Y` rewrites to `parachord://play?artist=X&title=Y`. `https://parachord.com/play/album?mbid=...` rewrites to `parachord://play/album?mbid=...`. Every verb that `parseParachord` understands now has free HTTPS coverage — including future verbs we haven't added yet.

### 3. Unit tests

Add per-verb round-trip tests in `DeepLinkHandlerTest`. Each test asserts that `parseHttpUrl(https://parachord.com/<path>)` produces the same `DeepLinkAction` as `parse(parachord://<path>)`. One test per verb in the manifest filter — locks the contract that the HTTPS form is a complete drop-in for the custom scheme.

### 4. Signing-cert SHA-256 fingerprints

Extract debug + release signing cert SHA-256 fingerprints via `keytool`. Drop them in the PR description for the website team's `assetlinks.json` file. Fingerprints aren't sensitive — they're in every release APK and Play Store listing — no secret channel needed.

## Out of scope

- **Website `assetlinks.json` deployment.** Lives in `parachord-website`; their work is a separate ticket. Until they ship, Android shows a "Open with: Parachord / Browser" chooser instead of routing automatically. Still better than today's silent failure.
- **iOS Universal Links** — blocked on iOS app target existing; tracked in #124.
- **Voice search / Assistant integration** — `https://parachord.com/<verb>` URLs are for tap-routing; voice search uses a different MediaSession callback surface.
- **Changes to `parseParachord` switch.** No new verbs are added in this PR.

## Acceptance criteria

- `./gradlew :app:lintDebug` passes — manifest validates.
- `DeepLinkHandlerTest` has per-verb HTTPS round-trip tests; all pass.
- No regression on existing `parachord://` URLs — the existing `DeepLinkHandlerTest` suite still passes unchanged.
- `adb shell am start -a android.intent.action.VIEW -d "https://parachord.com/play?artist=X&title=Y"` on a device with the app installed → shows a chooser dialog (pre-website-ship) or opens the app directly (post-website-ship).
- `adb shell pm get-app-links com.parachord.android.debug` reports `parachord.com: ask` initially, `parachord.com: verified` after website's `assetlinks.json` is live and Android re-verifies (24h propagation, can be forced with `pm verify-app-links --re-verify`).

## Verification matrix

After all three pieces (this PR, website, eventual iOS) ship, smoke test on real devices:

- Gmail / iMessage link tap
- Chrome address bar paste + go
- Notes / Slack long-press → "Open in Parachord"
- Achordion `PlayOnHoverFab` tap (the real-world canonical entry point)

Each should: open the app directly when installed; land on the website fallback page when not.

## File changes

- `app/src/main/AndroidManifest.xml` — second `<intent-filter>` on `MainActivity`
- `app/src/main/java/com/parachord/android/deeplink/DeepLinkHandler.kt` — third branch in `parseHttpUrl` + new `parseParachordHttps` private helper
- `app/src/test/java/com/parachord/android/deeplink/DeepLinkHandlerTest.kt` — per-verb round-trip tests
