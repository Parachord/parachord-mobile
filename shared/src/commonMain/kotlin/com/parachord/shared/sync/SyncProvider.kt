package com.parachord.shared.sync

/**
 * Cross-platform sync provider interface. Spotify, Apple Music, Tidal,
 * and any future provider implements this. SyncEngine never branches on
 * `provider.id`; it dispatches on `provider.features` and lets each
 * provider declare its own capability surface.
 *
 * See parent plan `docs/plans/2026-04-22-multi-provider-sync-correctness.md`
 * for the design rationale, propagation invariants, and per-provider
 * idiosyncrasies this interface abstracts.
 */
interface SyncProvider {
    /** Stable identifier ("spotify", "applemusic", future "tidal"). */
    val id: String

    /** Human-readable label shown in settings + wizard ("Spotify", "Apple Music"). */
    val displayName: String

    /** Capability flags. SyncEngine routes on these, never on `id`. */
    val features: ProviderFeatures

    // ── Playlist surface (Phase 4) ──────────────────────────────────
    // Library-sync members (fetchTracks/Albums/Artists, save/follow)
    // are deferred — Phase 4 ships playlists-only (Decision D1).

    /**
     * Fetch the user's owned + followed playlists from the provider.
     * Returns provider-shaped [SyncedPlaylist] rows; SyncEngine merges
     * them into local state via the three-layer dedup logic.
     */
    suspend fun fetchPlaylists(
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<SyncedPlaylist>

    /**
     * Fetch every track in [externalPlaylistId]. Provider-specific
     * track IDs (`spotifyId`, `appleMusicId`, etc.) are populated on
     * the returned rows.
     */
    suspend fun fetchPlaylistTracks(
        externalPlaylistId: String,
    ): List<com.parachord.shared.model.PlaylistTrack>

    /**
     * Returns the provider's snapshot/change-token for [externalPlaylistId].
     * Spotify returns its opaque `snapshot_id`; Apple Music returns the
     * `lastModifiedDate` ISO string. SyncEngine compares as strings;
     * mismatch ⇒ pull. Returns null when [features.snapshots] is
     * [SnapshotKind.None] or the playlist isn't found.
     */
    suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String?

    /**
     * Create a new remote playlist. Returns the newly-created external
     * ID and (if the provider supports snapshots) the initial snapshot
     * token.
     */
    suspend fun createPlaylist(
        name: String,
        description: String? = null,
    ): RemoteCreated

    /**
     * Full-replace [externalPlaylistId]'s tracklist with the given
     * external IDs. Returns the new snapshot token. Providers without
     * reliable replace (e.g. Apple Music when PUT 401's) degrade to
     * append-only after the first failure — a session kill-switch
     * remembers the rejection so subsequent calls go straight to POST.
     * Removals stay on the remote in that case.
     */
    suspend fun replacePlaylistTracks(
        externalPlaylistId: String,
        externalTrackIds: List<String>,
    ): String?

    // ── Incremental add/remove primitives (incremental-materialization) ──
    // The materialize executor (Task 5) calls these to apply a per-provider,
    // non-destructive diff instead of a wholesale replace. Removal dispatch is
    // driven by [ProviderFeatures.trackRemoveMode] — the executor NEVER branches
    // on `id`, so the two remove variants default to THROWING. A mis-dispatch (or
    // a provider that forgot to override the variant its mode declares) must be
    // LOUD, never silently destructive.

    /**
     * The provider's native track id for this track (the column the provider keys
     * its remote tracklist on), or null if absent. Replaces the central
     * `extractExternalTrackIds` switch — the executor reads remote tracks' native
     * ids through this. Throwing default so a new provider must declare it; the
     * executor calls it for every provider it removes from.
     */
    fun nativeIdOf(track: com.parachord.shared.model.PlaylistTrack): String? =
        throw UnsupportedOperationException("nativeIdOf not implemented by $id")

    /**
     * Append [externalTrackIds] to the remote, non-destructively. Every provider
     * supports an append; there is NO safe replace-based default (it would wipe),
     * so each provider overrides this. Returns the new snapshot token (provider's
     * snapshot semantics), or null when the provider has no snapshot.
     *
     * Empty input is a no-op; the return value on empty input is provider-defined
     * — callers should guard empty id lists. (Task-3 review note.)
     */
    suspend fun addPlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? =
        throw UnsupportedOperationException("addPlaylistTracks not implemented by $id")

    /**
     * Remove specific tracks by their native ID. Only called for
     * [TrackRemoveMode.ByNativeId]. Throws by default so a mis-dispatched call to
     * a provider whose mode is NOT ByNativeId fails loudly.
     */
    suspend fun removePlaylistTracksByNativeId(externalPlaylistId: String, externalTrackIds: List<String>): String? =
        throw UnsupportedOperationException("removeByNativeId not supported by $id")

    /**
     * Remove tracks by 0-based remote position. Only called for
     * [TrackRemoveMode.ByPosition]. Throws by default so a mis-dispatched call to
     * a provider whose mode is NOT ByPosition fails loudly.
     */
    suspend fun removePlaylistTracksByPosition(externalPlaylistId: String, positions: List<Int>): String? =
        throw UnsupportedOperationException("removeByPosition not supported by $id")

    /**
     * Update playlist metadata (rename, description). Best-effort.
     * Apple Music returns 401 here; providers must NOT throw on
     * documented-unsupported responses (kill-switch + return without
     * raising). Load-bearing: this runs before the track push in
     * the create-or-link path; a throw here would abort the track
     * push too.
     */
    suspend fun updatePlaylistDetails(
        externalPlaylistId: String,
        name: String?,
        description: String?,
    )

    /**
     * Delete [externalPlaylistId] from the provider. Apple Music
     * returns 401; providers must return [DeleteResult.Unsupported]
     * (NOT throw) so callers can surface "remove manually in the
     * Music app" UX.
     */
    suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult

    /**
     * Catalog-search-based ID hydration (un-defers Decision D1).
     *
     * Search the provider's catalog for a track matching the given
     * metadata. Returns the provider-specific external ID format —
     * `"spotify:track:<id>"` for Spotify, the bare numeric catalog
     * ID for Apple Music, etc. Returns null when no high-confidence
     * match is found (caller skips the track on push rather than
     * pushing a wrong-song match).
     *
     * SyncEngine calls this from [hydrateMissingTrackIds] before
     * extracting external IDs for the push, so a freshly-imported
     * playlist whose tracks lack provider IDs (e.g. a hosted XSPF)
     * can still push a complete tracklist on first sync. Resolved
     * IDs are persisted back to the track row so subsequent syncs
     * skip the search.
     *
     * Default: null (no catalog search). Providers without a
     * searchable catalog (e.g. SoundCloud once it gets sync) inherit
     * this no-op and tracks lacking the relevant ID are silently
     * skipped, matching the pre-Decision-D1 behavior.
     *
     * Confidence: implementations should return null for matches
     * below the equivalent of [com.parachord.shared.resolver.MIN_CONFIDENCE_THRESHOLD]
     * (0.60) using [com.parachord.shared.resolver.scoreConfidence] —
     * better to push N-1 tracks than to pollute the user's mirror
     * with a wrong-song match.
     */
    suspend fun searchForTrackId(
        title: String,
        artist: String,
        album: String? = null,
        isrc: String? = null,
    ): String? = null

    // ── Library surface (collection sync — Phase 6.5+) ───────────────
    // Pull the user's saved tracks / albums / followed artists. Push
    // adds/removes for tracks/albums; artists are pull-only on Apple
    // Music (no follow/unfollow API).
    //
    // Default implementations no-op so providers that don't ship
    // library sync don't have to implement them. Returning `null` from
    // a fetch method means "not supported" (skip this collection axis
    // for this provider); returning an empty list means "nothing to
    // sync."

    /**
     * Fetch the user's saved tracks (Spotify "Liked Songs" /
     * Apple Music "Loved Songs in library"). The implementation may
     * use [localCount] + [latestExternalId] to short-circuit when no
     * remote changes are detected (returns null in that case).
     */
    suspend fun fetchTracks(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<SyncedTrack>? = null

    suspend fun saveTracks(externalIds: List<String>) { /* no-op */ }
    suspend fun removeTracks(externalIds: List<String>) { /* no-op */ }

    /**
     * Fetch the user's saved albums (Spotify "Albums" / Apple Music
     * "Albums in library"). Same short-circuit semantics as
     * [fetchTracks].
     */
    suspend fun fetchAlbums(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<SyncedAlbum>? = null

    suspend fun saveAlbums(externalIds: List<String>) { /* no-op */ }
    suspend fun removeAlbums(externalIds: List<String>) { /* no-op */ }

    /**
     * Fetch the user's followed artists (Spotify) or library artists
     * (Apple Music — pull-only since AM has no follow API).
     */
    suspend fun fetchArtists(
        localCount: Int,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<SyncedArtist>? = null

    suspend fun followArtists(externalIds: List<String>) { /* no-op */ }
    suspend fun unfollowArtists(externalIds: List<String>) { /* no-op */ }
}

/**
 * Per-provider capability declarations. Adding a new provider means
 * implementing SyncProvider with its own ProviderFeatures — no changes
 * to SyncEngine required.
 */
data class ProviderFeatures(
    /** What kind of snapshot/change-token this provider's playlists carry. */
    val snapshots: SnapshotKind,
    /** Whether the provider supports a follow/unfollow API for artists. Apple Music = false. */
    val supportsFollow: Boolean = false,
    /** Whether the provider supports playlist deletion via API. Apple Music = false. */
    val supportsPlaylistDelete: Boolean = false,
    /** Whether the provider supports playlist rename via API. Apple Music = false. */
    val supportsPlaylistRename: Boolean = false,
    /** Whether full-replace PUT on tracks is reliable; if false, push degrades to append-only after first failure. */
    val supportsTrackReplace: Boolean = false,
    /**
     * How this provider removes specific tracks from a remote playlist.
     * Drives the materialize executor's removal dispatch. Defaults to the
     * conservative [TrackRemoveMode.ReplaceOnly] so a provider that forgets
     * to declare it is never incrementally destructive.
     */
    val trackRemoveMode: TrackRemoveMode = TrackRemoveMode.ReplaceOnly,
    /** Whether the provider preserves explicit track order on push (Spotify = true). */
    val canReorder: Boolean = false,
)

/**
 * How a provider deletes specific tracks from a remote playlist. Drives the
 * materialize executor's removal dispatch — the executor never branches on id.
 */
enum class TrackRemoveMode {
    /** Spotify: DELETE by track URI. */
    ByNativeId,
    /** ListenBrainz: POST item/delete by index+count. */
    ByPosition,
    /** Apple Music: no playlist-track remove endpoint (add-only). */
    Unsupported,
    /** Fallback for a future provider with only wholesale replace. */
    ReplaceOnly,
}

enum class SnapshotKind {
    /** Provider returns a stable opaque token (Spotify `snapshot_id`). String-equality compare. */
    Opaque,
    /** Provider returns a date/version string (Apple `lastModifiedDate`). String-equality compare; refetch on mismatch. */
    DateString,
    /** Provider has no snapshot. SyncEngine falls back to always-pull. Costlier — 1 extra API call per playlist per sync. */
    None,
}

/**
 * Result of a [SyncProvider.deletePlaylist] call. Provider implementations
 * must NEVER throw on documented-unsupported responses (e.g. Apple's 401
 * on DELETE) — return [Unsupported] instead so the caller can surface
 * "remove manually in the {provider}" UX.
 */
sealed class DeleteResult {
    object Success : DeleteResult()
    data class Unsupported(val status: Int) : DeleteResult()
    data class Failed(val error: Throwable) : DeleteResult()
}

/**
 * Returned by [SyncProvider.createPlaylist] — the newly-created remote
 * playlist's external ID and (if the provider supports snapshots) the
 * initial snapshot token.
 */
data class RemoteCreated(
    val externalId: String,
    /**
     * Null only when the provider's [SnapshotKind] is [SnapshotKind.None].
     * Snapshot-bearing providers must return a real token here; if the
     * create response omitted it, refetch via `getPlaylistSnapshotId` or
     * fail rather than returning null.
     */
    val snapshotId: String?,
)
