package com.parachord.shared.sync

import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track

/**
 * A track fetched from a remote sync provider, paired with the provider's
 * external identifier and the timestamp the user added it on the remote.
 *
 * Lives in the shared module because it represents cross-provider sync
 * metadata, not a Spotify-specific shape — Apple Music and any future
 * provider produce the same triple of (local entity, external id, addedAt).
 */
data class SyncedTrack(
    val entity: Track,
    val spotifyId: String,
    val addedAt: Long,
)

/**
 * An album fetched from a remote sync provider, paired with the provider's
 * external identifier and the timestamp the user added it on the remote.
 */
data class SyncedAlbum(
    val entity: Album,
    val spotifyId: String,
    val addedAt: Long,
)

/**
 * An artist fetched from a remote sync provider, paired with the provider's
 * external identifier. No addedAt — provider follow APIs typically don't
 * return one.
 */
data class SyncedArtist(
    val entity: Artist,
    val spotifyId: String,
)

/**
 * A playlist fetched from a remote sync provider, paired with the provider's
 * external identifier, snapshot/change-token, track count, and ownership flag.
 *
 * `snapshotId` is null when the provider has no snapshot concept
 * (see [SnapshotKind.None]); `isOwned` indicates whether the current user
 * can mutate the playlist on the remote.
 */
data class SyncedPlaylist(
    val entity: Playlist,
    val spotifyId: String,
    val snapshotId: String?,
    val trackCount: Int,
    val isOwned: Boolean,
    /** Can the user write to this playlist on this provider (owned OR
     *  collaborative)? Drives push/mirror candidacy (#269). Default true. */
    val writable: Boolean = true,
)
