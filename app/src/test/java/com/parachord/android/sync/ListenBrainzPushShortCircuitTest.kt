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
import com.parachord.shared.sync.TrackKeys
import com.parachord.shared.sync.TrackTombstoneService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private class Harness(nway: Boolean = false) {
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

        val provider = RemoteModelingProvider()
        val settings = FakeSyncSettings(
            enabledProviders = setOf(ListenBrainzSyncProvider.PROVIDER_ID),
            collectionsByProvider = mapOf(
                ListenBrainzSyncProvider.PROVIDER_ID to setOf("playlists"),
            ),
            nway = nway,
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
            syncPlaylistBaselineDao = baselineDao,
            syncPlaylistNwayDao = nwayDao,
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
        private val nway: Boolean = false,
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
        // Per-provider selection gate (branch addition): ALL so the push loop
        // pushes like it did before the gate (preserves this harness's intent).
        // Pull allowlist empty (= import all); no channel override; dedup done;
        // N-way off.
        override suspend fun getPlaylistSelection(providerId: String) =
            ProviderPlaylistSelection(PlaylistSyncMode.ALL)
        override suspend fun setPlaylistSelection(providerId: String, selection: ProviderPlaylistSelection) {}
        override suspend fun getPullPlaylists(providerId: String): Set<String> = emptySet()
        override suspend fun setPullPlaylists(providerId: String, externalIds: Set<String>) {}
        override suspend fun getPlaylistChannels(localPlaylistId: String): Set<String>? = null
        override suspend fun setPlaylistChannels(localPlaylistId: String, channels: Set<String>?) {}
        override suspend fun getTrackDedupV1Done(): Boolean = true
        override suspend fun setTrackDedupV1Done() {}
        override suspend fun isNwayEnabled(): Boolean = nway
        override suspend fun isNwayPropagateEnabled(): Boolean = false
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
        // A real local edit goes through the repository, which sets
        // locallyModified = true. The push loop DELIBERATELY skips id-linked,
        // unmodified playlists (SyncEngine.kt:1988) — so simulate the flag the
        // real edit path would have set, then the skip-unchanged compare runs.
        h.playlistDao.update(
            Playlist(
                id = "local-0",
                name = "Deep Focus",
                trackCount = 3,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastModified = 2_000L,
                locallyModified = true,
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

    @Test
    fun `N-way migration seeds baseline and per-provider state once enabled`() = runBlocking {
        val h = Harness(nway = true)
        h.seedLocalPlaylist("local-0", "Deep Focus")

        // First sync creates the LB playlist + link. No baseline yet — the
        // migration pass runs BEFORE the push, when local-0 has no mirror.
        h.engine.syncAll()
        assertNull("no baseline before the playlist has a mirror", h.baselineDao.selectForLocal("local-0"))
        val link = h.linkDao.selectForLink("local-0", ListenBrainzSyncProvider.PROVIDER_ID)
        assertNotNull("first sync linked the playlist to LB", link)

        // Second sync: the migration bootstrap now sees the LB mirror and seeds
        // the baseline (current local keys) + the per-provider N-way state.
        h.engine.syncAll()

        val baseline = h.baselineDao.selectForLocal("local-0")
        assertNotNull("baseline seeded on the migration pass", baseline)
        assertEquals(
            "baseline = current local tracklist as TrackKeys (mbid + norm; no isrc on PlaylistTrack)",
            listOf(
                TrackKeys(isrc = null, mbid = "mbid-track-local-0-0", norm = "artist 0|deep focus track 0"),
                TrackKeys(isrc = null, mbid = "mbid-track-local-0-1", norm = "artist 1|deep focus track 1"),
            ),
            baseline!!.tracks,
        )
        val state = h.nwayDao.selectForLink("local-0", ListenBrainzSyncProvider.PROVIDER_ID)
        assertNotNull("per-provider N-way state seeded for the mirror", state)
        assertEquals(
            "changeToken seeded from the link's stored snapshot (no provider fetch)",
            link!!.snapshotId,
            state!!.changeToken,
        )

        // Idempotent: a third sync must not change the baseline.
        h.engine.syncAll()
        assertEquals(
            "migration is idempotent — baseline unchanged on re-run",
            baseline.tracks,
            h.baselineDao.selectForLocal("local-0")!!.tracks,
        )
    }

    @Test
    fun `push never claims a remote already linked to another local playlist (269)`() = runBlocking {
        val h = Harness()
        // local-A mirrors to LB → creates + links its own remote.
        h.seedLocalPlaylist("local-A", "Guitarmageddon")
        h.engine.syncAll()
        val mbidA = h.linkDao.selectForLink("local-A", ListenBrainzSyncProvider.PROVIDER_ID)!!.externalId
        val createsAfterA = h.provider.createCount

        // A SECOND same-name local playlist appears (e.g. a followed copy + your own).
        h.seedLocalPlaylist("local-B", "Guitarmageddon")
        h.engine.syncAll()

        val linkB = h.linkDao.selectForLink("local-B", ListenBrainzSyncProvider.PROVIDER_ID)
        assertNotNull("local-B got linked", linkB)
        // THE GUARD: local-B must NOT claim local-A's remote (that's the tangle) —
        // it gets its own.
        assertNotEquals(
            "same-name playlists must not share one remote mirror",
            mbidA,
            linkB!!.externalId,
        )
        assertTrue("local-B created its own remote", h.provider.createCount > createsAfterA)
    }

    @Test
    fun `N-way shadow mode runs the reconcile path without pushing or mutating state`() = runBlocking {
        val h = Harness(nway = true)
        h.seedLocalPlaylist("local-0", "Deep Focus")
        h.engine.syncAll() // push + link
        h.engine.syncAll() // migration seeds the baseline; shadow runs from here on

        val pushesAfterMigrate = h.provider.replaceCalls.size
        val baselineAfterMigrate = h.baselineDao.selectForLocal("local-0")!!.tracks
        val tokenAfterMigrate = h.nwayDao.selectForLink("local-0", ListenBrainzSyncProvider.PROVIDER_ID)!!.changeToken

        // Run the on-demand shadow scan twice. It detects the LB mirror via the
        // cheap list signal (trackCount == baseline size → unchanged), so no
        // tracklist fetch, no delta → pushes nothing, mutates nothing. THE
        // echo-loop / sync×2 idempotency guard (design step 5). Divergence →
        // merge → would-push correctness is covered by the pure NwayShadowTest.
        h.engine.runNwayShadowScan()
        h.engine.runNwayShadowScan()

        assertEquals("shadow mode must never push", pushesAfterMigrate, h.provider.replaceCalls.size)
        assertEquals(
            "shadow must not mutate the baseline",
            baselineAfterMigrate,
            h.baselineDao.selectForLocal("local-0")!!.tracks,
        )
        assertEquals(
            "shadow must not mutate the per-provider change token",
            tokenAfterMigrate,
            h.nwayDao.selectForLink("local-0", ListenBrainzSyncProvider.PROVIDER_ID)!!.changeToken,
        )
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
