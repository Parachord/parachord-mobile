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
