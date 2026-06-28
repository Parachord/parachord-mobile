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
import com.parachord.shared.sync.SpotifySyncProvider
import com.parachord.shared.sync.SyncEngine
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncSettings
import com.parachord.shared.sync.SyncSettingsProvider
import com.parachord.shared.sync.SyncedPlaylist
import com.parachord.shared.sync.TombstoneStore
import com.parachord.shared.sync.Tombstone
import com.parachord.shared.sync.TrackTombstoneService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration coverage for the probe-gated dead-mirror reconcile wired into
 * [SyncEngine.syncPlaylists]. Two workstreams:
 *   B) the removal cleanups only delete a non-local pull-sourced row when the
 *      provider's targeted existence probe CONFIRMS a 404 — a partial/transient
 *      fetch must never mass-delete (tests B1/B2).
 *   A) the push dedup's stale-link branch probes before re-creating: confirmed
 *      gone → detach (override-exclude + drop link); still-exists → keep the
 *      link, never re-create (tests A1/A2).
 *
 * Mirrors the in-memory JDBC harness from NwaySourceAuthorityTest, with a
 * settings fake that actually STORES channel overrides (so detach is assertable)
 * and a provider fake whose fetch list + existence probe are both controllable.
 */
class DeadMirrorReconcileIntegrationTest {

    // ── B1 — helper path: a partial fetch must NOT delete a still-live AM-sourced row ──
    @Test
    fun `B1 helper — partial fetch keeps a non-local provider-sourced playlist`() = runBlocking {
        val am = FakeProvider("applemusic")
        val h = Harness(listOf(am))
        // A non-local, applemusic-sourced row (a pulled AM playlist).
        h.seed("applemusic-keepme", "Keep Me", sourceUrl = null)
        h.seedSource("applemusic-keepme", "applemusic", "keepme-ext")
        // The fetch came back EMPTY (truncated/partial) but the playlist still
        // exists — the probe says so.
        am.remotePlaylistsList = emptyList()
        am.goneExternalIds = emptySet() // "keepme-ext" still exists

        h.engine.syncAll()

        assertNotNull(
            "a still-live mirror absent from a partial fetch must NOT be removed",
            h.playlistDao.getById("applemusic-keepme"),
        )
        assertTrue(
            "its sync_source survives (not falsely cleaned up)",
            h.syncSourceDao.getByProvider("applemusic", "playlist").isNotEmpty(),
        )
    }

    // NOTE: the inline Spotify pull path (`syncPlaylists`) casts its provider to
    // the concrete SpotifySyncProvider, so it can't be exercised with a fake the
    // way the generic per-provider helper can. It reuses the SAME
    // `confirmedGoneMirrors` removal gate as the helper path (covered by B1) and
    // the pure-function tests, plus on-device verification.

    // ── A1 — confirmed-gone mirror on a local-* row: detach (drop link + override-exclude), preserve other live mirrors ──
    @Test
    fun `A1 detach — confirmed-gone mirror drops link and excludes provider, preserving the live one`() = runBlocking {
        val am = FakeProvider("applemusic")
        val h = Harness(listOf(am))
        // A user-created local playlist mirrored to BOTH Apple Music (now deleted)
        // and ListenBrainz (still live; no LB provider in this run).
        h.seed("local-x", "My Mix", sourceUrl = null)
        h.linkMirror("local-x", "applemusic", "am-dead")
        h.linkMirror("local-x", "listenbrainz", "lb-live")
        am.remotePlaylistsList = emptyList()
        am.goneExternalIds = setOf("am-dead") // probe → 404

        h.engine.syncAll()

        assertEquals(
            "channel override now excludes the dead provider but keeps the live LB mirror",
            setOf("listenbrainz"),
            h.settings.getPlaylistChannels("local-x"),
        )
        assertNull(
            "the dead Apple Music link is dropped",
            h.linkDao.selectForLink("local-x", "applemusic"),
        )
        assertNotNull(
            "the live ListenBrainz link is untouched",
            h.linkDao.selectForLink("local-x", "listenbrainz"),
        )
        assertNotNull(
            "the local playlist row itself is never deleted",
            h.playlistDao.getById("local-x"),
        )
        assertEquals("no new mirror was created", 0, am.createCount)
    }

