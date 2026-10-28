package com.parachord.android.net

/**
 * The outbound `User-Agent` for every Parachord Android HTTP client.
 *
 * Delegates to the shared [com.parachord.shared.config.parachordUserAgent],
 * which iOS uses too; see it for the MetaBrainz contract this has to meet.
 */
fun parachordUserAgent(versionName: String): String =
    com.parachord.shared.config.parachordUserAgent(versionName, "Android")

/** Verified live (HTTP 200). Changing this requires re-verifying it resolves. */
const val PARACHORD_CONTACT_URL = com.parachord.shared.config.PARACHORD_CONTACT_URL
