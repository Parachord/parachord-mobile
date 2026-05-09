package com.parachord.android.playback.scrobbler

import com.parachord.android.data.db.entity.TrackEntity

/**
 * Interface for scrobbling services, mirroring the desktop app's base-scrobbler.js.
 *
 * Each implementation handles a specific service (Last.fm, ListenBrainz, Libre.fm)
 * and is responsible for its own authentication and API protocol.
 */
interface Scrobbler {
    /** Unique identifier for this scrobbler (e.g. "lastfm", "listenbrainz", "librefm"). */
    val id: String

    /** Human-readable name shown in settings. */
    val displayName: String

    /** Whether this scrobbler is currently authenticated and enabled. */
    suspend fun isEnabled(): Boolean

    /** Send a "now playing" notification to the service. */
    suspend fun sendNowPlaying(track: TrackEntity)

    /** Submit a scrobble (track was listened to sufficiently). */
    suspend fun submitScrobble(track: TrackEntity, timestamp: Long)

    /**
     * Push a "love" / favorite of [track] up to the service. One-way per
     * desktop's design (see `docs/plans/2026-05-03-loved-tracks-scrobbler-
     * push-design.md`): if the user later un-loves the track locally, we
     * do NOT call an unlove. Idempotency / dedup is enforced by the
     * caller's `love_pushed_keys` cache.
     *
     * Throws on hard errors (auth invalid, rate-limited 5xx, malformed
     * MBID for LB, etc.). Per-service caveats:
     *
     * - **ListenBrainz** requires a 36-char-UUID `track.recordingMbid`.
     *   When absent, the impl SHOULD attempt the MBID Mapper fallback via
     *   [com.parachord.shared.metadata.MbidEnrichmentService.getRecordingMbid]
     *   before giving up; on no resolution at confidence ≥ 0.7 the impl
     *   skips silently (no throw — caller leaves cache untouched and
     *   retries next sync).
     * - **Last.fm** only needs `artist` + `track`; MBID is optional.
     * - **Libre.fm** has no equivalent endpoint. Default impl is a no-op
     *   per design.
     *
     * Default no-op so adding `loveTrack` to the interface doesn't break
     * any future scrobbler that genuinely doesn't support loves.
     */
    suspend fun loveTrack(track: TrackEntity) {
        // Default no-op — services without a love endpoint inherit this.
    }
}
