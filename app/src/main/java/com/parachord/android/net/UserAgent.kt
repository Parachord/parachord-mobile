package com.parachord.android.net

/**
 * The outbound `User-Agent` for every Parachord Android HTTP client.
 *
 * MetaBrainz's 2026-08 bot mitigation blocks clients that identify
 * themselves poorly, and OkHttp's default `okhttp/4.x` is exactly the
 * pattern it targets — Android is more exposed here than desktop, whose
 * Electron default at least embeds an app name. The required shape is
 * `App/version ( contact )` per MusicBrainz's rate-limiting doc.
 *
 * **The contact URL has to actually resolve.** This previously pointed at
 * `https://parachord.app`, which does not exist (DNS failure, not a 404), so
 * the header looked compliant while giving MetaBrainz nowhere to reach us —
 * no better than sending nothing for the purpose the policy exists to serve.
 *
 * Single source of truth: the string was duplicated across the Ktor
 * `AppConfig` and the OkHttp interceptor, which is one edit away from the
 * two disagreeing about who we are.
 */
fun parachordUserAgent(versionName: String): String =
    "Parachord/$versionName ( Android; $PARACHORD_CONTACT_URL )"

/** Verified live (HTTP 200). Changing this requires re-verifying it resolves. */
const val PARACHORD_CONTACT_URL = "https://parachord.com"
