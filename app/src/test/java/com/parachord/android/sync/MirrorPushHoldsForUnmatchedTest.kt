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
import com.parachord.shared.sync.canonicalTrackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #807: a legacy push replaces the mirror's whole list with the tracks that carry
 * the service's id. A track only waiting to be matched (its search unanswered
 * during a rate-limit pause, past the per-sync budget, or never searched) was
 * left out of that list, so the push removed it from the mirror: a 26-track
 * Spotify playlist went to 1. The push now waits until every track is matched or
 * has been searched and found missing.
 */
class MirrorPushHoldsForUnmatchedTest {

    private val providerId = "applemusic"

    private class RecordingProvider(
        override val id: String,
        private val search: (String) -> String?,
    ) : SyncProvider {
        override val displayName = "Recording ($id)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.DateString)
        val pushes = mutableListOf<List<String>>()

        override suspend fun searchForTrackId(title: String, artist: String, album: String?, isrc: String?): String? = search(title)
        override suspend fun fetchPlaylists(onProgress: ((current: Int, total: Int) -> Unit)?) = listOf(
            SyncedPlaylist(
                entity = Playlist(id = "$id-ext-1", name = "Mix"),
                spotifyId = "ext-1",
                snapshotId = "new-snap",
                trackCount = 3,
                isOwned = true,
            ),
        )
        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String {
            pushes += externalTrackIds
            return "new-snap"
        }
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

        /** One track the mirror already has an id for, two it doesn't. */
        val matched = PlaylistTrack(playlistId = "local-1", position = 0, trackTitle = "Kept", trackArtist = "A", trackAppleMusicId = "111")
        val unmatched = listOf(
            PlaylistTrack(playlistId = "local-1", position = 1, trackTitle = "Two", trackArtist = "B"),
            PlaylistTrack(playlistId = "local-1", position = 2, trackTitle = "Three", trackArtist = "C"),
        )

        suspend fun seed(providerId: String) {
            playlistDao.insert(
                Playlist(id = "local-1", name = "Mix", trackCount = 3, createdAt = 1_000L, updatedAt = 1_000L,
                    lastModified = 9_000L, locallyModified = true),
            )
            playlistTrackDao.insertAll(listOf(matched) + unmatched)
            syncSourceDao.insert(SyncSource(itemId = "local-1", itemType = "playlist", providerId = providerId, externalId = "ext-1", syncedAt = 1_000L))
            linkDao.upsertWithSnapshot(localPlaylistId = "local-1", providerId = providerId, externalId = "ext-1", snapshotId = "old-snap")
        }
    }

    private class FakeSettings(private val providerId: String) : SyncSettingsProvider {
        private var dataVersion = 5
        override suspend fun getSyncSettings() = SyncSettings(enabled = true, syncTracks = false, syncAlbums = false,
            syncArtists = false, syncPlaylists = true, pushLocalPlaylists = true)
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
        override suspend fun isNwayEnabled(): Boolean = false
        override suspend fun isNwayPropagateEnabled(): Boolean = false
    }

    @Test
    fun `an unanswered search holds the push instead of shrinking the mirror`() = runBlocking {
        val provider = RecordingProvider(providerId) { _ -> throw TrackIdLookupUnreachableException("429") }
        val h = Harness(provider, providerId)
        h.seed(providerId)

        assertTrue(h.engine.syncAll().success)
        assertEquals("nothing may replace the mirror with 1 of 3 tracks", emptyList<List<String>>(), provider.pushes)
        assertTrue("the playlist stays flagged so the next sync pushes it", h.playlistDao.getById("local-1")!!.locallyModified)
    }

    @Test
    fun `tracks searched and not found don't hold the push`() = runBlocking {
        val provider = RecordingProvider(providerId) { _ -> null }
        val h = Harness(provider, providerId)
        h.seed(providerId)

        assertTrue(h.engine.syncAll().success)
        assertEquals("the catalog lacks the other two, so the push sends what it has",
            listOf(listOf("111")), provider.pushes)
    }

    @Test
    fun `a miss cached by an earlier sync doesn't hold the push`() = runBlocking {
        // Unanswered now, but both were searched before and not found.
        val provider = RecordingProvider(providerId) { _ -> throw TrackIdLookupUnreachableException("429") }
        val h = Harness(provider, providerId)
        h.seed(providerId)
        for (t in h.unmatched) h.idCache.upsert(canonicalTrackKey(t), providerId, null, System.currentTimeMillis(), 1L)

        assertTrue(h.engine.syncAll().success)
        assertEquals(listOf(listOf("111")), provider.pushes)
    }

    @Test
    fun `every track matched pushes the whole list`() = runBlocking {
        val provider = RecordingProvider(providerId) { title -> "id-$title" }
        val h = Harness(provider, providerId)
        h.seed(providerId)

        assertTrue(h.engine.syncAll().success)
        assertEquals(listOf(listOf("111", "id-Two", "id-Three")), provider.pushes)
    }
}
