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
import com.parachord.shared.sync.SpotifySyncProvider
import com.parachord.shared.sync.SyncEngine
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncSettings
import com.parachord.shared.sync.SyncSettingsProvider
import com.parachord.shared.sync.SyncedPlaylist
import com.parachord.shared.sync.TrackTombstoneService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning one provider's sync off leaves the others syncing, and Spotify syncs
 * only while its own switch is on.
 *
 * Android's Spotify "Stop syncing" used to wipe every sync setting and cancel the
 * background schedule while Apple Music and ListenBrainz were still on, and it
 * left Spotify in `enabled_sync_providers`; the engine read that set alone, so
 * it kept syncing Spotify under a card that read "Syncing disabled". The same
 * set read also synced the Spotify library of an Android user who had only
 * turned on Apple Music (the wizard adds to the set's default of Spotify).
 */
class StopOneSyncProviderTest {

    private class Harness(providers: List<SyncProvider>, val settings: FakeSettings) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val playlistDao = PlaylistDao(db)
        val syncSourceDao = SyncSourceDao(db)

        val engine = SyncEngine(
            db = db,
            driver = driver,
            trackDao = TrackDao(db, driver),
            albumDao = AlbumDao(db),
            artistDao = ArtistDao(db),
            playlistDao = playlistDao,
            playlistTrackDao = PlaylistTrackDao(db),
            syncSourceDao = syncSourceDao,
            syncPlaylistLinkDao = SyncPlaylistLinkDao(db),
            syncPlaylistSourceDao = SyncPlaylistSourceDao(db),
            syncPlaylistBaselineDao = SyncPlaylistBaselineDao(db),
            syncPlaylistNwayDao = SyncPlaylistNwayDao(db),
            trackProviderIdCacheDao = TrackProviderIdCacheDao(db),
            settingsStore = settings,
            providers = providers,
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )
    }

    /** A provider with no remote playlists. */
    private class EmptyProvider(override val id: String) : SyncProvider {
        override val displayName = id
        override val features = ProviderFeatures(snapshots = SnapshotKind.DateString)
        override suspend fun fetchPlaylists(onProgress: ((Int, Int) -> Unit)?) = emptyList<SyncedPlaylist>()
        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = emptyList<PlaylistTrack>()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?) = RemoteCreated(externalId = "x", snapshotId = null)
        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    private class FakeSettings(
        var legacyEnabled: Boolean,
        var enabled: Set<String>,
    ) : SyncSettingsProvider {
        private var dataVersion = 5
        override suspend fun getSyncEngineMode(): String = "legacy"
        override suspend fun isNwayEnabled(): Boolean = false
        override suspend fun isNwayPropagateEnabled(): Boolean = false
        override suspend fun getSyncSettings() = SyncSettings(enabled = legacyEnabled, syncTracks = true,
            syncAlbums = false, syncArtists = false, syncPlaylists = true, pushLocalPlaylists = false)
        override suspend fun getEnabledSyncProviders() = enabled
        override suspend fun setEnabledSyncProviders(providers: Set<String>) { enabled = providers }
        override suspend fun getSyncCollectionsForProvider(providerId: String) = setOf("tracks", "playlists")
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

    private fun spotifyMock(): SpotifySyncProvider = mockk(relaxed = true) {
        every { id } returns SpotifySyncProvider.PROVIDER_ID
        every { displayName } returns "Spotify"
        every { features } returns ProviderFeatures(snapshots = SnapshotKind.Opaque)
    }

    @Test
    fun `stopping one provider leaves the others in the set and their synced items`() = runBlocking {
        val settings = FakeSettings(legacyEnabled = false, enabled = setOf("applemusic", "listenbrainz"))
        val h = Harness(listOf(EmptyProvider("applemusic"), EmptyProvider("listenbrainz")), settings)
        for ((id, provider) in listOf("applemusic-a" to "applemusic", "listenbrainz-b" to "listenbrainz")) {
            h.playlistDao.insert(Playlist(id = id, name = id, createdAt = 1L, updatedAt = 1L))
            h.syncSourceDao.insert(SyncSource(itemId = id, itemType = "playlist", providerId = provider,
                externalId = id, syncedAt = 1L))
        }

        val remaining = h.engine.disableProvider("applemusic", removeItems = false)

        assertEquals(setOf("listenbrainz"), remaining)
        assertEquals(setOf("listenbrainz"), settings.enabled)
        assertTrue(h.syncSourceDao.getAllByProvider("applemusic").isEmpty())
        assertEquals(1, h.syncSourceDao.getAllByProvider("listenbrainz").size)
        // Keep: the playlist stays, only its sync source goes.
        assertTrue(h.playlistDao.getById("applemusic-a") != null)
    }

    @Test
    fun `Spotify in the set with its switch off isn't asked for anything`() = runBlocking {
        val spotify = spotifyMock()
        val settings = FakeSettings(legacyEnabled = false, enabled = setOf("spotify", "applemusic"))
        val h = Harness(listOf(spotify, EmptyProvider("applemusic")), settings)

        val result = h.engine.syncAll()

        assertTrue("sync must succeed (error=${result.error})", result.success)
        coVerify(exactly = 0) { spotify.fetchPlaylists(any()) }
        coVerify(exactly = 0) { spotify.fetchTracks(any(), any(), any()) }
    }

    @Test
    fun `Spotify with its switch on is still synced`() = runBlocking {
        val spotify = spotifyMock()
        val settings = FakeSettings(legacyEnabled = true, enabled = setOf("spotify", "applemusic"))
        val h = Harness(listOf(spotify, EmptyProvider("applemusic")), settings)

        h.engine.syncAll()

        coVerify(atLeast = 1) { spotify.fetchPlaylists(any()) }
    }
}
