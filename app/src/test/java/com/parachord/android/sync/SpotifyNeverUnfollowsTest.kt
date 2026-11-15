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
 * #684: a sync never deletes or unfollows a remote playlist on its own.
 *
 * Mobile once ran a one-time "duplicate cleanup" that grouped the user's owned
 * Spotify playlists by name and unfollowed all but one in each group. It can't
 * see which copy another client (desktop) links to, so it unfollowed originals
 * desktop had imported, and Spotify won't let an owner re-follow from its app.
 *
 * This runs the real [SpotifySyncProvider] against a mocked Spotify Web API
 * with two owned playlists of the same name, through the inline Spotify pull
 * that the cleanup lived in, and fails on any DELETE. The sync data version is
 * frozen at the seeded value (a migration that never got to record itself),
 * which is the state in which the old cleanup still ran.
 */
class SpotifyNeverUnfollowsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val auth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? =
            if (realm == AuthRealm.Spotify) AuthCredential.BearerToken("test") else null
        override suspend fun invalidate(realm: AuthRealm) {}
    }

    private fun playlistJson(id: String, name: String) =
        """{"id":"$id","name":"$name","owner":{"id":"me"},"collaborative":false,""" +
            """"snapshot_id":"snap-$id","tracks":{"total":0}}"""

    private fun runSync(dataVersion: Int): Pair<List<String>, Int> = runBlocking {
        val requests = mutableListOf<String>()
        val mock = MockEngine { req ->
            requests += "${req.method.value} ${req.url.encodedPath}"
            val path = req.url.encodedPath
            val body = when {
                req.method != HttpMethod.Get -> """{"snapshot_id":"s"}"""
                path == "/v1/me" -> """{"id":"me","display_name":"Me"}"""
                path == "/v1/me/playlists" ->
                    """{"items":[${playlistJson("original", "Road Trip")},""" +
                        """${playlistJson("copy", "Road Trip")}],"total":2,"offset":0,"limit":50}"""
                else -> """{"items":[],"total":0,"offset":0,"limit":100}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(mock) { install(ContentNegotiation) { json(json) } }
        val spotify = SpotifySyncProvider(SpotifyClient(http, auth))

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val playlistDao = PlaylistDao(db)
        val engine = SyncEngine(
            db = db,
            driver = driver,
            trackDao = TrackDao(db, driver),
            albumDao = AlbumDao(db),
            artistDao = ArtistDao(db),
            playlistDao = playlistDao,
            playlistTrackDao = PlaylistTrackDao(db),
            syncSourceDao = SyncSourceDao(db),
            syncPlaylistLinkDao = SyncPlaylistLinkDao(db),
            syncPlaylistSourceDao = SyncPlaylistSourceDao(db),
            syncPlaylistBaselineDao = SyncPlaylistBaselineDao(db),
            syncPlaylistNwayDao = SyncPlaylistNwayDao(db),
            trackProviderIdCacheDao = TrackProviderIdCacheDao(db),
            settingsStore = FrozenVersionSettings(dataVersion),
            providers = listOf(spotify),
            tombstones = TrackTombstoneService(InMemoryTombstoneStore()),
        )
        engine.syncAll()
        val imported = playlistDao.getAllSync().count { it.id.startsWith("spotify-") }
        requests.filter { it.startsWith("DELETE") } to imported
    }

    @Test
    fun `fresh install with two same-named owned playlists deletes nothing`() {
        val (deletes, imported) = runSync(dataVersion = 0)
        assertTrue("the pull must have run", imported >= 1)
        assertEquals(emptyList<String>(), deletes)
    }

    @Test
    fun `upgrade from version 3 with two same-named owned playlists deletes nothing`() {
        val (deletes, imported) = runSync(dataVersion = 3)
        assertTrue("the pull must have run", imported >= 1)
        assertEquals(emptyList<String>(), deletes)
    }

    /** Only the Spotify playlists axis; the data version never moves. */
    private class FrozenVersionSettings(private val version: Int) : SyncSettingsProvider {
        override suspend fun getSyncSettings() = SyncSettings(
            enabled = true,
            syncTracks = false,
            syncAlbums = false,
            syncArtists = false,
            syncPlaylists = true,
            pushLocalPlaylists = true,
        )
        override suspend fun getEnabledSyncProviders() = setOf(SpotifySyncProvider.PROVIDER_ID)
        override suspend fun getSyncCollectionsForProvider(providerId: String) =
            if (providerId == SpotifySyncProvider.PROVIDER_ID) setOf("playlists") else emptySet()
        override suspend fun getSyncDataVersion() = version
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
