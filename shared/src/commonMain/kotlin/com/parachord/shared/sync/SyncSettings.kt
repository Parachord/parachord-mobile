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
    // #269 note: a FOLLOWED (non-writable) playlist CAN still mirror OUT to other
    // services — you own those mirror copies. What must never happen is writing
    // BACK to the non-writable source; for Spotify that's already enforced by the
    // `spotifyId == null` clause below (a followed `spotify-*` row has a spotifyId,
    // so it's never a Spotify push candidate). The per-copy write-back guard for
    // N-way propagation uses `playlist.writable`. So no blanket writable gate here.
    val base = playlist.id.startsWith("local-") || playlist.sourceUrl != null
    return when (providerId) {
        // `listenbrainz-*` is now eligible to RE-EXPORT to streaming services — a
        // playlist created/edited on ListenBrainz (Achordion) can mirror to Spotify
        // / Apple Music. It is OPT-IN ONLY ([autoMirrorsByDefault]) so a pulled LB
        // library doesn't auto-flood them with the user's whole archive.
        "spotify" -> playlist.spotifyId == null &&
            (base || playlist.id.startsWith("listenbrainz-"))
        "applemusic" -> base ||
            playlist.id.startsWith("spotify-") ||
            playlist.id.startsWith("listenbrainz-")
        "listenbrainz" -> base ||
            playlist.id.startsWith("spotify-") ||
            playlist.id.startsWith("applemusic-")
        else -> base
    }
}

/**
 * Whether [playlist] AUTO-mirrors to push providers via a provider's default
 * push selection (mode=ALL), vs. only when the user explicitly opts in with a
 * per-playlist channel override.
 *
 * A ListenBrainz-IMPORTED playlist (`listenbrainz-*`) is OPT-IN ONLY: it's
 * eligible to re-export ([isPlaylistPushCandidate]) but never auto-mirrors, so a
 * pulled LB library doesn't flood Spotify / Apple Music with the user's whole
 * archive (and risk duplicates). The user opts a specific LB playlist in by
 * toggling the service on in its Sync menu — which writes the channel override,
 * the authoritative path that bypasses this gate. Local / hosted / Spotify-imported
 * rows auto-mirror by default, unchanged.
 */
fun autoMirrorsByDefault(playlist: com.parachord.shared.model.Playlist): Boolean =
    !playlist.id.startsWith("listenbrainz-")

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
     * Per-playlist channel override: the exact set of providers a single
     * playlist syncs with, set via the playlist's Sync menu. `null` = no
     * override (fall back to the per-provider push selection / pull allowlist).
     * When present it is AUTHORITATIVE for that playlist on both push and pull —
     * so the user can sync one playlist to exactly the services they choose,
     * independent of the global per-provider defaults.
     */
    suspend fun getPlaylistChannels(localPlaylistId: String): Set<String>?

    /** Persist (or clear, with `null`) a playlist's channel override. */
    suspend fun setPlaylistChannels(localPlaylistId: String, channels: Set<String>?)

    /**
     * Per-playlist MIRROR-ONLY flag. When true the playlist is reconciled as a
     * one-way mirror FROM its source: the source is single-authority and its
     * rotate-out drops IMMEDIATELY (no streak gate), exactly like a followed
     * playlist — for OWNED-but-dynamic playlists the source-writability split
     * can't catch (e.g. a SmarterPlaylists-managed Daily Brew that Spotify reports
     * as user-owned). Default false. User-set in the playlist's Sync menu; a pull
     * must NEVER clobber it, which is why it lives here (KvStore) and NOT as a
     * `playlists` column (`writable` is refreshed on every pull, #269).
     *
     * Defaulted so test doubles and any non-persisting provider safely report
     * "not mirror-only"; the real [com.parachord.shared.settings.SettingsStore]
     * overrides both.
     */
    suspend fun getPlaylistMirrorOnly(localPlaylistId: String): Boolean = false

    /** Persist a playlist's mirror-only flag (see [getPlaylistMirrorOnly]). */
    suspend fun setPlaylistMirrorOnly(localPlaylistId: String, mirrorOnly: Boolean) {}

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

    /** N-way multimaster playlist sync opt-in (default OFF). Gates the entire
     *  N-way path — migration bootstrap, shadow mode, propagation. */
    suspend fun isNwayEnabled(): Boolean

    /** Stricter opt-in for N-way PROPAGATION — real provider writes. Requires
     *  [isNwayEnabled] too. Default OFF. */
    suspend fun isNwayPropagateEnabled(): Boolean

    /** Persist the timestamp of the last successful sync (epoch millis). */
    suspend fun setLastSyncAt(timestamp: Long)

    /**
     * Wipe all sync state. Called from [SyncEngine.stopSyncing] when the user
     * disconnects sync entirely.
     */
    suspend fun clearSyncSettings()
}
