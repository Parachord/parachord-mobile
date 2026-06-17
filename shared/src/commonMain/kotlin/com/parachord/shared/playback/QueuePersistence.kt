package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializable snapshot of the playback queue for restore-across-relaunch (#220).
 *
 * iOS analogue of Android's `app/.../playback/QueueState.kt` `PersistedQueueState`.
 * Because [Track] + [PlaybackContext] are `@Serializable`, this holds the models
 * directly — no `SerializableTrack` DTO. Queue state is **per-device** (never
 * synced), so the iOS and Android JSON shapes don't need to match byte-for-byte.
 *
 * The current track is NOT part of [QueueSnapshot] (the QueueManager keeps it
 * separate), so it's carried explicitly here.
 */
@Serializable
data class PersistedQueueState(
    val currentTrack: Track? = null,
    val upNext: List<Track> = emptyList(),
    val playHistory: List<Track> = emptyList(),
    val playbackContext: PlaybackContext? = null,
    val shuffleEnabled: Boolean = false,
    val originalOrder: List<Track>? = null,
)

/**
 * Pure JSON codec for [PersistedQueueState], shared by the iOS coordinator's
 * save/restore. Lenient decode so an older/newer persisted blob never crashes
 * restore — a malformed blob just yields null and the queue starts empty.
 */
object QueuePersistenceCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: PersistedQueueState): String =
        json.encodeToString(PersistedQueueState.serializer(), state)

    fun decode(jsonStr: String): PersistedQueueState? =
        runCatching { json.decodeFromString(PersistedQueueState.serializer(), jsonStr) }.getOrNull()
}
