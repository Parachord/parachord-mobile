package com.parachord.android.deeplink

import com.parachord.android.playback.PlaybackController
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.deeplink.RadioMode

/** One-shot result for the toast / log surface. Mirrors [ProtocolPlayResult]. */
sealed class PlayRadioResult {
    /** Mode B successfully kicked off (Last.fm fetch starts in background). */
    data class StartedModeB(val displayName: String) : PlayRadioResult()
    /** Mode C successfully kicked off — N tracks in pool. */
    data class StartedModeC(val displayName: String, val trackCount: Int) : PlayRadioResult()
    data class Failed(val reason: String) : PlayRadioResult()
}

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
 *  - **Mode C (pool-based)** — `?url=`/`?tracks=`/`?refill=`. Delegates
 *    to [ProtocolPlayHandler] for resolve + teardown + entity build, then
 *    [PlaybackController.startPoolBasedSpinoff] for the pool kick-off.
 *    The acknowledgment toast ("Building radio…") is the VM's
 *    responsibility — emitted before [dispatch] runs since Mode C URL
 *    fetch can take seconds and the user needs feedback.
 *
 * **Teardown semantics**: both modes clear the queue + exit any active
 * spinoff + stop listen-along, matching the Phase 2 album/playlist
 * behavior. The in-app right-click → Spinoff path does NOT call teardown
 * (it preserves the queue and returns to it on exit), but the deeplink
 * path is "start fresh radio" semantics, so teardown is correct here.
 */
class PlayRadioDispatcher(
    private val playbackController: PlaybackController,
    private val teardown: ProtocolPlayTeardown,
    private val protocolPlayHandler: ProtocolPlayHandler,
) {
    suspend fun dispatch(action: DeepLinkAction.PlayRadio): PlayRadioResult {
        return when (val mode = action.mode) {
            is RadioMode.ArtistSeed -> {
                teardown.prepareForNewPlayback()
                val displayName = action.name
                    ?: mode.title?.let { "Radio: ${mode.artist} – $it" }
                    ?: "Radio: ${mode.artist}"
                playbackController.startSpinoffWithSeed(
                    seedArtist = mode.artist,
                    seedTitle = mode.title,
                    displayName = displayName,
                    // Mode B is "start fresh radio now" semantics — kick
                    // the first pool track immediately rather than
                    // waiting for an existing song to finish (the
                    // teardown above clears the queue but doesn't stop
                    // whatever's currently playing).
                    kickStartFirstTrack = true,
                )
                PlayRadioResult.StartedModeB(displayName)
            }
            is RadioMode.PoolBased -> {
                // Mode C delegates resolve + teardown + entity build to
                // ProtocolPlayHandler. Translate its result into our
                // result type so the VM only has one sealed match site.
                when (val r = protocolPlayHandler.handle(action)) {
                    is ProtocolPlayResult.Started ->
                        PlayRadioResult.StartedModeC(r.displayName, r.trackCount)
                    is ProtocolPlayResult.Failed ->
                        PlayRadioResult.Failed(r.reason)
                }
            }
        }
    }
}