    // ── A2 — stale link but probe says it EXISTS (partial fetch): keep the link, never re-create ──
    @Test
    fun `A2 partial — stale link with probe-exists is kept and not re-created`() = runBlocking {
        val am = FakeProvider("applemusic")
        val h = Harness(listOf(am))
        h.seed("local-y", "Keepers", sourceUrl = null)
        h.linkMirror("local-y", "applemusic", "am-live")
        am.remotePlaylistsList = emptyList() // partial fetch: the mirror is absent…
        am.goneExternalIds = emptySet()       // …but the probe says it still EXISTS

        h.engine.syncAll()

        assertNotNull(
            "a stale-in-fetch but still-live link must be KEPT, not cleared",
            h.linkDao.selectForLink("local-y", "applemusic"),
        )
        assertNull(
            "no override is written when the mirror still exists",
            h.settings.getPlaylistChannels("local-y"),
        )
        assertEquals("the push loop did NOT re-create a duplicate mirror", 0, am.createCount)
    }

    // ── B3 — confirmed-gone mirror on a non-local row IS removed (the deletion side fires) ──
    @Test
    fun `B3 removal — confirmed-gone mirror on a non-local row is deleted`() = runBlocking {
        val am = FakeProvider("applemusic")
        val h = Harness(listOf(am))
        h.seed("applemusic-gone", "Deleted Upstream", sourceUrl = null)
        h.seedSource("applemusic-gone", "applemusic", "gone-ext")
        am.remotePlaylistsList = emptyList()
        am.goneExternalIds = setOf("gone-ext") // probe → 404

        h.engine.syncAll()

        assertNull(
            "a non-local row whose mirror is probe-confirmed gone is removed",
            h.playlistDao.getById("applemusic-gone"),
        )
        assertTrue(
            "its sync_source is cleaned up",
            h.syncSourceDao.getByProvider("applemusic", "playlist").isEmpty(),
        )
    }

    // ── B-mass — an implausible mass disappearance is treated as a partial fetch (floor) ──
    @Test
    fun `B mass-floor — over-floor suspected mirrors are NOT removed`() = runBlocking {
        val am = FakeProvider("applemusic")
        val h = Harness(listOf(am))
        // 31 non-local AM-sourced rows; the fetch dropped ALL of them at once,
        // which is almost certainly partial, so the floor (30) skips the whole
        // reconcile WITHOUT probing or removing any — even though every probe
        // WOULD have said "gone".
        val ids = (0 until 31).map { "applemusic-mass-$it" }
        for ((i, id) in ids.withIndex()) {
            h.seed(id, "Mass $i", sourceUrl = null)
            h.seedSource(id, "applemusic", "mass-ext-$i")
        }
        am.remotePlaylistsList = emptyList()
        am.goneExternalIds = ids.indices.map { "mass-ext-$it" }.toSet()

        h.engine.syncAll()

        assertEquals(
            "the mass-disappearance floor refuses to remove any row on a likely-partial fetch",
            31,
            ids.count { h.playlistDao.getById(it) != null },
        )
    }

    // ── A3 — single dead mirror → the override goes EMPTY (not null), link dropped ──
    @Test
    fun `A3 detach — single dead mirror writes an empty override and drops the link`() = runBlocking {
        val am = FakeProvider("applemusic")
        val h = Harness(listOf(am))
        h.seed("local-w", "Solo", sourceUrl = null)
        h.linkMirror("local-w", "applemusic", "am-only")
        am.remotePlaylistsList = emptyList()
        am.goneExternalIds = setOf("am-only")

        h.engine.syncAll()

        assertEquals(
            "detaching the only mirror leaves an EMPTY override (syncs with nothing), not null",
            emptySet<String>(),
            h.settings.getPlaylistChannels("local-w"),
        )
        assertNull("the dead link is dropped", h.linkDao.selectForLink("local-w", "applemusic"))
        assertNotNull("the local row survives", h.playlistDao.getById("local-w"))
    }

