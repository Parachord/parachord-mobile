package com.parachord.android.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.SyncPlaylistLinkDao
import com.parachord.shared.db.dao.SyncPlaylistSourceDao
import com.parachord.shared.db.dao.SyncSourceDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.ListenBrainzSyncProvider
import com.parachord.shared.sync.ProviderFeatures
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
import org.junit.Test

/**
 * Skip-unchanged short-circuit for the ListenBrainz playlist push
 * (CLAUDE.md "ListenBrainz Playlist Sync — Interop Contract", skip-unchanged
 * rule). Pairs with [SyncEngineIdempotencyTest]: that file asserts sync×2 ⇒
 * zero new `createPlaylist` calls; this file asserts sync×2 ⇒ zero
 * `replacePlaylistTracks` (delete-all + add-all) calls for an UNCHANGED
 * playlist.
 *
 * Flagged by the Achordion maintainer: "128 playlists modified in a single
 * day means the sync re-touched essentially your whole library — it isn't
 * honoring skip-unchanged (the desktop canShortCircuitPlaylistUpdate path)."
 * The desktop short-circuit (sync-providers/listenbrainz.js Step 3.5) skips
 * the delete+re-add when the intended tracklist already equals the remote's
 * current tracklist, ORDER-AWARE. We port that exactly: SyncEngine fetches
 * the remote tracklist before the replace and skips when it matches.
 *
 * The [RemoteModelingProvider] below models a real LB playlist: its
 * `replacePlaylistTracks` mutates an in-memory remote tracklist, and
 * `fetchPlaylistTracks` returns it — so the engine's compare-against-remote
 * short-circuit is genuinely exercised.
 */
class ListenBrainzPushShortCircuitTest {

    private class Harness {
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

        val provider = RemoteModelingProvider()
        val settings = FakeSyncSettings(
            enabledProviders = setOf(ListenBrainzSyncProvider.PROVIDER_ID),
            collectionsByProvider = mapOf(
                ListenBrainzSyncProvider.PROVIDER_ID to setOf("playlists"),
            ),
        )

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
            settingsStore = settings,
            providers = listOf(provider),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )

