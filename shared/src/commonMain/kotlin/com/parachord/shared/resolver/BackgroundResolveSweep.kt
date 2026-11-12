package com.parachord.shared.resolver

import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track

/**
 * One track the background sweep may resolve: a collection track or a playlist
 * track. [hasOwnSource] is true when the row already says where it plays (a
 * stored Spotify, Apple Music or SoundCloud id from sync, or a local file):
 * desktop counts a synced source as valid and never sweeps it.
 */
data class SweepItem(
    val title: String,
    val artist: String,
    val album: String?,
    val hasOwnSource: Boolean,
    /** The collection row, for a collection track. */
    val track: Track? = null,
    /** The playlist row, for a playlist track. */
    val playlistTrack: PlaylistTrack? = null,
) {
    /** Lowercased "title|artist", the resolver caches' key. */
    val key: String get() = "${title.lowercase().trim()}|${artist.lowercase().trim()}"

    companion object {
        fun of(track: Track) = SweepItem(
            title = track.title,
            artist = track.artist,
            album = track.album,
            hasOwnSource = !track.spotifyId.isNullOrBlank() || !track.appleMusicId.isNullOrBlank() ||
                !track.soundcloudId.isNullOrBlank() ||
                (track.resolver == "localfiles" && !track.sourceUrl.isNullOrBlank()),
            track = track,
        )

        fun of(track: PlaylistTrack) = SweepItem(
            title = track.trackTitle,
            artist = track.trackArtist,
            album = track.trackAlbum,
            hasOwnSource = !track.trackSpotifyId.isNullOrBlank() || !track.trackAppleMusicId.isNullOrBlank() ||
                !track.trackSoundcloudId.isNullOrBlank() ||
                (track.trackResolver == "localfiles" && !track.trackSourceUrl.isNullOrBlank()),
            playlistTrack = track,
        )
    }
}

/** What the sweep wants next. */
sealed interface SweepStep {
    /** Ask again after [ms]. */
    data class Wait(val ms: Long) : SweepStep

    /** Resolve these on the low-priority lane, then ask again once the resolver is idle. */
    data class Resolve(val items: List<SweepItem>) : SweepStep
}

/**
 * Desktop's idle background pre-resolution (`src/renderer/app.js`, the
 * `background` resolution context): while nothing else is resolving, resolve
 * the collection's tracks, then the playlists', that have no source yet, a
 * small batch at a time, so badges are there and plays start at once without
 * the person scrolling to each row first. Visible rows resolve on their own
 * ([#181]); this fills in the rest slowly.
 *
 * The rules are desktop's:
 * - nothing for [STARTUP_GRACE_MS] after launch;
 * - nothing while the person touched the screen in the last [ACTIVITY_QUIET_MS];
 * - nothing while no resolver is on (asks again after [NO_RESOLVERS_WAIT_MS]);
 * - batches of [FIRST_BATCH_SIZE] for the first [FIRST_BATCHES], then [BATCH_SIZE];
 * - only tracks with no playable source: none cached, and no id or local file
 *   on the row ([SweepItem.hasOwnSource]);
 * - with nothing to do, look again after [NOTHING_TO_DO_WAIT_MS].
 *
 * Each track is tried once per run of the app: a track still without a source
 * after its resolve isn't swept again (desktop's `resolved` set). Unlike
 * desktop, a pass that finds nothing starts over from the top next time, so a
 * track whose cached sources expired, or one added earlier in the list, is
 * found; it costs a scan and no requests.
 *
 * The platform drives it: it calls [next], waits or resolves, and after a
 * [SweepStep.Resolve] waits until its resolver cache is idle before calling
 * [next] again (desktop's scheduler calls back when its queue drains).
 */
class BackgroundResolveSweep(
    private val collection: suspend () -> List<SweepItem>,
    private val playlists: suspend () -> List<SweepItem>,
    private val startedAtMs: Long,
) {
    private var collectionCursor = 0
    private var playlistCursor = 0
    /** One read of each list per pass, not one per batch. */
    private var collectionPass: List<SweepItem>? = null
    private var playlistPass: List<SweepItem>? = null
    private var batches = 0
    private val attempted = mutableSetOf<String>()

    /**
     * The next step. [lastActivityMs] is when the person last touched the
     * screen; [anyResolverOn] whether any resolver is enabled;
     * [playableKeys] the [SweepItem.key]s the resolver cache already holds a
     * playable source for (a set rather than a callback, so iOS's main-actor
     * cache is read on its own thread).
     */
    suspend fun next(
        nowMs: Long,
        lastActivityMs: Long,
        anyResolverOn: Boolean,
        playableKeys: Set<String>,
    ): SweepStep {
        val sinceStart = nowMs - startedAtMs
        if (sinceStart < STARTUP_GRACE_MS) return SweepStep.Wait(STARTUP_GRACE_MS - sinceStart)
        if (nowMs - lastActivityMs < ACTIVITY_QUIET_MS) return SweepStep.Wait(ACTIVITY_QUIET_MS)
        if (!anyResolverOn) return SweepStep.Wait(NO_RESOLVERS_WAIT_MS)

        val size = if (batches < FIRST_BATCHES) FIRST_BATCH_SIZE else BATCH_SIZE
        val batch = mutableListOf<SweepItem>()
        val keys = mutableSetOf<String>()
        fun wants(item: SweepItem): Boolean =
            item.key !in attempted && item.key !in keys && !item.hasOwnSource && item.key !in playableKeys

        val fromCollection = collectionPass ?: collection().also { collectionPass = it }
        while (batch.size < size && collectionCursor < fromCollection.size) {
            val item = fromCollection[collectionCursor++]
            if (wants(item)) { batch += item; keys += item.key }
        }
        if (batch.size < size && collectionCursor >= fromCollection.size) {
            val fromPlaylists = playlistPass ?: playlists().also { playlistPass = it }
            while (batch.size < size && playlistCursor < fromPlaylists.size) {
                val item = fromPlaylists[playlistCursor++]
                if (wants(item)) { batch += item; keys += item.key }
            }
        }
        batches++
        if (batch.isEmpty()) {
            collectionCursor = 0
            playlistCursor = 0
            collectionPass = null
            playlistPass = null
            return SweepStep.Wait(NOTHING_TO_DO_WAIT_MS)
        }
        attempted += keys
        return SweepStep.Resolve(batch)
    }

    companion object {
        const val STARTUP_GRACE_MS = 30_000L
        const val ACTIVITY_QUIET_MS = 3_000L
        const val NO_RESOLVERS_WAIT_MS = 10_000L
        const val NOTHING_TO_DO_WAIT_MS = 60_000L
        const val FIRST_BATCHES = 3
        const val FIRST_BATCH_SIZE = 5
        const val BATCH_SIZE = 25

        /** The collection's tracks, then every playlist's, as sweep items. */
        fun collectionItems(tracks: List<Track>): List<SweepItem> = tracks.map { SweepItem.of(it) }
        fun playlistItems(tracks: List<PlaylistTrack>): List<SweepItem> = tracks.map { SweepItem.of(it) }
    }
}
