package com.parachord.shared.playback.scrobbler

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore

/** 36-char MusicBrainz Identifier shape — required by LB feedback API. */
private val LB_MBID_PATTERN =
    Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$", RegexOption.IGNORE_CASE)

/**
 * ListenBrainz scrobbler — submits listens + loves through the shared Ktor
 * [ListenBrainzClient] (#193, KMP). Mirrors the desktop's listenbrainz-scrobbler.js.
 * Auth is a user token (`Authorization: Token {token}`) read from [SettingsStore].
 *
 * The payload (listen_type, track_metadata, additional_info with MBIDs + the
 * `origin_url` / `music_service` / `spotify_id` streaming-source fields) is built
 * inside [ListenBrainzClient.submitListens]; this class just derives the
 * per-track enrichment via [deriveLbSourceEnrichment] and forwards it.
 */
class ListenBrainzScrobbler(
    private val settingsStore: SettingsStore,
    private val listenBrainzClient: ListenBrainzClient,
    private val mbidEnrichment: MbidEnrichmentService,
) : Scrobbler {
    companion object {
        private const val TAG = "ListenBrainzScrobbler"
    }

    override val id = "listenbrainz"
    override val displayName = "ListenBrainz"

    override suspend fun isEnabled(): Boolean = settingsStore.getListenBrainzToken() != null

    override suspend fun sendNowPlaying(track: Track) {
        val token = settingsStore.getListenBrainzToken() ?: return
        val e = deriveLbSourceEnrichment(track)
        val ok = listenBrainzClient.submitListens(
            token = token,
            artist = track.artist,
            title = track.title,
            release = track.album,
            recordingMbid = e.recordingMbid,
            artistMbids = e.artistMbids,
            releaseMbid = e.releaseMbid,
            durationMs = track.duration,
            listenedAt = null,
            originUrl = e.originUrl,
            musicService = e.musicService,
            musicServiceName = e.musicServiceName,
            spotifyId = e.spotifyId,
        )
        if (ok) Log.d(TAG, "Now playing: ${track.artist} - ${track.title}")
    }

    override suspend fun submitScrobble(track: Track, timestamp: Long) {
        val token = settingsStore.getListenBrainzToken() ?: return
        val e = deriveLbSourceEnrichment(track)
        val ok = listenBrainzClient.submitListens(
            token = token,
            artist = track.artist,
            title = track.title,
            release = track.album,
            recordingMbid = e.recordingMbid,
            artistMbids = e.artistMbids,
            releaseMbid = e.releaseMbid,
            durationMs = track.duration,
            listenedAt = timestamp,
            originUrl = e.originUrl,
            musicService = e.musicService,
            musicServiceName = e.musicServiceName,
            spotifyId = e.spotifyId,
        )
        if (ok) Log.d(TAG, "Scrobbled: ${track.artist} - ${track.title}")
    }

    /**
     * Push a love (feedback score=1) to ListenBrainz. Issue #125 — opt-in
     * cross-service love sync. One-way (no unlove). Requires a 36-char-UUID
     * `track.recordingMbid`; falls through to the MBID Mapper when the row
     * lacks one. Silent skip on no resolution (caller leaves the idempotency
     * key untouched). Throws on hard API failure.
     */
    override suspend fun loveTrack(track: Track) {
        val token = settingsStore.getListenBrainzToken()
            ?: throw IllegalStateException("ListenBrainz not authenticated (no token)")

        val mbid = track.recordingMbid?.takeIf { LB_MBID_PATTERN.matches(it) }
            ?: try {
                mbidEnrichment.getRecordingMbid(track.artist, track.title, track.isrc)
                    ?.takeIf { LB_MBID_PATTERN.matches(it) }
            } catch (e: Exception) {
                Log.w(TAG, "MBID mapper lookup failed for '${track.artist} - ${track.title}': ${e.message}")
                null
            }

        if (mbid == null) {
            Log.d(TAG, "Skipping LB love for '${track.artist} - ${track.title}': no MBID resolvable")
            return
        }

        val ok = listenBrainzClient.submitRecordingFeedback(mbid, score = 1, token = token)
        if (!ok) {
            throw RuntimeException("ListenBrainz feedback API rejected love for $mbid")
        }
        Log.d(TAG, "Loved on ListenBrainz: ${track.artist} — ${track.title} ($mbid)")
    }
}
