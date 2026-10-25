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
import com.parachord.shared.sync.TrackIdLookupUnreachableException
import com.parachord.shared.sync.SpotifySyncProvider
import com.parachord.shared.sync.canonicalTrackKey
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.SpotifyRateLimitedException
import com.parachord.shared.api.SpSearchResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A catalog search that nobody ANSWERED (429, rate-limit cooldown, 5xx, network)
 * must not be recorded as "this track isn't on the service". Before this fix,
 * Spotify's search swallowed every error and returned null, and the sync's
 * track-id cache then parked the track in the 7→30-day miss cooldown — during
 * Spotify's multi-hour cooldown, up to a dozen tracks per cycle dropped out of
 * their mirrors for up to a month without a request being sent. The legacy push
 * path also ignored [TrackIdLookupUnreachableException] (#394) entirely.
 */
class HydrationUnreachableTest {

    private val providerId = "applemusic"

    /** Provider whose only interesting surface is [searchForTrackId]. */
    private class SearchProvider(
        override val id: String,
        private val search: () -> String?,
    ) : SyncProvider {
        override val displayName = "Search ($id)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.DateString)
        var searches = 0

        override suspend fun searchForTrackId(title: String, artist: String, album: String?, isrc: String?): String? {
            searches++
            return search()
        }

        override suspend fun fetchPlaylists(onProgress: ((current: Int, total: Int) -> Unit)?) = listOf(
            SyncedPlaylist(
                entity = Playlist(id = "$id-ext-1", name = "Mix"),
                spotifyId = "ext-1",
                snapshotId = "new-snap", // != the seeded link's → the UPDATE push runs
                trackCount = 1,
                isOwned = true,
            ),
        )
        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>) = "new-snap"
        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = emptyList<PlaylistTrack>()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?) = RemoteCreated("x", null)
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    private class Harness(provider: SyncProvider, providerId: String) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val playlistDao = PlaylistDao(db)
        val playlistTrackDao = PlaylistTrackDao(db)
        val syncSourceDao = SyncSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val idCache = TrackProviderIdCacheDao(db)

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
            syncPlaylistSourceDao = SyncPlaylistSourceDao(db),
            syncPlaylistBaselineDao = SyncPlaylistBaselineDao(db),
            syncPlaylistNwayDao = SyncPlaylistNwayDao(db),
            trackProviderIdCacheDao = idCache,
            settingsStore = FakeSettings(providerId),
            providers = listOf(provider),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )

        /** A locally-modified, linked playlist with one track that has NO Apple Music id. */
        val track = PlaylistTrack(playlistId = "local-1", position = 0, trackTitle = "Song", trackArtist = "Artist")

        suspend fun seed(providerId: String) {
            playlistDao.insert(
                Playlist(
                    id = "local-1", name = "Mix", trackCount = 1, createdAt = 1_000L, updatedAt = 1_000L,
                    lastModified = 9_000L, locallyModified = true,
                ),
            )
            playlistTrackDao.insertAll(listOf(track))
            syncSourceDao.insert(
                SyncSource(itemId = "local-1", itemType = "playlist", providerId = providerId, externalId = "ext-1", syncedAt = 1_000L),
            )
            linkDao.upsertWithSnapshot(localPlaylistId = "local-1", providerId = providerId, externalId = "ext-1", snapshotId = "old-snap")
        }
    }

    private class FakeSettings(private val providerId: String) : SyncSettingsProvider {
        private var dataVersion = 5
        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true,
            syncTracks = false,
            syncAlbums = false,
            syncArtists = false,
            syncPlaylists = true,
            pushLocalPlaylists = true,
        )
        override suspend fun getEnabledSyncProviders() = setOf(providerId)
        override suspend fun getSyncCollectionsForProvider(providerId: String) =
            if (providerId == this.providerId) setOf("playlists") else emptySet()
        override suspend fun getSyncDataVersion() = dataVersion
        override suspend fun setSyncDataVersion(version: Int) { dataVersion = version }
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

    @Test
    fun `legacy push — an unreachable search leaves the track pending, no miss recorded`() = runBlocking {
        val provider = SearchProvider(providerId) { throw TrackIdLookupUnreachableException("429") }
        val h = Harness(provider, providerId)
        h.seed(providerId)

        assertTrue(h.engine.syncAll().success)
        assertEquals("the push path searched for the missing id", 1, provider.searches)
        assertNull(
            "a lookup nobody answered must not stamp the 7→30-day miss cooldown",
            h.idCache.select(canonicalTrackKey(h.track), providerId),
        )
    }

    @Test
    fun `legacy push — a real no-match still records a miss`() = runBlocking {
        val provider = SearchProvider(providerId) { null }
        val h = Harness(provider, providerId)
        h.seed(providerId)

        assertTrue(h.engine.syncAll().success)
        val entry = h.idCache.select(canonicalTrackKey(h.track), providerId)
        assertNotNull("an answered no-match is a miss", entry)
        assertEquals(1L, entry!!.attempts)
    }

    @Test
    fun `Spotify — a rate-limited search is unreachable, not a miss`() {
        val client = mockk<SpotifyClient> {
            coEvery { search(any(), any(), any(), any(), any()) } throws SpotifyRateLimitedException(3600)
        }
        assertThrows(TrackIdLookupUnreachableException::class.java) {
            runBlocking { SpotifySyncProvider(client).searchForTrackId("Song", "Artist", null, null) }
        }
    }

    @Test
    fun `Spotify — an answered empty search is a plain miss and asks to see 5xx as errors`() = runBlocking {
        val client = mockk<SpotifyClient> {
            coEvery { search(any(), any(), any(), any(), any()) } returns SpSearchResponse()
        }
        assertNull(SpotifySyncProvider(client).searchForTrackId("Song", "Artist", null, null))
        coVerify { client.search(any(), any(), any(), any(), throwOnServerError = true) }
    }
}
