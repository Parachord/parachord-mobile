package com.parachord.shared.ios

import com.parachord.shared.ai.ChatPlaybackSnapshot
import com.parachord.shared.ai.tools.DjToolExecutor
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Track
import com.parachord.shared.settings.SettingsStore

/**
 * iOS implementation of the shared [DjToolExecutor] interface (#223/#202).
 *
 * Mirrors Android's `app/.../ai/tools/DjToolExecutor.kt` 8-tool dispatch almost
 * line-for-line. The ONLY platform difference: Android calls `PlaybackController`
 * directly; here playback actions are delegated to the Swift `QueuePlayback
 * Coordinator` through [IosChatPlaybackBridge] closures (set once at app start).
 * Track resolution is deliberately lazy — we hand metadata-only [Track]s to the
 * coordinator, which resolves them on-the-fly at play time (the same path as
 * Spinoff / Listen Along), so no ResolverManager is needed on iOS.
 *
 * Contract (shared interface): NEVER throw — return `{"error": ...}` so the AI can
 * see the failure and recover.
 */
class IosDjToolExecutor(
    private val metadataService: MetadataService,
    private val settingsStore: SettingsStore,
    private val bridge: IosChatPlaybackBridge,
) : DjToolExecutor {

    override suspend fun execute(name: String, args: Map<String, Any?>): Map<String, Any?> {
        return try {
            when (name) {
                "play" -> executePlay(args)
                "control" -> executeControl(args)
                "search" -> executeSearch(args)
                "queue_add" -> executeQueueAdd(args)
                "queue_clear" -> { bridge.onClearQueue(); mapOf("success" to true) }
                "create_playlist" -> mapOf(
                    // iOS has no library/collection repository yet (#194) — degrade
                    // gracefully instead of faking success.
                    "error" to "Creating playlists isn't supported on iOS yet.",
                )
                "shuffle" -> executeShuffle(args)
                "block_recommendation" -> executeBlockRecommendation(args)
                else -> mapOf("error" to "Unknown tool: $name")
            }
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "Unknown error executing tool $name"))
        }
    }

    private suspend fun executePlay(args: Map<String, Any?>): Map<String, Any?> {
        val artist = args["artist"] as? String ?: return mapOf("error" to "Missing artist")
        val title = args["title"] as? String ?: return mapOf("error" to "Missing title")
        val results = metadataService.searchTracks("$artist $title", 20)
        if (results.isEmpty()) return mapOf("error" to "No results found for \"$artist - $title\"")
        val best = results.find {
            it.artist.equals(artist, ignoreCase = true) && it.title.equals(title, ignoreCase = true)
        } ?: results.first()
        val track = best.toTrack()
        bridge.onPlayTrack(track)
        return mapOf(
            "success" to true,
            "track" to mapOf("title" to track.title, "artist" to track.artist, "album" to (track.album ?: "")),
        )
    }

    private fun executeControl(args: Map<String, Any?>): Map<String, Any?> {
        val action = args["action"] as? String ?: return mapOf("error" to "Missing action")
        when (action) {
            "pause" -> bridge.onPause()
            "resume" -> bridge.onResume()
            "skip" -> bridge.onSkipNext()
            "previous" -> bridge.onSkipPrevious()
            else -> return mapOf("error" to "Unknown action: $action")
        }
        return mapOf("success" to true, "action" to action)
    }

    private suspend fun executeSearch(args: Map<String, Any?>): Map<String, Any?> {
        val query = args["query"] as? String ?: return mapOf("error" to "Missing query")
        val limit = (args["limit"] as? Number)?.toInt() ?: 10
        val results = metadataService.searchTracks(query, limit)
        return mapOf(
            "success" to true,
            "results" to results.map {
                mapOf("artist" to it.artist, "title" to it.title, "album" to (it.album ?: ""))
            },
        )
    }

    private suspend fun executeQueueAdd(args: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val tracksArg = args["tracks"] as? List<Map<String, Any?>>
            ?: return mapOf("error" to "Missing tracks")
        if (tracksArg.isEmpty()) return mapOf("error" to "Empty tracks list")
        val playFirst = args["playFirst"] as? Boolean ?: true

        val resolved = mutableListOf<Track>()
        for (t in tracksArg) {
            val artist = t["artist"] as? String ?: continue
            val title = t["title"] as? String ?: continue
            val results = metadataService.searchTracks("$artist $title", 5)
            val best = results.find {
                it.artist.equals(artist, ignoreCase = true) && it.title.equals(title, ignoreCase = true)
            } ?: results.firstOrNull()
            if (best != null) resolved.add(best.toTrack())
        }
        if (resolved.isEmpty()) return mapOf("error" to "Could not find any of the requested tracks")

        if (playFirst) {
            bridge.onClearQueue()
            bridge.onPlayTrack(resolved.first())
            resolved.drop(1).forEach { bridge.onAddToQueue(it) }
        } else {
            resolved.forEach { bridge.onAddToQueue(it) }
        }
        return mapOf(
            "success" to true,
            "queued" to resolved.size,
            "tracks" to resolved.map { mapOf("artist" to it.artist, "title" to it.title) },
        )
    }

    private fun executeShuffle(args: Map<String, Any?>): Map<String, Any?> {
        val enabled = args["enabled"] as? Boolean ?: return mapOf("error" to "Missing enabled parameter")
        if (bridge.current.shuffleEnabled != enabled) bridge.onSetShuffle(enabled)
        return mapOf("success" to true, "shuffleEnabled" to enabled)
    }

    private suspend fun executeBlockRecommendation(args: Map<String, Any?>): Map<String, Any?> {
        val type = args["type"] as? String ?: return mapOf("error" to "Missing type parameter")
        val entry = when (type) {
            "artist" -> "artist:${args["name"] as? String ?: return mapOf("error" to "Missing name for artist block")}"
            "album" -> {
                val artist = args["artist"] as? String ?: return mapOf("error" to "Missing artist for album block")
                val title = args["title"] as? String ?: return mapOf("error" to "Missing title for album block")
                "album:$artist:$title"
            }
            "track" -> {
                val artist = args["artist"] as? String ?: return mapOf("error" to "Missing artist for track block")
                val title = args["title"] as? String ?: return mapOf("error" to "Missing title for track block")
                "track:$artist:$title"
            }
            else -> return mapOf("error" to "Unknown block type: $type")
        }
        settingsStore.addBlockedRecommendation(entry)
        return mapOf("success" to true, "blocked" to entry)
    }

    /** Metadata-only Track — resolves on-the-fly at play time via the coordinator. */
    private fun TrackSearchResult.toTrack(): Track = Track(
        id = "chat:${title.lowercase()}|${artist.lowercase()}",
        title = title, artist = artist, album = album, albumId = null,
        duration = duration ?: 0, artworkUrl = artworkUrl,
        sourceType = null, sourceUrl = null, addedAt = 0,
        resolver = null, spotifyUri = null, soundcloudId = null,
        spotifyId = spotifyId, appleMusicId = null, isrc = null,
        recordingMbid = mbid, artistMbid = null, releaseMbid = null, crossResolverEnrichedAt = null,
    )
}

/**
 * Bridge from the Kotlin chat stack to the Swift playback coordinator (#223).
 * Swift sets these closures once at app start (closing over QueuePlaybackCoordinator)
 * via `IosContainer.bindChatPlayback(...)`. [current] is pushed by Swift so the
 * Kotlin side never reads @MainActor state cross-thread; the executor + the chat
 * system-prompt context provider both read it.
 */
/** Flat Swift-facing chat message (role is "user" / "assistant"). */
data class IosChatMessage(val role: String, val content: String)

class IosChatPlaybackBridge {
    var current: ChatPlaybackSnapshot = ChatPlaybackSnapshot()
    var onPlayTrack: (Track) -> Unit = {}
    // Single-track (Swift loops) — avoids bridging a Kotlin List into a Swift closure.
    var onAddToQueue: (Track) -> Unit = {}
    var onClearQueue: () -> Unit = {}
    var onPause: () -> Unit = {}
    var onResume: () -> Unit = {}
    var onSkipNext: () -> Unit = {}
    var onSkipPrevious: () -> Unit = {}
    var onSetShuffle: (Boolean) -> Unit = {}
}
