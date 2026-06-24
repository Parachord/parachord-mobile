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
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.ListenBrainzSyncProvider
import com.parachord.shared.sync.PlaylistSyncMode
import com.parachord.shared.sync.ProviderPlaylistSelection
import com.parachord.shared.sync.RemoteCreated
import com.parachord.shared.sync.SyncEngine
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncSettings
import com.parachord.shared.sync.SyncSettingsProvider
import com.parachord.shared.sync.SyncedPlaylist
import com.parachord.shared.sync.TrackKeys
import com.parachord.shared.sync.TrackTombstoneService
import com.parachord.shared.sync.isPlaylistPushCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-way-aware ListenBrainz PUSH picker (#266). Covers the load-bearing
 * engine-side behavior of the new picker: seed-from-links
 * ([SyncEngine.linkedPlaylistIdsForProvider]), deselect = local-only unlink
 * ([SyncEngine.unlinkPlaylistFromProviderLocally]), and newly-checked = channel
 * admit ([SyncEngine.linkPlaylistToProviderLocally]). The picker's set-diff
 * arithmetic (applyPushSelection) is modeled directly here since the ViewModel
 * pulls in heavy Android deps; the diff is `deselected = original - checked`,
 * `newlyChecked = checked - original` driving exactly those two engine calls.
 */
class LbPushPickerNwayTest {

    private val LB = ListenBrainzSyncProvider.PROVIDER_ID

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
        val baselineDao = SyncPlaylistBaselineDao(db)
        val nwayDao = SyncPlaylistNwayDao(db)

        val provider = DeleteCountingProvider()
        val settings = MutableFakeSyncSettings()

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
            settingsStore = settings,
            providers = listOf(provider),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )

        suspend fun seedPlaylist(id: String, name: String = id) {
            playlistDao.insert(
                Playlist(
                    id = id,
                    name = name,
                    trackCount = 1,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    lastModified = 1_000L,
                    locallyModified = false,
                ),
            )
        }
    }

    /** Counts deletePlaylist so tests can assert deselect NEVER remote-deletes. */
    private class DeleteCountingProvider : SyncProvider {
        override val id = ListenBrainzSyncProvider.PROVIDER_ID
        override val displayName = "LB (delete-counting fake)"
        override val features = com.parachord.shared.sync.ProviderFeatures(
            snapshots = com.parachord.shared.sync.SnapshotKind.None,
        )
        var deleteCount = 0
            private set

        override suspend fun fetchPlaylists(
            onProgress: ((current: Int, total: Int) -> Unit)?,
        ): List<SyncedPlaylist> = emptyList()

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) =
            emptyList<com.parachord.shared.model.PlaylistTrack>()

        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?): RemoteCreated =
            RemoteCreated(externalId = "remote-$name", snapshotId = null)

        override suspend fun replacePlaylistTracks(
            externalPlaylistId: String,
            externalTrackIds: List<String>,
        ): String? = null

        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult {
            deleteCount++
            return DeleteResult.Success
        }
    }

    /** Fake settings with a WORKING per-playlist channels map (allowlist + " "
     *  empty-sentinel parity with the real SettingsStore). */
    private class MutableFakeSyncSettings : SyncSettingsProvider {
        private var dataVersion = 5
        val channels = mutableMapOf<String, Set<String>>()
        /** Mutable so tests can enable Spotify/AM alongside LB. */
        var enabledProviders: Set<String> = setOf(ListenBrainzSyncProvider.PROVIDER_ID)
        /** Per-provider push selection; defaults via [ProviderPlaylistSelection.defaultMode]. */
        val selections = mutableMapOf<String, ProviderPlaylistSelection>()

        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true,
            syncTracks = false,
            syncAlbums = false,
            syncArtists = false,
            syncPlaylists = true,
            pushLocalPlaylists = true,
        )

        override suspend fun getEnabledSyncProviders() = enabledProviders
        override suspend fun getSyncCollectionsForProvider(providerId: String) = setOf("playlists")
        override suspend fun getSyncDataVersion() = dataVersion
        override suspend fun setSyncDataVersion(version: Int) { dataVersion = version }
        override suspend fun setLastSyncAt(timestamp: Long) {}
        override suspend fun clearSyncSettings() {}
        override suspend fun getPlaylistSelection(providerId: String) =
            selections[providerId]
                ?: ProviderPlaylistSelection(ProviderPlaylistSelection.defaultMode(providerId))
        override suspend fun setPlaylistSelection(providerId: String, selection: ProviderPlaylistSelection) {
            selections[providerId] = selection
        }
        override suspend fun getPullPlaylists(providerId: String): Set<String> = emptySet()
        override suspend fun setPullPlaylists(providerId: String, externalIds: Set<String>) {}
        override suspend fun getPlaylistChannels(localPlaylistId: String): Set<String>? =
            channels[localPlaylistId]
        override suspend fun setPlaylistChannels(localPlaylistId: String, channelsArg: Set<String>?) {
            if (channelsArg == null) channels.remove(localPlaylistId)
            else channels[localPlaylistId] = channelsArg
        }
        override suspend fun getTrackDedupV1Done(): Boolean = true
        override suspend fun setTrackDedupV1Done() {}
        override suspend fun isNwayEnabled(): Boolean = false
        override suspend fun isNwayPropagateEnabled(): Boolean = false
    }

    // ── A. Seed-from-links ──────────────────────────────────────────

    @Test
    fun `seed checked from links intersect existing playlists`() = runBlocking {
        val h = Harness()
        h.seedPlaylist("local-A")
        h.seedPlaylist("local-B")
        h.seedPlaylist("local-C") // candidate but not linked
        h.seedPlaylist("local-D")
        h.linkDao.upsert("local-A", LB, "remote-A")
        h.linkDao.upsert("local-B", LB, "remote-B")

        val checked = h.engine.linkedPlaylistIdsForProvider(LB)
        assertEquals(setOf("local-A", "local-B"), checked)
    }

    @Test
    fun `seed drops orphan links with no playlist row`() = runBlocking {
        val h = Harness()
        h.seedPlaylist("local-A")
        h.linkDao.upsert("local-A", LB, "remote-A")
        h.linkDao.upsert("local-Z", LB, "remote-Z") // no playlists row for Z

        val checked = h.engine.linkedPlaylistIdsForProvider(LB)
        assertEquals(setOf("local-A"), checked)
        assertFalse("orphan local-Z must not be seeded", "local-Z" in checked)
    }

    @Test
    fun `seed excludes a row whose pull-source is the same provider`() = runBlocking {
        val h = Harness()
        // An LB-imported playlist: pull-source row + a link row both for LB.
        h.seedPlaylist("listenbrainz-XYZ")
        h.sourceDao.upsert("listenbrainz-XYZ", LB, "XYZ", null, null)
        h.linkDao.upsert("listenbrainz-XYZ", LB, "XYZ")
        // A genuine push target.
        h.seedPlaylist("local-A")
        h.linkDao.upsert("local-A", LB, "remote-A")

        val checked = h.engine.linkedPlaylistIdsForProvider(LB)
        assertEquals(
            "LB's own pull-source import must NOT be a checkable push target",
            setOf("local-A"),
            checked,
        )
    }

    @Test
    fun `linked-but-not-candidate row still surfaces in the displayed list`() = runBlocking {
        val h = Harness()
        // applemusic-imported row is NOT an LB push candidate base, but it IS one
        // via the applemusic-* clause, so use a different non-candidate shape: a
        // spotify-* row IS an LB candidate. To get a TRUE non-candidate that's
        // still linked, link an id with no eligible prefix and sourceUrl null.
        h.seedPlaylist("weird-row")
        h.linkDao.upsert("weird-row", LB, "remote-weird")

        // Not a candidate:
        val pl = h.playlistDao.getById("weird-row")!!
        assertFalse(isPlaylistPushCandidate(pl, LB))

        // But it IS seeded as checked (link ∩ existing, no pull-source):
        val checked = h.engine.linkedPlaylistIdsForProvider(LB)
        assertTrue("a linked-but-not-candidate row must be checked", "weird-row" in checked)
        // The displayed list (candidate ∪ linked) therefore includes it:
        val all = h.playlistDao.getAllSync().filter { it.name.isNotBlank() }
        val displayed = all.filter { isPlaylistPushCandidate(it, LB) || it.id in checked }.map { it.id }
        assertTrue("displayed list must include the linked non-candidate", "weird-row" in displayed)
    }

    // ── B. Deselect → local-only unlink ─────────────────────────────

    @Test
    fun `unlink removes link and nway and writes channel override excluding LB`() = runBlocking {
        val h = Harness()
        h.seedPlaylist("local-A")
        // local-A mirrors to BOTH Spotify and LB.
        h.linkDao.upsert("local-A", "spotify", "sp-A")
        h.linkDao.upsert("local-A", LB, "lb-A")
        h.nwayDao.upsert("local-A", LB, changeToken = "tok", editedAt = 1L)
        h.baselineDao.upsert("local-A", listOf(TrackKeys(isrc = null, mbid = "m", norm = "a|b")))
        val baselineBefore = h.baselineDao.selectForLocal("local-A")!!.tracks

        h.engine.unlinkPlaylistFromProviderLocally("local-A", LB)

        assertNull("LB link removed", h.linkDao.selectForLink("local-A", LB))
        assertNull("LB nway state removed", h.nwayDao.selectForLink("local-A", LB))
        assertNotNull("Spotify link untouched", h.linkDao.selectForLink("local-A", "spotify"))
        val override = h.settings.getPlaylistChannels("local-A")
        assertNotNull("channel override written", override)
        assertFalse("override excludes LB", LB in override!!)
        assertTrue("override preserves Spotify mirror", "spotify" in override)
        assertEquals(
            "baseline untouched (3-way merge ancestor for other providers)",
            baselineBefore,
            h.baselineDao.selectForLocal("local-A")!!.tracks,
        )
        assertEquals("no remote-delete issued", 0, h.provider.deleteCount)
    }

    @Test
    fun `unlink on the sole-mirror row leaves an empty override and masks id-prefix`() = runBlocking {
        val h = Harness()
        // LB-prefixed row where LB is the ONLY mirror.
        h.seedPlaylist("listenbrainz-XYZ")
        h.linkDao.upsert("listenbrainz-XYZ", LB, "XYZ")

        h.engine.unlinkPlaylistFromProviderLocally("listenbrainz-XYZ", LB)

        // Override is present and empty → masks the id-prefix re-add.
        val mirrors = h.engine.getPlaylistMirrors("listenbrainz-XYZ")
        assertFalse(
            "id-prefix re-add masked by the (empty) override",
            LB in mirrors,
        )
        val override = h.settings.getPlaylistChannels("listenbrainz-XYZ")
        assertNotNull("override present (empty set, not null)", override)
        assertTrue("override is empty (LB was the sole mirror)", override!!.isEmpty())
    }

    // ── C. Newly-checked → channel admit ────────────────────────────

    @Test
    fun `link writes channel override including LB without creating a link`() = runBlocking {
        val h = Harness()
        h.seedPlaylist("local-C")

        h.engine.linkPlaylistToProviderLocally("local-C", LB)

        val override = h.settings.getPlaylistChannels("local-C")
        assertNotNull(override)
        assertTrue("override admits LB", LB in override!!)
        assertNull(
            "no link row created — creation deferred to the next sync push",
            h.linkDao.selectForLink("local-C", LB),
        )
    }

    @Test
    fun `link preserves a mode-default Spotify mirror that has no link row yet`() = runBlocking {
        val h = Harness()
        // Spotify ENABLED + mode=ALL (default) so a local-* push candidate mirrors
        // to Spotify via the mode-default with NO sync_playlist_link row yet.
        h.settings.enabledProviders = setOf("spotify", LB)
        h.seedPlaylist("local-X")
        // No spotify link row — Spotify's mode=ALL is the only thing mirroring it.
        assertNull(
            "precondition: no spotify link yet",
            h.linkDao.selectForLink("local-X", "spotify"),
        )

        h.engine.linkPlaylistToProviderLocally("local-X", LB)

        val override = h.settings.getPlaylistChannels("local-X")!!
        assertTrue("LB admitted", LB in override)
        assertTrue(
            "mode-default Spotify mirror preserved (not silently dropped)",
            "spotify" in override,
        )
    }

    @Test
    fun `unlink preserves a mode-default Spotify mirror that has no link row yet`() = runBlocking {
        val h = Harness()
        // local-X is LB-linked (no channel override) AND Spotify-enabled mode=ALL
        // with NO spotify link row — Spotify mirrors it via the mode default.
        h.settings.enabledProviders = setOf("spotify", LB)
        h.seedPlaylist("local-X")
        h.linkDao.upsert("local-X", LB, "lb-X")
        assertNull(
            "precondition: no spotify link yet",
            h.linkDao.selectForLink("local-X", "spotify"),
        )

        h.engine.unlinkPlaylistFromProviderLocally("local-X", LB)

        val override = h.settings.getPlaylistChannels("local-X")
        assertNotNull("override written", override)
        assertFalse("LB excluded", LB in override!!)
        assertTrue(
            "mode-default Spotify mirror preserved (override NOT empty — no data loss)",
            "spotify" in override,
        )
    }

    @Test
    fun `link preserves pre-existing mirrors in the override`() = runBlocking {
        val h = Harness()
        h.seedPlaylist("local-C")
        h.linkDao.upsert("local-C", "spotify", "sp-C")

        h.engine.linkPlaylistToProviderLocally("local-C", LB)

        val override = h.settings.getPlaylistChannels("local-C")!!
        assertTrue("LB admitted", LB in override)
        assertTrue("existing Spotify mirror preserved", "spotify" in override)
    }

    @Test
    fun `deselect-then-recheck ends with LB included`() = runBlocking {
        val h = Harness()
        h.seedPlaylist("local-A")
        h.linkDao.upsert("local-A", LB, "lb-A")

        h.engine.unlinkPlaylistFromProviderLocally("local-A", LB)
        assertFalse(LB in h.settings.getPlaylistChannels("local-A").orEmpty())

        h.engine.linkPlaylistToProviderLocally("local-A", LB)
        assertTrue(
            "re-check additively restores LB (no leftover exclusion)",
            LB in h.settings.getPlaylistChannels("local-A").orEmpty(),
        )
    }

    // ── D. applyPushSelection diffing + idempotency ─────────────────
    //
    // The VM's applyPushSelection is: deselected = original - checked;
    // newlyChecked = checked - original; one unlink per deselected, one link per
    // newlyChecked. Model that arithmetic against the engine to prove only diffed
    // rows are mutated and a no-op re-save touches nothing.

    private suspend fun applyPushDiff(
        engine: SyncEngine,
        original: Set<String>,
        checked: Set<String>,
    ) {
        for (id in original - checked) engine.unlinkPlaylistFromProviderLocally(id, LB)
        for (id in checked - original) engine.linkPlaylistToProviderLocally(id, LB)
    }

    @Test
    fun `diff mutates only the changed rows`() = runBlocking {
        val h = Harness()
        h.seedPlaylist("local-A"); h.linkDao.upsert("local-A", LB, "lb-A")
        h.seedPlaylist("local-B"); h.linkDao.upsert("local-B", LB, "lb-B")
        h.seedPlaylist("local-C")

        // original {A,B} → checked {B,C}: unlink A, link C, B untouched.
        applyPushDiff(h.engine, original = setOf("local-A", "local-B"), checked = setOf("local-B", "local-C"))

        assertNull("A unlinked", h.linkDao.selectForLink("local-A", LB))
        assertNotNull("B untouched (link intact)", h.linkDao.selectForLink("local-B", LB))
        assertNull("B got no override write", h.settings.getPlaylistChannels("local-B"))
        assertTrue("C admitted", LB in h.settings.getPlaylistChannels("local-C").orEmpty())
    }

    @Test
    fun `no-op re-save touches nothing`() = runBlocking {
        val h = Harness()
        h.seedPlaylist("local-A"); h.linkDao.upsert("local-A", LB, "lb-A")

        // Identical sets → empty diff → no engine calls, no deletes, no overrides.
        applyPushDiff(h.engine, original = setOf("local-A"), checked = setOf("local-A"))

        assertNotNull("link intact", h.linkDao.selectForLink("local-A", LB))
        assertNull("no override written on a no-op", h.settings.getPlaylistChannels("local-A"))
        assertEquals("no remote-delete", 0, h.provider.deleteCount)
    }
}
