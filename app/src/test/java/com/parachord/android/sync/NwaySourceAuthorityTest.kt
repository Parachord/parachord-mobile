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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PULL-SOURCE AUTHORITY in the N-way merge (Option A,
 * `docs/plans/2026-06-24-merge-source-authority-design.md`).
 *
 * The authoritative PULL SOURCE has the authority to DROP a baseline track: when
 * a CHURNING source (Spotify Daily Brew rotates ~40/day; a hosted-XSPF replaces
 * its set daily) rotates a track OUT, that absence must read as a GENUINE
 * deletion — NOT be re-added by the pending-aware augmentation (which would
 * accumulate the rotated-out residue forever — the Daily Brew / SiriusXMU bloat).
 *
 * Two refinements are load-bearing and pinned here:
 *  - **Refinement A** — authority is granted ONLY when the source is
 *    REMOVAL-CAPABLE (`trackRemoveMode != Unsupported`). An add-only source
 *    (Apple Music) keeps augment-all so a transient PARTIAL fetch can't read as
 *    deletions (V6).
 *  - **Refinement B** — the keys the authoritative copy dropped this cycle are
 *    subtracted from EVERY mirror's pending re-add, so no mirror can resurrect a
 *    source-deleted key (V7, the hosted-XSPF closer).
 *
 * This is a RECONCILIATION-layer change only — `PlaylistMerge.merge` stays
 * bit-for-bit identical (desktop #911 parity). The vectors mirror
 * [NwayMaterializeTest]'s Harness/FakeProvider.
 */
class NwaySourceAuthorityTest {

    /** Remote-modeling provider (identical model to [NwayMaterializeTest.FakeProvider]). */
    private class FakeProvider(
        override val id: String,
        private val removeMode: TrackRemoveMode,
        var hydrateFn: ((title: String) -> String?)? = { it },
    ) : SyncProvider {
        override val displayName = "$id (authority fake)"
        override val features = ProviderFeatures(
            snapshots = SnapshotKind.None,
            trackRemoveMode = removeMode,
        )

        private val prefix = "$id-nid"
        private fun nidFor(mbid: String) = "$prefix:$mbid"
        private fun mbidOf(nativeId: String) = nativeId.substringAfter("$prefix:")

        fun seedNativeId(track: PlaylistTrack): String? {
            val mbid = track.trackRecordingMbid ?: return null
            return if (id == ListenBrainzSyncProvider.PROVIDER_ID) mbid else nidFor(mbid)
        }

        class Entry(val externalId: String, val name: String) {
            val tracks = mutableListOf<String>()
        }

        val remote = linkedMapOf<String, Entry>()

        var createCount = 0
            private set
        val addCalls = mutableListOf<Pair<String, List<String>>>()
        val removeByNativeIdCalls = mutableListOf<Pair<String, List<String>>>()
        val removeByPositionCalls = mutableListOf<Pair<String, List<Int>>>()
        val replaceCalls = mutableListOf<Pair<String, List<String>>>()

        fun resetCallRecorders() {
            addCalls.clear(); removeByNativeIdCalls.clear()
            removeByPositionCalls.clear(); replaceCalls.clear()
        }

        /** Force this remote to a specific set of recording mbids (rotate / partial fetch). */
        fun setRemoteMbids(externalId: String, mbids: List<String>) {
            val e = remote[externalId] ?: return
            e.tracks.clear()
            e.tracks.addAll(mbids.map { if (id == ListenBrainzSyncProvider.PROVIDER_ID) it else nidFor(it) })
        }

        override fun nativeIdOf(track: PlaylistTrack): String? = when (id) {
            ListenBrainzSyncProvider.PROVIDER_ID -> track.trackRecordingMbid
            SpotifySyncProvider.PROVIDER_ID -> track.trackSpotifyUri
            AppleMusicSyncProvider.PROVIDER_ID -> track.trackAppleMusicId
            else -> null
        }

        override suspend fun searchForTrackId(title: String, artist: String, album: String?, isrc: String?): String? {
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

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String): List<PlaylistTrack> =
            remote[externalPlaylistId]?.tracks?.mapIndexed { i, nid ->
                val mbid = mbidOf(nid)
                val base = PlaylistTrack(
                    playlistId = "$id-$externalPlaylistId",
                    position = i,
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

        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
        override suspend fun remotePlaylistExists(externalPlaylistId: String): Boolean = true
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
        val sourceDao = SyncPlaylistSourceDao(db)
        val baselineDao = SyncPlaylistBaselineDao(db)
        val nwayDao = SyncPlaylistNwayDao(db)
        val cacheDao = TrackProviderIdCacheDao(db)

        val engine = SyncEngine(
            db = db, driver = driver, trackDao = trackDao, albumDao = AlbumDao(db), artistDao = ArtistDao(db),
            playlistDao = playlistDao, playlistTrackDao = playlistTrackDao, syncSourceDao = SyncSourceDao(db),
            syncPlaylistLinkDao = linkDao, syncPlaylistSourceDao = sourceDao,
            syncPlaylistBaselineDao = baselineDao, syncPlaylistNwayDao = nwayDao,
            trackProviderIdCacheDao = cacheDao,
            settingsStore = FakeSyncSettings(providers.map { it.id }.toSet()),
            providers = providers,
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )

        suspend fun linkMirrors(localId: String) {
            val now = 1_000L
            val tracks = playlistTrackDao.getByPlaylistIdSync(localId)
            for (provider in providers) {
                val created = provider.createPlaylist(playlistDao.getById(localId)!!.name, null)
                val ext = created.externalId
                val nativeIds = tracks.mapNotNull { t -> provider.seedNativeId(t) }
                if (nativeIds.isNotEmpty()) provider.addPlaylistTracks(ext, nativeIds)
                linkDao.upsertWithSnapshot(localId, provider.id, ext, snapshotId = null, syncedAt = now)
                nwayDao.upsert(localId, provider.id, changeToken = null, editedAt = now, lastSyncedAt = now)
                provider.resetCallRecorders()
            }
            baselineDao.upsert(localId, nwayBaselineTrackKeys(tracks), now)
        }

        /** Record `providerId` as the authoritative PULL SOURCE for `localId`. */
        suspend fun linkPullSource(localId: String, providerId: String, externalId: String) =
            sourceDao.upsert(localId, providerId, externalId, snapshotId = null, ownerId = null, syncedAt = 1_000L)

        /** Seed a local playlist; `sourceUrl` non-null marks it a hosted-XSPF (local-authoritative). */
        suspend fun seed(id: String, name: String, count: Int, sourceUrl: String? = null) {
            playlistDao.insert(Playlist(id = id, name = name, trackCount = count, createdAt = 1_000L, updatedAt = 1_000L, lastModified = 1_000L, locallyModified = false, sourceUrl = sourceUrl))
            playlistTrackDao.insertAll((0 until count).map { i -> pt(id, i, "mbid-$id-$i") })
        }

        suspend fun addLocalTrack(id: String, pos: Int, mbid: String?, title: String, artist: String) {
            playlistTrackDao.insertAll(listOf(PlaylistTrack(playlistId = id, position = pos, trackTitle = title, trackArtist = artist, trackRecordingMbid = mbid)))
        }

        suspend fun markModified(id: String, count: Int, lastModified: Long) {
            playlistDao.update(playlistDao.getById(id)!!.copy(trackCount = count, lastModified = lastModified, locallyModified = true))
        }

        fun localMbids(id: String): List<String> = runBlocking {
            playlistTrackDao.getByPlaylistIdSync(id).sortedBy { it.position }.map { it.trackRecordingMbid ?: it.trackTitle ?: "?" }
        }

        fun baselineSize(id: String): Int = runBlocking { baselineDao.selectForLocal(id)!!.tracks.size }
    }

    private fun ext(h: Harness, localId: String, providerId: String): String = runBlocking {
        h.linkDao.selectForLink(localId, providerId)!!.externalId
    }

    private fun remoteMbids(p: FakeProvider, ext: String): Set<String> =
        p.remote[ext]!!.tracks.map { it.substringAfterLast(':') }.toSet()

    // ── V1 — spotify source rotates out → residue drops to the live set ──────
    @Test
    fun `V1 spotify source rotates out — residue drops to live set`() = runBlocking {
        // baseline = 5 keys. Spotify (source, ByNativeId) ROTATED to 2 of the 5
        // (changed=true). It's PENDING on the 3 rotated-out keys (no cache entry) —
        // exactly the condition where the OLD augmentation would re-add them onto the
        // source and they'd survive forever (the Daily Brew bloat). Source-skip makes
        // Spotify vote-remove them. AM holds all 5 (the add-only residue).
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId)
        val am = FakeProvider(AppleMusicSyncProvider.PROVIDER_ID, TrackRemoveMode.Unsupported)
        val h = Harness(listOf(sp, am))
        h.seed("local-0", "Daily Brew", 5)
        h.linkMirrors("local-0")
        val spExt = ext(h, "local-0", SpotifySyncProvider.PROVIDER_ID)
        val amExt = ext(h, "local-0", AppleMusicSyncProvider.PROVIDER_ID)
        h.linkPullSource("local-0", SpotifySyncProvider.PROVIDER_ID, spExt)

        // Spotify rotated to the 2 live keys; AM keeps all 5 (add-only residue).
        sp.setRemoteMbids(spExt, listOf("mbid-local-0-0", "mbid-local-0-1"))
        sp.resetCallRecorders(); am.resetCallRecorders()

        h.engine.runNwayPropagation()

        val live = setOf("mbid-local-0-0", "mbid-local-0-1")
        assertEquals("canonical/local converges to the 2 live keys — the 3 rotated-out residue dropped",
            live, h.localMbids("local-0").toSet())
        assertEquals("local is exactly 2", 2, h.localMbids("local-0").size)
        assertEquals("baseline advanced to 2", 2, h.baselineSize("local-0"))
        assertTrue("Spotify (source) issued no add — it already holds its 2 live keys", sp.addCalls.isEmpty())
        // AM is an Unsupported add-only mirror that physically lags canonical — its
        // remove is a no-op and its residue persists (cannot be fixed app-side).
        assertTrue("AM issued no removal of any kind (Unsupported)",
            am.removeByNativeIdCalls.isEmpty() && am.removeByPositionCalls.isEmpty())
        assertEquals("AM physical residue is NOT resurrected onto canonical (still 5 on the mirror)",
            5, am.remote[amExt]!!.tracks.size)
    }

    // ── V2 — catalog-gap on an AM mirror survives source authority ───────────
    @Test
    fun `V2 catalog-gap on AM mirror survives source authority`() = runBlocking {
        // baseline = 4: K0,K1,K2,Kgap. Spotify (source) rotated to {K0,K1,Kgap}
        // (drops K2, KEEPS Kgap). AM can't store Kgap (hydrateFn null for it) and is
        // pending on it → AM is augmented for Kgap (Kgap ∉ authoritativeDropped, since
        // the source still asserts it). Result: K2 (source-dropped) gone, Kgap kept.
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId)
        val am = FakeProvider(AppleMusicSyncProvider.PROVIDER_ID, TrackRemoveMode.Unsupported,
            hydrateFn = { title -> if (title == "mbid-local-0-3") null else title })
        val h = Harness(listOf(sp, am))
        h.seed("local-0", "Gap", 4) // K0..K3; treat K3 == "Kgap"
        h.linkMirrors("local-0")
        val spExt = ext(h, "local-0", SpotifySyncProvider.PROVIDER_ID)
        val amExt = ext(h, "local-0", AppleMusicSyncProvider.PROVIDER_ID)
        h.linkPullSource("local-0", SpotifySyncProvider.PROVIDER_ID, spExt)

        // Spotify source: drop K2, keep the catalog-gap key K3. (changed=true, size 3.)
        sp.setRemoteMbids(spExt, listOf("mbid-local-0-0", "mbid-local-0-1", "mbid-local-0-3"))
        // AM: physically lacks the catalog-gap key K3 (changed=true, size 3), pending on it.
        am.setRemoteMbids(amExt, listOf("mbid-local-0-0", "mbid-local-0-1", "mbid-local-0-2"))
        sp.resetCallRecorders(); am.resetCallRecorders()

        h.engine.runNwayPropagation()

        assertEquals("canonical = {K0,K1,Kgap} — K2 (source-dropped) gone, catalog-gap Kgap survives",
            setOf("mbid-local-0-0", "mbid-local-0-1", "mbid-local-0-3"), h.localMbids("local-0").toSet())
        assertEquals("canonical size 3", 3, h.localMbids("local-0").size)
    }

    // ── V3 — multimaster local-authored (no source row) — UNCHANGED ──────────
    @Test
    fun `V3 multimaster local-authored — unchanged, no source row`() = runBlocking {
        // Replicates NwayMaterializeTest case 1 EXACTLY but lives here to pin that
        // authoritativeCopyId==null leaves the augmentation byte-identical: an
        // un-hydratable add stays pending, NOTHING dropped, baseline advances to 4.
        val lb = FakeProvider(ListenBrainzSyncProvider.PROVIDER_ID, TrackRemoveMode.ByPosition, hydrateFn = { null })
        val h = Harness(listOf(lb))
        h.seed("local-0", "Multimaster", 3) // no linkPullSource, sourceUrl=null
        h.linkMirrors("local-0")
        val lbExt = ext(h, "local-0", ListenBrainzSyncProvider.PROVIDER_ID)
        assertEquals(3, lb.remote[lbExt]!!.tracks.size)

        h.addLocalTrack("local-0", 3, mbid = null, title = "No MBID", artist = "Artist 3")
        h.markModified("local-0", 4, 2_000L)
        lb.resetCallRecorders()

        h.engine.runNwayPropagation()

        assertEquals("LB keeps its 3 — un-hydratable add stays pending (multimaster path inert)",
            3, lb.remote[lbExt]!!.tracks.size)
        assertTrue("NOTHING removed", lb.removeByPositionCalls.isEmpty())
        assertEquals("local keeps all 4", 4, h.localMbids("local-0").size)
        assertEquals("baseline advances to the merged 4", 4, h.baselineSize("local-0"))
    }

    // ── V4 — local-add-pending on a spotify-sourced playlist is KEPT ─────────
    @Test
    fun `V4 local-add-pending on a spotify-sourced playlist is kept`() = runBlocking {
        // Spotify source, baseline = 3 (all on Spotify, unchanged). Add a 4th
        // local-only track X (not on Spotify). X is a union-ADD (non-baseline) — the
        // authority logic (which only touches baseline keys) can NEVER drop it.
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId,
            hydrateFn = { title -> if (title == "X local only") null else title })
        val h = Harness(listOf(sp))
        h.seed("local-0", "AddPending", 3)
        h.linkMirrors("local-0")
        val spExt = ext(h, "local-0", SpotifySyncProvider.PROVIDER_ID)
        h.linkPullSource("local-0", SpotifySyncProvider.PROVIDER_ID, spExt)

        h.addLocalTrack("local-0", 3, mbid = "mbid-X", title = "X local only", artist = "Nobody")
        h.markModified("local-0", 4, 2_000L)
        sp.resetCallRecorders()

        h.engine.runNwayPropagation()

        assertEquals("local-add X is kept (union-add, never touched by authority); canonical = 4",
            4, h.localMbids("local-0").size)
        assertTrue("the 3 baseline keys are all still present", h.localMbids("local-0").containsAll(
            listOf("mbid-local-0-0", "mbid-local-0-1", "mbid-local-0-2")))
        assertTrue("X present in canonical", h.localMbids("local-0").contains("mbid-X"))
    }

    // ── V5 — a local removal still on the spotify source still DROPS ─────────
    @Test
    fun `V5 local removal still on spotify source still drops`() = runBlocking {
        // Spotify (source) STILL holds Y. The user removes Y LOCALLY. `local` is never
        // augmented and always votes-remove — authority is purely additive on the
        // source's augmentation, never an override of a local removal. Y drops even
        // though the source lists it (and Y ∈ source.keys ⇒ not in authoritativeDropped,
        // so nothing re-adds it).
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId)
        val h = Harness(listOf(sp))
        h.seed("local-0", "LocalRemoval", 3) // K0,K1,K2 ; remove K1 (== "Y") locally
        h.linkMirrors("local-0")
        val spExt = ext(h, "local-0", SpotifySyncProvider.PROVIDER_ID)
        h.linkPullSource("local-0", SpotifySyncProvider.PROVIDER_ID, spExt)

        // Spotify source still holds all 3 (unchanged); local drops Y (K1).
        h.playlistTrackDao.replaceAll("local-0", listOf(
            pt("local-0", 0, "mbid-local-0-0"),
            pt("local-0", 1, "mbid-local-0-2"),
        ))
        h.markModified("local-0", 2, 2_000L)
        sp.resetCallRecorders()

        h.engine.runNwayPropagation()

        assertEquals("Y dropped from canonical even though the source still lists it",
            setOf("mbid-local-0-0", "mbid-local-0-2"), h.localMbids("local-0").toSet())
        assertEquals("canonical size 2", 2, h.localMbids("local-0").size)
        assertEquals("Spotify (the source) had Y removed by native id", 1, sp.removeByNativeIdCalls.size)
    }

    // ── V6 — AM add-only source — authority DECLINED, augment-all ────────────
    @Test
    fun `V6 applemusic add-only source — authority declined, augment-all`() = runBlocking {
        // AM is the PULL SOURCE but add-only (Unsupported) → Refinement A DECLINES
        // authority. AM's `fetchPlaylistTracks` transiently returns only 2 of the 4
        // baseline keys (PARTIAL, non-empty, changed=true). With augment-all, AM's
        // partial absence is suppressed as pending → NOTHING drops. (Granting AM
        // authority here would drop 2 live tracks on a transient blip.)
        val am = FakeProvider(AppleMusicSyncProvider.PROVIDER_ID, TrackRemoveMode.Unsupported)
        val sp = FakeProvider(SpotifySyncProvider.PROVIDER_ID, TrackRemoveMode.ByNativeId)
        val h = Harness(listOf(am, sp))
        h.seed("local-0", "AmSource", 4)
        h.linkMirrors("local-0")
        val amExt = ext(h, "local-0", AppleMusicSyncProvider.PROVIDER_ID)
        h.linkPullSource("local-0", AppleMusicSyncProvider.PROVIDER_ID, amExt)

        // AM transiently fetches only 2 of the 4 (partial, changed=true). Spotify holds 4.
        am.setRemoteMbids(amExt, listOf("mbid-local-0-0", "mbid-local-0-1"))
        am.resetCallRecorders(); sp.resetCallRecorders()

        h.engine.runNwayPropagation()

        assertEquals("authority DECLINED for add-only source — the partial AM fetch drops NOTHING; canonical = 4",
            setOf("mbid-local-0-0", "mbid-local-0-1", "mbid-local-0-2", "mbid-local-0-3"),
            h.localMbids("local-0").toSet())
        assertEquals("canonical size still 4", 4, h.localMbids("local-0").size)
    }

    // ── V7 — hosted XSPF — XSPF removal drops, mirror cannot resurrect ───────
    @Test
    fun `V7 hosted XSPF — XSPF removal drops, mirror cannot resurrect`() = runBlocking {
        // sourceUrl != null ⇒ the LOCAL copy is authoritative. The XSPF poll replaced
        // local to 3 of the 5 baseline keys (K3,K4 removed; markModified ⇒
        // changed=true). authoritativeDropped = baseline(5) − local.keys(3) = {K3,K4}.
        // The AM mirror is changed and pending on K4 — but K4 ∈ authoritativeDropped ⇒
        // subtracted from AM's pending re-add ⇒ AM CANNOT resurrect it. canonical = 3.
        val am = FakeProvider(AppleMusicSyncProvider.PROVIDER_ID, TrackRemoveMode.Unsupported)
        val h = Harness(listOf(am))
        h.seed("local-0", "Hosted", 5, sourceUrl = "https://example.com/p.xspf")
        h.linkMirrors("local-0")
        val amExt = ext(h, "local-0", AppleMusicSyncProvider.PROVIDER_ID)

        // XSPF replaced local to {K0,K1,K2} (drops K3,K4).
        h.playlistTrackDao.replaceAll("local-0", listOf(
            pt("local-0", 0, "mbid-local-0-0"),
            pt("local-0", 1, "mbid-local-0-1"),
            pt("local-0", 2, "mbid-local-0-2"),
        ))
        h.markModified("local-0", 3, 2_000L)
        // AM (add-only) physically lacks K4 (changed=true, size 4), pending on it.
        am.setRemoteMbids(amExt, listOf("mbid-local-0-0", "mbid-local-0-1", "mbid-local-0-2", "mbid-local-0-3"))
        am.resetCallRecorders()

        h.engine.runNwayPropagation()

        assertEquals("canonical = {K0,K1,K2} — the 2 XSPF-removed keys dropped, AM did not resurrect K4",
            setOf("mbid-local-0-0", "mbid-local-0-1", "mbid-local-0-2"), h.localMbids("local-0").toSet())
        assertEquals("canonical size 3", 3, h.localMbids("local-0").size)
        assertEquals("baseline advanced to 3", 3, h.baselineSize("local-0"))
    }
}

private fun pt(playlistId: String, position: Int, mbid: String): PlaylistTrack =
    PlaylistTrack(playlistId = playlistId, position = position, trackTitle = mbid, trackArtist = mbid, trackRecordingMbid = mbid)
