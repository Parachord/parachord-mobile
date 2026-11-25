package com.parachord.shared.metadata

/*
 * Whether an artist has a page (#840, a port of desktop's
 * `artist-page-name.js`, parachord#1661).
 *
 * "Various Artists" is MusicBrainz's placeholder for a compilation's credit,
 * not an artist: its page listed hundreds of unrelated releases. It has no
 * page: nothing links to one, nothing is looked up about it, and a
 * compilation's album page names it as plain text.
 */

/** MusicBrainz's fixed id for Various Artists. */
const val VARIOUS_ARTISTS_MBID = "89ad4ac3-39f7-470e-963a-56509c546377"

/**
 * False for Various Artists (by [mbid], or the exact name, case and surrounding
 * spaces aside) and for a blank name. Never "Various" alone, which is a real
 * act, nor acts whose name only contains it.
 */
fun hasArtistPage(name: String?, mbid: String? = null): Boolean {
    if (mbid?.trim()?.lowercase() == VARIOUS_ARTISTS_MBID) return false
    val trimmed = name?.trim().orEmpty()
    return trimmed.isNotEmpty() && !trimmed.equals("Various Artists", ignoreCase = true)
}

/** What a link to an artist with no page says (desktop's toast). */
fun noArtistPageMessage(name: String): String = "${name.trim()} isn’t an artist, so it has no page"
