package com.parachord.shared.sync

/**
 * The cross-platform shape of the wizard-driven sync configuration.
 *
 * Lifted from the Android-only `SettingsStore.SyncSettings` nested data class
 * during sync extraction so [SyncEngine] (now living in shared) can read it
 * via [SyncSettingsProvider] without depending on DataStore.
 *
 * The Android-side `SettingsStore.SyncSettings` is now a typealias to this
 * class for source compatibility — call sites that constructed
 * `SettingsStore.SyncSettings(...)` keep compiling unchanged.
 */
data class SyncSettings(
    val enabled: Boolean = false,
    val provider: String = "spotify",
    val syncTracks: Boolean = true,
    val syncAlbums: Boolean = true,
    val syncArtists: Boolean = true,
    val syncPlaylists: Boolean = true,
    val selectedPlaylistIds: Set<String> = emptySet(),
    val pushLocalPlaylists: Boolean = true,
)

/**
 * How a given provider mirrors local playlists. Per-provider, so the user can
 * push everything to Spotify, a hand-picked subset to Apple Music, and nothing
 * to ListenBrainz (its default).
 */
/**
 * Whether [playlist] is eligible to be PUSHED (mirrored) to [providerId],
 * BEFORE the user's per-provider playlist selection is applied. Shared so the
 * settings "which playlists" picker shows exactly the rows the push loop would
 * consider. Mirrors `SyncEngine.isPushCandidate` (which delegates here).
 */
fun isPlaylistPushCandidate(playlist: com.parachord.shared.model.Playlist, providerId: String): Boolean {
    val base = playlist.id.startsWith("local-") || playlist.sourceUrl != null
    return when (providerId) {
        "spotify" -> playlist.spotifyId == null && base
        "applemusic" -> base || playlist.id.startsWith("spotify-")
        "listenbrainz" -> base ||
            playlist.id.startsWith("spotify-") ||
            playlist.id.startsWith("applemusic-")
        else -> base
    }
}

enum class PlaylistSyncMode { ALL, NONE, SELECTED }

/**
 * A provider's playlist-push selection. [localPlaylistIds] (local row ids,
 * e.g. `local-…`, `spotify-…`, `applemusic-…`) is only consulted when
 * [mode] == [PlaylistSyncMode.SELECTED].
 */
data class ProviderPlaylistSelection(
    val mode: PlaylistSyncMode,
    val localPlaylistIds: Set<String> = emptySet(),
) {
    /** Whether a local playlist row should mirror to this provider. */
    fun includes(localPlaylistId: String): Boolean = when (mode) {
        PlaylistSyncMode.ALL -> true
        PlaylistSyncMode.NONE -> false
        PlaylistSyncMode.SELECTED -> localPlaylistId in localPlaylistIds
    }

    companion object {
        /**
         * The mode for a provider with nothing stored yet. ListenBrainz →
         * [PlaylistSyncMode.NONE] (desktop parity — never push until opt-in,
         * the fix for the LB flood); everyone else → [PlaylistSyncMode.ALL]
         * (preserve mirror-everything).
         */
        fun defaultMode(providerId: String): PlaylistSyncMode =
            if (providerId == "listenbrainz") PlaylistSyncMode.NONE else PlaylistSyncMode.ALL
    }
}

/**
 * Platform-agnostic gateway to the user's sync configuration. The Android
 * implementation wraps the existing DataStore-backed `SettingsStore`; the
 * iOS implementation will write through the same shared keys via
 * `multiplatform-settings` (Phase 9B).
 *
 * Method shape mirrors the methods [com.parachord.shared.sync.SyncEngine]
 * historically called on `SettingsStore` directly — the smallest possible
 * surface that lets sync code live in `commonMain` without dragging the
 * full preferences API across the boundary.
 */
interface SyncSettingsProvider {
    /** The wizard-driven sync configuration (axes, push policy, selected playlists). */
    suspend fun getSyncSettings(): SyncSettings

    /** Provider IDs the user has enabled. Default is `setOf("spotify")`. */
    suspend fun getEnabledSyncProviders(): Set<String>

    /**
     * Per-provider opt-in for collection axes (`tracks`, `albums`, `artists`,
     * `playlists`). Defaults to all four when nothing has been written for
     * [providerId] — preserves the legacy behavior where global toggles gated
     * every provider uniformly.
     */
    suspend fun getSyncCollectionsForProvider(providerId: String): Set<String>

    /**
     * Per-provider playlist-push selection (which local playlists mirror to
     * [providerId]). Defaults: `spotify`/`applemusic` → [PlaylistSyncMode.ALL]
     * (preserve mirror-everything); `listenbrainz` → [PlaylistSyncMode.NONE]
     * (desktop parity — never push to LB until the user opts in). The push loop
     * in [SyncEngine.pushPlaylistsForProvider] filters candidates by this.
     */
    suspend fun getPlaylistSelection(providerId: String): ProviderPlaylistSelection

    /** Persist a provider's playlist-push selection (see [getPlaylistSelection]). */
    suspend fun setPlaylistSelection(providerId: String, selection: ProviderPlaylistSelection)

    /**
     * Per-provider PULL allowlist: which of the provider's remote playlists to
     * import (keyed on the provider's external id). EMPTY = import all (no
     * filter). A non-empty set imports only those ids. Spotify migrates from the
     * legacy global `selectedPlaylistIds`. Used by the Spotify + Apple Music pull
     * paths so the user can choose which of their service playlists sync.
     */
    suspend fun getPullPlaylists(providerId: String): Set<String>

    /** Persist a provider's pull allowlist (see [getPullPlaylists]). */
    suspend fun setPullPlaylists(providerId: String, externalIds: Set<String>)

    /**
     * Sync data version — bumped to force a full re-fetch after schema/migration
     * changes. SyncEngine compares against its own `SYNC_DATA_VERSION` constant.
     */
    suspend fun getSyncDataVersion(): Int

    /** Persist the new data version after a successful migration / refetch. */
    suspend fun setSyncDataVersion(version: Int)

    /** One-shot cross-provider track-dedup migration flag (independent of the
     *  SYNC_DATA_VERSION counter). */
    suspend fun getTrackDedupV1Done(): Boolean
    suspend fun setTrackDedupV1Done()

    /** Persist the timestamp of the last successful sync (epoch millis). */
    suspend fun setLastSyncAt(timestamp: Long)

    /**
     * Wipe all sync state. Called from [SyncEngine.stopSyncing] when the user
     * disconnects sync entirely.
     */
    suspend fun clearSyncSettings()
}
