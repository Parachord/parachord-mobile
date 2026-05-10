package com.parachord.android.deeplink

import com.parachord.android.playback.PlaybackController
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.platform.Log
import kotlin.concurrent.Volatile

private const val TAG = "ProtocolPlayTeardown"

/**
 * Concrete [ProtocolPlayTeardown] for Android.
 *
 * Wraps the platform-specific cleanup steps in the documented order
 * (spinoff → listen-along → queue clear). Lives as a Koin singleton.
 *
 * **Listen-along is a [MainViewModel] concern**, not a singleton — its
 * state (active friend, polling job, wake-lock) is heavily VM-bound. We
 * keep that boundary by injecting a stopper callback at activity-start
 * time via [setListenAlongStopper] from `MainActivity` (or `MainViewModel.init`).
 * Until that hook is wired, the listen-along step is a no-op (logged).
 *
 * Spinoff and queue clear go through the existing [PlaybackController]
 * surface, which is already a singleton.
 */
class AndroidProtocolPlayTeardown constructor(
    private val playbackController: PlaybackController,
) : ProtocolPlayTeardown {

    /**
     * Stopper for listen-along. Set by `MainActivity` after `MainViewModel`
     * is constructed (composition-time). Defaults to a no-op so a deeplink
     * arriving before composition doesn't NPE — the listen-along ticker
     * isn't running yet either, so there's nothing to stop.
     */
    @Volatile
    private var listenAlongStopper: () -> Unit = {}

    /**
     * Wire the listen-along stopper from `MainActivity` (or `MainViewModel.init`).
     * Call once after `MainViewModel` is constructed; subsequent calls
     * replace the previous stopper.
     */
    fun setListenAlongStopper(stop: () -> Unit) {
        listenAlongStopper = stop
    }

    override suspend fun prepareForNewPlayback() {
        // 1. Exit spinoff — must run BEFORE queue clear / new context. If
        //    we let `handlePlay`'s internal exit run, it executes AFTER the
        //    new context is set and undoes our work.
        try {
            playbackController.exitSpinoff()
        } catch (e: Exception) {
            Log.w(TAG, "exitSpinoff failed: ${e.message}")
        }

        // 2. Stop listen-along — without this, the next ticker poll
        //    reverts the new track to whatever the followed user is on.
        try {
            listenAlongStopper()
        } catch (e: Exception) {
            Log.w(TAG, "stopListenAlong failed: ${e.message}")
        }

        // 3. Clear the queue — wipes the prior context's tracks so new
        //    ones replace rather than append. PlaybackController's
        //    clearQueue delegates to QueueManager + flushes the
        //    MediaSession state.
        try {
            playbackController.clearQueue()
        } catch (e: Exception) {
            Log.w(TAG, "clearQueue failed: ${e.message}")
        }
    }

    override suspend fun prepareForListenAlongHandover() {
        // Step 1 — exit spinoff. Same rationale as [prepareForNewPlayback]:
        // a leftover spinoff parent would shape the new friend's playback.
        try {
            playbackController.exitSpinoff()
        } catch (e: Exception) {
            Log.w(TAG, "exitSpinoff failed: ${e.message}")
        }

        // Step 2 (stop listen-along) is INTENTIONALLY OMITTED — see
        // [ProtocolPlayTeardown.prepareForListenAlongHandover] KDoc.
        // MainViewModel.startListenAlong handles its own swap.

        // Step 3 — clear the queue. Without this, the previous context's
        // tracks remain queued behind the listen-along ticker's first
        // play, then surface as soon as the user is no longer in sync
        // with the friend. Mirrors the desktop's listen-along entry
        // path which also wipes the queue.
        try {
            playbackController.clearQueue()
        } catch (e: Exception) {
            Log.w(TAG, "clearQueue failed: ${e.message}")
        }
    }
}
