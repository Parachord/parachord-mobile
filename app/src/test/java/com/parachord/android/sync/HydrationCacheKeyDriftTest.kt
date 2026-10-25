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
import com.parachord.shared.sync.AppleMusicSyncProvider
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.ListenBrainzSyncProvider
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
import com.parachord.shared.sync.canonicalTrackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The track-id cache is keyed by `canonicalTrackKey(track)`: the recording MBID
 * when the row has one, else `norm-artist|title`. ListenBrainz's native id IS the
 * recording MBID, so when the legacy push hydrates a track for LB it writes the
 * MBID onto the `playlist_tracks` row — and that row's key moves from `norm-…`
 * to `mbid-…`. Every other provider's entry (a resolved id, or a miss with its
 * 7→30-day cooldown) stayed under the old key, so the next sync couldn't see it:
 * Apple Music re-searched a track it had already failed to find, and the
 * cooldown ladder restarted at 7 days.
 */
class HydrationCacheKeyDriftTest {

    private val am = AppleMusicSyncProvider.PROVIDER_ID
    private val lb = ListenBrainzSyncProvider.PROVIDER_ID

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
                snapshotId = "new-snap",
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

    private class Harness(private val providers: List<SyncProvider>) {
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
            settingsStore = FakeSettings(providers.map { it.id }.toSet()),
            providers = providers,
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )

        /** One track with no Apple Music id and no MBID yet. */
        val track = PlaylistTrack(playlistId = "local-1", position = 0, trackTitle = "Song", trackArtist = "Artist")

        suspend fun seed() {
            playlistDao.insert(
                Playlist(
                    id = "local-1", name = "Mix", trackCount = 1, createdAt = 1_000L, updatedAt = 1_000L,
                    lastModified = 9_000L, locallyModified = true,
                ),
            )
            playlistTrackDao.insertAll(listOf(track))
            for (p in providers) {
                syncSourceDao.insert(
                    SyncSource(itemId = "local-1", itemType = "playlist", providerId = p.id, externalId = "ext-1", syncedAt = 1_000L),
                )
                linkDao.upsertWithSnapshot(localPlaylistId = "local-1", providerId = p.id, externalId = "ext-1", snapshotId = "old-snap")
            }
        }

        /** A later local edit, so the next sync pushes the playlist again. */
        suspend fun editLocally() {
            playlistDao.update(playlistDao.getById("local-1")!!.copy(lastModified = 20_000L, locallyModified = true))
        }

        suspend fun row(): PlaylistTrack = playlistTrackDao.getByPlaylistIdSync("local-1").single()
    }

    private class FakeSettings(private val enabled: Set<String>) : SyncSettingsProvider {
        private var dataVersion = 5
        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true,
            syncTracks = false,
            syncAlbums = false,
            syncArtists = false,
            syncPlaylists = true,
            pushLocalPlaylists = true,
        )
        override suspend fun getEnabledSyncProviders() = enabled
        override suspend fun getSyncCollectionsForProvider(providerId: String) =
            if (providerId in enabled) setOf("playlists") else emptySet()
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
    fun `an LB-backfilled MBID keeps the Apple Music miss cooldown`() = runBlocking {
        val appleMusic = SearchProvider(am) { null }                         // not in the AM catalog
        val listenBrainz = SearchProvider(lb) { "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0" }
        // AM pushes before LB, so AM hydrates while the row has no MBID yet.
        val h = Harness(listOf(appleMusic, listenBrainz))
        h.seed()

        assertTrue(h.engine.syncAll().success)
        assertEquals("AM searched once and missed", 1, appleMusic.searches)
        val row = h.row()
        assertEquals("LB hydration wrote the MBID onto the row",
            "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", row.trackRecordingMbid)
        assertTrue("the row's cache key moved to the MBID tier", canonicalTrackKey(row).startsWith("mbid-"))

        h.editLocally()
        assertTrue(h.engine.syncAll().success)
        assertEquals("AM's miss is still in its cooldown — no second search", 1, appleMusic.searches)

        val amEntry = h.idCache.select(canonicalTrackKey(row), am)
        assertNotNull("AM's miss is readable under the row's current key", amEntry)
        assertNull(amEntry!!.resolvedId)
        assertEquals(1L, amEntry.attempts)
        val lbEntry = h.idCache.select(canonicalTrackKey(row), lb)
        assertEquals("LB's own hit moved with the row", row.trackRecordingMbid, lbEntry?.resolvedId)
    }
}
