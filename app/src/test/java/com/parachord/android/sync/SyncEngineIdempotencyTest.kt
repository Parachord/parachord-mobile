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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end idempotency harness for the playlist-sync push loop.
 *
 * This is the acceptance test called out in CLAUDE.md's "ListenBrainz
 * Playlist Sync — Interop Contract" item 5: a no-op sync run twice must
 * produce IDENTICAL remote state — same MBIDs, same link rows, and crucially
 * **zero new `createPlaylist` calls on the second run**. Counting playlists
 * alone misses the delete-then-recreate failure mode; the real guard is that
 * the provider's `createPlaylist` is never re-invoked for a playlist that
 * already has a live remote.
 *
 * Why this exists: a pagination bug in `ListenBrainzClient.getUserOwnedPlaylists`
 * (fetched only the newest 25 owned playlists instead of paginating the full
 * list) made `SyncEngine.pushPlaylistsForProvider`'s three-layer dedup fail to
 * see existing playlists, so every sync cycle RE-CREATED the user's entire
 * library — ~6,400 duplicate public playlists on a live account over ~18 days
 * (root-cause fix: parachord-mobile@cfb0c61 pagination + 40c4579 default
 * private). The pre-existing unit tests covered the dedup helpers in isolation
 * but never ran a full sync cycle twice — that gap is exactly what this file
 * closes.
 *
 * The harness stands up a real in-memory SQLDelight DB (so the
 * `sync_playlist_link` persistence under test is genuinely durable across
 * runs) plus a [FakeSyncProvider] whose in-memory remote store mirrors how
 * `ListenBrainzSyncProvider.toSyncedPlaylist` maps an owned playlist. It
 * drives the public [SyncEngine.syncAll] entry point with only the playlists
 * axis enabled and a single non-Spotify provider registered, which exercises
 * the private `pushPlaylistsForProvider` three-layer dedup without reflection.
 */
class SyncEngineIdempotencyTest {

    // ── Test fixture ────────────────────────────────────────────────

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

