package com.parachord.shared.model

data class Friend(
    val id: String,
    val username: String,
    val service: String, // "lastfm" | "listenbrainz"
    val displayName: String,
    val avatarUrl: String? = null,
    val addedAt: Long,
    val lastFetchedAt: Long = 0,
    val cachedTrackName: String? = null,
    val cachedTrackArtist: String? = null,
    val cachedTrackAlbum: String? = null,
    val cachedTrackTimestamp: Long = 0,
    val cachedTrackArtworkUrl: String? = null,
    val pinnedToSidebar: Boolean = false,
    val autoPinned: Boolean = false,
    /**
     * `true` for synthetic Friend records minted on the fly by the
     * `parachord://listen-along?service=…&user=…` deeplink for users who
     * are not in the local friends DB. Transient friends are NEVER
     * persisted via [com.parachord.shared.db.dao.FriendDao] (the DAO's
     * `upsert` doesn't read this field, and the listen-along path goes
     * directly to `MainViewModel.startListenAlong(friend)` without
     * touching Room). Default `false` keeps every existing call site
     * — DB hydration, sync, friend-add — untouched.
     */
    val transient: Boolean = false,
) {
    /** Friend is "on air" if their cached track was played within the last 10 minutes. */
    val isOnAir: Boolean
        get() = cachedTrackTimestamp > 0 &&
            (currentTimeMillis() / 1000 - cachedTrackTimestamp) < 600
}

/** Platform-agnostic current time in milliseconds. */
internal expect fun currentTimeMillis(): Long
