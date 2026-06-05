package com.parachord.shared.sync

import com.parachord.shared.model.Track
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mutex-guarded suspend facade over [TrackTombstones] + [TombstoneStore].
 * All integration points (SyncEngine, LibraryRepository, app-start prune)
 * go through this so concurrent sync / UI mutations can't interleave a
 * read-modify-write on the shared blob (#172).
 */
class TrackTombstoneService(private val store: TombstoneStore) {
    private val mutex = Mutex()

    /** Write a tombstone for every (providerId, externalId) in [entries]. Returns count written. */
    suspend fun addAll(entries: List<TombstoneEntry>): Int = mutex.withLock {
        TrackTombstones.addTombstones(store, entries, currentTimeMillis())
    }

    /** Clear tombstones for every (providerId, externalId) in [entries]. Returns count cleared. */
    suspend fun clearAll(entries: List<TombstoneEntry>): Int = mutex.withLock {
        TrackTombstones.clearTombstones(store, entries)
    }

    /**
     * Drop tombstoned items from [items] for [providerId], re-arming TTL on
     * every hit. [externalIdOf] extracts the remote ID from each item.
     */
    suspend fun <T> filterRemote(
        items: List<T>,
        providerId: String,
        externalIdOf: (T) -> String?,
    ): TombstoneFilterResult<T> = mutex.withLock {
        TrackTombstones.filterRemoteByTombstones(store, items, providerId, currentTimeMillis(), externalIdOf)
    }

    /** Sweep entries older than TTL. Call once per app launch. Returns count pruned. */
    suspend fun prune(ttlMs: Long = TrackTombstones.TTL_MS): Int = mutex.withLock {
        TrackTombstones.pruneExpired(store, ttlMs, currentTimeMillis())
    }

    companion object {
        /**
         * Provider tombstone keys derivable from a track's known external IDs.
         * Mirrors desktop `deriveTombstoneEntries` — spotify/applemusic/
         * listenbrainz only (the providers that sync the collection). Dedups
         * by "providerId:externalId".
         */
        fun deriveTombstoneEntries(track: Track): List<TombstoneEntry> {
            val out = mutableListOf<TombstoneEntry>()
            val seen = mutableSetOf<String>()
            fun push(providerId: String, externalId: String?) {
                if (externalId.isNullOrEmpty()) return
                if (seen.add("$providerId:$externalId")) out.add(TombstoneEntry(providerId, externalId))
            }
            push("spotify", track.spotifyId)
            push("applemusic", track.appleMusicId)
            push("listenbrainz", track.recordingMbid)
            return out
        }
    }
}
