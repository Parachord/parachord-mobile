package com.parachord.android.playback.handlers

import com.parachord.shared.platform.Log
import com.parachord.android.data.db.entity.TrackEntity
import kotlinx.coroutines.delay

/**
 * Handles Apple Music playback via MusicKit JS inside a hidden WebView.
 *
 * Like Spotify, Apple Music is an external playback handler — DRM-protected
 * streaming is managed by MusicKit JS, not ExoPlayer. The [MusicKitWebBridge]
 * handles all communication with the WebView.
 */
class AppleMusicPlaybackHandler constructor(
    val musicKitBridge: MusicKitWebBridge,
) : SourceHandler, ExternalPlaybackHandler {

    companion object {
        private const val TAG = "AppleMusicHandler"
    }

    /**
     * Eagerly initialize and configure the MusicKit bridge so the first
     * [play] call doesn't pay the WebView + JS library + token setup cost.
     * Best-effort — failures are silent since play() will retry anyway.
     */
    suspend fun warmUp() {
        try {
            if (!musicKitBridge.configured.value) {
                musicKitBridge.configure()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MusicKit warm-up failed (non-fatal)", e)
        }
    }

    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == "applemusic" && track.appleMusicId != null

    override suspend fun createMediaItem(track: TrackEntity) = null // External playback

    /** Timestamp when play() was called — used for grace period in isOurTrackDone(). */
    private var playStartedAt: Long = 0L

    override suspend fun play(track: TrackEntity) {
        val songId = track.appleMusicId ?: return
        playStartedAt = System.currentTimeMillis()
        // Ensure configured — configure() also restores saved MUT if available
        if (!musicKitBridge.configured.value) {
            if (!musicKitBridge.configure()) {
                Log.w(TAG, "MusicKit configuration failed")
                return
            }
        }
        // If not authorized after configure (no saved MUT or it expired), prompt login
        if (!musicKitBridge.authorized.value) {
            Log.d(TAG, "Not authorized, prompting sign-in")
            if (!musicKitBridge.authorize()) {
                Log.w(TAG, "MusicKit authorization required — prompting user")
                musicKitBridge.emitSignInRequired()
                return
            }
        }
        var success = musicKitBridge.play(songId)
        if (!success && musicKitBridge.lastPlayTimedOut) {
            // A hang (typically the network dropping mid-start), not a DRM
            // handshake or an expired token: retrying or prompting sign-in
            // can't help. MusicKit was reset; the next play starts fresh.
            throw PlaybackStartTimeoutException("Apple Music didn't start playing")
        }
        if (!success) {
            // Widevine DRM license negotiation can take a moment after auth.
            // Retry a few times with a short delay before falling back to re-auth.
            Log.w(TAG, "MusicKit playback failed, retrying after delay (DRM handshake may be in progress)")
            for (attempt in 1..3) {
                delay(1000L * attempt)
                success = musicKitBridge.play(songId)
                if (success) {
                    Log.d(TAG, "Play succeeded on retry attempt $attempt")
                    break
                }
            }
            if (!success) {
                // Retries exhausted — likely an expired user token, re-authorize
                Log.w(TAG, "MusicKit playback still failing, attempting re-authorization")
                if (musicKitBridge.authorize()) {
                    musicKitBridge.play(songId)
                } else {
                    musicKitBridge.emitSignInRequired()
                }
            }
        }
    }

    override suspend fun pause() { musicKitBridge.pause() }
    override suspend fun resume() { musicKitBridge.resume() }
    override suspend fun seekTo(positionMs: Long) { musicKitBridge.seekTo(positionMs) }
    override suspend fun stop() { musicKitBridge.stop() }

    override val isConnected: Boolean
        get() = musicKitBridge.configured.value

    fun getPosition(): Long = musicKitBridge.getPosition()
    fun getDuration(): Long = musicKitBridge.getDuration()
    fun isPlaying(): Boolean = musicKitBridge.getIsPlaying()

    /**
     * Detect whether the Apple Music track has finished playing.
     * Simpler than Spotify's detection since MusicKit JS reports state directly.
     */
    fun isOurTrackDone(): Boolean {
        // Grace period: don't trigger auto-advance within 5s of starting playback.
        // MusicKit can report transient !isPlaying states during initial buffering,
        // DRM negotiation, or WebView setup — same pattern as Spotify's grace period.
        val elapsed = System.currentTimeMillis() - playStartedAt
        if (elapsed < 5000) return false

        val playing = isPlaying()
        val state = musicKitBridge.playbackStateName

        // MusicKit transitions to "ended"/"completed" when a track finishes.
        // Position may reset to 0, so we can't rely solely on position math.
        if (!playing && (state == "ended" || state == "completed")) {
            Log.d(TAG, "isOurTrackDone=true: state=$state, playing=$playing, elapsed=${elapsed}ms")
            return true
        }

        val duration = getDuration()
        val position = getPosition()
        if (duration <= 0) return false
        val nearEnd = !playing && position > 0 && duration - position < 1500
        if (nearEnd) {
            Log.d(TAG, "isOurTrackDone=true: near end, pos=$position dur=$duration playing=$playing state=$state")
        }
        return nearEnd
    }
}
