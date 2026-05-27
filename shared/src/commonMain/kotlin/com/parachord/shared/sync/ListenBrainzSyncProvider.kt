package com.parachord.shared.sync

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.settings.SettingsStore
import kotlin.concurrent.Volatile

/**
 * Two-way playlist sync between Parachord and ListenBrainz.
 *
 * Mirrors [AppleMusicSyncProvider]'s shape — session-scoped
 * `authFailedForSession` kill-switch on 401, graceful no-op handling
 * for the not-yet-supported surface (tracks / albums / artists fall
 * back to inherited defaults).
 *
 * V1 scope: playlists only (push + pull). Loved tracks stay on
 * [com.parachord.android.playback.LovesPushService]. Library surface
 * (saved tracks/albums, followed artists) inherits the no-op
 * defaults from [SyncProvider].
 *
 * See `docs/plans/2026-05-27-listenbrainz-sync-provider-design.md`.
 */
class ListenBrainzSyncProvider(
    private val client: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val mbidEnrichmentService: MbidEnrichmentService,
) : SyncProvider {
    companion object {
        const val PROVIDER_ID = "listenbrainz"
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "ListenBrainz"

    override val features: ProviderFeatures = ProviderFeatures(
        snapshots = SnapshotKind.DateString,         // LB returns last_modified_at ISO string
        supportsFollow = false,                       // V2
        supportsPlaylistDelete = true,                // POST /1/playlist/{mbid}/delete
        supportsPlaylistRename = true,                // POST /1/playlist/edit/{mbid}
        supportsTrackReplace = true,                  // delete-all + add-all
    )

    /**
     * Session-scoped kill-switch. Tripped by a 401 from any mutation
     * endpoint; remaining LB pushes in the session short-circuit until
     * the user re-authenticates. Mirrors the AM pattern.
     */
    @Volatile
    private var authFailedForSession: Boolean = false

    // ── Playlist surface — all TODO for now, implemented in Tasks 10-13 ─

    override suspend fun fetchPlaylists(
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedPlaylist> {
        // Kill-switch active — short-circuit until session restart so we
        // don't hammer LB with calls we know will 401 again. Mirrors AM's
        // `amPutUnsupportedForSession` / `amPatchUnsupportedForSession`
        // pattern.
        if (authFailedForSession) return emptyList()

        // Provider not configured — token + username are both required.
        // Treat unconfigured as "no playlists" (NOT an error) so SyncEngine
        // can iterate every enabled provider blindly.
        val token = settingsStore.getListenBrainzToken()
        if (token.isNullOrBlank()) return emptyList()
        val username = settingsStore.getListenBrainzUsername()
        if (username.isNullOrBlank()) return emptyList()

        val lbPlaylists = try {
            client.getUserOwnedPlaylists(username)
        } catch (e: ListenBrainzUnauthorizedException) {
            // Trip the session-scoped kill-switch. Non-401 errors propagate
            // so SyncEngine can decide whether to surface them to the user.
            authFailedForSession = true
            return emptyList()
        }
        return lbPlaylists.map { it.toSyncedPlaylist() }
    }

    private fun com.parachord.shared.api.LbPlaylist.toSyncedPlaylist(): SyncedPlaylist {
        // SyncedPlaylist's `spotifyId` slot carries the provider's external
        // id (the field name is Spotify-shaped by historical accident; AM
        // does the same). The LB playlist MBID lives here.
        val description = annotation.ifBlank { null }
        val playlistEntity = Playlist(
            id = "listenbrainz-$mbid",
            name = title,
            description = description,
            artworkUrl = null,
            trackCount = 0,
            createdAt = 0L,
            updatedAt = currentTimeMillis(),
            spotifyId = null,
            snapshotId = lastModifiedAt,
            lastModified = 0L,
            locallyModified = false,
            ownerName = null,
            sourceUrl = null,
            sourceContentHash = null,
            localOnly = false,
        )
        return SyncedPlaylist(
            entity = playlistEntity,
            spotifyId = mbid,
            snapshotId = lastModifiedAt,
            trackCount = 0,
            // The `/1/user/{userName}/playlists` endpoint returns only the
            // user's OWN playlists — every entry here is owned + mutable.
            isOwned = true,
        )
    }

    override suspend fun fetchPlaylistTracks(
        externalPlaylistId: String,
    ): List<PlaylistTrack> = TODO("Task 11")

    override suspend fun getPlaylistSnapshotId(
        externalPlaylistId: String,
    ): String? = TODO("Task 11")

    override suspend fun createPlaylist(
        name: String,
        description: String?,
    ): RemoteCreated = TODO("Task 12")

    override suspend fun replacePlaylistTracks(
        externalPlaylistId: String,
        externalTrackIds: List<String>,
    ): String? = TODO("Task 12")

    override suspend fun updatePlaylistDetails(
        externalPlaylistId: String,
        name: String?,
        description: String?,
    ): Unit = TODO("Task 12")

    override suspend fun deletePlaylist(
        externalPlaylistId: String,
    ): DeleteResult = TODO("Task 13")

    override suspend fun searchForTrackId(
        title: String,
        artist: String,
        album: String?,
    ): String? = TODO("Task 13")

    // Library surface (saveTracks, saveAlbums, fetchArtists, etc.) intentionally
    // inherits the no-op defaults from SyncProvider.
}
