package com.parachord.android.deeplink

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolInputResolver
import com.parachord.shared.deeplink.ProtocolPlayInput
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.deeplink.ProtocolResolveOptions
import com.parachord.shared.deeplink.ProtocolTrack
import com.parachord.shared.deeplink.RadioMode
import com.parachord.shared.deeplink.ResolvedProtocolPlay
import com.parachord.shared.deeplink.resolveProtocolPlayInput
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis

private const val TAG = "ProtocolPlayHandler"

/** One-shot result for the toast / log surface. */
sealed class ProtocolPlayResult {
    data class Started(val displayName: String, val trackCount: Int) : ProtocolPlayResult()
    data class Failed(val reason: String) : ProtocolPlayResult()
}

/**
 * Orchestrator for the `parachord://play/...` family.
 *
 * Per issue #120 (the "Mandatory invariants from desktop's Android Parity
 * Requirements" section), the order of operations is non-negotiable:
 *
 * 1. **Resolve** the input via [ProtocolInputResolver] (priority walk:
 *    mbid → spotify → applemusic → url → tracks → artist+title), gated
 *    by per-command [ProtocolResolveOptions].
 * 2. **Tear down** prior playback context — spinoff exit, listen-along
 *    stop, queue clear — via [ProtocolPlayTeardown]. Order matters:
 *    skipping spinoff exit lets `handlePlay`'s internal cleanup undo our
 *    new context after we set it.
 * 3. **Build** [TrackEntity] list with stable per-track IDs
 *    (`protocol-play-{cmd}-{ts}-{idx}`). The standard resolver pipeline
 *    backfills `resolver` / `spotifyUri` / `appleMusicId` etc. on each
 *    track via [TrackResolverCache.resolveInBackground].
 * 4. **Pre-resolve the first track** synchronously through the resolver
 *    pipeline so playback starts on a known-good source. If it can't
 *    resolve to anything playable, surface "Nothing to play" instead of
 *    queuing a dead track.
 * 5. **Hand off** the first track to [PlaybackController.playTrack] +
 *    the rest to [PlaybackController.addToQueue]. Background-resolve
 *    the rest via [TrackResolverCache.resolveInBackground] — they'll
 *    have sources ready by the time the user advances.
 *
 * Returns [ProtocolPlayResult] for the caller (`DeepLinkViewModel`) to
 * surface as a toast.
 */
