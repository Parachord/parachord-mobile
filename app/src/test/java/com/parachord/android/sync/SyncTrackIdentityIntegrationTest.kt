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
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.SyncSource
import com.parachord.shared.model.Track
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
import com.parachord.shared.sync.SyncedTrack
import com.parachord.shared.sync.TrackTombstoneService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Library sync links an incoming track to the row that already holds it, through
 * the real [SyncEngine.syncAll] and an in-memory database (#682, desktop
 * parachord#1404 / #1419). Uses a non-Spotify provider id so the generic
 * `syncTracksForProvider` → `applyTrackDiff` path runs.
 */
class SyncTrackIdentityIntegrationTest {
    private val pid = "applemusic"

    private class Harness(val pid: String) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val trackDao = TrackDao(db, driver)
        val syncSourceDao = SyncSourceDao(db)
        val provider = FakeProvider(pid)
        val engine = SyncEngine(
            db = db, driver = driver, trackDao = trackDao,
            albumDao = AlbumDao(db), artistDao = ArtistDao(db),
            playlistDao = PlaylistDao(db), playlistTrackDao = PlaylistTrackDao(db),
            syncSourceDao = syncSourceDao,
            syncPlaylistLinkDao = SyncPlaylistLinkDao(db), syncPlaylistSourceDao = SyncPlaylistSourceDao(db),
            syncPlaylistBaselineDao = SyncPlaylistBaselineDao(db), syncPlaylistNwayDao = SyncPlaylistNwayDao(db),
            trackProviderIdCacheDao = TrackProviderIdCacheDao(db),
            settingsStore = Settings(pid), providers = listOf(provider),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )
        fun rows() = runBlocking { trackDao.getAllOnce() }
        fun sources() = runBlocking { syncSourceDao.getByProvider(pid, "track") }
    }

    private class FakeProvider(override val id: String) : SyncProvider {
        override val displayName = "Fake"
        override val features = ProviderFeatures(snapshots = SnapshotKind.None)
        var library: List<SyncedTrack> = emptyList()
        override fun nativeTrackId(track: Track): String? = track.appleMusicId
        override suspend fun fetchTracks(
            localCount: Int, latestExternalId: String?, onProgress: ((current: Int, total: Int) -> Unit)?,
        ): List<SyncedTrack> = library
        override suspend fun fetchPlaylists(onProgress: ((current: Int, total: Int) -> Unit)?) = emptyList<SyncedPlaylist>()
        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = emptyList<PlaylistTrack>()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?) = RemoteCreated(externalId = "x", snapshotId = null)
        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    private class Settings(private val pid: String) : SyncSettingsProvider {
        private var dataVersion = 5
        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true, syncTracks = true, syncAlbums = false, syncArtists = false,
            syncPlaylists = false, pushLocalPlaylists = false,
        )
        override suspend fun getEnabledSyncProviders() = setOf(pid)
        override suspend fun getSyncCollectionsForProvider(providerId: String) =
            if (providerId == pid) setOf("tracks") else emptySet()
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
        override suspend fun isNwayEnabled(): Boolean = false
        override suspend fun isNwayPropagateEnabled(): Boolean = false
    }

    private fun synced(lib: String, catalog: String, title: String, artist: String, album: String?) =
        SyncedTrack(
            Track(id = "isrc-$catalog", title = title, artist = artist, album = album, appleMusicId = catalog),
            spotifyId = lib, addedAt = 1_000,
        )

    @Test
    fun `a hearted track then synced is one row that keeps its id and album`() = runBlocking {
        val h = Harness(pid)
        h.trackDao.insert(Track(id = "search-creep", title = "Creep", artist = "Radiohead", album = null, addedAt = 500))
        h.provider.library = listOf(synced("i.1", "111", "Creep", "Radiohead", "Pablo Honey"))

        h.engine.syncAll()

        val rows = h.rows()
        assertEquals(1, rows.size)
        assertEquals("search-creep", rows.single().id)
        assertNull("the row keeps its own (no) album", rows.single().album)
        assertEquals("111", rows.single().appleMusicId)
        assertEquals("search-creep", h.sources().single().itemId)

        h.engine.syncAll()
        assertEquals("a second sync keeps the link and adds nothing", listOf("search-creep"), h.rows().map { it.id })
        assertEquals(1, h.sources().size)
    }

    @Test
    fun `an existing duplicate pair heals into the row the user saved`() = runBlocking {
        val h = Harness(pid)
        h.trackDao.insert(Track(id = "search-creep", title = "Creep", artist = "Radiohead", album = "Pablo Honey", addedAt = 900))
        h.trackDao.insert(
            Track(id = "isrc-111", title = "Creep", artist = "Radiohead", album = "Pablo Honey", appleMusicId = "111", addedAt = 100),
        )
        h.syncSourceDao.insert(SyncSource(itemId = "isrc-111", itemType = "track", providerId = pid, externalId = "i.1", addedAt = 100))
        h.provider.library = listOf(synced("i.1", "111", "Creep", "Radiohead", "Pablo Honey"))

        h.engine.syncAll()

        assertEquals(listOf("search-creep"), h.rows().map { it.id })
        assertEquals("search-creep", h.sources().single().itemId)
        assertEquals("i.1", h.sources().single().externalId)
        assertNotNull(h.trackDao.getById("search-creep")?.appleMusicId)
    }

    @Test
    fun `a track removed from the remote library leaves the matched row's link`() = runBlocking {
        val h = Harness(pid)
        h.trackDao.insert(Track(id = "search-creep", title = "Creep", artist = "Radiohead", addedAt = 500))
        h.provider.library = listOf(synced("i.1", "111", "Creep", "Radiohead", "Pablo Honey"))
        h.engine.syncAll()
        assertEquals(1, h.sources().size)

        h.provider.library = emptyList()
        h.engine.syncAll()

        assertEquals("the removal follows the matched row", 0, h.sources().size)
    }

    @Test
    fun `two remote copies of one recording add one row`() = runBlocking {
        val h = Harness(pid)
        h.provider.library = listOf(
            synced("i.1", "111", "Creep", "Radiohead", "Pablo Honey"),
            synced("i.2", "111", "Creep", "Radiohead", "Pablo Honey"),
        )
        h.engine.syncAll()
        h.engine.syncAll()
        assertEquals(1, h.rows().size)
        assertEquals(1, h.sources().size)
    }

    @Test
    fun `two saved entries under one playable id survive repeated syncs`() = runBlocking {
        val h = Harness(pid)
        h.trackDao.insert(Track(id = "isrc-A", title = "Your Misfortune", artist = "Mike Doughty", album = "Haughty Melodic", appleMusicId = "5"))
        h.trackDao.insert(Track(id = "isrc-B", title = "Your Misfortune", artist = "Mike Doughty", album = "Haughty Melodic (Deluxe Remaster)", appleMusicId = "5"))
        fun entry(id: String, album: String) = SyncedTrack(
            Track(id = id, title = "Your Misfortune", artist = "Mike Doughty", album = album, appleMusicId = "5"),
            spotifyId = "i.5", addedAt = 1_000,
        )
        h.provider.library = listOf(entry("isrc-A", "Haughty Melodic"), entry("isrc-B", "Haughty Melodic (Deluxe Remaster)"))
        h.engine.syncAll()
        h.provider.library = h.provider.library.reversed()
        h.engine.syncAll()
        h.engine.syncAll()
        assertEquals(setOf("isrc-A", "isrc-B"), h.rows().map { it.id }.toSet())
        assertEquals(2, h.sources().size)
    }
}
