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
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.ListenBrainzSyncProvider
import com.parachord.shared.sync.AppleMusicSyncProvider
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
 * Two-provider partial-coverage safety harness (reconciliation redesign Step 0/3).
 * Models a playlist mirrored to ListenBrainz (coverable — tracks key off
 * recordingMbid) AND Apple Music (un-hydratable here — searchForTrackId returns
 * null and the tracks carry no appleMusicId, so its hydrated coverage is 0).
 *
 * The decisive safety question before re-enabling real-writes: when ONE provider
 * can't be covered, does propagation ever DROP a track that no copy deleted? It
 * must not — the skipped provider is simply left behind (retried later), never a
 * deletion source.
 */
class NwayPartialCoverageTest {

    /** Generic remote-modeling fake. [hydrateFn] = what its searchForTrackId
     *  returns (null = can't hydrate that track). Mutable so a test can flip a
     *  provider from coverable (mirror gets created) to un-coverable (update
     *  skipped) mid-run. */
    private class FakeProvider(
        override val id: String,
        var hydrateFn: ((title: String) -> String?)? = null,
    ) : SyncProvider {
        override val displayName = "$id (fake)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.None)

        class Entry(val externalId: String, val name: String) {
            val tracks = mutableListOf<String>()
        }
        val remote = linkedMapOf<String, Entry>()
        var createCount = 0
            private set
        val replaceCalls = mutableListOf<Pair<String, List<String>>>()

        override suspend fun createPlaylist(name: String, description: String?): RemoteCreated {
            createCount++
            val ext = "$id-ext-$createCount"
            remote[ext] = Entry(ext, name)
            return RemoteCreated(externalId = ext, snapshotId = null)
        }

        override suspend fun fetchPlaylists(
            onProgress: ((current: Int, total: Int) -> Unit)?,
        ): List<SyncedPlaylist> = remote.values.map { e ->
            SyncedPlaylist(
                entity = Playlist(id = "$id-${e.externalId}", name = e.name),
                spotifyId = e.externalId,
                snapshotId = null,
                trackCount = e.tracks.size,
                isOwned = true,
            )
        }

        override suspend fun replacePlaylistTracks(
            externalPlaylistId: String,
            externalTrackIds: List<String>,
        ): String? {
            replaceCalls.add(externalPlaylistId to externalTrackIds)
            remote[externalPlaylistId]?.let { it.tracks.clear(); it.tracks.addAll(externalTrackIds) }
            return null
        }

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String): List<PlaylistTrack> =
            remote[externalPlaylistId]?.tracks?.mapIndexed { i, ext ->
                // Reflect the id back into the column this provider keys on, so a
                // round-trip is self-consistent.
                when (id) {
                    AppleMusicSyncProvider.PROVIDER_ID ->
                        PlaylistTrack(playlistId = "$id-$externalPlaylistId", position = i, trackTitle = "", trackArtist = "", trackAppleMusicId = ext)
                    else ->
                        PlaylistTrack(playlistId = "$id-$externalPlaylistId", position = i, trackTitle = "", trackArtist = "", trackRecordingMbid = ext)
                }
            } ?: emptyList()

