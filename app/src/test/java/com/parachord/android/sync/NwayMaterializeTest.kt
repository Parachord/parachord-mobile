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
import com.parachord.shared.sync.AppleMusicSyncProvider
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.ListenBrainzSyncProvider
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
import com.parachord.shared.sync.TrackRemoveMode
import com.parachord.shared.sync.TrackTombstoneService
import com.parachord.shared.sync.nwayBaselineTrackKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE REAL-WRITES GATE for the non-destructive incremental materialize swap
 * (incremental-materialization Task 8). Drives the LIVE `runNwayPropagation` push
 * end-to-end through [com.parachord.shared.sync.materializeToProvider], the swap's
 * replacement for the old hydrate→coverage-SKIP→replace-all path.
 *
 * The [FakeProvider] here is a genuine remote model: it implements the executor's
 * incremental primitives (add / removeByNativeId / removeByPosition), declares a
 * configurable [TrackRemoveMode], reflects materialized state back through
 * `fetchPlaylistTracks` (round-trip), and RECORDS which write path fired
 * (`addCalls` / `removeByNativeIdCalls` / `removeByPositionCalls` / `replaceCalls`)
 * so a case can assert the dispatch AND that a no-op cycle fires nothing.
 *
 * Identity model: a track's cross-service identity is its `recordingMbid`. Each
 * provider's NATIVE id encodes that mbid (`<prefix>:<mbid>`) so a round-tripped
 * remote track carries BOTH the right native-id column AND the same `recordingMbid`
 * identity the canonical keys on — the diff therefore matches the same song across
 * services and a materialized add is self-consistent on the next fetch.
 *
 * These cases are RED against the deleted replace-all path: the old FakeProviders
 * only implemented `replacePlaylistTracks`; under the swap the executor calls the
 * new add/remove primitives, so a replace-all model would (a) never record an
 * incremental call and (b) destructively shrink on a partial-coverage cycle. The
 * specific RED-before-swap notes are on each case.
 */
class NwayMaterializeTest {

