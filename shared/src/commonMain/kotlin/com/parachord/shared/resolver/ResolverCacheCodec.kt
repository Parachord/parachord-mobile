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

    fun decode(blob: String): Map<String, List<ResolvedSource>> =
        try {
            json.decodeFromString<Map<String, List<ResolvedSource>>>(blob).filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyMap()
        }
}
