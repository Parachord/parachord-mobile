package com.parachord.android.resolver

import com.parachord.shared.platform.Log
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MbidEnrichmentService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Centralized, session-wide cache for track resolver results.
 *
 * Mirrors the desktop's ResolutionScheduler approach:
 * - Cross-context deduplication (one track resolved once, all screens benefit)
 * - Concurrent resolution (4 workers, matching desktop)
 * - Priority-based ordering (callers submit with context priority)
 * - Resolved IDs are backfilled into the database for persistence
 *
 * ViewModels observe [trackResolvers] for UI display and call [resolveInBackground]
 * to submit tracks for resolution.
 */
class TrackResolverCache constructor(
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
    private val mbidEnrichment: MbidEnrichmentService,
    private val playlistTrackDao: com.parachord.android.data.db.dao.PlaylistTrackDao,
) {
    companion object {
        private const val TAG = "TrackResolverCache"
        /**
         * Permits for the priority lane — used by foreground screens
         * (the playlist the user just opened, the now-playing queue).
         * Matches desktop's 4 workers so a freshly-opened playlist gets
         * the same concurrency it would on desktop.
         */
        private const val MAX_PRIORITY_CONCURRENCY = 4
        /**
         * Permits for the background lane — Home recents, Library
         * sweeps, anything kicked off when the user is NOT looking at
         * the result. Lower so a 200-track library refresh can't starve
         * a freshly-opened playlist of bandwidth.
         */
        private const val MAX_BACKGROUND_CONCURRENCY = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Two semaphores instead of one shared `Semaphore(4)`. A single FIFO
     * lane meant that when a user opened a playlist after Home/Library
     * had already submitted ~200 tracks for resolution, those 200 sat
     * ahead of the playlist's tracks in the queue — visible badges took
     * 30+ seconds to appear, even though MusicBrainz/Last.fm/Spotify
     * could have answered almost immediately.
     *
     * The priority lane is reserved for whatever the user is currently
     * looking at; the background lane handles the rest. Total throughput
     * goes up from 4 → 6 concurrent, and the foreground always has its
     * own dedicated 4 permits regardless of background queue depth.
     */
    private val prioritySemaphore = Semaphore(MAX_PRIORITY_CONCURRENCY)
    private val backgroundSemaphore = Semaphore(MAX_BACKGROUND_CONCURRENCY)

    /** All cached resolved sources, keyed by "title|artist" */
    private val _trackSources = MutableStateFlow<Map<String, List<ResolvedSource>>>(emptyMap())
    val trackSources: StateFlow<Map<String, List<ResolvedSource>>> = _trackSources.asStateFlow()

    /**
     * Resolver badge names for UI display, sorted by user-configured priority order.
     * This ensures the highest-priority resolver (the one that will actually play)
     * appears first in the icon row, matching user expectations.
     */
    val trackResolvers: StateFlow<Map<String, List<String>>> =
        combine(
            _trackSources,
            settingsStore.getResolverOrderFlow(),
        ) { sources, resolverOrder ->
            sources.mapValues { (_, v) ->
                // Filter out noMatch sources (below confidence threshold) from UI display,
                // matching desktop's noMatch sentinel filtering.
                val aboveThreshold = v.filter {
                    (it.confidence ?: 0.0) >= ResolverScoring.MIN_CONFIDENCE_THRESHOLD
                }
                val resolvers = aboveThreshold.map { it.resolver }.distinct()
                if (resolverOrder.isEmpty()) resolvers
                else resolvers.sortedBy { r ->
                    val idx = resolverOrder.indexOf(r)
                    if (idx == -1) resolverOrder.size else idx
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Confidence scores for UI display, keyed by "title|artist" → resolver → confidence.
     * Matches the desktop's approach: confidence <= 0.8 → dimmed (opacity 0.6).
     */
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> =
        _trackSources.map { sources ->
            sources.mapValues { (_, v) ->
                v.associate { it.resolver to (it.confidence?.toFloat() ?: 1f) }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Best resolved album-art URL per track key ("title|artist") — the
     * highest-priority above-floor source that actually carries artwork. Lets
     * ephemeral, art-less track lists (artist Top Tracks, the play queue, AI
     * recs, etc.) show real album art once they resolve, even when the metadata
     * source had none (#188 / #190). The resolver that plays is the one whose
     * art we show, so it always matches.
     */
    val trackArtwork: StateFlow<Map<String, String>> =
        combine(
            _trackSources,
            settingsStore.getResolverOrderFlow(),
        ) { sources, resolverOrder ->
            sources.mapNotNull { (key, v) ->
                val aboveThreshold = v.filter {
                    (it.confidence ?: 0.0) >= ResolverScoring.MIN_CONFIDENCE_THRESHOLD
                }
                val ordered = if (resolverOrder.isEmpty()) {
                    aboveThreshold
                } else {
                    aboveThreshold.sortedBy { s ->
                        val idx = resolverOrder.indexOf(s.resolver)
                        if (idx == -1) resolverOrder.size else idx
                    }
                }
                val art = ordered.firstNotNullOfOrNull { it.artworkUrl?.takeIf { u -> u.isNotBlank() } }
                if (art != null) key to art else null
            }.toMap()
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Track keys currently being resolved (prevents duplicate in-flight requests). */
    private val inFlight = mutableSetOf<String>()

    /**
     * Observable view of [inFlight] — track keys with a resolution in progress.
     * Drives the row art skeleton (e.g. after a metadata edit, the new title/artist
     * key shows a shimmer until its resolve lands and art backfills onto the row).
     */
    private val _inFlightKeys = MutableStateFlow<Set<String>>(emptySet())
    val inFlightKeys: StateFlow<Set<String>> = _inFlightKeys.asStateFlow()
    private fun publishInFlight() {
        _inFlightKeys.value = synchronized(inFlight) { inFlight.toSet() }
    }

    /**
     * Submit tracks for background resolution with concurrent workers.
     *
     * Matches the desktop's context-priority pattern:
     * - Queue tracks (priority 1) should be submitted separately
     * - Page tracks (priority 4) are the normal case
     * - Already-resolved tracks are skipped (cross-context dedup)
     *
     * @param tracks TrackEntities to resolve
     * @param backfillDb If true, persist newly-discovered resolver IDs back to the DB
     */
    /**
     * Submit tracks for resolution.
     *
     * Defaults to [priority] = true: the overwhelming majority of
     * callers are ViewModels resolving the track list the user is
     * currently looking at — desktop matches this pattern with its
     * "page priority 4 > queue priority 1" ordering. The few callers
     * that pre-resolve tracks the user can't see yet (e.g.
     * [com.parachord.android.playback.PlaybackController] warming up
     * upcoming queue items, sync sweeps) opt into the lower-priority
     * lane by explicitly passing `priority = false`.
     */
    fun resolveInBackground(
        tracks: List<TrackEntity>,
        backfillDb: Boolean = true,
        priority: Boolean = true,
    ): Job = scope.launch {
        // Fire MBID mapper lookups in parallel with resolver resolution
        enrichTracksWithMbids(tracks)

        // Pick the lane based on whether the caller marks this batch as
        // user-visible. Priority lane gets dedicated permits so a 200-
        // track library sweep can't push a freshly-opened playlist to
        // the back of the queue.
        val sem = if (priority) prioritySemaphore else backgroundSemaphore

        for (track in tracks) {
            val key = trackKey(track.title, track.artist)
            // Skip if already resolved (cross-context dedup)
            if (_trackSources.value.containsKey(key)) continue
            // Skip if already in-flight from another context
            val shouldResolve = synchronized(inFlight) { inFlight.add(key) }
            if (!shouldResolve) continue
            publishInFlight()

            // Launch concurrent resolution (bounded by chosen semaphore)
            launch {
                try {
                    sem.withPermit {
                        val sources = resolverManager.resolveWithHints(
                            query = "${track.title} ${track.artist}",
                            spotifyId = track.spotifyId,
                            appleMusicId = track.appleMusicId,
                            soundcloudId = track.soundcloudId,
                            targetTitle = track.title,
                            targetArtist = track.artist,
                        )
                        if (sources.isNotEmpty()) {
                            _trackSources.update { it + (key to sources) }
                            if (backfillDb) {
                                backfillResolverIds(track, sources)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve '${track.title}': ${e.message}")
                } finally {
                    synchronized(inFlight) { inFlight.remove(key) }
                    publishInFlight()
                }
            }
        }
    }

    /**
     * Submit playlist tracks (PlaylistTrackEntity doesn't extend TrackEntity,
     * so we accept the raw fields).
     */
    /**
     * Same as [resolveInBackground] but accepts the lighter
     * [PlaylistTrackInfo] (no full TrackEntity row available for
     * playlist track rows). Defaults to [priority] = true for the
     * same reason — playlist detail screens always want their tracks
     * resolved fast, ahead of background sweeps.
     */
    fun resolvePlaylistTracksInBackground(
        tracks: List<PlaylistTrackInfo>,
        priority: Boolean = true,
    ): Job = scope.launch {
        val sem = if (priority) prioritySemaphore else backgroundSemaphore
        for (track in tracks) {
            val key = trackKey(track.title, track.artist)
            if (_trackSources.value.containsKey(key)) continue
            val shouldResolve = synchronized(inFlight) { inFlight.add(key) }
            if (!shouldResolve) continue
            publishInFlight()

            launch {
                try {
                    sem.withPermit {
                        val sources = resolverManager.resolveWithHints(
                            query = "${track.title} ${track.artist}",
                            spotifyId = track.spotifyId,
                            appleMusicId = track.appleMusicId,
                            soundcloudId = track.soundcloudId,
                            targetTitle = track.title,
                            targetArtist = track.artist,
                        )
                        if (sources.isNotEmpty()) {
                            _trackSources.update { it + (key to sources) }
                            backfillPlaylistTrackResolverIds(track, sources)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve '${track.title}': ${e.message}")
                } finally {
                    synchronized(inFlight) { inFlight.remove(key) }
                    publishInFlight()
                }
            }
        }
    }

    /**
     * Persist resolver IDs from the in-memory cache back onto the
     * `playlist_tracks` row so a fresh app start (or process kill) doesn't
     * have to wait for the resolver pipeline to repopulate the badges. The
     * SQL uses COALESCE so source-provided IDs (e.g. Spotify's canonical
     * sync ID) are never overwritten — only currently-NULL slots are filled.
     *
     * No-ops if [PlaylistTrackInfo.playlistId] / [PlaylistTrackInfo.position]
     * weren't supplied (ephemeral playlists like ListenBrainz weekly that
     * don't have a stable row in the DB).
     */
    private suspend fun backfillPlaylistTrackResolverIds(
        track: PlaylistTrackInfo,
        sources: List<ResolvedSource>,
    ) {
        val playlistId = track.playlistId ?: return
        val position = track.position ?: return
        // Wrong-song results (0.50 confidence) must not pollute the row;
        // mirror the threshold filter from the TrackEntity backfill path.
        val valid = sources.filter {
            (it.confidence ?: 0.0) >= ResolverScoring.MIN_CONFIDENCE_THRESHOLD
        }
        val spotifyId = valid.firstOrNull { it.resolver == "spotify" }?.spotifyId
        val spotifyUri = valid.firstOrNull { it.resolver == "spotify" }?.spotifyUri
        val appleMusicId = valid.firstOrNull { it.resolver == "applemusic" }?.appleMusicId
        val soundcloudId = valid.firstOrNull { it.resolver == "soundcloud" }?.soundcloudId
        if (spotifyId != null || spotifyUri != null || appleMusicId != null || soundcloudId != null) {
            try {
                playlistTrackDao.backfillResolverIds(
                    playlistId = playlistId,
                    position = position,
                    spotifyId = spotifyId,
                    spotifyUri = spotifyUri,
                    appleMusicId = appleMusicId,
                    soundcloudId = soundcloudId,
                    recordingMbid = null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backfill playlist_track resolver IDs for '${track.title}': ${e.message}")
            }
        }

        // Persist artwork from the highest-priority resolver that surfaced
        // one — respecting the user's configured resolver order via
        // [ResolverScoring.selectBest], NOT a hardcoded provider order.
        // Hosted-XSPF tracks come in with `trackArtworkUrl = null` (XSPF
        // has no per-track image data), so the resolver pipeline's first
        // successful result populates the row. `enrichPlaylistArt` Pass 1/2
        // then has nothing to do for these — the resolver already gave us
        // what we needed without an extra MB → Last.fm → Spotify metadata
        // cascade per track.
        val artworkSources = valid.filter { it.artworkUrl != null }
        val bestArtworkSource = if (artworkSources.isNotEmpty()) {
            resolverScoring.selectBest(artworkSources)
        } else null
        val artwork = bestArtworkSource?.artworkUrl
        if (artwork != null) {
            try {
                playlistTrackDao.updateTrackArtwork(playlistId, position, artwork)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backfill playlist_track artwork for '${track.title}': ${e.message}")
            }
        }
    }

    /** Store pre-resolved sources into the shared cache (e.g. from ViewModel-local resolution). */
    fun putSources(title: String, artist: String, sources: List<ResolvedSource>) {
        if (sources.isEmpty()) return
        val key = trackKey(title, artist)
        _trackSources.update { it + (key to sources) }
    }

    /** Get cached resolvers for a track, or null if not yet resolved. */
    fun getResolvers(title: String, artist: String): List<String>? {
        val key = trackKey(title, artist)
        return trackResolvers.value[key]
    }

    /** Get cached sources for a track, or null if not yet resolved. */
    fun getSources(title: String, artist: String): List<ResolvedSource>? {
        val key = trackKey(title, artist)
        return _trackSources.value[key]?.ifEmpty { null }
    }

    /** Fire MBID mapper lookups for tracks that don't yet have MBIDs. */
    private fun enrichTracksWithMbids(tracks: List<TrackEntity>) {
        val needsEnrichment = tracks.filter { it.recordingMbid == null }
        if (needsEnrichment.isEmpty()) return
        mbidEnrichment.enrichBatchInBackground(
            needsEnrichment.map {
                com.parachord.android.data.metadata.TrackEnrichmentRequest(it.id, it.artist, it.title)
            }
        )
    }

    /** Persist any newly-discovered resolver IDs back to the database. */
    private suspend fun backfillResolverIds(track: TrackEntity, sources: List<ResolvedSource>) {
        // Only backfill IDs from sources above the confidence threshold.
        // Wrong-song results (0.50 confidence) must not pollute the DB,
        // otherwise availableResolvers() will show badges for wrong matches.
        val validSources = sources.filter {
            (it.confidence ?: 0.0) >= ResolverScoring.MIN_CONFIDENCE_THRESHOLD
        }
        val spotifyId = validSources.firstOrNull { it.resolver == "spotify" }?.spotifyId
        val spotifyUri = validSources.firstOrNull { it.resolver == "spotify" }?.spotifyUri
        val appleMusicId = validSources.firstOrNull { it.resolver == "applemusic" }?.appleMusicId
        val soundcloudId = validSources.firstOrNull { it.resolver == "soundcloud" }?.soundcloudId

        // Pick the artwork URL from the highest-priority resolver source that
        // surfaced one — same selection logic as the playlist-track backfill.
        // [ResolverScoring.selectBest] honors the user's configured resolver
        // priority order rather than hardcoding Spotify/AM. Library tracks
        // (e.g. local-only files surfaced through Last.fm/MB metadata) get
        // the same benefit as playlist tracks: the resolver pipeline's first
        // successful artwork hit lands on `tracks.artworkUrl` so Coil has
        // something to render without forcing the metadata cascade to fire
        // every time the row is shown.
        val artworkSources = validSources.filter { it.artworkUrl != null }
        val bestArtworkSource = if (artworkSources.isNotEmpty()) {
            resolverScoring.selectBest(artworkSources)
        } else null
        val artworkUrl = bestArtworkSource?.artworkUrl

        // Only call DB if there's something new to fill in. The COALESCE
        // queries on the DB side are no-ops when the column is already set,
        // but skipping the call entirely saves a transaction.
        val needsIdBackfill = (spotifyId != null && track.spotifyId.isNullOrBlank()) ||
            (spotifyUri != null && track.spotifyUri.isNullOrBlank()) ||
            (appleMusicId != null && track.appleMusicId.isNullOrBlank()) ||
            (soundcloudId != null && track.soundcloudId.isNullOrBlank())
        val needsArtworkBackfill = artworkUrl != null && track.artworkUrl.isNullOrBlank()
        if (needsIdBackfill || needsArtworkBackfill) {
            try {
                libraryRepository.backfillTrackResolverIds(
                    trackId = track.id,
                    spotifyId = spotifyId,
                    spotifyUri = spotifyUri,
                    appleMusicId = appleMusicId,
                    soundcloudId = soundcloudId,
                    artworkUrl = artworkUrl,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backfill resolver IDs for '${track.title}': ${e.message}")
            }
        }
    }
}

/**
 * Lightweight data holder for playlist track resolution (avoids coupling to
 * entity). [playlistId] + [position] identify the row so freshly-discovered
 * resolver IDs can be backfilled — leave them null on call sites where you
 * don't want the backfill (e.g. ephemeral / weekly playlists).
 */
data class PlaylistTrackInfo(
    val title: String,
    val artist: String,
    val spotifyId: String? = null,
    val appleMusicId: String? = null,
    val soundcloudId: String? = null,
    val playlistId: String? = null,
    val position: Int? = null,
)

/** Canonical key format for track resolver lookups. */
fun trackKey(title: String, artist: String): String =
    "${title.lowercase().trim()}|${artist.lowercase().trim()}"