    /**
     * Remote-modeling provider implementing the incremental primitives.
     *
     * Identity model (faithful + exact round-trip): every track's recording mbid
     * EQUALS its title (the [seed]/[addLocalTrack] helpers enforce this). So
     * [searchForTrackId] can recover the mbid from the title it's given, and the
     * provider-native id ENCODES that mbid (`<id>-nid:<mbid>`). A round-tripped
     * remote track therefore decodes to the SAME `recordingMbid` the canonical keys
     * on — the diff matches the same song across the search boundary with no churn.
     *
     * @param removeMode the capability the executor dispatches removal on.
     * @param hydrateFn  given a track TITLE (== its mbid), returns the mbid the
     *                   catalog matched (null = not in this provider's catalog → that
     *                   add stays pending). Mutable so a test can flip
     *                   coverable→un-coverable mid-run. The default matches every
     *                   title to itself (full catalog).
     */
    private class FakeProvider(
        override val id: String,
        private val removeMode: TrackRemoveMode,
        var hydrateFn: ((title: String) -> String?)? = { it },
    ) : SyncProvider {
        override val displayName = "$id (materialize fake)"
        override val features = ProviderFeatures(
            snapshots = SnapshotKind.None,
            trackRemoveMode = removeMode,
        )

        /** The native-id prefix this provider keys on (decoded back to mbid on fetch). */
        private val prefix = "$id-nid"

        /** native id for a given recording mbid. */
        private fun nidFor(mbid: String) = "$prefix:$mbid"

        /** recording mbid encoded inside a native id. */
        private fun mbidOf(nativeId: String) = nativeId.substringAfter("$prefix:")

        /**
         * The native id this provider would store for a track whose identity
         * (recording mbid) is already KNOWN — used by the harness to pre-fill the
         * remote at link time WITHOUT going through [searchForTrackId]/hydrateFn (a
         * `{ null }` catalog must still carry the playlist's initially-coverable
         * tracks). Mirrors [nativeIdOf]'s per-provider format: LB keys on the bare
         * mbid; Spotify / Apple Music on the encoded native id. Returns null when the
         * track has no recording mbid (un-seedable on this provider).
         */
        fun seedNativeId(track: PlaylistTrack): String? {
            val mbid = track.trackRecordingMbid ?: return null
            return if (id == ListenBrainzSyncProvider.PROVIDER_ID) mbid else nidFor(mbid)
        }

        class Entry(val externalId: String, val name: String) {
            /** native ids, in remote order. */
            val tracks = mutableListOf<String>()
        }

        val remote = linkedMapOf<String, Entry>()

        /**
         * Optional per-externalId override for [fetchPlaylistTracks] — lets a test
         * model a remote whose tracklist DRIFTED from what was pushed (different
         * recording-mbid + remaster-suffixed title) to exercise the identity-bridge.
         * When set for an externalId, [fetchPlaylistTracks] returns these verbatim.
         */
        val driftRichTracks = mutableMapOf<String, List<PlaylistTrack>>()

        var createCount = 0
            private set
        val addCalls = mutableListOf<Pair<String, List<String>>>()
        val removeByNativeIdCalls = mutableListOf<Pair<String, List<String>>>()
        val removeByPositionCalls = mutableListOf<Pair<String, List<Int>>>()
        val replaceCalls = mutableListOf<Pair<String, List<String>>>()
        var searchCalls = 0
            private set

        /** Clear write/search recorders after the harness's setup fill (not a real cycle). */
        fun resetCallRecorders() {
            addCalls.clear(); removeByNativeIdCalls.clear()
            removeByPositionCalls.clear(); replaceCalls.clear()
            searchCalls = 0
        }

        override fun nativeIdOf(track: PlaylistTrack): String? = when (id) {
            // LB keys directly on the bare recording mbid (matches the legacy
            // extractExternalTrackIds("listenbrainz") format). Spotify / AM key on
            // their encoded native-id column (populated by a search / the first push).
            ListenBrainzSyncProvider.PROVIDER_ID -> track.trackRecordingMbid
            SpotifySyncProvider.PROVIDER_ID -> track.trackSpotifyUri
            AppleMusicSyncProvider.PROVIDER_ID -> track.trackAppleMusicId
            else -> null
        }

        override suspend fun searchForTrackId(title: String, artist: String, album: String?, isrc: String?): String? {
            searchCalls++
            // title == mbid (the harness invariant); hydrateFn yields the matched mbid
            // (or null). LB returns the bare mbid; Spotify / AM return the encoded id.
            val mbid = hydrateFn?.invoke(title) ?: return null
            return if (id == ListenBrainzSyncProvider.PROVIDER_ID) mbid else nidFor(mbid)
        }

        override suspend fun addPlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? {
            addCalls.add(externalPlaylistId to externalTrackIds)
            remote[externalPlaylistId]?.tracks?.addAll(externalTrackIds)
            return null
        }

        override suspend fun removePlaylistTracksByNativeId(externalPlaylistId: String, externalTrackIds: List<String>): String? {
            if (features.trackRemoveMode != TrackRemoveMode.ByNativeId) {
                return super.removePlaylistTracksByNativeId(externalPlaylistId, externalTrackIds)
            }
            removeByNativeIdCalls.add(externalPlaylistId to externalTrackIds)
            remote[externalPlaylistId]?.tracks?.removeAll(externalTrackIds.toSet())
            return null
        }

        override suspend fun removePlaylistTracksByPosition(externalPlaylistId: String, positions: List<Int>): String? {
            if (features.trackRemoveMode != TrackRemoveMode.ByPosition) {
                return super.removePlaylistTracksByPosition(externalPlaylistId, positions)
            }
            removeByPositionCalls.add(externalPlaylistId to positions)
            remote[externalPlaylistId]?.let { e ->
                val drop = positions.toSet()
                val kept = e.tracks.filterIndexed { i, _ -> i !in drop }
                e.tracks.clear(); e.tracks.addAll(kept)
            }
            return null
        }

        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? {
            replaceCalls.add(externalPlaylistId to externalTrackIds)
            remote[externalPlaylistId]?.let { it.tracks.clear(); it.tracks.addAll(externalTrackIds) }
            return null
        }

        override suspend fun createPlaylist(name: String, description: String?): RemoteCreated {
            createCount++
            val ext = "$id-ext-$createCount"
            remote[ext] = Entry(ext, name)
            return RemoteCreated(externalId = ext, snapshotId = null)
        }

        override suspend fun fetchPlaylists(onProgress: ((current: Int, total: Int) -> Unit)?): List<SyncedPlaylist> =
            remote.values.map { e ->
                SyncedPlaylist(
                    entity = Playlist(id = "$id-${e.externalId}", name = e.name),
                    spotifyId = e.externalId,
                    snapshotId = null,
                    trackCount = e.tracks.size,
                    isOwned = true,
                )
            }

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String): List<PlaylistTrack> {
            driftRichTracks[externalPlaylistId]?.let { return it }
            return remote[externalPlaylistId]?.tracks?.mapIndexed { i, nid ->
                val mbid = mbidOf(nid)
                val base = PlaylistTrack(
                    playlistId = "$id-$externalPlaylistId",
                    position = i,
                    // Round-trip the title/artist so the `norm` tier of a track WITHOUT
                    // an mbid still keys consistently. mbid carries the identity here.
                    trackTitle = mbid,
                    trackArtist = mbid,
                    trackRecordingMbid = mbid,
                )
                when (id) {
                    SpotifySyncProvider.PROVIDER_ID -> base.copy(trackSpotifyUri = nid)
                    AppleMusicSyncProvider.PROVIDER_ID -> base.copy(trackAppleMusicId = nid)
                    else -> base
                }
            } ?: emptyList()
        }

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

