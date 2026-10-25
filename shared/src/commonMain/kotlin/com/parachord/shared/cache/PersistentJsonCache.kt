package com.parachord.shared.cache

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A small key → value cache persisted as one JSON blob, with a TTL and a size
 * cap. Desktop keeps the same kind of cache for release details (30 days,
 * 400 entries) and artist data (30 days); mobile refetched both on every
 * launch, at MusicBrainz's 1 request per second.
 *
 * - The blob is read once, on first use, before any lookup is answered — so a
 *   lookup can never run against a not-yet-loaded cache and refetch everything.
 * - Writes are debounced ([saveDelayMs]) so a burst of puts is one disk write.
 * - Over [maxEntries], the oldest entries are dropped.
 * - An unreadable blob counts as empty (logged), never as a crash.
 */
class PersistentJsonCache<V>(
    valueSerializer: KSerializer<V>,
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Unit,
    private val ttlMs: Long,
    private val maxEntries: Int,
    private val name: String = "cache",
    private val saveDelayMs: Long = 2_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    @Serializable
    private data class Entry<V>(val value: V, val cachedAt: Long)

    private val mapSerializer = MapSerializer(String.serializer(), Entry.serializer(valueSerializer))
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry<V>>? = null
    private var saveJob: Job? = null

    private suspend fun loaded(): MutableMap<String, Entry<V>> {
        entries?.let { return it }
        val restored = try {
            read()?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(mapSerializer, it) }
        } catch (e: Exception) {
            Log.w(TAG, "$name: unreadable cache, starting empty (${e.message})")
            null
        }
        val now = nowMs()
        return (restored?.filterValues { now - it.cachedAt < ttlMs }?.toMutableMap() ?: mutableMapOf())
            .also { entries = it }
    }

    /** The cached value for [key], or null if missing or older than the TTL. */
    suspend fun get(key: String): V? = mutex.withLock {
        val map = loaded()
        val e = map[key] ?: return@withLock null
        if (nowMs() - e.cachedAt >= ttlMs) {
            map.remove(key)
            null
        } else {
            e.value
        }
    }

    suspend fun put(key: String, value: V) {
        mutex.withLock {
            val map = loaded()
            map[key] = Entry(value, nowMs())
            if (map.size > maxEntries) {
                map.entries.sortedBy { it.value.cachedAt }
                    .take(map.size - maxEntries)
                    .forEach { map.remove(it.key) }
            }
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(saveDelayMs)
            flush()
        }
    }

    /** Write the cache now (also what the debounced save calls). */
    suspend fun flush() {
        val blob = mutex.withLock {
            val map = entries ?: return
            json.encodeToString(mapSerializer, map)
        }
        try {
            write(blob)
        } catch (e: Exception) {
            Log.w(TAG, "$name: save failed (${e.message})")
        }
    }

    private companion object {
        const val TAG = "PersistentJsonCache"
    }
}
