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
    /**
     * Why the current track couldn't start, for surfaces that can't show a
     * toast (Android Auto shows it on the car screen). Null while playing or
     * idle; cleared when the next track starts.
     */
    val playbackError: String? = null,
    /**
     * True while a play is under way, from the request (a tap, a Next, a
     * source pick) until its engine has started it or it failed: the lookup,
     * the route and any wait before a player holds the item. [isBuffering]
     * comes from the engine that's playing, so it can't cover that gap (the
     * old engine's polls keep writing it). The play buttons show their
     * spinner for either ([showsLoading]), as desktop's playbar does while
     * its `trackLoading` is set.
     */
    val isStartingPlay: Boolean = false,
    /**
     * A source picked in Now Playing's switcher whose play hasn't started
     * yet. Display only: the switcher shows it at once
     * (`SourceReselection.pickerShownResolver`); it's never written onto the
     * track, and it's dropped when the play from it ends, started or not.
     */
    val pendingSourcePick: com.parachord.shared.resolver.PendingSourcePick? = null,
    /**
     * Now Playing's quality chip ("Dolby Atmos", "Lossless"): what the
     * service's ExoPlayer actually selected for a plugin DRM session (shared
     * `qualityLabel`). Null for anything else, or a lossy stereo pick.
     */
    val playingQualityLabel: String? = null,
)

/** The play buttons' spinner: the engine is buffering, or a play is still starting. */
val PlaybackState.showsLoading: Boolean
    get() = isBuffering || isStartingPlay

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
 * The "effective" track that represents what's actually playing, for DISPLAY
 * only: the mini player, Now Playing, Home's Continue Listening, the widget,
 * the notification and Android Auto's Now Playing card.
 * Overlays streaming metadata (from Spotify/Apple Music) onto the queued [TrackEntity].
 *
 * Never save it. It keeps the queued track's ids (Apple Music id, link, length)
 * under the service's reported title and artist, and when the two disagree (a
 * report left over from the previous song) a heart stored one song's ids under
 * another's names (#721). Hearts and track menus act on [PlaybackState.currentTrack],
 * as desktop's do.
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
            // It describes the track's own album, not the stream's.
            albumArtist = track.albumArtist.takeIf { meta.album == null || meta.album == track.album },
            artworkUrl = meta.artworkUrl ?: track.artworkUrl,
        )
    }

/**
 * The cover for what's playing, by the same chain as track rows: the art the
 * streaming service reported, the track's own, then the art its resolved
 * sources carry ([trackArtwork] is `TrackResolverCache.trackArtwork`). The mini
 * player, Now Playing and Home's Continue Listening all use it, so they never
 * disagree. Null shows the placeholder.
 */
fun PlaybackState.effectiveArtwork(trackArtwork: Map<String, String>): String? {
    val track = currentTrack ?: return null
    return effectiveTrack?.artworkUrl?.takeIf { it.isNotBlank() }
        ?: trackArtwork[com.parachord.android.resolver.trackKey(track.title, track.artist)]
}

/** Off, all or one; the shared enum, so the queue rules ([com.parachord.shared.playback.advanceAction]) read it directly. */
typealias RepeatMode = com.parachord.shared.playback.RepeatMode

/**
 * A playback message the app shows as a snackbar, with an optional action
 * that opens [actionUrl] (e.g. "Open in SoundCloud" for a track SoundCloud's
 * widget won't play).
 */
data class PlaybackNotice(
    val message: String,
    val actionLabel: String? = null,
    val actionUrl: String? = null,
)
