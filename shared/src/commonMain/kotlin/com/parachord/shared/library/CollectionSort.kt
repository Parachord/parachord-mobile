package com.parachord.shared.library

import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Friend
import com.parachord.shared.model.Track
import com.parachord.shared.platform.currentTimeMillis

/*
 * The Collection screen's sort orders and text filter, one copy for both apps
 * (#593). Labels and defaults are desktop's (`getCollectionSortOptions`,
 * `collectionSort`). Each sort's `name` is what's saved ("Remember sort"),
 * so don't rename one. Sorting is stable: rows that compare equal keep their
 * order.
 */

enum class ArtistSort(val label: String) {
    ALPHA_ASC("A-Z"),
    ALPHA_DESC("Z-A"),
    RECENT("Recently Added");

    companion object {
        val DEFAULT = ALPHA_ASC
        fun fromName(name: String?): ArtistSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

enum class AlbumSort(val label: String) {
    ALPHA_ASC("A-Z"),
    ALPHA_DESC("Z-A"),
    ARTIST("Artist Name"),
    RECENT("Recently Added");

    companion object {
        val DEFAULT = RECENT
        fun fromName(name: String?): AlbumSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

enum class TrackSort(val label: String) {
    TITLE_ASC("Title A-Z"),
    TITLE_DESC("Title Z-A"),
    ARTIST("Artist Name"),
    ALBUM("Album Name"),
    DURATION("Duration"),
    RECENT("Recently Added");

    companion object {
        val DEFAULT = RECENT
        fun fromName(name: String?): TrackSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

enum class FriendSort(val label: String) {
    ALPHA_ASC("A-Z"),
    ALPHA_DESC("Z-A"),
    RECENT("Recently Added"),
    ACTIVE("Recently Active"),
    ON_AIR("On Air Now");

    companion object {
        val DEFAULT = ALPHA_ASC
        fun fromName(name: String?): FriendSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

object CollectionSort {
    /** "Recently Active" keeps friends who played something in the last 14 days. */
    const val ACTIVE_WITHIN_SECONDS = 14L * 24 * 60 * 60

    private fun matches(query: String, vararg fields: String?): Boolean =
        query.isBlank() || fields.any { it?.contains(query.trim(), ignoreCase = true) == true }

    fun artists(list: List<Artist>, sort: ArtistSort, query: String): List<Artist> {
        val f = list.filter { matches(query, it.name) }
        return when (sort) {
            ArtistSort.ALPHA_ASC -> f.sortedBy { it.name.lowercase() }
            ArtistSort.ALPHA_DESC -> f.sortedByDescending { it.name.lowercase() }
            ArtistSort.RECENT -> f.sortedByDescending { it.addedAt }
        }
    }

    fun albums(list: List<Album>, sort: AlbumSort, query: String): List<Album> {
        val f = list.filter { matches(query, it.title, it.artist) }
        return when (sort) {
            AlbumSort.ALPHA_ASC -> f.sortedBy { it.title.lowercase() }
            AlbumSort.ALPHA_DESC -> f.sortedByDescending { it.title.lowercase() }
            AlbumSort.ARTIST -> f.sortedBy { it.artist.lowercase() }
            AlbumSort.RECENT -> f.sortedByDescending { it.addedAt }
        }
    }

    fun tracks(list: List<Track>, sort: TrackSort, query: String): List<Track> {
        val f = list.filter { matches(query, it.title, it.artist, it.album) }
        return when (sort) {
            TrackSort.TITLE_ASC -> f.sortedBy { it.title.lowercase() }
            TrackSort.TITLE_DESC -> f.sortedByDescending { it.title.lowercase() }
            TrackSort.ARTIST -> f.sortedBy { it.artist.lowercase() }
            TrackSort.ALBUM -> f.sortedBy { (it.album ?: "").lowercase() }
            TrackSort.DURATION -> f.sortedByDescending { it.duration ?: 0L }
            TrackSort.RECENT -> f.sortedByDescending { it.addedAt }
        }
    }

    /** "Recently Active" and "On Air Now" also leave out the friends they don't cover. */
    fun friends(list: List<Friend>, sort: FriendSort, query: String): List<Friend> =
        friends(list, sort, query, currentTimeMillis())

    fun friends(list: List<Friend>, sort: FriendSort, query: String, nowMs: Long): List<Friend> {
        val f = list.filter { matches(query, it.displayName) }
        return when (sort) {
            FriendSort.ALPHA_ASC -> f.sortedBy { it.displayName.lowercase() }
            FriendSort.ALPHA_DESC -> f.sortedByDescending { it.displayName.lowercase() }
            FriendSort.RECENT -> f.sortedByDescending { it.addedAt }
            FriendSort.ACTIVE -> {
                val cutoff = nowMs / 1000 - ACTIVE_WITHIN_SECONDS
                f.filter { it.cachedTrackTimestamp > cutoff }.sortedByDescending { it.cachedTrackTimestamp }
            }
            FriendSort.ON_AIR -> f.filter { it.isOnAir }.sortedByDescending { it.cachedTrackTimestamp }
        }
    }
}
