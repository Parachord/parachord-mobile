package com.parachord.shared.repository

import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.SyncPlaylistLinkDao
import com.parachord.shared.db.dao.SyncPlaylistSourceDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.platform.randomUUID
import com.parachord.shared.sync.SyncEngine
import com.parachord.shared.sync.TrackTombstoneService
import kotlinx.coroutines.flow.Flow

/**
 * Library repository — Room/SQLDelight CRUD for tracks, albums, artists,
 * playlists + sync-aware deletion + MBID enrichment hooks on insert.
 *
 * KMP migration notes:
 *  - `MbidEnrichmentService` is Android-only (its disk cache uses `Context+File`),
 *    so it's forwarded via two non-suspend fire-and-forget lambdas
 *    (`mbidEnrichTrack`, `mbidEnrichBatch`) wired in `AndroidModule`.
 *  - `java.util.UUID.randomUUID().toString()` → shared `randomUUID()`.
 *  - `System.currentTimeMillis()` → shared `currentTimeMillis()`.
 *  - All DAOs and entity types are already in `shared/`; the app-side
 *    Library Repository becomes a thin typealias shim.
 */
class LibraryRepository(
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val syncEngine: SyncEngine,
    private val syncPlaylistLinkDao: SyncPlaylistLinkDao,
    private val syncPlaylistSourceDao: SyncPlaylistSourceDao,
    /** Fire-and-forget MBID enrichment for a single track. */
    private val mbidEnrichTrack: (trackId: String, artist: String, title: String) -> Unit,
    /** Fire-and-forget MBID enrichment for a batch of tracks. */
    private val mbidEnrichBatch: (tracks: List<Track>) -> Unit,
    /**
     * Fire-and-forget love-push trigger (issue #125). Called from
     * [addToCollection] — i.e. only when the user explicitly toggles a
     * track into their collection — NOT from the raw [addTrack] path
     * (sync, MediaScanner local-file scan, etc. shouldn't auto-love).
     *
     * Wired to `LovesPushService.pushLoved` in `AndroidModule`. Default
     * no-op so non-Android consumers and tests don't need to provide one.
     */
    private val pushLovedTrack: (Track) -> Unit = {},
    /**
     * Track tombstone facade (#172). User-intent add paths ([addTrack] /
     * [addTracks]) clear a track's tombstones so a deliberately re-added
     * track may be re-imported by sync. NEVER cleared from the sync-import
     * path — that goes through `trackDao.insertAll` in `SyncEngine`, not
     * here, and the tombstone filter already blocks re-import there.
     */
    private val tombstones: TrackTombstoneService,
) {

    /**
     * True iff the playlist has any sync intent — either a `syncedFrom`
     * pull source OR at least one `syncedTo` push mirror. Local-only
     * playlists return false, so editing one doesn't pointlessly flag
     * `locallyModified` (which would force the next sync cycle to scan
     * a never-syncable playlist).
     *
     * Fix 2 of the multi-provider mirror-propagation rules from desktop
     * CLAUDE.md.
     */
    private suspend fun hasSyncIntent(playlistId: String): Boolean {
        val source = syncPlaylistSourceDao.selectForLocal(playlistId)
        if (source != null) return true
        return syncPlaylistLinkDao.selectForLocal(playlistId).isNotEmpty()
    }

    fun getAllTracks(): Flow<List<Track>> = trackDao.getAll()
    fun getAllAlbums(): Flow<List<Album>> = albumDao.getAll()
    fun getAllArtists(): Flow<List<Artist>> = artistDao.getAll()
    fun getAllPlaylists(): Flow<List<Playlist>> = playlistDao.getAll()

    /**
     * Fill in [createdAt] / [updatedAt] / [lastModified] when the caller left
     * them at the default `0L` (Playlists sort by these, so rows with 0 sink
     * to the bottom of "Recently Added" forever). Callers can still pass
     * explicit values to override; only the zero case is patched.
     */
    private fun Playlist.withInsertTimestamps(): Playlist {
        val now = currentTimeMillis()
        return copy(
            createdAt = if (createdAt == 0L) now else createdAt,
            updatedAt = if (updatedAt == 0L) now else updatedAt,
            lastModified = if (lastModified == 0L) now else lastModified,
        )
    }

    fun searchTracks(query: String): Flow<List<Track>> = trackDao.search(query)
    fun searchAlbums(query: String): Flow<List<Album>> = albumDao.search(query)

    fun getAlbumTracks(albumId: String): Flow<List<Track>> = trackDao.getByAlbumId(albumId)

    suspend fun getAlbumByTitleAndArtist(title: String, artist: String): Album? =
        albumDao.getByTitleAndArtist(title, artist)

    suspend fun addTrack(track: Track) {
        val existing = trackDao.getById(track.id)
        // Preserve the synced addedAt timestamp if one exists
        trackDao.insert(if (existing != null) track.copy(addedAt = existing.addedAt) else track)
        // User re-adding a track clears its tombstones so sync may re-import it (#172).
        tombstones.clearAll(TrackTombstoneService.deriveTombstoneEntries(track))
        // Background MBID enrichment
        mbidEnrichTrack(track.id, track.artist, track.title)
    }

    /**
     * User-intent collection add: writes the track AND fires the love-push
     * (when enabled in Settings) — issue #125.
     *
     * **Use this from any user-driven "add to collection" / "love" action**
     * (heart toggle, ViewModel `addToCollection`, etc). DON'T use it from
     * sync paths, MediaScanner local-file scans, or any automated import —
     * those should call [addTrack] directly so they don't generate phantom
     * loves on tracks the user never touched.
     */
    suspend fun addToCollection(track: Track) {
        addTrack(track)
        // Fire-and-forget; LovesPushService handles its own coroutine + idempotency.
        pushLovedTrack(track)
    }

    suspend fun addTracks(tracks: List<Track>) {
        trackDao.insertAll(tracks)
        // User re-adding tracks clears their tombstones so sync may re-import them (#172).
        // No-op for local-scan tracks: they carry no streaming IDs, so
        // deriveTombstoneEntries returns empty and clearAll does nothing. The
        // clear is therefore gated to user-intent semantics by the fact that
        // only deliberate user adds carry streaming IDs today — do NOT route
        // automated streaming-ID imports through addTracks without revisiting
        // this (they'd silently resurrect tracks the user purposely removed).
        val entries = tracks.flatMap { TrackTombstoneService.deriveTombstoneEntries(it) }
        if (entries.isNotEmpty()) tombstones.clearAll(entries)
        // Background MBID enrichment for batch imports
        mbidEnrichBatch(tracks)
    }
    /**
     * In-app metadata edit for a track row (local-files tag editor — Android +
     * iOS parity). Overwrites ONLY title/artist/album; every other field
     * (sourceUrl, streaming IDs, MBIDs, artwork) is preserved via copy. This is
     * the metadata Parachord displays / resolves / scrobbles — it does NOT
     * rewrite the on-disk file's tags. Re-fires MBID enrichment for the corrected
     * title/artist. No-op if the id isn't found.
     */
    suspend fun updateTrackMetadata(id: String, title: String, artist: String, album: String?) {
        val existing = trackDao.getById(id) ?: return
        val t = title.trim().ifBlank { existing.title }
        val a = artist.trim().ifBlank { "Unknown Artist" }
        val al = album?.trim()?.ifBlank { null }
        trackDao.update(existing.copy(title = t, artist = a, album = al))
        mbidEnrichTrack(id, a, t)
    }

    suspend fun addAlbum(album: Album) = albumDao.insert(album)
    suspend fun addAlbums(albums: List<Album>) = albumDao.insertAll(albums)
    suspend fun addArtist(artist: Artist) = artistDao.insert(artist)
    suspend fun addArtists(artists: List<Artist>) = artistDao.insertAll(artists)
    suspend fun addPlaylist(playlist: Playlist) =
        playlistDao.insert(playlist.withInsertTimestamps())

    /** Backfill lastModified from updatedAt for playlists synced before tracking. */
    suspend fun backfillPlaylistLastModified() = playlistDao.backfillLastModified()

    /** Reactive check whether a track exists in collection by title+artist. */
    fun isTrackInCollection(title: String, artist: String): Flow<Boolean> =
        trackDao.existsByTitleAndArtist(title, artist)

    /** Reactive check whether an artist exists in collection by name. */
    fun isArtistInCollection(name: String): Flow<Boolean> =
        artistDao.existsByName(name)

    suspend fun deleteArtistByName(name: String) = artistDao.deleteByName(name)

    /** Reactive check whether an album exists in collection by title+artist. */
    fun isAlbumInCollection(title: String, artist: String): Flow<Boolean> =
        albumDao.existsByTitleAndArtist(title, artist)

    suspend fun deleteTrack(track: Track) = trackDao.delete(track)
    suspend fun deleteAlbum(album: Album) = albumDao.delete(album)
    suspend fun deletePlaylist(playlist: Playlist) = playlistDao.delete(playlist)

    /**
     * Phase 6.5 — delete a playlist locally AND attempt to delete each
     * remote mirror. Returns the per-provider attempt results so the
     * caller (a ViewModel) can surface a toast if any provider returned
     * `Unsupported` (per Decision D8 — Apple Music returns 401 on
     * `DELETE /me/library/playlists/{id}`, so the AM mirror persists
     * and the user has to remove it manually in the Music app).
     *
     * Order matters: remote-cleanup runs FIRST so the per-provider link
     * rows are consulted before the local row's foreign-key cascade
     * blows them away. The local delete is still last so a partial
     * failure doesn't leave the local row referencing dead remotes.
     */
    suspend fun deletePlaylistWithSync(
        playlist: Playlist,
        deleteFromProviders: Set<String>? = null,
    ): List<SyncEngine.PlaylistDeletionAttempt> {
        val attempts = syncEngine.onPlaylistRemoved(playlist, deleteFromProviders)
        playlistDao.delete(playlist)
        return attempts
    }

    /** All remote mirrors of a playlist (providerId -> externalId) — drives the
     *  per-mirror delete dialog. */
    suspend fun getPlaylistMirrors(localPlaylistId: String): Map<String, String> =
        syncEngine.getPlaylistMirrors(localPlaylistId)

    /** localPlaylistId -> push-mirror providers, for the playlist-list chips. */
    suspend fun getAllPlaylistLinkProviders(): Map<String, List<String>> =
        syncEngine.getAllPlaylistLinkProviders()

    /** localPlaylistId -> providers it EFFECTIVELY syncs with (override-aware) —
     *  the live source for playlist-list chips. */
    suspend fun getAllEffectivePlaylistChannels(): Map<String, List<String>> =
        syncEngine.getAllEffectivePlaylistChannels()

    // ── Sync-aware deletions (push removal back to source) ───────────

    suspend fun deleteTrackWithSync(track: Track) {
        syncEngine.onTrackRemoved(track)
        trackDao.delete(track)
    }

    suspend fun deleteAlbumWithSync(album: Album) {
        syncEngine.onAlbumRemoved(album)
        albumDao.delete(album)
    }

    suspend fun deleteArtistWithSync(artist: Artist) {
        syncEngine.onArtistRemoved(artist)
        artistDao.delete(artist)
    }

    // ── Playlist tracks ─────────────────────────────────────────────

    fun getPlaylistTracks(playlistId: String): Flow<List<PlaylistTrack>> =
        playlistTrackDao.getByPlaylistId(playlistId)

    /**
     * Create a playlist with its tracks in one operation.
     * Tracks are stored in the playlist_tracks junction table, NOT in the
     * Collection tracks table.
     */
    suspend fun createPlaylistWithTracks(
        playlist: Playlist,
        tracks: List<Track>,
    ) {
        playlistDao.insert(playlist.withInsertTimestamps())
        val playlistTracks = tracks.mapIndexed { index, track ->
            PlaylistTrack(
                playlistId = playlist.id,
                position = index,
                trackTitle = track.title,
                trackArtist = track.artist,
                trackAlbum = track.album,
                trackDuration = track.duration,
                trackArtworkUrl = track.artworkUrl,
                trackSourceUrl = track.sourceUrl,
                trackResolver = track.resolver,
                trackSpotifyUri = track.spotifyUri,
                trackSoundcloudId = track.soundcloudId,
                trackSpotifyId = track.spotifyId,
                trackAppleMusicId = track.appleMusicId,
            )
        }
        playlistTrackDao.insertAll(playlistTracks)
    }

    /**
     * Append tracks to an existing playlist.
     * Positions are assigned after the current max position.
     */
    suspend fun addTracksToPlaylist(playlistId: String, tracks: List<Track>) {
        val startPosition = playlistTrackDao.getMaxPosition(playlistId) + 1
        val playlistTracks = tracks.mapIndexed { index, track ->
            PlaylistTrack(
                playlistId = playlistId,
                position = startPosition + index,
                trackTitle = track.title,
                trackArtist = track.artist,
                trackAlbum = track.album,
                trackDuration = track.duration,
                trackArtworkUrl = track.artworkUrl,
                trackSourceUrl = track.sourceUrl,
                trackResolver = track.resolver,
                trackSpotifyUri = track.spotifyUri,
                trackSoundcloudId = track.soundcloudId,
                trackSpotifyId = track.spotifyId,
                trackAppleMusicId = track.appleMusicId,
            )
        }
        playlistTrackDao.insertAll(playlistTracks)
        // Update track count and modification timestamps
        val playlist = playlistDao.getById(playlistId) ?: return
        val now = currentTimeMillis()
        // Fix 2: only flag locallyModified when the playlist has sync
        // intent (syncedFrom OR any syncedTo). Editing a local-only
        // playlist shouldn't pointlessly flag a never-syncable row.
        // Preserve an existing locallyModified=true (don't downgrade
        // a flag set by an earlier mutator that the push loop hasn't
        // cleared yet).
        val flag = playlist.locallyModified || hasSyncIntent(playlistId)
        playlistDao.update(playlist.copy(
            trackCount = playlist.trackCount + tracks.size,
            updatedAt = now,
            lastModified = now,
            locallyModified = flag,
        ))
    }

    /** Rename a playlist. */
    suspend fun renamePlaylist(playlistId: String, newName: String) {
        val playlist = playlistDao.getById(playlistId) ?: return
        val now = currentTimeMillis()
        val flag = playlist.locallyModified || hasSyncIntent(playlistId)
        playlistDao.update(playlist.copy(
            name = newName,
            updatedAt = now,
            lastModified = now,
            locallyModified = flag,
        ))
    }

    /** Reorder playlist tracks by replacing all positions. */
    suspend fun reorderPlaylistTracks(playlistId: String, tracks: List<PlaylistTrack>) {
        val reindexed = tracks.mapIndexed { index, track ->
            track.copy(position = index)
        }
        playlistTrackDao.replaceAll(playlistId, reindexed)
        val playlist = playlistDao.getById(playlistId) ?: return
        val now = currentTimeMillis()
        val flag = playlist.locallyModified || hasSyncIntent(playlistId)
        playlistDao.update(playlist.copy(
            updatedAt = now,
            lastModified = now,
            locallyModified = flag,
        ))
    }

    /** Remove a single track from a playlist by position and update the count. */
    suspend fun removeTrackFromPlaylist(playlistId: String, position: Int) {
        playlistTrackDao.deleteTrack(playlistId, position)
        val playlist = playlistDao.getById(playlistId) ?: return
        val now = currentTimeMillis()
        val flag = playlist.locallyModified || hasSyncIntent(playlistId)
        playlistDao.update(playlist.copy(
            trackCount = (playlist.trackCount - 1).coerceAtLeast(0),
            updatedAt = now,
            lastModified = now,
            locallyModified = flag,
        ))
    }

    /**
     * Backfill resolver IDs on a stored track from resolution results.
     * Only fills in IDs that are currently null/blank — never overwrites.
     *
     * Also persists [artworkUrl] when supplied. The underlying
     * `tracks.updateArtworkById` query is COALESCE-style (only writes when
     * the row's `artworkUrl` is NULL/empty), so callers can pass artwork
     * defensively without risking overwriting user-imported album art.
     * Used by `TrackResolverCache.backfillResolverIds` to capture artwork
     * surfaced by `ResolvedSource.artworkUrl` during the resolver pipeline,
     * which is faster + cheaper than re-asking the metadata cascade later.
     */
    suspend fun backfillTrackResolverIds(
        trackId: String,
        spotifyId: String?,
        spotifyUri: String?,
        appleMusicId: String?,
        soundcloudId: String?,
        artworkUrl: String? = null,
    ) {
        trackDao.backfillResolverIds(trackId, spotifyId, spotifyUri, appleMusicId, soundcloudId)
        if (artworkUrl != null) {
            trackDao.updateArtworkById(trackId, artworkUrl)
        }
    }

    /** Convert a PlaylistTrack to a Track for playback. */
    fun playlistTrackToTrackEntity(pt: PlaylistTrack): Track =
        Track(
            id = randomUUID(),
            title = pt.trackTitle,
            artist = pt.trackArtist,
            album = pt.trackAlbum,
            duration = pt.trackDuration,
            artworkUrl = pt.trackArtworkUrl,
            sourceUrl = pt.trackSourceUrl,
            resolver = pt.trackResolver,
            spotifyUri = pt.trackSpotifyUri,
            spotifyId = pt.trackSpotifyId,
            soundcloudId = pt.trackSoundcloudId,
            appleMusicId = pt.trackAppleMusicId,
        )
}
