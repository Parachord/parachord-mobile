package com.parachord.shared.model

data class Playlist(
    val id: String,
    val name: String,
    val description: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val spotifyId: String? = null,
    val snapshotId: String? = null,
    val lastModified: Long = 0L,
    val locallyModified: Boolean = false,
    val ownerName: String? = null,
    val sourceUrl: String? = null,
    val sourceContentHash: String? = null,
    val localOnly: Boolean = false,
    /** Can the user write to this playlist on its source provider (owned OR
     *  collaborative)? Read-only followed playlists are `false` and are never
     *  push/mirror candidates (#269). Defaults true (local/owned). */
    val writable: Boolean = true,
    /** When the tracks last changed (added, removed or reordered); null until
     *  they first do. Local only, stamped by the DAO ([com.parachord.shared.home.PlaylistFreshness]). */
    val tracksChangedAt: Long? = null,
    /** How many tracks that change added (0 for a removal or a reorder). */
    val lastChangeAdded: Int = 0,
)
