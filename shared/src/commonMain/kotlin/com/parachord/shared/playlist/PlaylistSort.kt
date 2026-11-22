package com.parachord.shared.playlist

import com.parachord.shared.model.Playlist
import com.parachord.shared.sync.ProviderChannelPlaylist

/**
 * How the Playlists list and the sync picker ("Configure what syncs") are
 * ordered, one copy for both apps (#591). A sort's `name` is what's saved
 * ("Remember sort"), so don't rename one. Sorting is stable.
 */
enum class PlaylistSort(val label: String) {
    RECENT("Recently Added"),
    CREATED("Date Created"),
    MODIFIED("Recently Modified"),
    ALPHA_ASC("A-Z"),
    ALPHA_DESC("Z-A");

    companion object {
        val DEFAULT = RECENT
        fun fromName(name: String?): PlaylistSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

object PlaylistSorting {
    /** The Playlists list: filtered by name, then sorted. */
    fun playlists(list: List<Playlist>, sort: PlaylistSort, query: String): List<Playlist> {
        val q = query.trim()
        val f = if (q.isEmpty()) list else list.filter { it.name.contains(q, ignoreCase = true) }
        return sorted(f, sort, { it.name }, { it.createdAt }) {
            modifiedTime(it.tracksChangedAt, it.lastModified, it.updatedAt, it.createdAt)
        }
    }

    /** The sync picker's rows. */
    fun channelRows(list: List<ProviderChannelPlaylist>, sort: PlaylistSort): List<ProviderChannelPlaylist> =
        sorted(list, sort, { it.name }, { it.createdAt }) {
            modifiedTime(it.tracksChangedAt, it.lastModified, it.updatedAt, it.createdAt)
        }

    /**
     * "Recently Modified" orders by when the tracks last changed
     * (`tracksChangedAt`: tracks added, removed or reordered, by an edit, a
     * sync pull or a hosted link), else `lastModified`, else the row's last
     * write, else when it was created; desktop's `playlists-sort.js`
     * (#790, parachord#1626). `lastModified` alone moved on every sync pull,
     * a first import and a hosted link whose text changed but not its tracks,
     * so untouched playlists rose to the top. A rename alone doesn't move a
     * playlist up, as on desktop.
     */
    fun modifiedTime(tracksChangedAt: Long?, lastModified: Long, updatedAt: Long, createdAt: Long): Long =
        tracksChangedAt?.takeIf { it > 0L }
            ?: lastModified.takeIf { it > 0L }
            ?: updatedAt.takeIf { it > 0L }
            ?: createdAt

    private fun <T> sorted(
        list: List<T>,
        sort: PlaylistSort,
        name: (T) -> String,
        createdAt: (T) -> Long,
        modifiedAt: (T) -> Long,
    ): List<T> = when (sort) {
        PlaylistSort.RECENT -> list.sortedByDescending(createdAt)
        PlaylistSort.CREATED -> list.sortedBy(createdAt)
        PlaylistSort.MODIFIED -> list.sortedByDescending(modifiedAt)
        PlaylistSort.ALPHA_ASC -> list.sortedBy { name(it).lowercase() }
        PlaylistSort.ALPHA_DESC -> list.sortedByDescending { name(it).lowercase() }
    }
}