    // ── A-mass — over-floor stale push links → no detach-probe, all links + overrides intact ──
    @Test
    fun `A mass-floor — over-floor stale links skip the detach-probe and keep every link`() = runBlocking {
        val am = FakeProvider("applemusic")
        val h = Harness(listOf(am))
        // 31 user-created local playlists, each mirrored ONLY to Apple Music. The
        // provider fetch came back EMPTY (truncated/partial), so every link looks
        // stale (absent from remoteById). Even though each probe WOULD report a
        // 404, the mass-floor (30) must treat this as a partial fetch and skip the
        // dead-mirror detach-probe entirely — no probing, no detaching, no
        // re-creation. (Companion to `B mass-floor` on the removal side.)
        val ids = (0 until 31).map { "local-mass-$it" }
        for ((i, id) in ids.withIndex()) {
            h.seed(id, "Mass $i", sourceUrl = null)
            h.linkMirror(id, "applemusic", "am-dead-$i")
        }
        am.remotePlaylistsList = emptyList()
        am.goneExternalIds = ids.indices.map { "am-dead-$it" }.toSet() // every probe WOULD say GONE

        h.engine.syncAll()

        assertEquals(
            "no stale link is dropped — the mass-floor refuses to detach on a likely-partial fetch",
            31,
            ids.count { h.linkDao.selectForLink(it, "applemusic") != null },
        )
        assertTrue(
            "no channel override is written for any playlist (nothing was detached)",
            ids.all { h.settings.getPlaylistChannels(it) == null },
        )
        assertEquals(
            "the detach-probe never ran (no probe-storm through the rate-limit gate)",
            0,
            am.probeCount,
        )
        assertEquals("no mirror was re-created", 0, am.createCount)
    }

    // ── A4 — a pre-existing override is respected: detach never re-enables a user-disabled provider ──
    @Test
    fun `A4 detach — respects a pre-existing override and never re-enables a disabled provider`() = runBlocking {
        val am = FakeProvider("applemusic")
        val h = Harness(listOf(am))
        // User syncs this playlist ONLY to Apple Music (override excludes the live
        // ListenBrainz mirror). AM then dies. Detach must NOT resurrect LB.
        h.seed("local-z", "Curated", sourceUrl = null)
        h.linkMirror("local-z", "applemusic", "am-dead")
        h.linkMirror("local-z", "listenbrainz", "lb-live")
        h.settings.setPlaylistChannels("local-z", setOf("applemusic"))
        am.remotePlaylistsList = emptyList()
        am.goneExternalIds = setOf("am-dead")

        h.engine.syncAll()

        assertEquals(
            "override goes empty — the user's disabled ListenBrainz is NOT resurrected",
            emptySet<String>(),
            h.settings.getPlaylistChannels("local-z"),
        )
        assertNull("the dead AM link is dropped", h.linkDao.selectForLink("local-z", "applemusic"))
        assertNotNull("the live (but user-disabled) LB link is left intact", h.linkDao.selectForLink("local-z", "listenbrainz"))
    }