    private class Harness(private val providers: List<FakeProvider>) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val trackDao = TrackDao(db, driver)
        val playlistDao = PlaylistDao(db)
        val playlistTrackDao = PlaylistTrackDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val baselineDao = SyncPlaylistBaselineDao(db)
        val nwayDao = SyncPlaylistNwayDao(db)
        val cacheDao = TrackProviderIdCacheDao(db)

        val engine = SyncEngine(
            db = db, driver = driver, trackDao = trackDao, albumDao = AlbumDao(db), artistDao = ArtistDao(db),
            playlistDao = playlistDao, playlistTrackDao = playlistTrackDao, syncSourceDao = SyncSourceDao(db),
            syncPlaylistLinkDao = linkDao, syncPlaylistSourceDao = SyncPlaylistSourceDao(db),
            syncPlaylistBaselineDao = baselineDao, syncPlaylistNwayDao = nwayDao,
            trackProviderIdCacheDao = cacheDao,
            settingsStore = FakeSyncSettings(providers.map { it.id }.toSet()),
            providers = providers,
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )

        /**
         * First-sync setup, done DIRECTLY via the DAOs instead of through
         * `syncAll()` — `syncAll`'s inline Spotify-pull does `as SpotifySyncProvider`,
         * which a generic [FakeProvider] (even one with `id == "spotify"`) can't
         * satisfy, so the whole playlist sync would abort and no mirror would link.
         * This seeds exactly the converged state the LIVE first-push produces — link,
         * baseline, per-provider N-way token, and a remote pre-filled with the
         * playlist's native ids — so `runNwayPropagation` then exercises the swap's
         * push end-to-end. The propagation path itself is cast-free.
         */
        suspend fun linkMirrors(localId: String) {
            val now = 1_000L
            val tracks = playlistTrackDao.getByPlaylistIdSync(localId)
            for (provider in providers) {
                val created = provider.createPlaylist(playlistDao.getById(localId)!!.name, null)
                val ext = created.externalId
                // Pre-fill the remote with the seeded tracks' KNOWN native ids (from
                // each track's recording mbid — NOT via the catalog search, so a
                // `{ null }` catalog still carries the initially-coverable tracks the
                // way the live first-push would). Round-trips to the canonical identity.
                val nativeIds = tracks.mapNotNull { t -> provider.seedNativeId(t) }
                if (nativeIds.isNotEmpty()) provider.addPlaylistTracks(ext, nativeIds)
                linkDao.upsertWithSnapshot(localId, provider.id, ext, snapshotId = null, syncedAt = now)
                nwayDao.upsert(localId, provider.id, changeToken = null, editedAt = now, lastSyncedAt = now)
                // The fill's bookkeeping is SETUP, not a propagation write — clear the
                // recorders so a case's add/remove/replace/search assertions count only
                // what runNwayPropagation does.
                provider.resetCallRecorders()
            }
            baselineDao.upsert(localId, nwayBaselineTrackKeys(tracks), now)
        }

