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
 * #802: a pull sets `locallyModified` when the playlist has other mirrors, so the
 * next push loop catches them up. The pull source's own branch must not read that
 * flag as a local edit and push the tracks back to the source: Apple Music can't
 * replace a playlist's tracks, so the push appended every track again, the next pull
 * brought the copies home, and the ListenBrainz mirror grew to 1,182 entries of 341
 * songs. Only a real local edit (`lastModified` after the source's last sync) goes
 * back to the source, as desktop's `hasRealLocalEdits` decides.
 */
class NoPushBackToPullSourceTest {

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

        /** A playlist pulled from [providerId] that also has a ListenBrainz mirror. */
        suspend fun seed(lastModified: Long, sourceSyncedAt: Long) {
            playlistDao.insert(
                Playlist(id = "applemusic-ext1", name = "Starred", trackCount = 1, createdAt = 1_000L,
                    updatedAt = 1_000L, lastModified = lastModified, locallyModified = true),
            )
            playlistTrackDao.insertAll(
                listOf(PlaylistTrack(playlistId = "applemusic-ext1", position = 0, trackTitle = "Song",
                    trackArtist = "Artist", trackAppleMusicId = "111")),
            )
            syncSourceDao.insert(SyncSource(itemId = "applemusic-ext1", itemType = "playlist",
                providerId = "applemusic", externalId = "ext1", syncedAt = sourceSyncedAt))
            sourceDao.upsert(localPlaylistId = "applemusic-ext1", providerId = "applemusic",
                externalId = "ext1", snapshotId = "snap", ownerId = null, syncedAt = sourceSyncedAt)
            linkDao.upsertWithSnapshot(localPlaylistId = "applemusic-ext1", providerId = "applemusic",
                externalId = "ext1", snapshotId = "snap")
            linkDao.upsertWithSnapshot(localPlaylistId = "applemusic-ext1", providerId = "listenbrainz",
                externalId = "lb-mbid", snapshotId = "lb-snap")
        }
    }

    /** The remote is unchanged (same snapshot and track count) and records every push. */
    private class SourceProvider(override val id: String) : SyncProvider {
        override val displayName = "Source ($id)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.DateString)
        val replaced = mutableListOf<String>()

        override suspend fun fetchPlaylists(onProgress: ((Int, Int) -> Unit)?): List<SyncedPlaylist> =
            listOf(SyncedPlaylist(entity = Playlist(id = "$id-ext1", name = "Starred"),
                spotifyId = "ext1", snapshotId = "snap", trackCount = 1, isOwned = true))

        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? {
            replaced.add(externalPlaylistId); return "snap"
        }

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = emptyList<PlaylistTrack>()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = "snap"
        override suspend fun createPlaylist(name: String, description: String?) = RemoteCreated(externalId = "x", snapshotId = null)
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    private class FakeSettings(private val providerId: String) : SyncSettingsProvider {
        private var dataVersion = 5
        override suspend fun getSyncEngineMode(): String = "legacy"
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

    private fun run(lastModified: Long, sourceSyncedAt: Long): List<String> = runBlocking {
        val provider = SourceProvider(providerId)
        val h = Harness(provider, FakeSettings(providerId))
        h.seed(lastModified = lastModified, sourceSyncedAt = sourceSyncedAt)
        val result = h.engine.syncAll()
        assertTrue("sync must succeed (error=${result.error})", result.success)
        provider.replaced
    }

    @Test
    fun `a flag set by the pull itself is not pushed back to the pull source`() {
        // The pull stamped lastModified and then the source's syncedAt, so nothing
        // changed locally since the source last agreed.
        assertEquals(emptyList<String>(), run(lastModified = 5_000L, sourceSyncedAt = 5_000L))
    }

    @Test
    fun `a real local edit after the last pull still goes to the pull source`() {
        assertEquals(listOf("ext1"), run(lastModified = 9_000L, sourceSyncedAt = 5_000L))
    }
}
