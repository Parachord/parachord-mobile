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
import com.parachord.shared.model.SyncSource
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brick #3 of parachord-mobile#289: the `new`-mode mutual-exclusion guard in
 * `SyncEngine.syncPlaylists`. When `sync_engine_mode == "new"` the N-way
 * reconcile is the SOLE playlist authority, so the LEGACY playlist push/import
 * must stand down. In `legacy` / `shadow` the legacy path runs unchanged.
 *
 * The guard reads `getSyncEngineMode()` directly, so these tests isolate it by
 * overriding the mode while keeping the N-way booleans false — that keeps
 * `runNwayPropagation` inert, so what's asserted is PURELY the legacy stand-down
 * (N-way's own write behavior is covered by the materialize/propagation tests).
 * Drives the public `syncAll` entry point with a non-Spotify provider so the
 * generic pull → UPDATE → `pushPlaylist` path runs (the legacy import/push).
 */
class NewModeGuardTest {

    private val providerId = "applemusic"

    private class Harness(provider: SyncProvider, settings: SyncSettingsProvider) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val playlistDao = PlaylistDao(db)
        val playlistTrackDao = PlaylistTrackDao(db)
        val syncSourceDao = SyncSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val sourceDao = SyncPlaylistSourceDao(db)

        val engine = SyncEngine(
            db = db,
            driver = driver,
            trackDao = TrackDao(db, driver),
            albumDao = AlbumDao(db),
            artistDao = ArtistDao(db),
            playlistDao = playlistDao,
            playlistTrackDao = playlistTrackDao,
            syncSourceDao = syncSourceDao,
            syncPlaylistLinkDao = linkDao,
            syncPlaylistSourceDao = sourceDao,
            syncPlaylistBaselineDao = SyncPlaylistBaselineDao(db),
            syncPlaylistNwayDao = SyncPlaylistNwayDao(db),
            trackProviderIdCacheDao = TrackProviderIdCacheDao(db),
            settingsStore = settings,
            providers = listOf(provider),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )

        suspend fun seedLocallyModified(localId: String, externalId: String, name: String, providerId: String) {
            playlistDao.insert(
                Playlist(id = localId, name = name, trackCount = 1, createdAt = 1_000L,
                    updatedAt = 1_000L, lastModified = 9_000L, locallyModified = true),
            )
            playlistTrackDao.insertAll(
                listOf(PlaylistTrack(playlistId = localId, position = 0, trackTitle = "$name track",
                    trackArtist = "Artist", trackAppleMusicId = "am-$externalId-0")),
            )
            syncSourceDao.insert(SyncSource(itemId = localId, itemType = "playlist",
                providerId = providerId, externalId = externalId, syncedAt = 1_000L))
            linkDao.upsertWithSnapshot(localPlaylistId = localId, providerId = providerId,
                externalId = externalId, snapshotId = "old-snap")
        }
    }

    /** Records every legacy push (`replacePlaylistTracks`). Remote reports a NEW snapshot so the UPDATE branch fires. */
    private class RecordingProvider(override val id: String) : SyncProvider {
        override val displayName = "Recording ($id)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.DateString)
        val replaced = mutableListOf<String>()

        override suspend fun fetchPlaylists(onProgress: ((Int, Int) -> Unit)?): List<SyncedPlaylist> =
            listOf(SyncedPlaylist(entity = Playlist(id = "$id-ext1", name = "P1"),
                spotifyId = "ext1", snapshotId = "new-snap-ext1", trackCount = 1, isOwned = true))

        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? {
            replaced.add(externalPlaylistId); return "new-snap-$externalPlaylistId"
        }

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = emptyList<PlaylistTrack>()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?) = RemoteCreated(externalId = "x", snapshotId = null)
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    private class FakeSettings(
        private val providerId: String,
        private val mode: String,
    ) : SyncSettingsProvider {
        private var dataVersion = 5
        // Override the mode directly; keep the N-way booleans false so runNwayPropagation
        // stays inert and we isolate the legacy stand-down (see class doc).
        override suspend fun getSyncEngineMode(): String = mode
        override suspend fun isNwayEnabled(): Boolean = false
        override suspend fun isNwayPropagateEnabled(): Boolean = false
        override suspend fun getSyncSettings() = SyncSettings(enabled = true, syncTracks = false,
            syncAlbums = false, syncArtists = false, syncPlaylists = true, pushLocalPlaylists = true)
        override suspend fun getEnabledSyncProviders() = setOf(providerId)
        override suspend fun getSyncCollectionsForProvider(providerId: String) =
            if (providerId == this.providerId) setOf("playlists") else emptySet()
        override suspend fun getSyncDataVersion() = dataVersion
        override suspend fun setSyncDataVersion(version: Int) { dataVersion = version }
        override suspend fun setLastSyncAt(timestamp: Long) {}
        override suspend fun clearSyncSettings() {}
        override suspend fun getPlaylistSelection(providerId: String) = ProviderPlaylistSelection(PlaylistSyncMode.ALL)
        override suspend fun setPlaylistSelection(providerId: String, selection: ProviderPlaylistSelection) {}
        override suspend fun getPullPlaylists(providerId: String): Set<String> = emptySet()
        override suspend fun setPullPlaylists(providerId: String, externalIds: Set<String>) {}
        override suspend fun getPlaylistChannels(localPlaylistId: String): Set<String>? = null
        override suspend fun setPlaylistChannels(localPlaylistId: String, channels: Set<String>?) {}
        override suspend fun getTrackDedupV1Done(): Boolean = true
        override suspend fun setTrackDedupV1Done() {}
    }

    private fun runMode(mode: String): List<String> = runBlocking {
        val provider = RecordingProvider(providerId)
        val h = Harness(provider, FakeSettings(providerId, mode))
        h.seedLocallyModified("local-1", "ext1", "P1", providerId)
        val result = h.engine.syncAll()
        assertTrue("sync must succeed in $mode (error=${result.error})", result.success)
        provider.replaced
    }

    @Test
    fun `legacy mode runs the legacy push`() {
        assertEquals(listOf("ext1"), runMode("legacy"))
    }

    @Test
    fun `shadow mode runs the legacy push`() {
        assertEquals(listOf("ext1"), runMode("shadow"))
    }

    @Test
    fun `new mode stands the legacy push down`() {
        assertEquals(
            "in `new` the legacy push/import must stand down — N-way is the sole authority",
            emptyList<String>(),
            runMode("new"),
        )
    }
}
