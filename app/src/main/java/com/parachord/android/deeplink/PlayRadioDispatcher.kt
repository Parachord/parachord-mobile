package com.parachord.android.deeplink

import com.parachord.android.playback.PlaybackController
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.deeplink.RadioMode
import com.parachord.shared.playback.resolveSpinoffSeed
import kotlinx.coroutines.CancellationException

/** One-shot result for the toast / log surface. Mirrors [ProtocolPlayResult]. */
sealed class PlayRadioResult {
    /** Mode B (artist seed, with or without a title) started. */
    data class StartedModeB(val displayName: String) : PlayRadioResult()
    /** Mode C (pool-based) started — N tracks in pool. */
    data class StartedModeC(val displayName: String, val trackCount: Int) : PlayRadioResult()
    /** Mode B artist-only and the artist has no track to seed from. Queue untouched. */
    data class NoSeed(val artist: String) : PlayRadioResult()
    data class Failed(val reason: String) : PlayRadioResult()
}

/**
 * Orchestrator for `parachord://play/radio` (Phase 3, issue #121).
 *
 *  - **Mode B (artist seed)** — `?artist=<name>` with or without `&title=<hint>`.
 *    Per-track spinoff via Last.fm `track.getsimilar`, the same backend as the
 *    in-app right-click → Spinoff. With no title, the seed is the artist's top
 *    Last.fm track (#397, desktop parachord#1005).
 *  - **Mode C (pool-based)** — `?url=`/`?tracks=`/`?refill=`. Delegates to
 *    [ProtocolPlayHandler] for resolve + teardown + entity build, then
 *    [PlaybackController.startPoolBasedSpinoff] for the pool kick-off.
 *
 * **Never clear the queue for a radio that won't start.** Both modes resolve
 * EVERYTHING they need before [ProtocolPlayTeardown.prepareForNewPlayback]: Mode C
 * via the handler (resolve → empty-check → teardown), Mode B by resolving the seed
 * AND building the whole pool first. Desktop only guarantees the seed before
 * clearing, so a similar-tracks fetch that comes back empty still costs its users
 * their queue; Android builds the pool first, matching iOS and Mode C.
 *
 * Artist-only links used to synthesize a ListenBrainz `lb-radio` URL. That
 * endpoint now returns 401 without a ListenBrainz token (MetaBrainz anti-scraper
 * policy), so they played nothing for most users; the explicit Mode C form
 * (`?url=https://api.listenbrainz.org/1/explore/lb-radio?...`) still works.
 *
 * **Teardown semantics**: a started radio clears the queue + exits any active
 * spinoff + stops listen-along ("start fresh radio"). The in-app spinoff does
 * NOT tear down — it preserves the queue and returns to it on exit.
 */
class PlayRadioDispatcher(
    private val playbackController: PlaybackController,
    private val teardown: ProtocolPlayTeardown,
    private val protocolPlayHandler: ProtocolPlayHandler,
) {
    suspend fun dispatch(action: DeepLinkAction.PlayRadio): PlayRadioResult =
        when (val mode = action.mode) {
            is RadioMode.ArtistSeed -> dispatchArtistSeed(action, mode)
            is RadioMode.PoolBased -> dispatchPoolBased(action)
        }

    /**
     * Mode B. Resolve the seed, then build the whole pool, and only then tear the
     * queue down. Any failure before that point returns WITHOUT touching it.
     */
    private suspend fun dispatchArtistSeed(
        action: DeepLinkAction.PlayRadio,
        mode: RadioMode.ArtistSeed,
    ): PlayRadioResult {
        playbackController.setPlaybarLoading(true)
        val (seed, pool) = try {
            val seed = resolveSpinoffSeed(mode.artist, mode.title, playbackController::artistTopTrackTitle)
                ?: return PlayRadioResult.NoSeed(mode.artist)
            val pool = playbackController.buildSpinoffPool(seed.artist, seed.title)
            if (pool.isEmpty()) return PlayRadioResult.Failed("No similar tracks found for \"${seed.title}\"")
            seed to pool
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PlayRadioResult.Failed("Couldn't fetch similar tracks")
        } finally {
            playbackController.setPlaybarLoading(false)
        }

        teardown.prepareForNewPlayback()
        val displayName = action.name
            ?: if (!mode.title.isNullOrBlank()) "Radio: ${mode.artist} – ${mode.title}"
            else "Radio: ${mode.artist}"
        playbackController.startSpinoffFromPool(
            pool = pool,
            displayName = displayName,
            seedArtist = seed.artist,
            seedTitle = seed.title,
            // "Start fresh radio now": play the first pool track immediately
            // rather than waiting for whatever was on to finish.
            kickStartFirstTrack = true,
        )
        return PlayRadioResult.StartedModeB(displayName)
    }

    /**
     * Mode C pool path.
     *
     * Wires the refill fetcher BEFORE the handler runs so
     * `startPoolBasedSpinoff()` — invoked synchronously inside `handle()` —
     * sees a configured fetcher when it sets the refillUrl. The fetcher
     * is a closure over the handler's `resolveTrackList(url)`, so refills
     * go through the same SSRF guard / JSPF parser / LB-token auto-attach
     * as the initial pool fetch.
     *
     * Shows the mini-player spinner during the URL fetch + parse + resolve
     * cascade. try/finally guarantees the flag clears on failure paths.
     */
    private suspend fun dispatchPoolBased(action: DeepLinkAction.PlayRadio): PlayRadioResult {
        playbackController.setPoolFetcher { url ->
            protocolPlayHandler.resolveTrackList(url)
        }
        playbackController.setPlaybarLoading(true)
        return try {
            when (val r = protocolPlayHandler.handle(action)) {
                is ProtocolPlayResult.Started ->
                    PlayRadioResult.StartedModeC(r.displayName, r.trackCount)
                is ProtocolPlayResult.Failed ->
                    PlayRadioResult.Failed(r.reason)
            }
        } finally {
            playbackController.setPlaybarLoading(false)
        }
    }
}