    // ── C1 — stale CHIP on a surviving multi-mirror row: drop the chip, keep the playlist + other mirror ──
    @Test
    fun `C1 stale-chip — prefix-derived LB chip on a surviving row is dropped, other mirror kept`() = runBlocking {
        val lb = FakeProvider("listenbrainz")
        val h = Harness(listOf(lb))
        // A `listenbrainz-*` row (its LB chip is PREFIX-derived — no sync_source) that
        // ALSO mirrors to Spotify. The user deleted it on ListenBrainz; the row survives
        // (it has the Spotify mirror). It's not a push candidate for LB and has no LB
        // push-link, so neither the push detach nor the source-row removal touches it —
        // only the stale-chip reconcile can drop the dead chip.
        h.seed("listenbrainz-deadmbid", "Shared Mix", sourceUrl = null)
        h.linkMirror("listenbrainz-deadmbid", "spotify", "sp-live")
        lb.remotePlaylistsList = emptyList()
        lb.goneExternalIds = setOf("deadmbid") // probe → 404

        h.engine.syncAll()

        assertNotNull("the playlist is kept (chip drop is non-destructive)", h.playlistDao.getById("listenbrainz-deadmbid"))
        assertEquals(
            "the dead ListenBrainz chip is dropped; the Spotify mirror stays",
            setOf("spotify"),
            h.settings.getPlaylistChannels("listenbrainz-deadmbid"),
        )
        assertNotNull("the Spotify link is untouched", h.linkDao.selectForLink("listenbrainz-deadmbid", "spotify"))
    }

    // ── C2 — stale CHIP on an LB-only survivor: chip dropped (empty override), playlist KEPT (never deleted) ──
    @Test
    fun `C2 stale-chip — LB-only prefix chip is dropped to an empty override, playlist kept`() = runBlocking {
        val lb = FakeProvider("listenbrainz")
        val h = Harness(listOf(lb))
        h.seed("listenbrainz-solo", "Solo", sourceUrl = null) // prefix LB chip, no sync_source, no other mirror
        lb.remotePlaylistsList = emptyList()
        lb.goneExternalIds = setOf("solo")

        h.engine.syncAll()

        assertNotNull("never deletes the playlist — the chip just goes away", h.playlistDao.getById("listenbrainz-solo"))
        assertEquals(
            "the only chip is dropped → empty override (syncs with nothing), not null",
            emptySet<String>(),
            h.settings.getPlaylistChannels("listenbrainz-solo"),
        )
    }