        /** Seed a local playlist whose tracks key on `mbid-<id>-<i>` (title == mbid). */
        suspend fun seed(id: String, name: String, count: Int) {
            playlistDao.insert(Playlist(id = id, name = name, trackCount = count, createdAt = 1_000L, updatedAt = 1_000L, lastModified = 1_000L, locallyModified = false))
            playlistTrackDao.insertAll((0 until count).map { i -> pt(id, i, "mbid-$id-$i") })
        }

        suspend fun addLocalTrack(id: String, pos: Int, mbid: String?, title: String, artist: String) {
            playlistTrackDao.insertAll(listOf(PlaylistTrack(playlistId = id, position = pos, trackTitle = title, trackArtist = artist, trackRecordingMbid = mbid)))
        }

        suspend fun markModified(id: String, count: Int, lastModified: Long) {
            playlistDao.update(playlistDao.getById(id)!!.copy(trackCount = count, lastModified = lastModified, locallyModified = true))
        }
    }

    // ── 1. non-destructive partial coverage ─────────────────────────────────
    @Test
    fun `partial coverage — un-hydratable add never drops an existing remote track`() = runBlocking {
        // RED-before-swap: the deleted path hydrated 3/4 then took the coverage-SKIP
        // (catalog gap) branch; here the executor leaves the un-hydratable add PENDING
        // and removes NOTHING — assertable via removeByNativeIdCalls being empty.
        val lb = FakeProvider(ListenBrainzSyncProvider.PROVIDER_ID, TrackRemoveMode.ByPosition, hydrateFn = { null })
        val h = Harness(listOf(lb))
        h.seed("local-0", "Mixed", 3)
        h.linkMirrors("local-0") // create + link + baseline=3 (LB native==mbid, all coverable)

        val lbMbid = h.linkDao.selectForLink("local-0", ListenBrainzSyncProvider.PROVIDER_ID)!!.externalId
        assertEquals("LB holds the 3 seeded tracks after first sync", 3, lb.remote[lbMbid]!!.tracks.size)

        // Add a 4th local track with NO mbid → un-hydratable on LB (searchForTrackId null).
        h.addLocalTrack("local-0", 3, mbid = null, title = "No MBID", artist = "Artist 3")
        h.markModified("local-0", 4, 2_000L)

        lb.addCalls.clear(); lb.removeByPositionCalls.clear()
        h.engine.runNwayPropagation()

        assertEquals("LB keeps its 3 coverable tracks — the un-hydratable add is left pending", 3, lb.remote[lbMbid]!!.tracks.size)
        assertTrue("NOTHING removed — a pending add must never drive a removal", lb.removeByPositionCalls.isEmpty())
        assertTrue("no add issued — the only add was unresolvable (pending)", lb.addCalls.isEmpty())
        assertEquals("local keeps all 4", 4, h.playlistTrackDao.getByPlaylistIdSync("local-0").size)
        // Baseline advances unconditionally now (decoupled from coverage).
        assertEquals("baseline advances to the merged 4", 4, h.baselineDao.selectForLocal("local-0")!!.tracks.size)
    }

    // ── 2. incremental convergence (unresolvable then resolvable) ────────────
    @Test
    fun `incremental convergence — resolves next cycle, no destructive intermediate`() = runBlocking {
        // The new track carries NO recording mbid, so on Spotify it must be resolved
        // via search. Cycle 1 the catalog misses (pending); cycle 2 it matches.
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId, hydrateFn = { null })
        val h = Harness(listOf(sp))
        h.seed("local-0", "Conv", 2)
        h.linkMirrors("local-0")
        val ext = getSpotifyExternalId(h, "local-0")
        assertEquals("first sync filled Spotify with 2", 2, sp.remote[ext]!!.tracks.size)

        h.addLocalTrack("local-0", 2, mbid = null, title = "New Song", artist = "Artist 2")
        h.markModified("local-0", 3, 2_000L)

        sp.addCalls.clear()
        h.engine.runNwayPropagation() // cycle 1 — search misses → pending
        assertEquals("cycle 1: Spotify still holds 2 (new track pending, nothing dropped)", 2, sp.remote[ext]!!.tracks.size)
        assertTrue("cycle 1: no add (unresolvable)", sp.addCalls.isEmpty())

        // Cycle 2 — the catalog now matches the new track (but the negative-cache
        // cooldown would suppress the re-search; reset the cache to model "the
        // catalog recovered and a fresh attempt is allowed").
        h.cacheDao.deleteForProvider(SpotifySyncProvider.PROVIDER_ID)
        sp.hydrateFn = { title -> if (title == "New Song") "mbid-new" else null }
        sp.addCalls.clear()
        h.markModified("local-0", 3, 3_000L) // re-flag so detection re-runs the diff
        h.engine.runNwayPropagation()
        assertEquals("cycle 2: the now-resolvable track is added (incremental, no replace)", 3, sp.remote[ext]!!.tracks.size)
        assertEquals("cycle 2: exactly one add of the one new track", 1, sp.addCalls.size)
        assertTrue("never a destructive replace-all", sp.replaceCalls.isEmpty())
    }

    private fun getSpotifyExternalId(h: Harness, localId: String = "local-0"): String = runBlocking {
        h.linkDao.selectForLink(localId, SpotifySyncProvider.PROVIDER_ID)?.externalId
            ?: error("spotify mirror not linked")
    }

    // ── 3. add-heavy churn (80% replaced) ───────────────────────────────────
    @Test
    fun `add-heavy churn — 80 percent of tracks replaced flows non-destructively`() = runBlocking {
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId, hydrateFn = { it })
        val h = Harness(listOf(sp))
        h.seed("local-0", "Churn", 5)
        h.linkMirrors("local-0")
        val ext = getSpotifyExternalId(h)
        assertEquals(5, sp.remote[ext]!!.tracks.size)

        // Replace 4 of 5 (keep index 0), add 4 brand-new tracks → 5 tracks, 80% turnover.
        val replaced = buildList {
            add(pt("local-0", 0, "mbid-local-0-0"))
            (1..4).forEach { i -> add(pt("local-0", i, "mbid-fresh-$i")) }
        }
        h.playlistTrackDao.replaceAll("local-0", replaced)
        h.markModified("local-0", 5, 2_000L)

        h.engine.runNwayPropagation()
        val remoteMbids = sp.remote[ext]!!.tracks.map { it.substringAfterLast(':') }.toSet()
        assertEquals("converges to the 5 canonical tracks", 5, sp.remote[ext]!!.tracks.size)
        assertEquals("exactly the canonical identities present",
            setOf("mbid-local-0-0", "mbid-fresh-1", "mbid-fresh-2", "mbid-fresh-3", "mbid-fresh-4"), remoteMbids)
        assertTrue("never a replace-all", sp.replaceCalls.isEmpty())
        assertEquals("4 added", 1, sp.addCalls.size)
        assertEquals("4 removed", 1, sp.removeByNativeIdCalls.size)
    }

    // ── 4. multi-master (add on A + remove on B reach every writable copy) ───
    @Test
    fun `multi-master — add and remove both reach every writable copy`() = runBlocking {
        // Two writable mirrors of a local playlist (LB + Spotify). Local adds one
        // track and removes one — both deltas must land on BOTH remotes.
        val lb = FakeProvider(ListenBrainzSyncProvider.PROVIDER_ID, TrackRemoveMode.ByPosition, hydrateFn = { it })
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId, hydrateFn = { it })
        val h = Harness(listOf(lb, sp))
        h.seed("local-0", "Multi", 3)
        h.linkMirrors("local-0")
        val lbExt = h.linkDao.selectForLink("local-0", ListenBrainzSyncProvider.PROVIDER_ID)!!.externalId
        val spExt = h.linkDao.selectForLink("local-0", SpotifySyncProvider.PROVIDER_ID)!!.externalId
        assertEquals(3, lb.remote[lbExt]!!.tracks.size)
        assertEquals(3, sp.remote[spExt]!!.tracks.size)

        // Drop index 0, add a new track → {1, 2, new}.
        val edited = listOf(
            pt("local-0", 0, "mbid-local-0-1"),
            pt("local-0", 1, "mbid-local-0-2"),
            pt("local-0", 2, "mbid-added"),
        )
        h.playlistTrackDao.replaceAll("local-0", edited)
        h.markModified("local-0", 3, 2_000L)

        h.engine.runNwayPropagation()

        val want = setOf("mbid-local-0-1", "mbid-local-0-2", "mbid-added")
        assertEquals("LB converged to {1,2,new}", want, lb.remote[lbExt]!!.tracks.map { it.substringAfterLast(':') }.toSet())
        assertEquals("Spotify converged to {1,2,new}", want, sp.remote[spExt]!!.tracks.map { it.substringAfterLast(':') }.toSet())
        // Each got its own capability-specific remove + add.
        assertEquals("LB removed by position", 1, lb.removeByPositionCalls.size)
        assertEquals("Spotify removed by native id", 1, sp.removeByNativeIdCalls.size)
    }

    // ── 5. real removal vs Unsupported (AM) ─────────────────────────────────
    @Test
    fun `removal propagates on ByNativeId and ByPosition but Unsupported keeps the track`() = runBlocking {
        val lb = FakeProvider(ListenBrainzSyncProvider.PROVIDER_ID, TrackRemoveMode.ByPosition, hydrateFn = { it })
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId, hydrateFn = { it })
        val am = FakeProvider(AppleMusicSyncProvider.PROVIDER_ID, TrackRemoveMode.Unsupported, hydrateFn = { it })
        val h = Harness(listOf(lb, sp, am))
        h.seed("local-0", "Rem", 3)
        h.linkMirrors("local-0")
        val lbExt = h.linkDao.selectForLink("local-0", ListenBrainzSyncProvider.PROVIDER_ID)!!.externalId
        val spExt = h.linkDao.selectForLink("local-0", SpotifySyncProvider.PROVIDER_ID)!!.externalId
        val amExt = h.linkDao.selectForLink("local-0", AppleMusicSyncProvider.PROVIDER_ID)!!.externalId

        // Remove index 1 locally → {0, 2}.
        val kept = listOf(
            pt("local-0", 0, "mbid-local-0-0"),
            pt("local-0", 1, "mbid-local-0-2"),
        )
        h.playlistTrackDao.replaceAll("local-0", kept)
        h.markModified("local-0", 2, 2_000L)

        h.engine.runNwayPropagation()

        assertEquals("LB drops to 2 (remove honored by position)", 2, lb.remote[lbExt]!!.tracks.size)
        assertEquals("Spotify drops to 2 (remove honored by native id)", 2, sp.remote[spExt]!!.tracks.size)
        assertEquals("AM still holds 3 — remove is Unsupported, track stays", 3, am.remote[amExt]!!.tracks.size)
        assertTrue("AM issued no remove call of any kind",
            am.removeByNativeIdCalls.isEmpty() && am.removeByPositionCalls.isEmpty())
    }

    // ── 6. capability dispatch ──────────────────────────────────────────────
    @Test
    fun `capability dispatch — each removeMode takes exactly its own path`() = runBlocking {
        val byId = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId, hydrateFn = { it })
        val byPos = FakeProvider(ListenBrainzSyncProvider.PROVIDER_ID, TrackRemoveMode.ByPosition, hydrateFn = { it })
        val unsup = FakeProvider(AppleMusicSyncProvider.PROVIDER_ID, TrackRemoveMode.Unsupported, hydrateFn = { it })
        val h = Harness(listOf(byId, byPos, unsup))
        h.seed("local-0", "Disp", 2)
        h.linkMirrors("local-0")

        // Remove the first track everywhere → exercises each remove path.
        val kept = listOf(pt("local-0", 0, "mbid-local-0-1"))
        h.playlistTrackDao.replaceAll("local-0", kept)
        h.markModified("local-0", 1, 2_000L)
        h.engine.runNwayPropagation()

        assertEquals("ByNativeId provider used the native-id remove path", 1, byId.removeByNativeIdCalls.size)
        assertTrue("ByNativeId provider never hit the position path", byId.removeByPositionCalls.isEmpty())
        assertEquals("ByPosition provider used the position remove path", 1, byPos.removeByPositionCalls.size)
        assertTrue("ByPosition provider never hit the native-id path", byPos.removeByNativeIdCalls.isEmpty())
        assertTrue("Unsupported provider never removed", unsup.removeByNativeIdCalls.isEmpty() && unsup.removeByPositionCalls.isEmpty())
        assertTrue("no provider ever replace-all'd", byId.replaceCalls.isEmpty() && byPos.replaceCalls.isEmpty() && unsup.replaceCalls.isEmpty())
    }

    // ── 7. total-wipe-only (canonical→0 blocked; large non-empty drop allowed) ─
    @Test
    fun `total wipe blocked but a large non-empty drop is allowed`() = runBlocking {
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId, hydrateFn = { it })
        val h = Harness(listOf(sp))
        h.seed("local-0", "Wipe", 8)
        h.linkMirrors("local-0")
        val ext = getSpotifyExternalId(h)

        // (a) total wipe — local → 0 must abort, remote untouched.
        h.playlistTrackDao.replaceAll("local-0", emptyList())
        h.markModified("local-0", 0, 2_000L)
        sp.removeByNativeIdCalls.clear()
        h.engine.runNwayPropagation()
        assertEquals("TOTAL-WIPE: remote untouched", 8, sp.remote[ext]!!.tracks.size)
        assertTrue("TOTAL-WIPE: no remove issued", sp.removeByNativeIdCalls.isEmpty())

        // (b) large non-empty drop (8 → 2, 75%) must propagate.
        val remaining = (0..1).map { i -> pt("local-0", i, "mbid-local-0-$i") }
        h.playlistTrackDao.replaceAll("local-0", remaining)
        h.markModified("local-0", 2, 3_000L)
        h.engine.runNwayPropagation()
        assertEquals("large non-empty drop propagates (75%)", 2, sp.remote[ext]!!.tracks.size)
    }

    // ── 8. idempotency ×2 — a no-op cycle fires ZERO writes ─────────────────
    @Test
    fun `idempotent — a no-op second cycle fires zero add remove or replace`() = runBlocking {
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId, hydrateFn = { it })
        val h = Harness(listOf(sp))
        h.seed("local-0", "Idem", 3)
        h.linkMirrors("local-0")
        val ext = getSpotifyExternalId(h)

        // Diverge once, converge.
        h.addLocalTrack("local-0", 3, mbid = "mbid-extra", title = "Extra", artist = "Artist 3")
        h.markModified("local-0", 4, 2_000L)
        h.engine.runNwayPropagation()
        assertEquals(4, sp.remote[ext]!!.tracks.size)

        // No new local change → second propagation must be a pure no-op.
        sp.addCalls.clear(); sp.removeByNativeIdCalls.clear(); sp.replaceCalls.clear()
        h.engine.runNwayPropagation()
        assertTrue("no add on the no-op cycle", sp.addCalls.isEmpty())
        assertTrue("no remove on the no-op cycle", sp.removeByNativeIdCalls.isEmpty())
        assertTrue("no replace on the no-op cycle", sp.replaceCalls.isEmpty())
        assertEquals("remote unchanged", 4, sp.remote[ext]!!.tracks.size)
    }

    // ── 9. identity-diff — same song bridged by the norm tier, no spurious churn ─
    @Test
    fun `identity diff — a remaster-drifted same song yields no spurious churn`() = runBlocking {
        // Strongest-tier bridging: the local track has a recording mbid, but the
        // round-tripped remote copy of the SAME song carries a DIFFERENT recording
        // mbid AND a "- 2025 Remastered" title-suffix (exactly what a service returns
        // on round-trip). The norm-strip identity tier must bridge them so the diff
        // sees NO add and NO remove. A naive id-equality diff would churn (remove the
        // old mbid + add the new) — the regression this guards.
        val lb = FakeProvider(ListenBrainzSyncProvider.PROVIDER_ID, TrackRemoveMode.ByPosition, hydrateFn = { null })
        val h = Harness(listOf(lb))
        h.playlistDao.insert(Playlist(id = "local-0", name = "Drift", trackCount = 1, createdAt = 1_000L, updatedAt = 1_000L, lastModified = 1_000L, locallyModified = false))
        h.playlistTrackDao.insertAll(listOf(
            PlaylistTrack(playlistId = "local-0", position = 0, trackTitle = "Clarity", trackArtist = "Zedd", trackRecordingMbid = "mbid-local-clarity"),
        ))
        h.linkMirrors("local-0")
        val ext = h.linkDao.selectForLink("local-0", ListenBrainzSyncProvider.PROVIDER_ID)!!.externalId

        // Manually drift the remote: a DIFFERENT mbid + remaster-suffixed title, SAME
        // artist+base title (so the norm-strip tier bridges them). LB is snapshot-less,
        // so the trackCount stays 1 → unchanged by the cheap list signal; force a
        // re-fetch by bumping the count signal AND re-flagging local.
        lb.remote[ext]!!.tracks.clear()
        lb.remote[ext]!!.tracks.add("mbid-remote-clarity-remaster")
        // Round-trip identity for this drifted remote track: title carries the
        // remaster suffix, artist matches; the norm-strip collapses to "zedd|clarity".
        lb.driftRichTracks[ext] = listOf(
            PlaylistTrack(playlistId = "listenbrainz-$ext", position = 0, trackTitle = "Clarity - 2025 Remastered", trackArtist = "Zedd", trackRecordingMbid = "mbid-remote-clarity-remaster"),
        )
        h.markModified("local-0", 1, 2_000L)
        lb.addCalls.clear(); lb.removeByPositionCalls.clear()
        h.engine.runNwayPropagation()

        assertTrue("no spurious add — the remaster-drifted song is the SAME identity", lb.addCalls.isEmpty())
        assertTrue("no spurious remove — never churns a bridged identity", lb.removeByPositionCalls.isEmpty())
        assertEquals("remote still holds the one (bridged) track", 1, lb.remote[ext]!!.tracks.size)
    }

    // ── 10. cooldown — an unresolvable add isn't re-searched within the window ─
    @Test
    fun `cooldown — an unresolvable add is not re-searched within the window`() = runBlocking {
        // searchForTrackId always misses; across two propagation cycles (separate
        // coordinators, shared persistent cache) the SECOND must short-circuit on the
        // negative-cache cooldown — total searchCalls stays at 1 for the track.
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId, hydrateFn = { null })
        val h = Harness(listOf(sp))
        h.seed("local-0", "Cool", 2)
        h.linkMirrors("local-0")

        // Add an un-hydratable track (no mbid → must search → misses).
        h.addLocalTrack("local-0", 2, mbid = null, title = "Ghost", artist = "Nobody")
        h.markModified("local-0", 3, 2_000L)

        h.engine.runNwayPropagation() // cycle 1 — one live search, misses, stamps the cache
        val afterFirst = sp.searchCalls
        assertTrue("cycle 1 performed at least one search", afterFirst >= 1)

        // Re-flag so detection re-runs the diff, but the track is still un-hydratable.
        h.markModified("local-0", 3, 3_000L)
        h.engine.runNwayPropagation() // cycle 2 — cooldown must suppress the re-search

        assertEquals("cooldown: the un-hydratable track is NOT re-searched in cycle 2", afterFirst, sp.searchCalls)
        // And nothing got destructively dropped across either cycle.
        val ext = getSpotifyExternalId(h)
        assertFalse("Spotify remote never emptied", sp.remote[ext]!!.tracks.isEmpty())
    }
}

/**
 * Track factory enforcing the harness invariant title == recordingMbid, so the
 * provider's search (which only sees the title) recovers the exact mbid and the
 * round-trip identity is exact.
 */
private fun pt(playlistId: String, position: Int, mbid: String): PlaylistTrack =
    PlaylistTrack(playlistId = playlistId, position = position, trackTitle = mbid, trackArtist = mbid, trackRecordingMbid = mbid)
