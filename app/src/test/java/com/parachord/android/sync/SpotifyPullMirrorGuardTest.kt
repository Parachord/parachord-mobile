package com.parachord.android.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
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
import com.parachord.shared.sync.PlaylistSyncMode
import com.parachord.shared.sync.ProviderPlaylistSelection
import com.parachord.shared.sync.SpotifySyncProvider
import com.parachord.shared.sync.SyncEngine
import com.parachord.shared.sync.SyncSettings
import com.parachord.shared.sync.SyncSettingsProvider
import com.parachord.shared.sync.TrackTombstoneService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ListenBrainz or Apple Music playlist that a Spotify playlist of the same
 * name gets linked to is a mirror: Spotify's pull never replaces its tracks.
 *
 * The first sync's name match kept the local playlist, but the second sync
 * took the "snapshot changed" branch (the playlist's legacy snapshot is never
 * Spotify's) and replaced its tracks with the Spotify copy's. On a Pixel that
 * emptied a 79-track ListenBrainz playlist (Nov 2026). Runs the real
 * [SpotifySyncProvider] against a mocked Web API whose same-named playlist
 * holds one other track, and syncs three times.
 */
class SpotifyPullMirrorGuardTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val auth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? =
            if (realm == AuthRealm.Spotify) AuthCredential.BearerToken("test") else null
        override suspend fun invalidate(realm: AuthRealm) {}
    }

    @Test
    fun `a ListenBrainz playlist matched by name keeps its tracks across syncs`() = runBlocking {
        var snapshot = "snap-1"
        val mock = MockEngine { req ->
            val path = req.url.encodedPath
            val body = when {
                req.method != HttpMethod.Get -> """{"snapshot_id":"s"}"""
                path == "/v1/me" -> """{"id":"me","display_name":"Me"}"""
                path == "/v1/me/playlists" ->
                    """{"items":[{"id":"sp2012","name":"2012","owner":{"id":"me"},"collaborative":false,""" +
                        """"snapshot_id":"$snapshot","tracks":{"total":1}}],"total":1,"offset":0,"limit":50}"""
                path.startsWith("/v1/playlists/sp2012/tracks") ->
                    """{"items":[{"added_at":"2026-01-01T00:00:00Z","track":{"id":"t1","name":"Other Song",""" +
                        """"artists":[{"id":"a1","name":"Other"}],"album":{"id":"al1","name":"Al","images":[]},""" +
                        """"duration_ms":1000,"uri":"spotify:track:t1"}}],"total":1,"offset":0,"limit":100}"""
                else -> """{"items":[],"total":0,"offset":0,"limit":100}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(mock) { install(ContentNegotiation) { json(json) } }
        val spotify = SpotifySyncProvider(SpotifyClient(http, auth))

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val playlistDao = PlaylistDao(db)
        val playlistTrackDao = PlaylistTrackDao(db)
        val sourceDao = SyncPlaylistSourceDao(db)
        val id = "listenbrainz-abc"
        playlistDao.insert(Playlist(id = id, name = "2012", trackCount = 3, createdAt = 1, updatedAt = 1))
        playlistTrackDao.insertAll((0 until 3).map { PlaylistTrack(playlistId = id, position = it, trackTitle = "Song $it", trackArtist = "Artist") })
        sourceDao.upsert(localPlaylistId = id, providerId = "listenbrainz", externalId = "abc", snapshotId = null, ownerId = null)

        val engine = SyncEngine(
            db = db,
            driver = driver,
            trackDao = TrackDao(db, driver),
            albumDao = AlbumDao(db),
            artistDao = ArtistDao(db),
            playlistDao = playlistDao,
            playlistTrackDao = playlistTrackDao,
            syncSourceDao = SyncSourceDao(db),
            syncPlaylistLinkDao = SyncPlaylistLinkDao(db),
            syncPlaylistSourceDao = sourceDao,
            syncPlaylistBaselineDao = SyncPlaylistBaselineDao(db),
            syncPlaylistNwayDao = SyncPlaylistNwayDao(db),
            trackProviderIdCacheDao = TrackProviderIdCacheDao(db),
            settingsStore = Settings(),
            providers = listOf(spotify),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )
        engine.syncAll()
        snapshot = "snap-2"
        engine.syncAll()
        engine.syncAll()

        assertEquals(3, playlistTrackDao.getByPlaylistIdSync(id).size)
        assertEquals("listenbrainz", sourceDao.selectForLocal(id)?.providerId)
    }

    /** #852: the source's own cover comes back over a mosaic, and stock art gives the mosaic back. */
    @Test
    fun `a Spotify playlist's own cover replaces the mosaic made from it`() = runBlocking {
        var image = "https://i.scdn.co/image/own"
        val mock = MockEngine { req ->
            val path = req.url.encodedPath
            val body = when {
                req.method != HttpMethod.Get -> """{"snapshot_id":"s"}"""
                path == "/v1/me" -> """{"id":"me","display_name":"Me"}"""
                path == "/v1/me/playlists" ->
                    """{"items":[{"id":"sp1","name":"Mine","owner":{"id":"me"},"collaborative":false,""" +
                        """"images":[{"url":"$image","width":640,"height":640}],""" +
                        """"snapshot_id":"snap-1","tracks":{"total":1}}],"total":1,"offset":0,"limit":50}"""
                path.startsWith("/v1/playlists/sp1/tracks") ->
                    """{"items":[{"added_at":"2026-01-01T00:00:00Z","track":{"id":"t1","name":"Song",""" +
                        """"artists":[{"id":"a1","name":"Artist"}],"album":{"id":"al1","name":"Al","images":[]},""" +
                        """"duration_ms":1000,"uri":"spotify:track:t1"}}],"total":1,"offset":0,"limit":100}"""
                else -> """{"items":[],"total":0,"offset":0,"limit":100}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val spotify = SpotifySyncProvider(SpotifyClient(HttpClient(mock) { install(ContentNegotiation) { json(json) } }, auth))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val playlistDao = PlaylistDao(db)
        val engine = SyncEngine(
            db = db, driver = driver, trackDao = TrackDao(db, driver), albumDao = AlbumDao(db), artistDao = ArtistDao(db),
            playlistDao = playlistDao, playlistTrackDao = PlaylistTrackDao(db), syncSourceDao = SyncSourceDao(db),
            syncPlaylistLinkDao = SyncPlaylistLinkDao(db), syncPlaylistSourceDao = SyncPlaylistSourceDao(db),
            syncPlaylistBaselineDao = SyncPlaylistBaselineDao(db), syncPlaylistNwayDao = SyncPlaylistNwayDao(db),
            trackProviderIdCacheDao = TrackProviderIdCacheDao(db), settingsStore = Settings(),
            providers = listOf(spotify), tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )
        engine.syncAll()
        val row = playlistDao.getAllSync().single { it.name == "Mine" }
        // The mosaic that used to be built over the cover; the snapshot is unchanged.
        playlistDao.replaceArtworkById(row.id, expected = image, artworkUrl = "file:/data/playlist_mosaics/${row.id}.jpg?v=1")
        engine.syncAll()
        assertEquals(image, playlistDao.getById(row.id)?.artworkUrl)

        // The cover removed on Spotify: its generated stock art, and the mosaic comes back.
        image = "https://mosaic.scdn.co/640/stock"
        engine.syncAll()
        assertEquals(null, playlistDao.getById(row.id)?.artworkUrl)
    }

    private class Settings : SyncSettingsProvider {
        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true,
            syncTracks = false,
            syncAlbums = false,
            syncArtists = false,
            syncPlaylists = true,
            pushLocalPlaylists = false,
        )
        override suspend fun getEnabledSyncProviders() = setOf(SpotifySyncProvider.PROVIDER_ID)
        override suspend fun getSyncCollectionsForProvider(providerId: String) =
            if (providerId == SpotifySyncProvider.PROVIDER_ID) setOf("playlists") else emptySet()
        override suspend fun getSyncDataVersion() = 5
        override suspend fun setSyncDataVersion(version: Int) {}
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
}