        suspend fun seedLocalPlaylist(id: String, name: String, trackCount: Int = 2) {
            playlistDao.insert(
                Playlist(
                    id = id,
                    name = name,
                    trackCount = trackCount,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    lastModified = 1_000L,
                    locallyModified = false,
                ),
            )
            val tracks = (0 until trackCount).map { i ->
                PlaylistTrack(
                    playlistId = id,
                    position = i,
                    trackTitle = "$name track $i",
                    trackArtist = "Artist $i",
                    trackRecordingMbid = "mbid-track-$id-$i",
                )
            }
            playlistTrackDao.insertAll(tracks)
        }
    }

    /**
     * Models a single owned ListenBrainz playlist library where each remote
     * carries an order-significant tracklist. `replacePlaylistTracks` rewrites
     * the remote tracklist; `fetchPlaylistTracks` reads it back. That round
     * trip is what the engine's compare-against-remote short-circuit reads.
     */
    private class RemoteModelingProvider : SyncProvider {
        override val id = ListenBrainzSyncProvider.PROVIDER_ID
        override val displayName = "ListenBrainz (remote-modeling fake)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.None)

        class RemoteEntry(val mbid: String, val name: String) {
            val tracks = mutableListOf<String>()
        }

        val remote = linkedMapOf<String, RemoteEntry>()

        var createCount = 0
            private set
        val replaceCalls = mutableListOf<Pair<String, List<String>>>()

        override suspend fun createPlaylist(name: String, description: String?): RemoteCreated {
            createCount++
            val mbid = "mbid-$createCount-$name"
            remote[mbid] = RemoteEntry(mbid, name)
            return RemoteCreated(externalId = mbid, snapshotId = null)
        }

        override suspend fun fetchPlaylists(
            onProgress: ((current: Int, total: Int) -> Unit)?,
        ): List<SyncedPlaylist> = remote.values.map { entry ->
            SyncedPlaylist(
                entity = Playlist(
                    id = "${ListenBrainzSyncProvider.PROVIDER_ID}-${entry.mbid}",
                    name = entry.name,
                ),
                spotifyId = entry.mbid,
                snapshotId = null,
                trackCount = entry.tracks.size,
                isOwned = true,
            )
        }

        override suspend fun replacePlaylistTracks(
            externalPlaylistId: String,
            externalTrackIds: List<String>,
        ): String? {
            replaceCalls.add(externalPlaylistId to externalTrackIds)
            remote[externalPlaylistId]?.let {
                it.tracks.clear()
                it.tracks.addAll(externalTrackIds)
            }
            return null
        }

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String): List<PlaylistTrack> =
            remote[externalPlaylistId]?.tracks?.mapIndexed { index, mbid ->
                PlaylistTrack(
                    playlistId = "${ListenBrainzSyncProvider.PROVIDER_ID}-$externalPlaylistId",
                    position = index,
                    trackTitle = "",
                    trackArtist = "",
                    trackRecordingMbid = mbid,
                )
            } ?: emptyList()

        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    private class FakeSyncSettings(
        private val enabledProviders: Set<String>,
        private val collectionsByProvider: Map<String, Set<String>>,
    ) : SyncSettingsProvider {
        private var dataVersion = 5

        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true,
            syncTracks = false,
            syncAlbums = false,
            syncArtists = false,
            syncPlaylists = true,
            pushLocalPlaylists = true,
        )

        override suspend fun getEnabledSyncProviders() = enabledProviders
        override suspend fun getSyncCollectionsForProvider(providerId: String) =
            collectionsByProvider[providerId] ?: emptySet()
        override suspend fun getSyncDataVersion() = dataVersion
        override suspend fun setSyncDataVersion(version: Int) { dataVersion = version }
        override suspend fun setLastSyncAt(timestamp: Long) {}
        override suspend fun clearSyncSettings() {}
    }

    // ── Tests ───────────────────────────────────────────────────────

    /**
     * THE skip-unchanged guard. First sync creates + pushes tracks once;
     * the second no-op sync must NOT re-push the identical tracklist.
     */
    @Test
    fun `sync x2 with no local changes pushes tracks exactly once`() = runBlocking {
        val h = Harness()
        h.seedLocalPlaylist("local-0", "Morning Jams")

        h.engine.syncAll()

        assertEquals("first sync pushes the tracklist once", 1, h.provider.replaceCalls.size)
        assertEquals("first push created exactly one remote", 1, h.provider.createCount)

        h.engine.syncAll()

        assertEquals(
            "SKIP-UNCHANGED GUARD: remote already matches — replacePlaylistTracks must NOT fire again",
            1,
            h.provider.replaceCalls.size,
        )
        assertEquals("no duplicate remote created on re-sync", 1, h.provider.createCount)
    }

    /**
     * A changed tracklist (track added) must fall through the short-circuit
     * and re-push on the next sync.
     */
    @Test
    fun `editing the tracklist re-pushes on next sync`() = runBlocking {
        val h = Harness()
        h.seedLocalPlaylist("local-0", "Deep Focus")

        h.engine.syncAll()
        assertEquals(1, h.provider.replaceCalls.size)

        // Add a third track locally — the intended list no longer matches remote.
        h.playlistTrackDao.insertAll(
            listOf(
                PlaylistTrack(
                    playlistId = "local-0",
                    position = 2,
                    trackTitle = "Deep Focus track 2",
                    trackArtist = "Artist 2",
                    trackRecordingMbid = "mbid-track-local-0-2",
                ),
            ),
        )

        h.engine.syncAll()

        assertEquals(
            "changed tracklist must re-push",
            2,
            h.provider.replaceCalls.size,
        )
        assertEquals("the re-push carries all three tracks", 3, h.provider.replaceCalls.last().second.size)
    }

    /**
     * A `locallyModified` playlist must always re-push, even when its
     * tracklist happens to still equal the remote (e.g. a metadata-only edit
     * or a reorder-then-undo). Mirrors the task's "locallyModified always
     * pushes" rule.
     */
    @Test
    fun `locallyModified forces a re-push even when the tracklist is unchanged`() = runBlocking {
        val h = Harness()
        h.seedLocalPlaylist("local-0", "Throwback Hits")

        h.engine.syncAll()
        assertEquals(1, h.provider.replaceCalls.size)

        // Flag the row modified without touching its tracks.
        val current = h.playlistDao.getById("local-0")!!
        h.playlistDao.update(current.copy(locallyModified = true, lastModified = 2_000L))

        h.engine.syncAll()

        assertEquals(
            "locallyModified must bypass the short-circuit and re-push",
            2,
            h.provider.replaceCalls.size,
        )
    }
}
