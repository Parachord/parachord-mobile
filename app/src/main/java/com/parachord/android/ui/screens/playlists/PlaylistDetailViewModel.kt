package com.parachord.android.ui.screens.playlists

import kotlinx.coroutines.Job
import com.parachord.shared.ui.UiMessages
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.dao.SyncPlaylistLinkDao
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.PlaylistTrackInfo
import com.parachord.android.resolver.toTrackInfo
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.sync.SpotifySyncProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class PlaylistDetailViewModel constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val playbackStateHolder: com.parachord.android.playback.PlaybackStateHolder,
    private val settingsStore: SettingsStore,
    private val trackResolverCache: TrackResolverCache,
    private val spotifySyncProvider: SpotifySyncProvider,
    private val imageEnrichmentService: ImageEnrichmentService,
    private val syncPlaylistLinkDao: SyncPlaylistLinkDao,
    private val syncPlaylistSourceDao: com.parachord.shared.db.dao.SyncPlaylistSourceDao,
) : ViewModel() {

    companion object {
        private const val TAG = "PlaylistDetailVM"
    }

    /** User-configured resolver priority order, used to sort resolver icons on track rows. */
    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val playlistId: String = savedStateHandle["playlistId"] ?: ""

    val playlist: StateFlow<PlaylistEntity?> = playlistDao.getByIdFlow(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** providerId -> externalId for every remote this playlist mirrors to, for
     *  the detail-page "open on <service>" chips. Reloads when the row changes. */
    val mirrors: StateFlow<Map<String, String>> = playlist
        .map { runCatching { libraryRepository.getPlaylistMirrors(playlistId) }.getOrDefault(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The provider this playlist is pulled from, for its source line; null when it isn't. */
    val pullProvider: StateFlow<String?> = playlist
        .map { runCatching { libraryRepository.getPlaylistPullProvider(playlistId) }.getOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Title of the currently playing track (for highlight). */
    val nowPlayingTitle: StateFlow<String?> = playbackStateHolder.state
        .map { it.currentTrack?.title }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tracks: StateFlow<List<PlaylistTrackEntity>> =
        libraryRepository.getPlaylistTracks(playlistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Resolver badge names for UI display — shared across all screens via TrackResolverCache. */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    /**
     * Resolves one playlist row while it's on screen (#181); see ResolveWhileVisible.
     * The row's coordinates let [TrackResolverCache] backfill resolver ids onto the
     * DB row, so later opens of a synced playlist show every badge from the first
     * render. The playlist the user just opened is the most visible list there is,
     * so it takes the priority lane.
     */
    fun resolveVisible(pt: PlaylistTrackEntity): Job =
        trackResolverCache.resolvePlaylistTracksInBackground(listOf(pt.toTrackInfo()), priority = true)

    /** True when the remote playlist (Spotify) has changes not yet pulled locally. */
    private val _hasRemoteUpdate = MutableStateFlow(false)
    val hasRemoteUpdate: StateFlow<Boolean> = _hasRemoteUpdate.asStateFlow()

    /** True while pulling remote changes. */
    private val _isPulling = MutableStateFlow(false)
    val isPulling: StateFlow<Boolean> = _isPulling.asStateFlow()

    init {
        // Generate the mosaic / enrich track art when the playlist has no
        // cover. Required for hosted XSPF imports — XSPF doesn't carry per-
        // track image data, so without this the detail screen would always
        // show the gray "QueueMusic" placeholder. Fire-and-forget; the
        // service deduplicates concurrent calls and the underlying mosaic
        // file is written once.
        viewModelScope.launch {
            playlist.collect { p ->
                if (p != null && p.artworkUrl.isNullOrBlank()) {
                    imageEnrichmentService.enrichPlaylistArt(p.id)
                }
            }
        }

        // Check if the remote playlist has been updated since last sync
        checkForRemoteUpdate()
    }

    fun checkForRemoteUpdate() {
        viewModelScope.launch {
            val pl = playlistDao.getById(playlistId) ?: return@launch
            // Hosted XSPF playlists are canonical via their `sourceUrl` — the
            // 5-minute poller is the single source of truth, and SyncEngine
            // only ever pushes them upstream, never pulls. A "pull from
            // Spotify" banner here would offer the user a destructive no-op
            // (the next poll tick would overwrite it), so the flag is
            // suppressed entirely. Mirrors desktop's pull-banner behavior
            // for hosted rows (CLAUDE.md "Hosted XSPF Playlists").
            if (pl.sourceUrl != null) return@launch
            // Only the playlist's pull source may replace its tracks: a
            // Spotify copy of a ListenBrainz or Apple Music playlist is a
            // mirror, and its changes are not the playlist's.
            if (!pullFromSpotifyOffered(pl)) return@launch
            val spotifyId = pl.spotifyId ?: return@launch
            val localSnapshot = pl.snapshotId ?: return@launch

            try {
                val remoteSnapshot = spotifySyncProvider.getPlaylistSnapshotId(spotifyId)
                if (remoteSnapshot != null && remoteSnapshot != localSnapshot) {
                    Log.d(TAG, "Remote update detected: local=$localSnapshot, remote=$remoteSnapshot")
                    _hasRemoteUpdate.value = true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to check remote playlist update: ${e.message}")
            }
        }
    }

    /** Pull the latest tracks from the remote source (Spotify). */
    fun pullRemoteChanges() {
        viewModelScope.launch {
            val pl = playlistDao.getById(playlistId) ?: return@launch
            if (!pullFromSpotifyOffered(pl)) {
                _hasRemoteUpdate.value = false
                return@launch
            }
            val spotifyId = pl.spotifyId ?: return@launch

            _isPulling.value = true
            try {
                val remoteTracks = spotifySyncProvider.fetchPlaylistTracks(spotifyId)
                val localCount = playlistTrackDao.getByPlaylistIdSync(playlistId).size
                if (!com.parachord.shared.sync.PlaylistPullBanner.acceptsPulledTracks(localCount, remoteTracks.size)) {
                    Log.w(TAG, "Pull from Spotify $spotifyId returned no tracks; keeping the $localCount here")
                    _toastEvents.trySend(com.parachord.shared.sync.PlaylistPullBanner.emptyPullMessage("Spotify"))
                    return@launch
                }
                val remoteSnapshot = spotifySyncProvider.getPlaylistSnapshotId(spotifyId)

                playlistTrackDao.replaceAll(playlistId, remoteTracks)

                val now = System.currentTimeMillis()
                // Fix 1 (multi-provider mirror propagation): if this playlist
                // has push-mirror entries on providers OTHER than Spotify
                // (e.g. Apple Music), those copies are now stale relative
                // to the just-pulled state. Flag locallyModified so the
                // next push loop catches them up. Without this, an
                // Android-edit → Spotify → desktop pull stops at the
                // desktop and never reaches Apple Music.
                val hasOtherMirrors = syncPlaylistLinkDao.hasOtherMirrors(playlistId, "spotify")
                playlistDao.update(pl.copy(
                    trackCount = remoteTracks.size,
                    snapshotId = remoteSnapshot ?: pl.snapshotId,
                    updatedAt = now,
                    lastModified = now,
                    locallyModified = hasOtherMirrors,
                ))

                _hasRemoteUpdate.value = false
                Log.d(TAG, "Pulled ${remoteTracks.size} tracks from Spotify playlist $spotifyId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull remote changes: ${e.message}", e)
            } finally {
                _isPulling.value = false
            }
        }
    }

    /** Whether this playlist's pull source is Spotify (shared [com.parachord.shared.sync.PlaylistPullBanner]). */
    private suspend fun pullFromSpotifyOffered(pl: PlaylistEntity): Boolean =
        com.parachord.shared.sync.PlaylistPullBanner.offersPull(
            sourceProviderId = syncPlaylistSourceDao.selectForLocal(pl.id)?.providerId,
            playlistId = pl.id,
            providerId = SpotifySyncProvider.PROVIDER_ID,
        )

    /** Dismiss the remote update banner without pulling. */
    fun dismissRemoteUpdate() {
        _hasRemoteUpdate.value = false
    }

    /** Play all tracks starting from the given index. */
    fun playAll(startIndex: Int = 0) {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        val entities = trackList.map { libraryRepository.playlistTrackToTrackEntity(it) }
        val context = PlaybackContext(
            type = "playlist",
            name = playlist.value?.name ?: "Playlist",
            id = playlistId,
        )
        playbackController.playQueue(entities, startIndex, context = context)
    }

    /** Play a single track from the playlist. */
    fun playTrack(index: Int) {
        playAll(startIndex = index)
    }

    /** Get all playlists for the playlist picker. */
    val allPlaylists: StateFlow<List<PlaylistEntity>> = playlistDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Context menu actions ─────────────────────────────────────────

    fun trackEntityAt(index: Int): TrackEntity? {
        val pt = tracks.value.getOrNull(index) ?: return null
        return libraryRepository.playlistTrackToTrackEntity(pt)
    }

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(targetPlaylist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTracksToPlaylist(targetPlaylist.id, listOf(track))
        }
    }

    /** How many later copies of a song the playlist holds, when the page offers the
     *  duplicates banner (shared [com.parachord.shared.playlist.PlaylistDuplicates]); else 0. */
    val duplicateCount: StateFlow<Int> = combine(playlist, tracks) { p, t ->
        val n = com.parachord.shared.playlist.PlaylistDuplicates.count(t)
        if (com.parachord.shared.playlist.PlaylistDuplicates.offersRemoval(p, n)) n else 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Remove the later copies, keeping the first of each song; says how many went. */
    fun removeDuplicates() {
        viewModelScope.launch {
            val removed = runCatching { libraryRepository.removePlaylistDuplicates(playlistId) }
                .onFailure { Log.w(TAG, "removePlaylistDuplicates failed", it) }
                .getOrNull() ?: return@launch
            if (removed > 0) {
                _toastEvents.trySend(com.parachord.shared.playlist.PlaylistDuplicateCopy.done(removed))
            }
        }
    }

    fun removeFromPlaylist(position: Int) {
        viewModelScope.launch {
            libraryRepository.removeTrackFromPlaylist(playlistId, position)
        }
    }

    fun addToCollection(track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addToCollection(track)
        }
    }

    /** Add all playlist tracks to the queue without interrupting playback. */
    fun queueAll() {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        val entities = trackList.map { libraryRepository.playlistTrackToTrackEntity(it) }
        playbackController.addToQueue(entities)
    }

    /**
     * Phase 6.5 — one-shot toast events emitted after sync-aware deletes
     * when a provider returned `Unsupported` (e.g. Apple Music's 401 on
     * DELETE). The screen collects this Flow and shows the message via
     * an Android Toast.
     */
    private val _toastEvents = Channel<String>(Channel.BUFFERED)
    val toastEvents: Flow<String> = _toastEvents.receiveAsFlow()

    /** The services this playlist is on, for the delete dialog (#684). */
    suspend fun getDeletableMirrors(playlistId: String): List<String> =
        libraryRepository.getPlaylistMirrors(playlistId).keys.toList()

    /**
     * Delete this playlist locally AND attempt to delete each remote
     * mirror. Per Decision D8, when Apple Music returns
     * `DeleteResult.Unsupported` (its public API rejects DELETE on
     * library playlists with 401), surface a toast telling the user to
     * remove the playlist manually in the Music app — the local row
     * is gone but the AM mirror is not.
     */
    fun deletePlaylist(deleteFromProviders: Set<String>) {
        viewModelScope.launch {
            val pl = playlist.value ?: return@launch
            val attempts = libraryRepository.deletePlaylistWithSync(pl, deleteFromProviders)
            val unsupported = attempts.filter {
                it.result is com.parachord.shared.sync.DeleteResult.Unsupported
            }
            if (unsupported.isNotEmpty()) {
                _toastEvents.trySend(
                    UiMessages.playlistDeletionUnsupported(unsupported.map { it.providerDisplayName }),
                )
            }
        }
    }
}