        val provider = FakeSyncProvider()
        val settings = FakeSyncSettings(
            enabledProviders = setOf(ListenBrainzSyncProvider.PROVIDER_ID),
            collectionsByProvider = mapOf(
                // Spotify opts OUT of the playlists axis so the inline
                // Spotify pull (and its `spotifyProvider` getter) never
                // fires — the only registered provider is the fake.
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

        /** Seed a local push-candidate playlist (`local-*`) with [trackCount] tracks. */
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
            // Every track carries its provider ID (recording MBID) so the
            // push path's hydrateMissingTrackIds short-circuits without a
            // catalog-search round-trip.
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

        fun listenBrainzLinks(): List<SyncPlaylistLinkDao.Link> = runBlocking {
            linkDao.selectAll().filter { it.providerId == ListenBrainzSyncProvider.PROVIDER_ID }
        }
    }

    /**
     * In-memory [SyncProvider] modelling ListenBrainz's owned-playlist
     * surface. `createPlaylist` mints a deterministic MBID and records the
     * playlist in [remote]; `fetchPlaylists` hands back the COMPLETE remote
     * store (mirroring the all-or-nothing pagination contract — the fake
     * never truncates to a page). `replacePlaylistTracks` mutates without
     * creating. [createCount] is the regression guard.
     */
    private class FakeSyncProvider : SyncProvider {
        override val id = ListenBrainzSyncProvider.PROVIDER_ID
        override val displayName = "ListenBrainz (fake)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.None)

        data class RemoteEntry(val mbid: String, val name: String)

        /** externalId(mbid) -> entry. The provider's entire owned-playlist library. */
        val remote = linkedMapOf<String, RemoteEntry>()

        var createCount = 0
            private set
        val replaceCalls = mutableListOf<Pair<String, List<String>>>()

        /** Pre-seed an already-existing remote playlist (a prior sync's creation). */
        fun seedRemote(mbid: String, name: String) {
            remote[mbid] = RemoteEntry(mbid, name)
        }

        override suspend fun createPlaylist(name: String, description: String?): RemoteCreated {
            createCount++
            // Deterministic, collision-free across same-named creates.
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
                spotifyId = entry.mbid, // external-id slot, per toSyncedPlaylist
                snapshotId = null,      // SnapshotKind.None
                trackCount = 0,
                isOwned = true,
            )
        }

        override suspend fun replacePlaylistTracks(
            externalPlaylistId: String,
            externalTrackIds: List<String>,
        ): String? {
            replaceCalls.add(externalPlaylistId to externalTrackIds)
            return null
        }

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = emptyList<PlaylistTrack>()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    private class FakeSyncSettings(
        private val enabledProviders: Set<String>,
        private val collectionsByProvider: Map<String, Set<String>>,
    ) : SyncSettingsProvider {
        // Version >= MIGRATION_DEDUP_LOCAL_V1 (5) so every version-gated
        // startup migration in syncPlaylists is skipped — keeps the test
        // focused on the push/pull loops.
        private var dataVersion = 5

        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true,
            // Only the playlists axis runs; tracks/albums/artists would hit
            // the Spotify-only legacy paths (and the spotifyProvider getter).
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
     * THE core regression guard. Two no-op syncs must not re-create a
     * single remote playlist. (CLAUDE.md interop contract item 5.)
     */
    @Test
    fun `sync x2 with no local changes creates each playlist exactly once`() = runBlocking {
        val h = Harness()
        val names = listOf("Morning Jams", "Deep Focus", "Throwback Hits")
        names.forEachIndexed { i, name -> h.seedLocalPlaylist("local-$i", name) }

        // ── First sync: every candidate is created once. ──
        h.engine.syncAll()

        assertEquals("first sync creates one remote per local playlist", names.size, h.provider.createCount)
        assertEquals("one remote playlist per local", names.size, h.provider.remote.size)
        assertEquals("one durable link per local playlist", names.size, h.listenBrainzLinks().size)

        val linksAfterFirst = h.listenBrainzLinks().map { it.localPlaylistId to it.externalId }.toSet()

        // ── Second sync: nothing changed locally. ──
        h.engine.syncAll()

        assertEquals(
            "REGRESSION GUARD: createPlaylist must NOT fire again on a no-op re-sync",
            names.size,
            h.provider.createCount,
        )
        assertEquals("no new remote playlists after re-sync", names.size, h.provider.remote.size)

        val linksAfterSecond = h.listenBrainzLinks().map { it.localPlaylistId to it.externalId }.toSet()
        assertEquals("link rows (localId -> externalId) unchanged across re-sync", linksAfterFirst, linksAfterSecond)
    }

    /**
     * Pagination regression — directly pins the May 2026 incident. With many
     * MORE owned remote playlists than a single naive page (25) would return,
     * all already linked from a prior sync, the dedup must see ALL of them and
     * create nothing. The fake's `fetchPlaylists` returns the full list, so
     * this asserts SyncEngine consumes the complete list the provider hands it
     * (complementing the client-level pagination test in
     * `ListenBrainzClientMutationTest`).
     */
    @Test
    fun `60-plus pre-linked remote playlists produce zero creates on re-sync`() = runBlocking {
        val h = Harness()
        val count = 60 // > LB's default count=25 page

        for (i in 0 until count) {
            val name = "Playlist $i"
            val mbid = "mbid-existing-$i"
            h.seedLocalPlaylist("local-$i", name)
            // Simulate a prior sync: remote exists + durable link in place.
            h.provider.seedRemote(mbid, name)
            h.linkDao.upsertWithSnapshot(
                localPlaylistId = "local-$i",
                providerId = ListenBrainzSyncProvider.PROVIDER_ID,
                externalId = mbid,
                snapshotId = null,
            )
        }

        h.engine.syncAll()

        assertEquals(
            "dedup saw ALL $count remote playlists (not just a page) — zero re-creations",
            0,
            h.provider.createCount,
        )
        assertEquals("remote library size unchanged — no duplicates appended", count, h.provider.remote.size)
        assertEquals("still exactly one link per playlist", count, h.listenBrainzLinks().size)
    }

    /**
     * Link-loss / fresh-device recovery: when the durable link row is gone but
     * the remote playlist still exists, the push must Layer-3 name-match and
     * re-link it — NOT create a duplicate.
     */
    @Test
    fun `link loss re-links via name match instead of creating a duplicate`() = runBlocking {
        val h = Harness()
        val names = listOf("Workout 2024", "Sunday Chill")
        names.forEachIndexed { i, name -> h.seedLocalPlaylist("local-$i", name) }

        // First sync creates + links.
        h.engine.syncAll()
        assertEquals(names.size, h.provider.createCount)

        // Simulate link loss (fresh device / cleared link table) — keep the
        // remote playlists; drop only the durable links.
        names.forEachIndexed { i, _ -> h.linkDao.deleteForLocal("local-$i") }
        assertEquals("links cleared", 0, h.listenBrainzLinks().size)

        // Second sync must recover via the Layer-3 name match.
        h.engine.syncAll()

        assertEquals(
            "name-match re-link must NOT create new remotes",
            names.size,
            h.provider.createCount,
        )
        assertEquals("remote library size unchanged — no duplicates", names.size, h.provider.remote.size)
        assertEquals("links restored by re-sync", names.size, h.listenBrainzLinks().size)
        assertTrue(
            "re-linked externalIds point at the original remotes",
            h.listenBrainzLinks().all { it.externalId in h.provider.remote.keys },
        )
    }
}