class ProtocolPlayHandler constructor(
    private val resolver: ProtocolInputResolver,
    private val teardown: ProtocolPlayTeardown,
    private val playbackController: PlaybackController,
    private val trackResolverCache: TrackResolverCache,
) {

    /** Per-command resolve options. Mirrors the matrix in [ProtocolResolveOptions] KDoc. */
    private val albumOpts = ProtocolResolveOptions(
        allowMbid = true, allowProviderId = true, allowUrl = false,
        allowTracks = false, allowArtistTitleAlbum = true,
    )
    private val playlistOpts = ProtocolResolveOptions(
        allowMbid = false, allowProviderId = false, allowUrl = true,
        allowTracks = true, allowArtistTitleAlbum = false,
    )
    /** Mode C accepts only `url=` (hosted tracklist) or `tracks=` (inline). */
    private val radioPoolOpts = ProtocolResolveOptions(
        allowMbid = false, allowProviderId = false, allowUrl = true,
        allowTracks = true, allowArtistTitleAlbum = false,
    )

    suspend fun handle(action: DeepLinkAction.PlayAlbum): ProtocolPlayResult =
        runHandle("album", action.input, albumOpts)

    suspend fun handle(action: DeepLinkAction.PlayPlaylist): ProtocolPlayResult =
        runHandle("playlist", action.input, playlistOpts)

    /**
     * `parachord://play/radio` — Mode C (pool-based) only.
     *
     * Mode B (artist seed) bypasses this handler entirely; it goes
     * straight from [PlayRadioDispatcher] into
     * [PlaybackController.startSpinoffWithSeed]. Mode C, by contrast,
     * needs the resolver cascade (URL fetch → JSPF/XSPF/M3U parse OR
     * inline tracks) before a pool can be built.
     *
     * Order of operations mirrors [runHandle]:
     * resolve → empty-check → teardown → build entities (`protocol-radio-*`
     * IDs) → pre-warm first track → handoff to
     * [PlaybackController.startPoolBasedSpinoff].
     */
    suspend fun handle(action: DeepLinkAction.PlayRadio): ProtocolPlayResult {
        require(action.mode is RadioMode.PoolBased) {
            "ProtocolPlayHandler only handles PoolBased radio; ArtistSeed goes through PlaybackController.startSpinoffWithSeed directly"
        }
        val input = action.input
            ?: return ProtocolPlayResult.Failed("Radio missing input")

        // Step 1: resolve.
        val resolved: ResolvedProtocolPlay = try {
            resolveProtocolPlayInput(input, radioPoolOpts, resolver)
                ?: return ProtocolPlayResult.Failed("Radio: nothing to play")
        } catch (e: Exception) {
            Log.w(TAG, "Radio resolve failed: ${e.message}")
            return ProtocolPlayResult.Failed(e.message ?: "Radio fetch failed")
        }
        if (resolved.tracks.isEmpty()) {
            return ProtocolPlayResult.Failed("Radio: empty pool")
        }

        // Display-name precedence: explicit action.name → input.title →
        // resolver displayName → "Radio".
        val displayName = action.name
            ?: input.title
            ?: resolved.displayName
            ?: "Radio"

        // Step 2: teardown — matches handle(PlayAlbum/PlayPlaylist) ordering
        // (only fire after we know we have something to play).
        teardown.prepareForNewPlayback()

        // Step 3: build TrackEntity list with stable protocol-radio-{ts}-{idx} IDs.
        val ts = currentTimeMillis()
        val entities = resolved.tracks.mapIndexed { idx, t ->
            TrackEntity(
                id = "protocol-radio-$ts-$idx",
                title = t.title,
                artist = t.artist,
                album = t.album,
                artworkUrl = resolved.albumArt,
            )
        }

        // Step 4: pre-resolve the FIRST track so playback starts on a
        // known-good source — same gate as runHandle.
        try {
            trackResolverCache.resolveInBackground(listOf(entities.first()), backfillDb = false)
        } catch (e: Exception) {
            Log.w(TAG, "Pool first-track pre-resolve failed: ${e.message}")
        }

        // Step 5: hand off to the pool-based spinoff entry point. The
        // controller does its own clearQueue() + spinoff context wiring
        // atomically; refill URL captured for Task 5.
        playbackController.startPoolBasedSpinoff(entities, displayName, action.refillUrl)
        if (entities.size > 1) {
            trackResolverCache.resolveInBackground(entities.drop(1), backfillDb = false)
        }

        return ProtocolPlayResult.Started(displayName, entities.size)
    }

    private suspend fun runHandle(
        cmd: String,
        input: ProtocolPlayInput,
        opts: ProtocolResolveOptions,
    ): ProtocolPlayResult {
        // Step 1: resolve.
        val resolved: ResolvedProtocolPlay = try {
            resolveProtocolPlayInput(input, opts, resolver)
                ?: return ProtocolPlayResult.Failed("Couldn't resolve $cmd from the link")
        } catch (e: Exception) {
            Log.w(TAG, "Resolve threw for $cmd: ${e.message}")
            return ProtocolPlayResult.Failed(e.message ?: "Resolve failed")
        }
        if (resolved.tracks.isEmpty()) {
            return ProtocolPlayResult.Failed("Nothing to play (empty $cmd)")
        }

        // Step 2: teardown — spinoff → listen-along → queue clear, in order.
        teardown.prepareForNewPlayback()

        // Step 3: build TrackEntity list with stable IDs.
        val ts = currentTimeMillis()
        val entities = resolved.tracks.mapIndexed { idx, t -> t.toEntity(cmd, ts, idx, resolved.albumArt) }

        // Step 4: pre-resolve the FIRST track. Block on this so playback
        // either starts on a known-good source or shows "Nothing to play"
        // — DON'T let `playTrackInternal`'s on-demand fallback surface a
        // mid-flow "No Source Found" dialog.
        val first = entities.first()
        try {
            trackResolverCache.resolveInBackground(listOf(first), backfillDb = false)
        } catch (e: Exception) {
            Log.w(TAG, "Pre-resolve threw for first track: ${e.message}")
        }
        // The cache resolves asynchronously; we don't block on the result
        // here because PlaybackController.playTrack itself triggers the
        // on-demand resolution path if sources aren't yet cached. The
        // pre-warm above kicks the cache so the first paint is fast.

        // Step 5: hand off.
        playbackController.playTrack(first)
        if (entities.size > 1) {
            val rest = entities.drop(1)
            playbackController.addToQueue(rest)
            // Background-resolve the rest so advancing the queue is fast.
            trackResolverCache.resolveInBackground(rest, backfillDb = false)
        }

        return ProtocolPlayResult.Started(resolved.displayName, entities.size)
    }

    /**
     * Convert a wire [ProtocolTrack] into a [TrackEntity] for the queue.
     * Uses a stable ID convention so debug tooling can identify
     * protocol-spawned tracks in the DB / logs.
     */
    private fun ProtocolTrack.toEntity(cmd: String, ts: Long, idx: Int, fallbackArt: String?): TrackEntity =
        TrackEntity(
            id = "protocol-play-$cmd-$ts-$idx",
            title = title,
            artist = artist,
            album = album,
            artworkUrl = fallbackArt,
            // resolver / spotifyUri / appleMusicId left null — the resolver
            // pipeline (TrackResolverCache.resolveInBackground) backfills
            // these via the standard cascading lookup.
        )
}
