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
 * Regression guard for Fix #2: a single playlist's push failure must NOT abort
 * the whole sync cycle.
 *
 * `pushPlaylist` (the locally-modified UPDATE path, used for hosted-XSPF rows
 * and any locally-edited mirror) is invoked from the import/pull branches,
 * which are NOT wrapped per-playlist. Before the fix, an Apple Music HTTP 400
 * from `replacePlaylistTracks` there threw all the way to the top-level
 * `syncAll` catch and ABORTED the entire sync — so every playlist queued behind
 * the failing one silently stopped syncing. The sibling
 * `pushPlaylistsForProvider` loop already catches per-playlist; `pushPlaylist`
 * must be equally resilient.
 *
 * This drives the public [SyncEngine.syncAll] entry point with a fake provider
 * whose `replacePlaylistTracks` THROWS for ONE playlist's external id, and
 * asserts: (a) the sync completes (no throw escapes), and (b) the OTHER
 * locally-modified playlist still gets pushed.
 */
class PushPlaylistResilienceTest {

    // Non-Spotify id so the inline-Spotify pull path is bypassed and the
    // generic `pullPlaylistsForProvider` → UPDATE → `pushPlaylist` path runs.
    private val providerId = "applemusic"

    private class Harness(provider: SyncProvider, private val providerId: String) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            ParachordDb.Schema.create(it)
        }
        val db = ParachordDb(driver)

        val trackDao = TrackDao(db, driver)
        val albumDao = AlbumDao(db)
        val artistDao = ArtistDao(db)
        val playlistDao = PlaylistDao(db)
        val playlistTrackDao = PlaylistTrackDao(db)
        val syncSourceDao = SyncSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val sourceDao = SyncPlaylistSourceDao(db)
        val baselineDao = SyncPlaylistBaselineDao(db)
        val nwayDao = SyncPlaylistNwayDao(db)

        val engine = SyncEngine(
            db = db,
            driver = driver,
            trackDao = trackDao,
            albumDao = albumDao,
            artistDao = artistDao,
            playlistDao = playlistDao,
            playlistTrackDao = playlistTrackDao,
            syncSourceDao = syncSourceDao,
            syncPlaylistLinkDao = linkDao,
            syncPlaylistSourceDao = sourceDao,
            syncPlaylistBaselineDao = baselineDao,
            syncPlaylistNwayDao = nwayDao,
            trackProviderIdCacheDao = TrackProviderIdCacheDao(db),
            settingsStore = FakeSettings(providerId),
            providers = listOf(provider),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )

        /**
         * Seed a locally-modified playlist that the pull loop will route to
         * `pushPlaylist`: a `sync_source` row (so the remote matches an existing
         * local), a `sync_playlist_link` (so `pushPlaylist` resolves the
         * external id), one track, and `locallyModified = true`.
         */
        suspend fun seedLocallyModified(localId: String, externalId: String, name: String) {
            playlistDao.insert(
                Playlist(
                    id = localId,
                    name = name,
                    trackCount = 1,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    lastModified = 9_000L,
                    locallyModified = true,
                ),
            )
            playlistTrackDao.insertAll(
                listOf(
                    PlaylistTrack(
                        playlistId = localId,
                        position = 0,
                        trackTitle = "$name track",
                        trackArtist = "Artist",
                        trackAppleMusicId = "am-$externalId-0",
                    ),
                ),
            )
            syncSourceDao.insert(
                SyncSource(
                    itemId = localId,
                    itemType = "playlist",
                    providerId = providerId,
                    externalId = externalId,
                    syncedAt = 1_000L,
                ),
            )
            linkDao.upsertWithSnapshot(
                localPlaylistId = localId,
                providerId = providerId,
                externalId = externalId,
                snapshotId = "old-snap",
            )
        }
    }

    /**
     * Fake provider whose remote list contains both playlists; its
     * `replacePlaylistTracks` THROWS for [throwForExternalId] and records the
     * call for every other id. Each remote reports a *different* snapshot than
     * the seeded link so the UPDATE branch's `remoteSnapshotId != localSnapshotId`
     * fires and `pushPlaylist` runs.
     */
    private class ThrowingProvider(
        override val id: String,
        private val throwForExternalId: String,
        private val remoteExternalIds: List<Pair<String, String>>, // externalId to name
    ) : SyncProvider {
        override val displayName = "Throwing ($id)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.DateString)

        val replaced = mutableListOf<String>()

        override suspend fun fetchPlaylists(
            onProgress: ((current: Int, total: Int) -> Unit)?,
        ): List<SyncedPlaylist> = remoteExternalIds.map { (extId, name) ->
            SyncedPlaylist(
                entity = Playlist(id = "$id-$extId", name = name),
                spotifyId = extId,            // external-id slot
                snapshotId = "new-snap-$extId", // != "old-snap" → UPDATE fires
                trackCount = 1,
                isOwned = true,
            )
        }

        override suspend fun replacePlaylistTracks(
            externalPlaylistId: String,
            externalTrackIds: List<String>,
        ): String? {
            if (externalPlaylistId == throwForExternalId) {
                throw RuntimeException("AM HTTP 400 on replacePlaylistTracks for $externalPlaylistId")
            }
            replaced.add(externalPlaylistId)
            return "new-snap-$externalPlaylistId"
        }

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = emptyList<PlaylistTrack>()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?) =
            RemoteCreated(externalId = "x", snapshotId = null)
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
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
    fun `one playlist push failure does not abort sync — sibling still pushes`() = runBlocking {
        val provider = ThrowingProvider(
            id = providerId,
            throwForExternalId = "ext-bad",
            remoteExternalIds = listOf("ext-bad" to "Bad Playlist", "ext-good" to "Good Playlist"),
        )
        val h = Harness(provider, providerId)
        h.seedLocallyModified("local-bad", "ext-bad", "Bad Playlist")
        h.seedLocallyModified("local-good", "ext-good", "Good Playlist")

        // Must not throw, AND the cycle must report success — without the
        // pushPlaylist try/catch the throw bubbles to syncAll's top-level catch,
        // which returns success=false and skips setLastSyncAt + every remaining
        // playlist/provider.
        val result = h.engine.syncAll()

        assertTrue(
            "REGRESSION GUARD: a single playlist's push failure must NOT fail the whole sync " +
                "(error=${result.error})",
            result.success,
        )
        // The good playlist's push still ran despite the bad one throwing.
        assertTrue(
            "the surviving playlist must still be pushed after the sibling's push failed",
            "ext-good" in provider.replaced,
        )
        assertEquals(
            "only the good playlist was successfully replaced (the bad one threw)",
            listOf("ext-good"),
            provider.replaced,
        )
    }
}
