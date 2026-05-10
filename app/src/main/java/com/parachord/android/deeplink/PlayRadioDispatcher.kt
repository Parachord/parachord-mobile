package com.parachord.android.deeplink

import com.parachord.android.playback.PlaybackController
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.deeplink.RadioMode
import com.parachord.shared.platform.Log

private const val TAG = "PlayRadioDispatcher"

/**
 * Orchestrator for `parachord://play/radio` (Phase 3, issue #121).
 *
 * Splits the two radio modes:
 *
 *  - **Mode B (artist seed)** — `?artist=<name>[&title=<hint>]`. Tears down
 *    the prior playback context, then dispatches into
 *    [PlaybackController.startSpinoffWithSeed]. Title-bearing seeds use
 *    Last.fm `track.getsimilar`; artist-only seeds fall back to
 *    `artist.getTopTracks`.
 *  - **Mode C (pool-based)** — `?url=`/`?tracks=`/`?refill=`. Wired in
 *    Task 4 (this file is left intentionally minimal until then).
 *
 * Acknowledgment toast ("Building radio…") fires before teardown so the
 * user gets immediate feedback that the deeplink was understood.
 *
 * **Teardown semantics**: Mode B clears the queue + exits any active
 * spinoff + stops listen-along, matching the Phase 2 album/playlist
 * behavior. The in-app right-click → Spinoff path does NOT call teardown
 * (it preserves the queue and returns to it on exit), but the deeplink
 * path is "start fresh radio" semantics, so teardown is correct here.
 */
class PlayRadioDispatcher(
    private val playbackController: PlaybackController,
    private val teardown: ProtocolPlayTeardown,
    private val toast: suspend (String) -> Unit,
) {
    suspend fun dispatch(action: DeepLinkAction.PlayRadio) {
        when (val mode = action.mode) {
            is RadioMode.ArtistSeed -> {
                toast("Building radio…")
                teardown.prepareForNewPlayback()
                playbackController.startSpinoffWithSeed(
                    seedArtist = mode.artist,
                    seedTitle = mode.title,
                    displayName = action.name
                        ?: mode.title?.let { "Radio: ${mode.artist} – $it" }
                        ?: "Radio: ${mode.artist}",
                    // Mode B is "start fresh radio now" semantics — kick
                    // the first pool track immediately rather than
                    // waiting for an existing song to finish (the
                    // teardown above clears the queue but doesn't stop
                    // whatever's currently playing).
                    kickStartFirstTrack = true,
                )
            }
            is RadioMode.PoolBased -> {
                // Wired in Task 4.
                Log.d(TAG, "Mode C (pool-based) not yet wired: $action")
                toast("Mode C coming next commit")
            }
        }
    }
}
