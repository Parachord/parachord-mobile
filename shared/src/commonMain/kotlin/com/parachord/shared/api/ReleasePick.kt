package com.parachord.shared.api

/*
 * Which release of an album to show (#560, desktop parachord#1290, a port of
 * desktop's `src/renderer/lib/release-pick.js`).
 *
 * A MusicBrainz release group (the album) holds every edition: CDs, vinyl,
 * digital, reissues, deluxe sets. The album page used to take whichever release
 * MusicBrainz listed first. For George Michael's "The Faith Tour" that was a
 * 9-track CD; the digital release has all 18, as Apple Music's does.
 *
 * The rule:
 *   1. Prefer releases that are digital only (every medium "Digital Media"):
 *      they are what streaming services carry, and the tracks have to resolve
 *      on those services.
 *   2. Among those, take the most common track count, which is the standard
 *      edition. "Most tracks" would pick a 40-track super deluxe Abbey Road
 *      over the 17-track album.
 *   3. A tie in counts, and the choice within a count, goes to the earliest
 *      release date; undated releases sort last. Otherwise MusicBrainz order.
 * With no digital-only release, the same rule runs over all of them.
 *
 * Change it on desktop first: both apps must open the same edition.
 */

private const val DIGITAL_MEDIA = "Digital Media"

/** Undated releases sort after dated ones; partial dates ("2001") compare as text. */
private const val UNDATED = "￿"

/** A release's track count: each medium's `track-count`, else its listed tracks. */
fun releaseTrackCount(release: MbReleaseDetail): Int =
    release.media.sumOf { it.trackCount ?: it.tracks.size }

private fun isDigitalOnly(release: MbReleaseDetail): Boolean =
    release.media.isNotEmpty() && release.media.all { it.format == DIGITAL_MEDIA }

private fun dateKey(release: MbReleaseDetail): String = release.date?.takeIf { it.isNotEmpty() } ?: UNDATED

/** The release of a release group to open, by the rule above; null for none. */
fun pickRelease(releases: List<MbReleaseDetail>): MbReleaseDetail? {
    if (releases.isEmpty()) return null
    if (releases.size == 1) return releases[0]

    val digital = releases.filter(::isDigitalOnly)
    val pool = digital.ifEmpty { releases }

    // Group by track count (0 means MusicBrainz didn't say), keeping MusicBrainz order.
    val groups = LinkedHashMap<Int, MutableList<MbReleaseDetail>>()
    for (release in pool) groups.getOrPut(releaseTrackCount(release)) { mutableListOf() }.add(release)
    val counted = groups.filterKeys { it > 0 }.values
    if (counted.isEmpty()) return pool[0]

    fun earliestIn(rs: List<MbReleaseDetail>) = rs.reduce { best, r -> if (dateKey(r) < dateKey(best)) r else best }
    var bestGroup: List<MbReleaseDetail>? = null
    var bestFirst: MbReleaseDetail? = null
    for (rs in counted) {
        val first = earliestIn(rs)
        val current = bestGroup
        if (current == null ||
            rs.size > current.size ||
            (rs.size == current.size && dateKey(first) < dateKey(bestFirst!!))
        ) {
            bestGroup = rs
            bestFirst = first
        }
    }
    return bestFirst
}
