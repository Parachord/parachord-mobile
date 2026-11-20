package com.parachord.shared.home

import com.parachord.shared.recommendations.slug

/**
 * When did a playlist's tracks last change, and how many tracks did that
 * change add. A port of desktop's `playlist-freshness.js`.
 *
 * Home's "Your playlists, freshest first" orders by `tracksChangedAt` and
 * badges "+N" from `lastChangeAdded`. `lastModified` can't do that job, since
 * renames and other metadata writes bump it. A change is tracks added, removed
 * or reordered, by a local edit, a sync pull or a hosted playlist's refresh. A
 * rename, a sync-state stamp or a re-poll that brings the same tracks isn't.
 *
 * Both fields are local only: no sync push sends them to a service.
 *
 * Track identity is the track's artist and title, folded as desktop's
 * `trackSlug` folds them ([slug]), not its id: hosted XSPF tracks have no id,
 * and ListenBrainz ids are positions, so a weekly playlist that replaces every
 * track keeps every id. A track with neither artist nor title falls back to
 * its id.
 *
 * Applied by [com.parachord.shared.db.dao.PlaylistTrackDao] on every track
 * write, on both apps.
 */
object PlaylistFreshness {

    data class Stamp(val tracksChangedAt: Long, val lastChangeAdded: Int)

    /** Desktop's `trackKey`: `"<artist>-<title>"` folded, else `"id:<id>"`, else `""`. */
    fun trackKey(artist: String?, title: String?, id: String? = null): String = when {
        !artist.isNullOrEmpty() || !title.isNullOrEmpty() -> "${slug(artist)}-${slug(title)}"
        !id.isNullOrEmpty() -> "id:$id"
        else -> ""
    }

    /** How many tracks [next] has that [prev] doesn't, counting copies (a second
     *  copy of a track already there is one added). */
    fun countAdded(prev: List<String>, next: List<String>): Int {
        val have = HashMap<String, Int>()
        for (k in prev) have[k] = (have[k] ?: 0) + 1
        var added = 0
        for (k in next) {
            val n = have[k] ?: 0
            if (n > 0) have[k] = n - 1 else added++
        }
        return added
    }

    /**
     * The stamp for a write that took a playlist's tracks from [prevKeys] to
     * [nextKeys] (both in order), or null when the keys are equal, order
     * included. [prevKeys] null means no earlier tracks were known, so every
     * track counts as added. [prevAt] is the stored stamp; it doesn't change the
     * answer (the DAO compares keys it read itself), but is kept beside the
     * keys so callers pass what desktop's `stampAgainst` sees.
     */
    @Suppress("UNUSED_PARAMETER")
    fun stamp(prevKeys: List<String>?, nextKeys: List<String>, prevAt: Long?, now: Long): Stamp? {
        if (prevKeys != null && prevKeys == nextKeys) return null
        return Stamp(now, prevKeys?.let { countAdded(it, nextKeys) } ?: nextKeys.size)
    }
}
