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
    /**
     * Whether each [playHistory] entry belongs to the queue (repeat all wraps
     * only those; see `QueueManager.historyInQueue`). Null or short in a blob
     * saved before it existed: the missing entries count as in the queue.
     */
    val playHistoryInQueue: List<Boolean>? = null,
    /** The current track was a spinoff pool track (`QueueManager.currentIsPoolTrack`). */
    val currentIsPoolTrack: Boolean = false,
    /**
     * Whether each [upNext] entry belongs to the lap (Previous can put an
     * out-of-lap track there); null or short counts as in the lap.
     */
    val upNextInLap: List<Boolean>? = null,
    /** The current track belongs to the lap (`QueueManager.currentInLap`). */
    val currentInLap: Boolean = true,
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

/**
 * Where the current track had got to, saved beside the queue so a queue
 * restored at launch resumes the track there (#567).
 *
 * Kept out of [PersistedQueueState]: it's written every few seconds while a
 * song plays, and the queue can be hundreds of tracks.
 *
 * @param trackId the track the position belongs to; a restored track with
 *   another id starts from the top.
 * @param durationMs the track's length when known, else 0.
 */
@Serializable
data class SavedPlaybackPosition(
    val trackId: String,
    val positionMs: Long,
    val durationMs: Long = 0,
    /**
     * This listen was already scrobbled (#657, desktop parachord#1346): a
     * resume at the position mustn't scrobble it again after a restart.
     */
    val scrobbled: Boolean = false,
)

/**
 * When to save the playback position, and where a restored track resumes.
 *
 * Without it, a phone that ended the app while it was paused (Android frees
 * the memory of a paused app it no longer protects; iOS ends suspended apps)
 * reopened with the song back at 0:00 (#567).
 */
object PlaybackResumePosition {
    /** How often the position is saved while a song plays. */
    const val SAVE_INTERVAL_MS = 15_000L

    /** A track this barely started starts over. */
    const val MIN_RESUME_MS = 5_000L

    /** A track this close to its end starts over rather than ending at once. */
    const val END_MARGIN_MS = 10_000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Save when playback has just stopped (a pause, a failed stream), and
     * every [SAVE_INTERVAL_MS] while it plays.
     */
    fun shouldSave(wasPlaying: Boolean, isPlaying: Boolean, msSinceLastSave: Long): Boolean =
        (wasPlaying && !isPlaying) || (isPlaying && msSinceLastSave >= SAVE_INTERVAL_MS)

    /**
     * Where [trackId] should start, in ms: the saved position when it's for
     * this track and worth resuming, else 0 (from the top).
     */
    fun resumeAtMs(saved: SavedPlaybackPosition?, trackId: String?): Long {
        if (saved == null || trackId == null || saved.trackId != trackId) return 0
        if (saved.positionMs < MIN_RESUME_MS) return 0
        if (saved.durationMs > 0 && saved.positionMs > saved.durationMs - END_MARGIN_MS) return 0
        return saved.positionMs
    }

    fun encode(position: SavedPlaybackPosition): String =
        json.encodeToString(SavedPlaybackPosition.serializer(), position)

    /** A malformed or missing value is null: the track starts from the top. */
    fun decode(jsonStr: String?): SavedPlaybackPosition? =
        jsonStr?.let { runCatching { json.decodeFromString(SavedPlaybackPosition.serializer(), it) }.getOrNull() }
}

/**
 * When a saved queue may be put back at startup.
 *
 * Only when nothing has played yet in this run of the app. The restore reads
 * storage first, so something else can start playing before it gets to apply
 * what it read: the car (Android Auto or CarPlay) starting the app with the
 * phone locked, a deep link, a tap on the phone. Applying it then put the
 * queue and track from the last session back over what was playing: the
 * phone showed the old track, and on Android the car was handed it too,
 * paused, with Play starting the old track over.
 */
object QueueRestorePolicy {
    /**
     * @param hasCurrentTrack a track is already showing as the current one.
     * @param playRequested a play was asked for in this run (it may still be
     *   resolving, so there's no current track yet).
     */
    fun shouldApply(hasCurrentTrack: Boolean, playRequested: Boolean): Boolean =
        !hasCurrentTrack && !playRequested

    /**
     * Whether the queue as it is now may be written over the saved one. An
     * empty queue is saved only once this run has had a queue (someone
     * cleared it, or the queue ran out): a run that starts empty, because its
     * restore was skipped for a play link that then found nothing (#604),
     * would otherwise wipe the last session's queue for good.
     *
     * @param isEmpty no current track and nothing in Up Next.
     * @param hadQueueThisRun a queue was restored, or a non-empty one saved, in this run.
     */
    fun shouldSave(isEmpty: Boolean, hadQueueThisRun: Boolean): Boolean =
        !isEmpty || hadQueueThisRun
}
