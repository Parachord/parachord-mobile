package com.parachord.shared.playback.scrobbler

import com.parachord.shared.model.Track

/**
 * A scrobbler target (Last.fm / ListenBrainz / Libre.fm …). Shared so iOS
 * scrobbles through the same instances as Android (#193). Implementations are
 * pure KMP — they call the shared Ktor clients ([com.parachord.shared.api.LastFmClient],
 * [com.parachord.shared.api.ListenBrainzClient]) and read credentials from the
 * shared [com.parachord.shared.settings.SettingsStore].
 *
 * Mirrors the desktop app's scrobbler-plugin contract. [ScrobbleManager] owns
 * the threshold logic and dispatches to every enabled scrobbler.
 */
interface Scrobbler {
    val id: String
    val displayName: String

    /** True when the user has authenticated this service. */
    suspend fun isEnabled(): Boolean

    /**
     * Send a "now playing" update when a track starts. [durationMs] is the
     * ACTUAL playing-source (engine) duration in milliseconds — prefer it over
     * the metadata `track.duration`, which is unreliable (inconsistent units per
     * source, `0` for metadata-only tracks). Null when the engine hasn't reported
     * a duration yet; consumers should then OMIT the duration rather than fall
     * back to the metadata value (#347).
     */
    suspend fun sendNowPlaying(track: Track, durationMs: Long? = null)

    /**
     * Submit a scrobble once the listen-threshold is reached. [durationMs] is the
     * engine duration in ms (see [sendNowPlaying]); reliably non-null here since
     * the threshold gates on a known duration.
     */
    suspend fun submitScrobble(track: Track, timestamp: Long, durationMs: Long? = null)

    /**
     * Push a love/feedback for the track. Default no-op — only services that
     * support it (Last.fm `track.love`, ListenBrainz `recording-feedback`)
     * override. Throws on hard failure so the caller can decide whether to
     * persist the idempotency key.
     */
    suspend fun loveTrack(track: Track) {}
}