    // ── C3 — probe says the chip's remote EXISTS (partial fetch): chip kept, no override ──
    @Test
    fun `C3 stale-chip — a probe-exists chip is kept (partial-fetch safety)`() = runBlocking {
        val lb = FakeProvider("listenbrainz")
        val h = Harness(listOf(lb))
        h.seed("listenbrainz-live", "Live", sourceUrl = null)
        lb.remotePlaylistsList = emptyList() // absent from the (partial) fetch…
        lb.goneExternalIds = emptySet()       // …but the probe says it still EXISTS

        h.engine.syncAll()

        assertNull(
            "a still-live chip is NOT detached on a partial fetch (no override written)",
            h.settings.getPlaylistChannels("listenbrainz-live"),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Harness
    // ──────────────────────────────────────────────────────────────────────

    private class Harness(providers: List<FakeProvider>) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val playlistDao = PlaylistDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        val sourceDao = SyncPlaylistSourceDao(db)
        val syncSourceDao = SyncSourceDao(db)
        val settings = ChannelStoringSettings(providers.map { it.id }.toSet())

        val engine = SyncEngine(
            db = db, driver = driver, trackDao = TrackDao(db, driver), albumDao = AlbumDao(db),
            artistDao = ArtistDao(db), playlistDao = playlistDao, playlistTrackDao = PlaylistTrackDao(db),
            syncSourceDao = syncSourceDao, syncPlaylistLinkDao = linkDao, syncPlaylistSourceDao = sourceDao,
            syncPlaylistBaselineDao = SyncPlaylistBaselineDao(db), syncPlaylistNwayDao = SyncPlaylistNwayDao(db),
            trackProviderIdCacheDao = TrackProviderIdCacheDao(db),
            settingsStore = settings, providers = providers,
            tombstones = TrackTombstoneService(InMemTombstoneStore()),
        )

        suspend fun seed(id: String, name: String, sourceUrl: String?) {
            playlistDao.insert(
                Playlist(
                    id = id, name = name, trackCount = 0, createdAt = 1_000L, updatedAt = 1_000L,
                    lastModified = 1_000L, locallyModified = false, sourceUrl = sourceUrl,
                ),
            )
        }

        suspend fun linkMirror(localId: String, providerId: String, externalId: String) =
            linkDao.upsertWithSnapshot(localId, providerId, externalId, snapshotId = null, syncedAt = 1_000L)

        /** Seed a pulled (non-local) playlist's pull source — BOTH the generic
         *  `sync_source` row the removal cleanup keys on AND the `sync_playlist_source`
         *  pull-source row (so the push loop's syncedFrom guard skips it). */
        suspend fun seedSource(localId: String, providerId: String, externalId: String) {
            syncSourceDao.insert(
                SyncSource(itemId = localId, itemType = "playlist", providerId = providerId, externalId = externalId, syncedAt = 1_000L),
            )
            sourceDao.upsert(localId, providerId, externalId, snapshotId = null, ownerId = null, syncedAt = 1_000L)
        }
    }

    // Settings fake that STORES channel overrides (the real no-op fake can't
    // assert detach). N-way disabled (orthogonal); high data-version skips the
    // one-time Spotify dedup migration.
    private class ChannelStoringSettings(private val enabled: Set<String>) : SyncSettingsProvider {
        private val channels = mutableMapOf<String, Set<String>>()
        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true, syncTracks = false, syncAlbums = false, syncArtists = false,
            syncPlaylists = true, pushLocalPlaylists = true,
        )
        override suspend fun getEnabledSyncProviders() = enabled
        override suspend fun getSyncCollectionsForProvider(providerId: String) = setOf("playlists")
        override suspend fun getSyncDataVersion() = 999
        override suspend fun setSyncDataVersion(version: Int) {}
        override suspend fun setLastSyncAt(timestamp: Long) {}
        override suspend fun clearSyncSettings() {}
        override suspend fun getPlaylistSelection(providerId: String) = ProviderPlaylistSelection(PlaylistSyncMode.ALL)
        override suspend fun setPlaylistSelection(providerId: String, selection: ProviderPlaylistSelection) {}
        override suspend fun getPullPlaylists(providerId: String): Set<String> = emptySet()
        override suspend fun setPullPlaylists(providerId: String, externalIds: Set<String>) {}
        override suspend fun getPlaylistChannels(localPlaylistId: String): Set<String>? = channels[localPlaylistId]
        override suspend fun setPlaylistChannels(localPlaylistId: String, channels: Set<String>?) {
            if (channels == null) this.channels.remove(localPlaylistId) else this.channels[localPlaylistId] = channels
        }
        override suspend fun getTrackDedupV1Done(): Boolean = true
        override suspend fun setTrackDedupV1Done() {}
        override suspend fun isNwayEnabled(): Boolean = false
        override suspend fun isNwayPropagateEnabled(): Boolean = false
    }

    private class InMemTombstoneStore : TombstoneStore {
        private var data: MutableMap<String, MutableMap<String, Tombstone>> = mutableMapOf()
        override fun read() = data
        override fun write(d: Map<String, Map<String, Tombstone>>) {
            data = d.mapValues { it.value.toMutableMap() }.toMutableMap()
        }
    }

    // Provider fake: fetch list + existence probe are independently controllable.
    private class FakeProvider(override val id: String) : SyncProvider {
        override val displayName = "$id (dead-mirror fake)"
        override val features = ProviderFeatures(snapshots = SnapshotKind.None)

        var remotePlaylistsList: List<SyncedPlaylist> = emptyList()
        var goneExternalIds: Set<String> = emptySet()
        var createCount = 0
            private set
        var probeCount = 0
            private set

        override suspend fun fetchPlaylists(onProgress: ((current: Int, total: Int) -> Unit)?): List<SyncedPlaylist> =
            remotePlaylistsList

        override suspend fun remotePlaylistExists(externalPlaylistId: String): Boolean {
            probeCount++
            return externalPlaylistId !in goneExternalIds
        }

        override suspend fun fetchPlaylistTracks(externalPlaylistId: String): List<PlaylistTrack> = emptyList()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?): RemoteCreated {
            createCount++
            return RemoteCreated(externalId = "$id-new-$createCount", snapshotId = null)
        }
        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }
}
