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
        return sorted(f, sort, { it.name }, { it.createdAt }, { modified(it.lastModified, it.updatedAt) })
    }

    /** The sync picker's rows. */
    fun channelRows(list: List<ProviderChannelPlaylist>, sort: PlaylistSort): List<ProviderChannelPlaylist> =
        sorted(list, sort, { it.name }, { it.createdAt }, { modified(it.lastModified, it.updatedAt) })

    /** "Recently Modified" uses the last content change, else the last write to the row. */
    private fun modified(lastModified: Long, updatedAt: Long): Long = if (lastModified > 0L) lastModified else updatedAt

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
