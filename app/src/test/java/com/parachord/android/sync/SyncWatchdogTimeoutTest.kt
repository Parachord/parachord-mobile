package com.parachord.android.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.SyncPlaylistBaselineDao
import com.parachord.shared.db.dao.SyncPlaylistLinkDao
import com.parachord.shared.db.dao.SyncPlaylistNwayDao
import com.parachord.shared.db.dao.SyncPlaylistSourceDao
import com.parachord.shared.db.dao.SyncSourceDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.db.dao.TrackProviderIdCacheDao
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.PlaylistSyncMode
import com.parachord.shared.sync.ProviderFeatures
import com.parachord.shared.sync.ProviderPlaylistSelection
import com.parachord.shared.sync.RemoteCreated
import com.parachord.shared.sync.SnapshotKind
import com.parachord.shared.sync.SyncEngine
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncSettings
import com.parachord.shared.sync.SyncSettingsProvider
import com.parachord.shared.sync.SyncedPlaylist
import com.parachord.shared.sync.TrackTombstoneService
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for Fix #1: a HUNG sync must not hold [SyncEngine]'s
 * mutex + "syncing" indicator forever.
 *
 * On-device, a MusicBrainz 503 tight-retry could wedge the sync coroutine so it
 * never returned. Because `syncAll` resets `_syncing` and unlocks `syncMutex`
 * only in a `finally`, a never-returning body meant the `finally` never ran —
 * the app reported "already running" permanently and no manual sync could ever
 * start again.
 *
 * The fix wraps the sync body in `withTimeout(SYNC_WATCHDOG_TIMEOUT_MS)`. On
 * timeout, `withTimeout` cancels the body, the `finally` still runs (cancellation
 * runs finally blocks), the mutex is released, and the indicator clears.
 *
 * This test shrinks the watchdog (the const is a plain `var` for exactly this)
 * and drives a provider whose first reachable suspend hangs forever. We assert
 * `syncAll` RETURNS (doesn't hang the test) with `success=false`, the indicator
 * is cleared, and a SUBSEQUENT sync is NOT blocked (mutex released).
 */
class SyncWatchdogTimeoutTest {

    private val originalTimeout = SyncEngine.SYNC_WATCHDOG_TIMEOUT_MS

    @After
    fun restoreTimeout() {
        SyncEngine.SYNC_WATCHDOG_TIMEOUT_MS = originalTimeout
    }

    private class Harness(provider: SyncProvider, val settings: HangingSettings) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            ParachordDb.Schema.create(it)
        }
        val db = ParachordDb(driver)

        val engine = SyncEngine(
            db = db,
            driver = driver,
            trackDao = TrackDao(db, driver),
            albumDao = AlbumDao(db),
            artistDao = ArtistDao(db),
            playlistDao = PlaylistDao(db),
            playlistTrackDao = PlaylistTrackDao(db),
            syncSourceDao = SyncSourceDao(db),
            syncPlaylistLinkDao = SyncPlaylistLinkDao(db),
            syncPlaylistSourceDao = SyncPlaylistSourceDao(db),
            syncPlaylistBaselineDao = SyncPlaylistBaselineDao(db),
            syncPlaylistNwayDao = SyncPlaylistNwayDao(db),
            trackProviderIdCacheDao = TrackProviderIdCacheDao(db),
            settingsStore = settings,
            providers = listOf(provider),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )
    }

    /**
     * `getEnabledSyncProviders()` is called inside `syncPlaylists` as a bare
     * top-level statement (NOT inside any broad `catch (e: Exception)`), so when
     * the watchdog cancels mid-await the `TimeoutCancellationException`
     * propagates straight to `syncAll`'s outer catch — no inner catch can
     * swallow it. The FIRST call (during the early collection-sync axes, all
     * disabled here) returns normally; once [armed] is set we hang, which
     * happens on the playlist axis.
     */
    private class HangingSettings(private val providerId: String) : SyncSettingsProvider {
        @Volatile var armed = false
        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true,
            syncTracks = false,
            syncAlbums = false,
            syncArtists = false,
            syncPlaylists = true,
            pushLocalPlaylists = true,
        )
        override suspend fun getEnabledSyncProviders(): Set<String> {
            if (armed) awaitCancellation() // hang forever until the watchdog cancels us
            return setOf(providerId)
        }
        override suspend fun getSyncCollectionsForProvider(providerId: String) =
            if (providerId == this.providerId) setOf("playlists") else emptySet()
        override suspend fun getSyncDataVersion() = 5
        override suspend fun setSyncDataVersion(version: Int) {}
        override suspend fun setLastSyncAt(timestamp: Long) {}
        override suspend fun clearSyncSettings() {}
        override suspend fun getPlaylistSelection(providerId: String) =
            ProviderPlaylistSelection(PlaylistSyncMode.ALL)
        override suspend fun setPlaylistSelection(providerId: String, selection: ProviderPlaylistSelection) {}
        override suspend fun getPullPlaylists(providerId: String): Set<String> = emptySet()
        override suspend fun setPullPlaylists(providerId: String, externalIds: Set<String>) {}
        override suspend fun getPlaylistChannels(localPlaylistId: String): Set<String>? = null
        override suspend fun setPlaylistChannels(localPlaylistId: String, channels: Set<String>?) {}
        override suspend fun getTrackDedupV1Done(): Boolean = true
        override suspend fun setTrackDedupV1Done() {}
        override suspend fun isNwayEnabled(): Boolean = false
        override suspend fun isNwayPropagateEnabled(): Boolean = false
    }

    private class NoopProvider(override val id: String) : SyncProvider {
        override val displayName = "Noop ($id)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.DateString)
        override suspend fun fetchPlaylists(
            onProgress: ((current: Int, total: Int) -> Unit)?,
        ): List<SyncedPlaylist> = emptyList()
        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = emptyList<PlaylistTrack>()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?) =
            RemoteCreated(externalId = "x", snapshotId = null)
        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    @Test
    fun `hung sync times out, returns failure, and releases mutex + indicator`() = runBlocking {
        // Short watchdog so the test is fast. The body hangs forever; the
        // watchdog must cancel it well under the test's own safety timeout.
        SyncEngine.SYNC_WATCHDOG_TIMEOUT_MS = 300L

        val provider = NoopProvider("applemusic")
        val settings = HangingSettings(provider.id)
        val h = Harness(provider, settings)
        settings.armed = true

        // Outer safety net so a regression (watchdog not firing) FAILS the test
        // instead of hanging the whole suite forever.
        val result = withTimeout(10_000L) { h.engine.syncAll() }

        // (a) syncAll returned a failure instead of hanging.
        assertFalse("a hung sync must report failure, not success", result.success)
        assertEquals("Sync timed out", result.error)

        // (b) the "syncing" UI indicator was cleared by the finally.
        assertFalse(
            "the syncing indicator must be cleared after the watchdog aborts",
            h.engine.syncing.value,
        )

        // (c) the mutex was released — a SUBSEQUENT sync is NOT blocked. Disarm
        // the hang so the next cycle runs to completion (success). If the mutex
        // were still held, this would return SYNC_IN_PROGRESS.
        settings.armed = false
        val second = h.engine.syncAll()
        assertTrue(
            "the mutex must be released after a watchdog timeout so the next sync can run " +
                "(error=${second.error})",
            second.success,
        )
        assertFalse(h.engine.syncing.value)
    }
}