        override suspend fun searchForTrackId(title: String, artist: String, album: String?, isrc: String?): String? =
            hydrateFn?.invoke(title)

        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    private class FakeSyncSettings(private val enabled: Set<String>) : SyncSettingsProvider {
        private var dataVersion = 5
        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true, syncTracks = false, syncAlbums = false, syncArtists = false,
            syncPlaylists = true, pushLocalPlaylists = true,
        )
        override suspend fun getEnabledSyncProviders() = enabled
        override suspend fun getSyncCollectionsForProvider(providerId: String) = setOf("playlists")
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
        override suspend fun isNwayEnabled(): Boolean = true
        override suspend fun isNwayPropagateEnabled(): Boolean = true
    }

    private class Harness {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val trackDao = TrackDao(db, driver)
        val playlistDao = PlaylistDao(db)
        val playlistTrackDao = PlaylistTrackDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val baselineDao = SyncPlaylistBaselineDao(db)
        val nwayDao = SyncPlaylistNwayDao(db)

        // LB is coverable (keys off recordingMbid, which the seeded tracks have).
        val lb = FakeProvider(ListenBrainzSyncProvider.PROVIDER_ID)
        // Apple Music starts coverable (so its mirror gets created), then a test
        // flips hydrateFn = { null } to model hydration failing on UPDATE.
        val am = FakeProvider(AppleMusicSyncProvider.PROVIDER_ID, hydrateFn = { title -> "am-$title" })

        val engine = SyncEngine(
            db = db, driver = driver, trackDao = trackDao, albumDao = AlbumDao(db), artistDao = ArtistDao(db),
            playlistDao = playlistDao, playlistTrackDao = playlistTrackDao, syncSourceDao = SyncSourceDao(db),
            syncPlaylistLinkDao = linkDao, syncPlaylistSourceDao = SyncPlaylistSourceDao(db),
            syncPlaylistBaselineDao = baselineDao, syncPlaylistNwayDao = nwayDao,
            settingsStore = FakeSyncSettings(setOf(ListenBrainzSyncProvider.PROVIDER_ID, AppleMusicSyncProvider.PROVIDER_ID)),
            providers = listOf(lb, am),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )

        suspend fun seed(id: String, name: String, count: Int) {
            playlistDao.insert(Playlist(id = id, name = name, trackCount = count, createdAt = 1_000L, updatedAt = 1_000L, lastModified = 1_000L, locallyModified = false))
            playlistTrackDao.insertAll((0 until count).map { i ->
                PlaylistTrack(playlistId = id, position = i, trackTitle = "$name $i", trackArtist = "Artist $i", trackRecordingMbid = "mbid-$id-$i")
            })
        }
    }

    @Test
    fun `partial coverage never drops a track no copy deleted`() = runBlocking {
        val h = Harness()
        h.seed("local-0", "Mixed", 3)

        h.engine.syncAll() // create + link LB + AM mirrors (AM coverable here)
        h.engine.syncAll() // migrate baseline = 3

        // Now AM hydration FAILS (e.g. iTunes/catalog down) → its UPDATE can't be
        // covered, while LB stays coverable.
        h.am.hydrateFn = { null }

        // Add a 4th track locally (has recordingMbid → LB-coverable; AM can't hydrate).
        h.playlistTrackDao.insertAll(listOf(
            PlaylistTrack(playlistId = "local-0", position = 3, trackTitle = "Mixed 3", trackArtist = "Artist 3", trackRecordingMbid = "mbid-local-0-3"),
        ))
        val cur = h.playlistDao.getById("local-0")!!
        h.playlistDao.update(cur.copy(trackCount = 4, lastModified = 2_000L, locallyModified = true))

        h.engine.runNwayPropagation()

        // SINGLE-CYCLE SAFETY (holds today): nothing is dropped this cycle. LB
        // (fully coverable) gets all 4; local keeps all 4; AM keeps the 3 it can
        // address (the new track has no AM catalog id — an accepted N-1 gap, the
        // gap being < the mass-change threshold so AM isn't skipped).
        val lbMbid = h.linkDao.selectForLink("local-0", ListenBrainzSyncProvider.PROVIDER_ID)!!.externalId
        assertEquals("LB (coverable) gets the full 4-track merged list", 4, h.lb.remote[lbMbid]!!.tracks.size)
        assertEquals("local keeps all 4 tracks", 4, h.playlistTrackDao.getByPlaylistIdSync("local-0").size)

        val amExt = h.linkDao.selectForLink("local-0", AppleMusicSyncProvider.PROVIDER_ID)?.externalId
            ?: error("AM mirror was not linked — test would be vacuous")
        assertEquals("AM holds its coverable subset (catalog gap on the new track)", 3, h.am.remote[amExt]!!.tracks.size)

        // ⚠️ KNOWN-DEFERRED RESIDUAL (documented, NOT yet fixed — real-writes stays
        // OFF because of it): the baseline ADVANCES to 4 here even though AM holds
        // only 3. Next cycle AM (3 tracks) reads as having DELETED the 4th vs the
        // advanced baseline → delete-always-wins union-removes it → data loss,
        // despite local + LB still having it. This is the per-provider catalog-gap
        // semantics the reconciliation redesign defers (Step 3 / pending-push +
        // catalog-gap exclusion). Asserting current behavior so a future fix
        // flips it deliberately:
        assertEquals(
            "TODAY: baseline advances past AM's catalog gap (the deferred catalog-gap bug)",
            4,
            h.baselineDao.selectForLocal("local-0")!!.tracks.size,
        )
    }
}
