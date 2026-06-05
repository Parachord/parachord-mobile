package com.parachord.android.sync

import com.parachord.shared.sync.Tombstone
import com.parachord.shared.sync.TombstoneStore

/**
 * In-memory [TombstoneStore] fake for SyncEngine unit tests. Mirrors the
 * production map shape (providerId -> externalId -> Tombstone) without any
 * persistence layer.
 */
class InMemoryTombstoneStore : TombstoneStore {
    private var data: MutableMap<String, MutableMap<String, Tombstone>> = mutableMapOf()

    override fun read(): MutableMap<String, MutableMap<String, Tombstone>> {
        // Return a deep mutable copy so callers can mutate before write().
        val copy: MutableMap<String, MutableMap<String, Tombstone>> = mutableMapOf()
        for ((p, bucket) in data) copy[p] = bucket.toMutableMap()
        return copy
    }

    override fun write(data: Map<String, Map<String, Tombstone>>) {
        val next: MutableMap<String, MutableMap<String, Tombstone>> = mutableMapOf()
        for ((p, bucket) in data) next[p] = bucket.toMutableMap()
        this.data = next
    }
}
