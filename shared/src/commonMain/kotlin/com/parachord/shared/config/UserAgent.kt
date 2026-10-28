package com.parachord.shared.config

/**
 * The outbound `User-Agent` for every Parachord HTTP client, on both platforms.
 *
 * MetaBrainz's 2026-08 bot mitigation blocks clients that identify
 * themselves poorly, and OkHttp's default `okhttp/4.x` is exactly the
 * pattern it targets. The required shape is `App/version ( contact )` per
 * MusicBrainz's rate-limiting doc.
 *
 * **The contact URL has to actually resolve.** This previously pointed at
 * `https://parachord.app`, which does not exist (DNS failure, not a 404), so
 * the header looked compliant while giving MetaBrainz nowhere to reach us —
 * no better than sending nothing for the purpose the policy exists to serve.
 *
 * Single source of truth: Android's Ktor `AppConfig` and OkHttp interceptor and
 * iOS's `AppConfig` all build theirs here. iOS used to hard-code `Parachord/0.1`,
 * so every iOS build claimed the same version.
 *
 * @param versionName the app's marketing version (Android `versionName`, iOS
 *   `CFBundleShortVersionString`). Blank falls back to `unknown` so the header
 *   keeps its `App/version` shape.
 * @param platform `Android` or `iOS`.
 */
fun parachordUserAgent(versionName: String, platform: String): String =
    "Parachord/${versionName.trim().ifBlank { "unknown" }} ( $platform; $PARACHORD_CONTACT_URL )"

/** Verified live (HTTP 200). Changing this requires re-verifying it resolves. */
const val PARACHORD_CONTACT_URL = "https://parachord.com"
