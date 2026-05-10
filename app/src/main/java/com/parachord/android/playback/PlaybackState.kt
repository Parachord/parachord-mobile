package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity

/** Represents the current playback state exposed to the UI layer. */
data class PlaybackState(
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val upNext: List<TrackEntity> = emptyList(),
    val playbackContext: PlaybackContext? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val spinoffMode: Boolean = false,
    val spinoffLoading: Boolean = false,
    val spinoffAvailable: Boolean? = null, // null=unchecked, true/false=checked
    /**
     * True while a deeplink-initiated radio is fetching its pool
     * (`parachord://play/radio?url=…` Mode C) and there's no current
     * track yet. Drives the mini-player loading spinner so the user
     * knows something is happening during the multi-second URL fetch.
     * The dispatcher toggles this around the handler call. Cleared
     * automatically when [currentTrack] is set, but the dispatcher
     * also clears it in a finally-block to cover the failure paths.
     */
    val isPlaybarLoading: Boolean = false,
    /**
     * Actual metadata from the streaming source (Spotify / Apple Music).
     * When non-null, the streaming service reported different track info than
     * what we queued — indicates a low-confidence match or mismatched track.
     * Null when playback is via ExoPlayer (local/SoundCloud) since metadata
     * always matches, or when the streaming source confirms the queued track.
     */
    val streamingMetadata: StreamingMetadata? = null,
)

/**
 * Actual metadata reported by the streaming source (Spotify Connect / Apple Music MusicKit).
 * Used to verify that the track playing is what we expected. When it differs from
 * [PlaybackState.currentTrack], the UI shows the actual streaming info.
 */
data class StreamingMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
)

/**
 * The "effective" track that represents what's actually playing.
 * Overlays streaming metadata (from Spotify/Apple Music) onto the queued [TrackEntity],
 * so actions like favorite, add-to-playlist, and scrobble reflect the real track.
 *
 * Returns null if no track is playing. If no streaming metadata is available
 * (ExoPlayer playback), returns [currentTrack] unchanged.
 */
val PlaybackState.effectiveTrack: TrackEntity?
    get() {
        val track = currentTrack ?: return null
        val meta = streamingMetadata ?: return track
        return track.copy(
            title = meta.title ?: track.title,
            artist = meta.artist ?: track.artist,
            album = meta.album ?: track.album,
            artworkUrl = meta.artworkUrl ?: track.artworkUrl,
        )
    }

enum class RepeatMode { OFF, ALL, ONE }
