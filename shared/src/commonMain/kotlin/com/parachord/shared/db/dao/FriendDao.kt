package com.parachord.shared.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.parachord.shared.db.Friends
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.model.Friend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [FriendQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class FriendDao(private val db: ParachordDb) {

    private val queries get() = db.friendQueries

    /* ---- Mapping ---- */

    private fun Friends.toFriend() = Friend(
        id = id,
        username = username,
        service = service,
        displayName = displayName,
        avatarUrl = avatarUrl,
        addedAt = addedAt,
        lastFetchedAt = lastFetchedAt,
        cachedTrackName = cachedTrackName,
        cachedTrackArtist = cachedTrackArtist,
        cachedTrackAlbum = cachedTrackAlbum,
        cachedTrackTimestamp = cachedTrackTimestamp,
        cachedTrackArtworkUrl = cachedTrackArtworkUrl,
        pinnedToSidebar = pinnedToSidebar != 0L,
        autoPinned = autoPinned != 0L,
    )

    /* ---- Queries returning Flow ---- */

    fun getAllFriends(): Flow<List<Friend>> =
        queries.getAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toFriend() } }

    fun getPinnedFriends(): Flow<List<Friend>> =
        queries.getPinned().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toFriend() } }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getAllFriendsSync(): List<Friend> = withContext(Dispatchers.Default) {
        queries.getAll().executeAsList().map { it.toFriend() }
    }

    suspend fun getFriendById(id: String): Friend? = withContext(Dispatchers.Default) {
        queries.getById(id).executeAsOneOrNull()?.toFriend()
    }

    /**
     * Look up a saved friend by `(service, username)` with a
     * case-insensitive username match. One-shot query — does NOT
     * round-trip through the live Flow used by [getAllFriends].
     */
    suspend fun findByServiceAndUsername(service: String, username: String): Friend? =
        withContext(Dispatchers.Default) {
            queries.findByServiceAndUsername(service, username).executeAsOneOrNull()?.toFriend()
        }

    /* ---- Writes ---- */

    suspend fun upsert(friend: Friend): Unit = withContext(Dispatchers.Default) {
        queries.upsert(
            id = friend.id,
            username = friend.username,
            service = friend.service,
            displayName = friend.displayName,
            avatarUrl = friend.avatarUrl,
            addedAt = friend.addedAt,
            lastFetchedAt = friend.lastFetchedAt,
            cachedTrackName = friend.cachedTrackName,
            cachedTrackArtist = friend.cachedTrackArtist,
            cachedTrackAlbum = friend.cachedTrackAlbum,
            cachedTrackTimestamp = friend.cachedTrackTimestamp,
            cachedTrackArtworkUrl = friend.cachedTrackArtworkUrl,
            pinnedToSidebar = if (friend.pinnedToSidebar) 1L else 0L,
            autoPinned = if (friend.autoPinned) 1L else 0L,
        )
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteById(id)
    }

    suspend fun setPinned(id: String, pinned: Boolean, auto: Boolean = false): Unit = withContext(Dispatchers.Default) {
        queries.setPinned(
            pinnedToSidebar = if (pinned) 1L else 0L,
            autoPinned = if (auto) 1L else 0L,
            id = id,
        )
    }

    suspend fun updateCachedTrack(
        id: String,
        name: String?,
        artist: String?,
        album: String?,
        timestamp: Long,
        artworkUrl: String?,
        fetchedAt: Long,
    ): Unit = withContext(Dispatchers.Default) {
        queries.updateCachedTrack(
            cachedTrackName = name,
            cachedTrackArtist = artist,
            cachedTrackAlbum = album,
            cachedTrackTimestamp = timestamp,
            cachedTrackArtworkUrl = artworkUrl,
            lastFetchedAt = fetchedAt,
            id = id,
        )
    }
}
