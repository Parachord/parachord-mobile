package com.parachord.shared.resolver

import kotlinx.serialization.json.Json

/**
 * JSON form of the iOS persistent resolver cache (`IosTrackResolverCache`):
 * track key → ranked sources.
 *
 * Only real results reach disk. A resolve that failed or was rate-limited is
 * kept in memory as an empty list (so the session doesn't re-ask on every
 * scroll), but an empty list on disk would read back as "resolved, nothing
 * found" on every later launch, and that track would never be fully resolved
 * again. So [encode] drops empty entries, and [decode] drops any an older build
 * already wrote.
 */
object ResolverCacheCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(map: Map<String, List<ResolvedSource>>): String =
        json.encodeToString(map.filterValues { it.isNotEmpty() })

    /**
     * Entries are decoded one at a time: a single bad entry (a field whose type
     * changed, a truncated value) drops only that entry. The whole map used to
     * be decoded at once, so one bad entry threw the entire cache away and every
     * track re-resolved — a burst of catalog searches.
     */
    fun decode(blob: String): Map<String, List<ResolvedSource>> {
        val root = try {
            json.parseToJsonElement(blob) as? kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        val sourcesSerializer = kotlinx.serialization.builtins.ListSerializer(ResolvedSource.serializer())
        return buildMap {
            for ((key, value) in root) {
                val sources = try {
                    json.decodeFromJsonElement(sourcesSerializer, value)
                } catch (e: Exception) {
                    continue
                }
                if (sources.isNotEmpty()) put(key, sources)
            }
        }
    }
}
