package com.parachord.shared.settings

import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.parachord.shared.store.SettingsMigration
import kotlin.concurrent.Volatile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * KMP preferences store — successor to the desktop app's electron-store.
 *
 * Phase 9B Stage 3 (Apr 2026) moved this class from `app/` into
 * `shared/commonMain` so iOS can consume the same API. All non-secure
 * preferences live in [KvStore] (multiplatform-settings backed —
 * `SharedPreferences` on Android, `NSUserDefaults` on iOS). OAuth tokens
 * and BYO API keys live in [SecureTokenStore] (per-platform encrypted
 * backing — `EncryptedSharedPreferences` on Android, Keychain on iOS).
 *
 * **Migration to KvStore on Android.** Older installs stored everything
 * in Jetpack DataStore (`parachord_settings`). On first construction
 * with a non-NoOp [SettingsMigration], [ensureMigrated] copies every
 * recognized key from DataStore into KvStore (`parachord_kmp_prefs`)
 * and writes a `_migration_v1` marker. Subsequent launches see the
 * marker and skip the copy. iOS installs start fresh with [SettingsMigration.NoOp].
 *
 * (Phase 9B Stage 2 had a narrower `_migration_sync_v1` marker for the
 * sync key family. Stage 3 supersedes it with a single unified marker
 * — the old marker is left in place but no longer consulted.)
 *
 * security: C4 — encrypt tokens at rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStore(
    val secureStore: SecureTokenStore,
    private val kv: KvStore,
    private val migration: SettingsMigration = SettingsMigration.NoOp,
    private val appleMusicDeveloperTokenFallback: String = "",
) : com.parachord.shared.sync.SyncSettingsProvider {

    companion object {
        const val THEME_MODE = "theme_mode"
        const val SCROBBLING_ENABLED = "scrobbling_enabled"
        const val PERSIST_QUEUE = "persist_queue"
        const val PERSISTED_QUEUE_STATE = "persisted_queue_state"
        const val LASTFM_USERNAME = "lastfm_username"
        const val LISTENBRAINZ_USERNAME = "listenbrainz_username"
        const val RESOLVER_ORDER = "resolver_order"
        const val ACTIVE_RESOLVERS = "active_resolvers"
        const val RESOLVER_VOLUME_OFFSETS = "resolver_volume_offsets"
        const val DISABLED_META_PROVIDERS = "disabled_meta_providers"
        const val BLOCKED_RECOMMENDATIONS = "blocked_recommendations"
        const val SEND_LISTENING_HISTORY = "send_listening_history"
        const val CHATGPT_MODEL = "chatgpt_model"
        const val CLAUDE_MODEL = "claude_model"
        const val GEMINI_MODEL = "gemini_model"
        const val SELECTED_CHAT_PROVIDER = "selected_chat_provider"
        const val APPLE_MUSIC_STOREFRONT = "apple_music_storefront"
        const val PREFERRED_SPOTIFY_DEVICE_ID = "preferred_spotify_device_id"
        const val SORT_ARTISTS = "sort_artists"
        const val SORT_ALBUMS = "sort_albums"
        const val SORT_TRACKS = "sort_tracks"
        const val SORT_FRIENDS = "sort_friends"
        const val SORT_PLAYLISTS = "sort_playlists"
        const val DELETED_FRIEND_KEYS = "deleted_friend_keys"
        const val CONCERT_LATITUDE = "concert_latitude"
        const val CONCERT_LONGITUDE = "concert_longitude"
        const val CONCERT_CITY = "concert_city"
        const val CONCERT_RADIUS = "concert_radius_miles"
        const val LAST_PLUGIN_SYNC = "last_plugin_sync_timestamp"
        const val DISABLED_PLUGINS = "disabled_plugins"
        const val SPOTIFY_ACCESS_TOKEN_EXPIRES_AT = "spotify_access_token_expires_at"

        /** Per-service opt-in toggle for the loved-tracks push. Mirrors
         *  desktop's `scrobbler_love_push_enabled` shape from the design
         *  doc (`docs/plans/2026-05-03-loved-tracks-scrobbler-push-design.md`).
         *  CSV format `service:bool,service:bool` (e.g. `lastfm:true,listenbrainz:false`).
         *  Default for unset service = false (per desktop's "default OFF"). */
        const val LOVE_PUSH_ENABLED = "scrobbler_love_push_enabled"

        /** Idempotency cache: `trackId -> {service: epochMs}` of completed
         *  love pushes. JSON-encoded `{ "<trackId>": { "lastfm": 1234, "listenbrainz": 1235 } }`.
         *  Survives crashes mid-backfill (each successful push writes its
         *  key before moving on, so resume picks up where it left off).
         *  Mirrors desktop's `love_pushed_keys`. */
        const val LOVE_PUSHED_KEYS = "love_pushed_keys"

        // Sync key family (Phase 9B Stage 2 — already KvStore-resident)
        const val SYNC_ENABLED = "sync_enabled"
        const val SYNC_PROVIDER = "sync_provider"
        const val SYNC_TRACKS = "sync_tracks"
        const val SYNC_ALBUMS = "sync_albums"
        const val SYNC_ARTISTS = "sync_artists"
        const val SYNC_PLAYLISTS = "sync_playlists"
        const val SYNC_SELECTED_PLAYLIST_IDS = "sync_selected_playlist_ids"
        const val SYNC_LAST_COMPLETED_AT = "sync_last_completed_at"
        const val SYNC_PUSH_LOCAL_PLAYLISTS = "sync_push_local_playlists"
        const val SYNC_DATA_VERSION = "sync_data_version"
        // Dedicated one-shot flag for the cross-provider track-dedup wipe. NOT on
        // the SYNC_DATA_VERSION counter — that counter is shared by the playlist
        // dedup migration, and bumping it here would skip migrations that haven't
        // run yet on a fresh-from-old install.
        const val TRACK_DEDUP_V1_DONE = "track_dedup_v1_done"
        const val ENABLED_SYNC_PROVIDERS = "enabled_sync_providers"
        // N-way multimaster playlist sync (per-user opt-in, default OFF). Gates
        // the WHOLE N-way path — migration bootstrap, shadow mode, propagation.
        // Inert until flipped on, so Phases 2-3 ship dark.
        const val NWAY_ENABLED = "nway_enabled"

        /** Per-provider opt-in for which collection axes to sync.
         *  Keyed as `sync_collections_<providerId>` ("tracks,albums,artists,playlists").
         *  Absent ⇒ default to ALL axes (preserves the global-toggle behavior
         *  that existed before per-provider opt-in landed). */
        fun syncCollectionsKey(providerId: String) = "sync_collections_$providerId"

        /** Per-provider playlist-push selection. `sync_playlist_mode_<id>` holds
         *  ALL|NONE|SELECTED; `sync_playlist_ids_<id>` holds the CSV of local
         *  playlist ids used when SELECTED. Absent mode ⇒ ALL for spotify /
         *  applemusic, NONE for listenbrainz (desktop parity). */
        fun playlistModeKey(providerId: String) = "sync_playlist_mode_$providerId"
        fun playlistIdsKey(providerId: String) = "sync_playlist_ids_$providerId"

        /** Per-provider PULL allowlist (which remote playlists to import). */
        fun pullPlaylistsKey(providerId: String) = "sync_pull_playlists_$providerId"

        /** Per-playlist channel override (which providers ONE playlist syncs with). */
        fun playlistChannelsKey(localPlaylistId: String) = "sync_playlist_channels_$localPlaylistId"

        /** Marker key — set to `true` once the one-shot DataStore→KvStore
         *  copy completes. Lives in KvStore so it survives across reboots
         *  without re-running the migration. */
        const val MIGRATION_DONE_V1 = "_migration_v1"

        /** Default canonical resolver order matching the desktop app. */
        private const val DEFAULT_RESOLVER_ORDER =
            "spotify,applemusic,bandcamp,soundcloud,localfiles,youtube"
    }

    @Volatile private var migrated = false
    private val migrationMutex = Mutex()

    /**
     * One-shot migration of legacy preferences into [kv]. Idempotent —
     * the marker key in [kv] prevents repeated runs across launches.
     * Mutex-guarded so concurrent first-callers serialize on the same
     * pass instead of racing.
     */
    private suspend fun ensureMigrated() {
        if (migrated) return
        migrationMutex.withLock {
            if (migrated) return
            if (kv.getBoolean(MIGRATION_DONE_V1)) {
                migrated = true
                return
            }
            migration.migrate(kv)
            kv.setBoolean(MIGRATION_DONE_V1, true)
            migrated = true
        }
    }

    /** Inline-able migration hook for flow builders. */
    private fun migratedFlow(): Flow<Unit> = flow {
        ensureMigrated()
        emit(Unit)
    }

    // ── Theme / scrobbling / queue ───────────────────────────────────

    val themeMode: Flow<String> = migratedFlow().flatMapConcat {
        kv.observeString(THEME_MODE, default = "system")
    }
    val scrobblingEnabled: Flow<Boolean> = migratedFlow().flatMapConcat {
        kv.observeBoolean(SCROBBLING_ENABLED, default = false)
    }
    val persistQueue: Flow<Boolean> = migratedFlow().flatMapConcat {
        kv.observeBoolean(PERSIST_QUEUE, default = true)
    }
    val nwayEnabledFlow: Flow<Boolean> = migratedFlow().flatMapConcat {
        kv.observeBoolean(NWAY_ENABLED, default = false)
    }

    suspend fun setThemeMode(mode: String) {
        ensureMigrated()
        kv.setString(THEME_MODE, mode)
    }

    suspend fun setScrobblingEnabled(enabled: Boolean) {
        ensureMigrated()
        kv.setBoolean(SCROBBLING_ENABLED, enabled)
    }

    // ── Sort preferences ─────────────────────────────────────────────

    suspend fun getSortArtists(): String? {
        ensureMigrated(); return kv.getStringOrNull(SORT_ARTISTS)
    }
    suspend fun getSortAlbums(): String? {
        ensureMigrated(); return kv.getStringOrNull(SORT_ALBUMS)
    }
    suspend fun getSortTracks(): String? {
        ensureMigrated(); return kv.getStringOrNull(SORT_TRACKS)
    }
    suspend fun getSortFriends(): String? {
        ensureMigrated(); return kv.getStringOrNull(SORT_FRIENDS)
    }
    suspend fun getSortPlaylists(): String? {
        ensureMigrated(); return kv.getStringOrNull(SORT_PLAYLISTS)
    }

    suspend fun setSortArtists(sort: String) { ensureMigrated(); kv.setString(SORT_ARTISTS, sort) }
    suspend fun setSortAlbums(sort: String) { ensureMigrated(); kv.setString(SORT_ALBUMS, sort) }
    suspend fun setSortTracks(sort: String) { ensureMigrated(); kv.setString(SORT_TRACKS, sort) }
    suspend fun setSortFriends(sort: String) { ensureMigrated(); kv.setString(SORT_FRIENDS, sort) }
    suspend fun setSortPlaylists(sort: String) {
        ensureMigrated(); kv.setString(SORT_PLAYLISTS, sort)
    }

    // ── Spotify tokens (SecureTokenStore) ────────────────────────────

    suspend fun setSpotifyTokens(accessToken: String, refreshToken: String) {
        secureStore.set("spotify_access_token", accessToken)
        secureStore.set("spotify_refresh_token", refreshToken)
    }

    fun getSpotifyAccessTokenFlow(): Flow<String?> =
        secureStore.observe("spotify_access_token")

    suspend fun getSpotifyAccessToken(): String? =
        secureStore.get("spotify_access_token")

    suspend fun getSpotifyRefreshToken(): String? =
        secureStore.get("spotify_refresh_token")

    suspend fun clearSpotifyTokens() {
        secureStore.remove("spotify_access_token")
        secureStore.remove("spotify_refresh_token")
        ensureMigrated(); kv.remove(SPOTIFY_ACCESS_TOKEN_EXPIRES_AT)
    }

    /**
     * Epoch-millis when the current Spotify access token expires. Computed
     * as `now + expires_in * 1000` after every successful token response
     * (initial auth + refresh). Read by the foreground hook to proactively
     * refresh within a 5-minute window — moves the user-visible discovery
     * moment for a dead refresh token from "tap play, wait 23s in silence"
     * to "open app, banner immediately shows".
     *
     * Returns 0 if never set (treat as "expired / refresh now").
     */
    suspend fun getSpotifyAccessTokenExpiresAt(): Long {
        ensureMigrated(); return kv.getLong(SPOTIFY_ACCESS_TOKEN_EXPIRES_AT, default = 0L)
    }

    suspend fun setSpotifyAccessTokenExpiresAt(expiresAt: Long) {
        ensureMigrated(); kv.setLong(SPOTIFY_ACCESS_TOKEN_EXPIRES_AT, expiresAt)
    }

    // ── Last.fm session + username ───────────────────────────────────

    fun getLastFmSessionKeyFlow(): Flow<String?> =
        secureStore.observe("lastfm_session_key")

    suspend fun getLastFmSessionKey(): String? =
        secureStore.get("lastfm_session_key")

    suspend fun setLastFmSession(sessionKey: String) {
        secureStore.set("lastfm_session_key", sessionKey)
    }

    suspend fun clearLastFmSession() {
        secureStore.remove("lastfm_session_key")
        ensureMigrated()
        kv.remove(LASTFM_USERNAME)
    }

    suspend fun getLastFmUsername(): String? {
        ensureMigrated(); return kv.getStringOrNull(LASTFM_USERNAME)
    }

    fun getLastFmUsernameFlow(): Flow<String?> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(LASTFM_USERNAME)
    }

    suspend fun setLastFmUsername(username: String) {
        ensureMigrated(); kv.setString(LASTFM_USERNAME, username)
    }

    // ── ListenBrainz username + token ────────────────────────────────

    suspend fun getListenBrainzUsername(): String? {
        ensureMigrated(); return kv.getStringOrNull(LISTENBRAINZ_USERNAME)?.ifBlank { null }
    }

    fun getListenBrainzUsernameFlow(): Flow<String?> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(LISTENBRAINZ_USERNAME).map { it?.ifBlank { null } }
    }

    suspend fun setListenBrainzUsername(username: String) {
        ensureMigrated(); kv.setString(LISTENBRAINZ_USERNAME, username)
    }

    suspend fun clearListenBrainzUsername() {
        ensureMigrated(); kv.remove(LISTENBRAINZ_USERNAME)
    }

    suspend fun getListenBrainzToken(): String? = secureStore.get("listenbrainz_token")

    fun getListenBrainzTokenFlow(): Flow<String?> = secureStore.observe("listenbrainz_token")

    suspend fun setListenBrainzToken(token: String) {
        secureStore.set("listenbrainz_token", token)
    }

    suspend fun clearListenBrainzToken() {
        secureStore.remove("listenbrainz_token")
    }

    // ── SoundCloud (tokens + BYO credentials) ────────────────────────

    suspend fun setSoundCloudToken(token: String) {
        secureStore.set("soundcloud_access_token", token)
    }

    suspend fun setSoundCloudTokens(accessToken: String, refreshToken: String) {
        secureStore.set("soundcloud_access_token", accessToken)
        secureStore.set("soundcloud_refresh_token", refreshToken)
    }

    fun getSoundCloudTokenFlow(): Flow<String?> = secureStore.observe("soundcloud_access_token")

    suspend fun getSoundCloudToken(): String? = secureStore.get("soundcloud_access_token")

    suspend fun getSoundCloudRefreshToken(): String? = secureStore.get("soundcloud_refresh_token")

    suspend fun clearSoundCloudToken() {
        secureStore.remove("soundcloud_access_token")
        secureStore.remove("soundcloud_refresh_token")
    }

    // Spotify uses a BYO Developer Client ID (PKCE — no secret). Parachord ships
    // no key; the user creates a free app at developer.spotify.com/dashboard.
    suspend fun getSpotifyClientId(): String? = secureStore.get("spotify_client_id")
    fun getSpotifyClientIdFlow(): Flow<String?> = secureStore.observe("spotify_client_id")
    suspend fun setSpotifyClientId(clientId: String) { secureStore.set("spotify_client_id", clientId) }
    suspend fun clearSpotifyClientId() { secureStore.remove("spotify_client_id") }

    suspend fun setSoundCloudCredentials(clientId: String, clientSecret: String) {
        secureStore.set("soundcloud_client_id", clientId)
        secureStore.set("soundcloud_client_secret", clientSecret)
    }

    suspend fun getSoundCloudClientId(): String? = secureStore.get("soundcloud_client_id")

    suspend fun getSoundCloudClientSecret(): String? = secureStore.get("soundcloud_client_secret")

    fun getSoundCloudClientIdFlow(): Flow<String?> = secureStore.observe("soundcloud_client_id")

    suspend fun clearSoundCloudCredentials() {
        secureStore.remove("soundcloud_client_id")
        secureStore.remove("soundcloud_client_secret")
        secureStore.remove("soundcloud_access_token")
        secureStore.remove("soundcloud_refresh_token")
    }

    // ── Resolver order / active / volume offsets ─────────────────────

    suspend fun getResolverOrder(): List<String> {
        ensureMigrated()
        val raw = kv.getStringOrNull(RESOLVER_ORDER) ?: DEFAULT_RESOLVER_ORDER
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun getResolverOrderFlow(): Flow<List<String>> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(RESOLVER_ORDER).map { raw ->
            (raw ?: DEFAULT_RESOLVER_ORDER).split(",").filter { it.isNotBlank() }
        }
    }

    suspend fun setResolverOrder(order: List<String>) {
        ensureMigrated(); kv.setString(RESOLVER_ORDER, order.joinToString(","))
    }

    suspend fun getActiveResolvers(): List<String> {
        ensureMigrated()
        val raw = kv.getStringOrNull(ACTIVE_RESOLVERS) ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun getActiveResolversFlow(): Flow<List<String>> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(ACTIVE_RESOLVERS).map { raw ->
            raw?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        }
    }

    suspend fun setActiveResolvers(resolvers: List<String>) {
        ensureMigrated(); kv.setString(ACTIVE_RESOLVERS, resolvers.joinToString(","))
    }

    suspend fun getResolverVolumeOffsets(): Map<String, Int> {
        ensureMigrated()
        val raw = kv.getStringOrNull(RESOLVER_VOLUME_OFFSETS) ?: return defaultVolumeOffsets()
        return raw.split(",").associate { entry ->
            val parts = entry.split(":")
            parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }

    suspend fun setResolverVolumeOffsets(offsets: Map<String, Int>) {
        ensureMigrated()
        kv.setString(
            RESOLVER_VOLUME_OFFSETS,
            offsets.entries.joinToString(",") { (k, v) -> "$k:$v" },
        )
    }

    // ── Queue persistence ────────────────────────────────────────────

    suspend fun setPersistQueue(enabled: Boolean) {
        ensureMigrated(); kv.setBoolean(PERSIST_QUEUE, enabled)
    }

    suspend fun isPersistQueueEnabled(): Boolean {
        ensureMigrated(); return kv.getBoolean(PERSIST_QUEUE, default = true)
    }

    /** One-shot cross-provider track-dedup migration flag (#cross-provider-track-dedup). */
    override suspend fun getTrackDedupV1Done(): Boolean {
        ensureMigrated(); return kv.getBoolean(TRACK_DEDUP_V1_DONE, default = false)
    }
    override suspend fun setTrackDedupV1Done() {
        ensureMigrated(); kv.setBoolean(TRACK_DEDUP_V1_DONE, true)
    }

    suspend fun getPersistedQueueState(): String? {
        ensureMigrated(); return kv.getStringOrNull(PERSISTED_QUEUE_STATE)
    }

    suspend fun setPersistedQueueState(json: String) {
        ensureMigrated(); kv.setString(PERSISTED_QUEUE_STATE, json)
    }

    suspend fun clearPersistedQueueState() {
        ensureMigrated(); kv.remove(PERSISTED_QUEUE_STATE)
    }

    // ── Libre.fm session ─────────────────────────────────────────────

    suspend fun getLibreFmSessionKey(): String? = secureStore.get("librefm_session_key")

    fun getLibreFmSessionKeyFlow(): Flow<String?> = secureStore.observe("librefm_session_key")

    suspend fun setLibreFmSession(sessionKey: String) {
        secureStore.set("librefm_session_key", sessionKey)
    }

    suspend fun clearLibreFmSession() {
        secureStore.remove("librefm_session_key")
    }

    // ── Discogs token ────────────────────────────────────────────────

    suspend fun getDiscogsToken(): String? =
        secureStore.get("discogs_personal_token")?.ifBlank { null }

    suspend fun setDiscogsToken(token: String) {
        secureStore.set("discogs_personal_token", token)
    }

    suspend fun clearDiscogsToken() {
        secureStore.remove("discogs_personal_token")
    }

    // ── Apple Music ──────────────────────────────────────────────────

    fun getAppleMusicDeveloperTokenFlow(): Flow<String?> =
        secureStore.observe("apple_music_developer_token").map {
            it?.ifBlank { null } ?: appleMusicDeveloperTokenFallback.ifBlank { null }
        }

    suspend fun getAppleMusicDeveloperToken(): String? =
        secureStore.get("apple_music_developer_token")?.ifBlank { null }
            ?: appleMusicDeveloperTokenFallback.ifBlank { null }

    suspend fun setAppleMusicDeveloperToken(token: String) {
        secureStore.set("apple_music_developer_token", token)
    }

    suspend fun clearAppleMusicDeveloperToken() {
        secureStore.remove("apple_music_developer_token")
    }

    fun getAppleMusicStorefrontFlow(): Flow<String?> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(APPLE_MUSIC_STOREFRONT).map { it?.ifBlank { null } }
    }

    suspend fun getAppleMusicStorefront(): String? {
        ensureMigrated(); return kv.getStringOrNull(APPLE_MUSIC_STOREFRONT)?.ifBlank { null }
    }

    suspend fun setAppleMusicStorefront(storefront: String) {
        ensureMigrated(); kv.setString(APPLE_MUSIC_STOREFRONT, storefront)
    }

    /** Persisted Apple Music user token (MUT) — allows skipping re-auth on relaunch. */
    suspend fun getAppleMusicUserToken(): String? =
        secureStore.get("apple_music_user_token")?.ifBlank { null }

    suspend fun setAppleMusicUserToken(token: String) {
        secureStore.set("apple_music_user_token", token)
    }

    suspend fun clearAppleMusicUserToken() {
        secureStore.remove("apple_music_user_token")
    }

    // ── Preferred Spotify Device ─────────────────────────────────────

    suspend fun getPreferredSpotifyDeviceId(): String? {
        ensureMigrated(); return kv.getStringOrNull(PREFERRED_SPOTIFY_DEVICE_ID)
    }

    fun getPreferredSpotifyDeviceIdFlow(): Flow<String?> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(PREFERRED_SPOTIFY_DEVICE_ID)
    }

    suspend fun setPreferredSpotifyDeviceId(deviceId: String) {
        ensureMigrated(); kv.setString(PREFERRED_SPOTIFY_DEVICE_ID, deviceId)
    }

    suspend fun clearPreferredSpotifyDeviceId() {
        ensureMigrated(); kv.remove(PREFERRED_SPOTIFY_DEVICE_ID)
    }

    // ── Metadata provider enable/disable ─────────────────────────────

    suspend fun getDisabledMetaProviders(): Set<String> {
        ensureMigrated()
        val raw = kv.getStringOrNull(DISABLED_META_PROVIDERS) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun getDisabledMetaProvidersFlow(): Flow<Set<String>> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(DISABLED_META_PROVIDERS).map { raw ->
            raw?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }
    }

    suspend fun setDisabledMetaProviders(disabled: Set<String>) {
        ensureMigrated(); kv.setString(DISABLED_META_PROVIDERS, disabled.joinToString(","))
    }

    suspend fun setMetaProviderEnabled(providerName: String, enabled: Boolean) {
        val current = getDisabledMetaProviders().toMutableSet()
        if (enabled) current.remove(providerName) else current.add(providerName)
        setDisabledMetaProviders(current)
    }

    // ── Recommendation Blocklist ─────────────────────────────────────

    suspend fun getBlockedRecommendations(): Set<String> {
        ensureMigrated()
        val raw = kv.getStringOrNull(BLOCKED_RECOMMENDATIONS) ?: return emptySet()
        return raw.split("\n").filter { it.isNotBlank() }.toSet()
    }

    fun getBlockedRecommendationsFlow(): Flow<Set<String>> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(BLOCKED_RECOMMENDATIONS).map { raw ->
            raw?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }
    }

    suspend fun addBlockedRecommendation(entry: String) {
        ensureMigrated()
        val current = getBlockedRecommendations().toMutableSet()
        current.add(entry)
        kv.setString(BLOCKED_RECOMMENDATIONS, current.joinToString("\n"))
    }

    // ── Listening History for AI ─────────────────────────────────────

    suspend fun getSendListeningHistory(): Boolean {
        ensureMigrated(); return kv.getBoolean(SEND_LISTENING_HISTORY, default = true)
    }

    fun getSendListeningHistoryFlow(): Flow<Boolean> = migratedFlow().flatMapConcat {
        kv.observeBoolean(SEND_LISTENING_HISTORY, default = true)
    }

    suspend fun setSendListeningHistory(enabled: Boolean) {
        ensureMigrated(); kv.setBoolean(SEND_LISTENING_HISTORY, enabled)
    }

    // ── AI Providers ─────────────────────────────────────────────────

    suspend fun getAiProviderApiKey(providerId: String): String? {
        val secureKey = aiApiKeyName(providerId) ?: return null
        return secureStore.get(secureKey)?.ifBlank { null }
    }

    fun getAiProviderApiKeyFlow(providerId: String): Flow<String?> {
        val secureKey = aiApiKeyName(providerId) ?: return flowOf(null)
        return secureStore.observe(secureKey).map { it?.ifBlank { null } }
    }

    suspend fun setAiProviderApiKey(providerId: String, apiKey: String) {
        val secureKey = aiApiKeyName(providerId) ?: return
        secureStore.set(secureKey, apiKey)
    }

    suspend fun clearAiProviderApiKey(providerId: String) {
        val secureKey = aiApiKeyName(providerId) ?: return
        secureStore.remove(secureKey)
    }

    suspend fun getAiProviderModel(providerId: String): String {
        val key = aiModelKey(providerId) ?: return ""
        ensureMigrated()
        return kv.getStringOrNull(key) ?: ""
    }

    fun getAiProviderModelFlow(providerId: String): Flow<String> {
        val key = aiModelKey(providerId) ?: return flowOf("")
        return migratedFlow().flatMapConcat {
            kv.observeStringOrNull(key).map { it ?: "" }
        }
    }

    suspend fun setAiProviderModel(providerId: String, model: String) {
        val key = aiModelKey(providerId) ?: return
        ensureMigrated()
        kv.setString(key, model)
    }

    private fun aiApiKeyName(providerId: String): String? = when (providerId) {
        "chatgpt", "claude", "gemini" -> "ai_${providerId}_api_key"
        else -> null
    }

    private fun aiModelKey(providerId: String): String? = when (providerId) {
        "chatgpt" -> CHATGPT_MODEL
        "claude" -> CLAUDE_MODEL
        "gemini" -> GEMINI_MODEL
        else -> null
    }

    // ── Selected Chat Provider ───────────────────────────────────────

    suspend fun getSelectedChatProvider(): String? {
        ensureMigrated(); return kv.getStringOrNull(SELECTED_CHAT_PROVIDER)?.ifBlank { null }
    }

    suspend fun setSelectedChatProvider(providerId: String) {
        ensureMigrated(); kv.setString(SELECTED_CHAT_PROVIDER, providerId)
    }

    // ── Sync Settings (Phase 9B Stage 2 + Stage 3 unified marker) ────

    val syncEnabledFlow: Flow<Boolean> = migratedFlow().flatMapConcat {
        kv.observeBoolean(SYNC_ENABLED, default = false)
    }

    val lastSyncAtFlow: Flow<Long> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(SYNC_LAST_COMPLETED_AT).map { it?.toLongOrNull() ?: 0L }
    }

    fun getSyncSettingsFlow(): Flow<SyncSettings> = migratedFlow().flatMapConcat {
        combine(
            kv.observeBoolean(SYNC_ENABLED, default = false),
            kv.observeString(SYNC_PROVIDER, default = "spotify"),
            kv.observeBoolean(SYNC_TRACKS, default = true),
            kv.observeBoolean(SYNC_ALBUMS, default = true),
            kv.observeBoolean(SYNC_ARTISTS, default = true),
            kv.observeBoolean(SYNC_PLAYLISTS, default = true),
            kv.observeStringOrNull(SYNC_SELECTED_PLAYLIST_IDS),
            kv.observeBoolean(SYNC_PUSH_LOCAL_PLAYLISTS, default = true),
        ) { values ->
            SyncSettings(
                enabled = values[0] as Boolean,
                provider = values[1] as String,
                syncTracks = values[2] as Boolean,
                syncAlbums = values[3] as Boolean,
                syncArtists = values[4] as Boolean,
                syncPlaylists = values[5] as Boolean,
                selectedPlaylistIds = (values[6] as String? ?: "")
                    .split(",").filter { it.isNotBlank() }.toSet(),
                pushLocalPlaylists = values[7] as Boolean,
            )
        }
    }

    override suspend fun getSyncSettings(): SyncSettings {
        ensureMigrated()
        return SyncSettings(
            enabled = kv.getBoolean(SYNC_ENABLED, default = false),
            provider = kv.getString(SYNC_PROVIDER, default = "spotify"),
            syncTracks = kv.getBoolean(SYNC_TRACKS, default = true),
            syncAlbums = kv.getBoolean(SYNC_ALBUMS, default = true),
            syncArtists = kv.getBoolean(SYNC_ARTISTS, default = true),
            syncPlaylists = kv.getBoolean(SYNC_PLAYLISTS, default = true),
            selectedPlaylistIds = (kv.getStringOrNull(SYNC_SELECTED_PLAYLIST_IDS) ?: "")
                .split(",").filter { it.isNotBlank() }.toSet(),
            pushLocalPlaylists = kv.getBoolean(SYNC_PUSH_LOCAL_PLAYLISTS, default = true),
        )
    }

    suspend fun saveSyncSettings(settings: SyncSettings) {
        ensureMigrated()
        kv.setBoolean(SYNC_ENABLED, settings.enabled)
        kv.setString(SYNC_PROVIDER, settings.provider)
        kv.setBoolean(SYNC_TRACKS, settings.syncTracks)
        kv.setBoolean(SYNC_ALBUMS, settings.syncAlbums)
        kv.setBoolean(SYNC_ARTISTS, settings.syncArtists)
        kv.setBoolean(SYNC_PLAYLISTS, settings.syncPlaylists)
        kv.setString(SYNC_SELECTED_PLAYLIST_IDS, settings.selectedPlaylistIds.joinToString(","))
        kv.setBoolean(SYNC_PUSH_LOCAL_PLAYLISTS, settings.pushLocalPlaylists)
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        ensureMigrated(); kv.setBoolean(SYNC_ENABLED, enabled)
    }

    /** N-way multimaster playlist sync opt-in. Default OFF — gates the entire
     *  N-way path (migration bootstrap, shadow mode, propagation). */
    override suspend fun isNwayEnabled(): Boolean {
        ensureMigrated(); return kv.getBoolean(NWAY_ENABLED, default = false)
    }

    suspend fun setNwayEnabled(enabled: Boolean) {
        ensureMigrated(); kv.setBoolean(NWAY_ENABLED, enabled)
    }

    override suspend fun setLastSyncAt(timestamp: Long) {
        ensureMigrated(); kv.setString(SYNC_LAST_COMPLETED_AT, timestamp.toString())
    }

    override suspend fun getSyncDataVersion(): Int {
        ensureMigrated()
        return kv.getStringOrNull(SYNC_DATA_VERSION)?.toIntOrNull() ?: 0
    }

    override suspend fun setSyncDataVersion(version: Int) {
        ensureMigrated(); kv.setString(SYNC_DATA_VERSION, version.toString())
    }

    /**
     * The set of sync provider IDs the user has enabled. Default is
     * `setOf("spotify")`. Stored as a comma-separated string for KvStore
     * portability.
     */
    override suspend fun getEnabledSyncProviders(): Set<String> {
        ensureMigrated()
        return parseEnabledSyncProviders(kv.getStringOrNull(ENABLED_SYNC_PROVIDERS))
    }

    fun getEnabledSyncProvidersFlow(): Flow<Set<String>> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(ENABLED_SYNC_PROVIDERS).map(::parseEnabledSyncProviders)
    }

    suspend fun setEnabledSyncProviders(providers: Set<String>) {
        ensureMigrated(); kv.setString(ENABLED_SYNC_PROVIDERS, providers.joinToString(","))
    }

    private fun parseEnabledSyncProviders(raw: String?): Set<String> =
        if (raw.isNullOrBlank()) setOf("spotify")
        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** All recognized collection axis identifiers. Default-everything
     *  return value when no per-provider opt-in has been set. */
    private val ALL_SYNC_COLLECTIONS = setOf("tracks", "albums", "artists", "playlists")

    override suspend fun getSyncCollectionsForProvider(providerId: String): Set<String> {
        ensureMigrated()
        return parseSyncCollections(kv.getStringOrNull(syncCollectionsKey(providerId)))
    }

    fun getSyncCollectionsForProviderFlow(providerId: String): Flow<Set<String>> =
        migratedFlow().flatMapConcat {
            kv.observeStringOrNull(syncCollectionsKey(providerId)).map(::parseSyncCollections)
        }

    suspend fun setSyncCollectionsForProvider(providerId: String, collections: Set<String>) {
        ensureMigrated()
        kv.setString(syncCollectionsKey(providerId), collections.joinToString(","))
    }

    private fun parseSyncCollections(raw: String?): Set<String> =
        if (raw == null) ALL_SYNC_COLLECTIONS
        else if (raw.isBlank()) emptySet()
        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    override suspend fun getPlaylistSelection(
        providerId: String,
    ): com.parachord.shared.sync.ProviderPlaylistSelection {
        ensureMigrated()
        val rawMode = kv.getStringOrNull(playlistModeKey(providerId))
        val mode = when (rawMode) {
            "ALL" -> com.parachord.shared.sync.PlaylistSyncMode.ALL
            "NONE" -> com.parachord.shared.sync.PlaylistSyncMode.NONE
            "SELECTED" -> com.parachord.shared.sync.PlaylistSyncMode.SELECTED
            // Unset → provider default (LB=NONE, others=ALL).
            else -> com.parachord.shared.sync.ProviderPlaylistSelection.defaultMode(providerId)
        }
        val ids = (kv.getStringOrNull(playlistIdsKey(providerId)) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return com.parachord.shared.sync.ProviderPlaylistSelection(mode, ids)
    }

    override suspend fun setPlaylistSelection(
        providerId: String,
        selection: com.parachord.shared.sync.ProviderPlaylistSelection,
    ) {
        ensureMigrated()
        kv.setString(playlistModeKey(providerId), selection.mode.name)
        kv.setString(playlistIdsKey(providerId), selection.localPlaylistIds.joinToString(","))
    }

    override suspend fun getPullPlaylists(providerId: String): Set<String> {
        ensureMigrated()
        val raw = kv.getStringOrNull(pullPlaylistsKey(providerId))
        if (raw != null) return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // Migration: Spotify's pull allowlist used to be the global
        // SYNC_SELECTED_PLAYLIST_IDS. Seed from it so existing installs keep
        // their selection. Other providers default to empty (= import all).
        if (providerId == "spotify") {
            return (kv.getStringOrNull(SYNC_SELECTED_PLAYLIST_IDS) ?: "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        return emptySet()
    }

    override suspend fun setPullPlaylists(providerId: String, externalIds: Set<String>) {
        ensureMigrated()
        val csv = externalIds.joinToString(",")
        kv.setString(pullPlaylistsKey(providerId), csv)
        // Keep the legacy global key in sync for Spotify so any code still
        // reading SyncSettings.selectedPlaylistIds stays consistent.
        if (providerId == "spotify") kv.setString(SYNC_SELECTED_PLAYLIST_IDS, csv)
    }

    override suspend fun getPlaylistChannels(localPlaylistId: String): Set<String>? {
        ensureMigrated()
        val raw = kv.getStringOrNull(playlistChannelsKey(localPlaylistId)) ?: return null
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    override suspend fun setPlaylistChannels(localPlaylistId: String, channels: Set<String>?) {
        ensureMigrated()
        if (channels == null) {
            kv.remove(playlistChannelsKey(localPlaylistId))
        } else {
            // Empty-but-present override = "syncs with nothing" (fully local) —
            // distinct from null (= no override). Store a sentinel so an empty
            // set round-trips as empty, not as absent.
            kv.setString(playlistChannelsKey(localPlaylistId), if (channels.isEmpty()) " " else channels.joinToString(","))
        }
    }

    override suspend fun clearSyncSettings() {
        ensureMigrated()
        kv.remove(SYNC_ENABLED)
        kv.remove(SYNC_PROVIDER)
        kv.remove(SYNC_TRACKS)
        kv.remove(SYNC_ALBUMS)
        kv.remove(SYNC_ARTISTS)
        kv.remove(SYNC_PLAYLISTS)
        kv.remove(SYNC_SELECTED_PLAYLIST_IDS)
        kv.remove(SYNC_LAST_COMPLETED_AT)
        kv.remove(SYNC_PUSH_LOCAL_PLAYLISTS)
    }

    // ── Deleted Friends (prevent re-sync) ────────────────────────────

    suspend fun getDeletedFriendKeys(): Set<String> {
        ensureMigrated(); return kv.getStringSetCsv(DELETED_FRIEND_KEYS)
    }

    suspend fun addDeletedFriendKey(key: String) {
        ensureMigrated()
        val current = kv.getStringSetCsv(DELETED_FRIEND_KEYS).toMutableSet()
        current.add(key)
        kv.setStringSetCsv(DELETED_FRIEND_KEYS, current)
    }

    suspend fun removeDeletedFriendKey(key: String) {
        ensureMigrated()
        val current = kv.getStringSetCsv(DELETED_FRIEND_KEYS).toMutableSet()
        current.remove(key)
        kv.setStringSetCsv(DELETED_FRIEND_KEYS, current)
    }

    // ── Concert Location ─────────────────────────────────────────────

    data class ConcertLocation(
        val latitude: Double?,
        val longitude: Double?,
        val city: String?,
        val radiusMiles: Int,
    )

    suspend fun getConcertLocation(): ConcertLocation {
        ensureMigrated()
        return ConcertLocation(
            latitude = kv.getStringOrNull(CONCERT_LATITUDE)?.toDoubleOrNull(),
            longitude = kv.getStringOrNull(CONCERT_LONGITUDE)?.toDoubleOrNull(),
            city = kv.getStringOrNull(CONCERT_CITY),
            radiusMiles = kv.getStringOrNull(CONCERT_RADIUS)?.toIntOrNull() ?: 50,
        )
    }

    fun getConcertLocationFlow(): Flow<ConcertLocation> = migratedFlow().flatMapConcat {
        combine(
            kv.observeStringOrNull(CONCERT_LATITUDE),
            kv.observeStringOrNull(CONCERT_LONGITUDE),
            kv.observeStringOrNull(CONCERT_CITY),
            kv.observeStringOrNull(CONCERT_RADIUS),
        ) { lat, lon, city, radius ->
            ConcertLocation(
                latitude = lat?.toDoubleOrNull(),
                longitude = lon?.toDoubleOrNull(),
                city = city,
                radiusMiles = radius?.toIntOrNull() ?: 50,
            )
        }
    }

    suspend fun setConcertLocation(lat: Double, lon: Double, city: String, radiusMiles: Int = 50) {
        ensureMigrated()
        kv.setString(CONCERT_LATITUDE, lat.toString())
        kv.setString(CONCERT_LONGITUDE, lon.toString())
        kv.setString(CONCERT_CITY, city)
        kv.setString(CONCERT_RADIUS, radiusMiles.toString())
    }

    suspend fun setConcertRadius(radiusMiles: Int) {
        ensureMigrated(); kv.setString(CONCERT_RADIUS, radiusMiles.toString())
    }

    // ── Concert API Keys (BYO) ───────────────────────────────────────

    fun getTicketmasterApiKeyFlow(): Flow<String?> = secureStore.observe("ticketmaster_api_key")
    suspend fun getTicketmasterApiKey(): String? = secureStore.get("ticketmaster_api_key")
    suspend fun setTicketmasterApiKey(key: String) { secureStore.set("ticketmaster_api_key", key) }
    suspend fun clearTicketmasterApiKey() { secureStore.remove("ticketmaster_api_key") }

    fun getSeatGeekClientIdFlow(): Flow<String?> = secureStore.observe("seatgeek_client_id")
    suspend fun getSeatGeekClientId(): String? = secureStore.get("seatgeek_client_id")
    suspend fun setSeatGeekClientId(id: String) { secureStore.set("seatgeek_client_id", id) }
    suspend fun clearSeatGeekClientId() { secureStore.remove("seatgeek_client_id") }

    private fun defaultVolumeOffsets(): Map<String, Int> = mapOf(
        "spotify" to 0,
        "applemusic" to 0,
        "localfiles" to 0,
        "soundcloud" to 0,
        "bandcamp" to -3,
        "youtube" to -6,
    )

    // ── Plugin Sync ──────────────────────────────────────────────────

    suspend fun getLastPluginSyncTimestamp(): Long {
        ensureMigrated(); return kv.getLong(LAST_PLUGIN_SYNC, default = 0L)
    }

    suspend fun setLastPluginSyncTimestamp(timestamp: Long) {
        ensureMigrated(); kv.setLong(LAST_PLUGIN_SYNC, timestamp)
    }

    // ── Disabled Plugins ─────────────────────────────────────────────

    suspend fun getDisabledPlugins(): Set<String> {
        ensureMigrated()
        val raw = kv.getStringOrNull(DISABLED_PLUGINS) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun getDisabledPluginsFlow(): Flow<Set<String>> = migratedFlow().flatMapConcat {
        kv.observeStringOrNull(DISABLED_PLUGINS).map { raw ->
            raw?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        ensureMigrated()
        val current = getDisabledPlugins().toMutableSet()
        if (enabled) current.remove(pluginId) else current.add(pluginId)
        kv.setString(DISABLED_PLUGINS, current.joinToString(","))
    }

    // ── Loved-tracks push (issue #125) ────────────────────────────────

    /**
     * Return the per-service love-push enabled map. Unrecognized keys are
     * preserved (forward-compat) but only `lastfm` / `listenbrainz` are
     * meaningful today. Default for any unset service = `false` (defaults
     * OFF per desktop's design doc).
     */
    suspend fun getLovePushEnabled(): Map<String, Boolean> {
        ensureMigrated()
        val raw = kv.getStringOrNull(LOVE_PUSH_ENABLED) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val service = parts[0].trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val flag = parts[1].trim().toBooleanStrictOrNull() ?: return@mapNotNull null
            service to flag
        }.toMap()
    }

    /** Flow variant for reactive Settings UI. */
    fun getLovePushEnabledFlow(): Flow<Map<String, Boolean>> = migratedFlow().flatMapConcat {
        kv.observeString(LOVE_PUSH_ENABLED, default = "").map { raw ->
            if (raw.isBlank()) emptyMap()
            else raw.split(",").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 2) return@mapNotNull null
                val service = parts[0].trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val flag = parts[1].trim().toBooleanStrictOrNull() ?: return@mapNotNull null
                service to flag
            }.toMap()
        }
    }

    /** Set the toggle for a single service; preserves other services' state. */
    suspend fun setLovePushEnabled(service: String, enabled: Boolean) {
        ensureMigrated()
        val current = getLovePushEnabled().toMutableMap()
        current[service] = enabled
        kv.setString(
            LOVE_PUSH_ENABLED,
            current.entries.joinToString(",") { (s, b) -> "$s:$b" },
        )
    }

    /**
     * Idempotency cache: `trackId -> {service: epochMs}` of completed pushes.
     * JSON-encoded so per-service timestamps round-trip cleanly.
     *
     * Format:
     * ```json
     * { "<trackId>": { "lastfm": 1234567890123, "listenbrainz": 1234567891000 } }
     * ```
     *
     * Returns an empty map on no entry / parse failure (corrupted prefs
     * shouldn't block the love-push flow). Reads are infrequent — the
     * service caches in-memory after the first read.
     */
    suspend fun getLovePushedKeys(): Map<String, Map<String, Long>> {
        ensureMigrated()
        val raw = kv.getStringOrNull(LOVE_PUSHED_KEYS) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
            element.mapValues { (_, inner) ->
                inner.jsonObject.mapValues { (_, ts) ->
                    ts.jsonPrimitive.long
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Persist the idempotency map. Called per-track after each successful push. */
    suspend fun setLovePushedKeys(map: Map<String, Map<String, Long>>) {
        ensureMigrated()
        val obj = kotlinx.serialization.json.buildJsonObject {
            for ((trackId, services) in map) {
                putJsonObject(trackId) {
                    for ((service, ts) in services) {
                        put(service, ts)
                    }
                }
            }
        }
        kv.setString(LOVE_PUSHED_KEYS, obj.toString())
    }
}

/**
 * File-scoped typealias for source compatibility with code that writes
 * `SyncSettings(...)` after importing it from the settings package.
 * The canonical type lives at [com.parachord.shared.sync.SyncSettings].
 */
typealias SyncSettings = com.parachord.shared.sync.SyncSettings
