package com.parachord.shared.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [TombstoneStore] backed by a single JSON blob, mirroring desktop's
 * electron-store key `removed_track_tombstones`. Read/write are synchronous
 * (the pure [TrackTombstones] functions call them inline). Backing I/O is
 * injected as sync lambdas so this stays KMP-pure (no suspend, no platform
 * deps). On a missing or corrupt blob, [read] returns an empty map — never
 * throws — so a malformed persisted value self-heals on next write.
 */
class KvTombstoneStore(
    private val loadBlob: () -> String?,
    private val saveBlob: (String) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) : TombstoneStore {

    override fun read(): MutableMap<String, MutableMap<String, Tombstone>> {
        val raw = loadBlob() ?: return mutableMapOf()
        if (raw.isBlank()) return mutableMapOf()
        return try {
            // Rebuild as a fully-mutable nested map: the TombstoneStore contract
            // promises a map the pure functions mutate in place (inner buckets
            // included), so both levels must be MutableMap — don't simplify.
            json.decodeFromString<Map<String, Map<String, Tombstone>>>(raw)
                .mapValues { (_, v) -> v.toMutableMap() }
                .toMutableMap()
        } catch (_: Exception) {
            mutableMapOf() // corrupt blob → start clean
        }
    }

    override fun write(data: Map<String, Map<String, Tombstone>>) {
        saveBlob(json.encodeToString(data))
    }

    companion object {
        const val KEY = "removed_track_tombstones"
    }
}
