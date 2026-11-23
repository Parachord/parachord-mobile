package com.parachord.shared.ui

import com.parachord.shared.playback.resolverDisplayName

/**
 * The small status pill (desktop `badge.js`): a playlist's "Shared", the
 * services it syncs to, a friend's service. One per platform, Android
 * `ParachordBadge` and iOS `PCBadge`: 12 / 600 on a full pill that never
 * wraps, with a [BadgeTone]'s colours, or a service's colour: the pill tinted
 * with it at [SERVICE_TINT_ALPHA], a dot of it before the label and primary
 * text.
 */
object BadgeSpec {
    const val TEXT_SIZE = 12
    const val TEXT_WEIGHT = 600
    const val LINE_HEIGHT = 16
    const val PADDING_V = 2
    const val PADDING_H = 8
    /** Between the dot or icon and the label. */
    const val GAP = 4
    const val DOT_SIZE = 8
    const val ICON_SIZE = 12
    /** A service-coloured pill's fill (desktop's `color-mix(… 16%, transparent)`). */
    const val SERVICE_TINT_ALPHA = 0.16f
    /** The info tone's fill, `text-info` at 12%. */
    const val INFO_TINT_ALPHA = 0.12f
}

/**
 * A badge's colours when it has no service colour: neutral (`bg-secondary`,
 * secondary text), info (`text-info` on itself at 12%), accent
 * (`accent-surface`, the accent) and surface (the card's colour and shadow,
 * primary text: over a cover).
 */
enum class BadgeTone { NEUTRAL, INFO, ACCENT, SURFACE }

/**
 * One service a playlist syncs to (desktop's playlist page: "one Badge per
 * service, named and coloured from its plugin"). [colored] is false while the
 * playlist is kept local only: then the badge is neutral, and [note] says it's
 * only linked.
 */
data class PlaylistSyncBadge(
    val providerId: String,
    val label: String,
    val colored: Boolean,
    val note: String,
)

object PlaylistSyncBadges {
    /** The order the badges show in; another service follows, by name. */
    val ORDER = listOf("spotify", "applemusic", "listenbrainz")

    /** A badge per service in [providerIds], in [ORDER]. */
    fun of(providerIds: List<String>, localOnly: Boolean = false): List<PlaylistSyncBadge> =
        providerIds.distinct()
            .sortedWith(compareBy<String>({ ORDER.indexOf(it).let { i -> if (i < 0) ORDER.size else i } }, { it }))
            .map { id ->
                val label = resolverDisplayName(id)
                PlaylistSyncBadge(id, label, colored = !localOnly, note = note(label, localOnly))
            }

    /** Desktop's tooltip, read out as the badge's description. */
    fun note(label: String, localOnly: Boolean): String =
        if (localOnly) "Linked to $label, but kept local only" else "Syncs to $label"

    /** The playlist's page on that service, or null when it has none to open. */
    fun pageUrl(providerId: String, externalId: String): String? = when (providerId) {
        "spotify" -> "https://open.spotify.com/playlist/$externalId"
        "listenbrainz" -> "https://listenbrainz.org/playlist/$externalId/"
        "applemusic" -> "https://music.apple.com/library/playlist/$externalId"
        else -> null
    }
}
