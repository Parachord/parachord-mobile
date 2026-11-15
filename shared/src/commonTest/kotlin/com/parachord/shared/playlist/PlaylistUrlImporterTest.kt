package com.parachord.shared.playlist

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.installSharedPlugins
import com.parachord.shared.config.AppConfig
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playlist import from a link goes through the resolvers, on both apps: native
 * Spotify and Apple Music, then the plugin that claims the URL, else hosted XSPF.
 */
class PlaylistUrlImporterTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appConfig = AppConfig(
        userAgent = "Parachord/test (Android; https://parachord.com)",
        isDebug = false,
    )
    private val auth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? =
            if (realm == AuthRealm.Spotify) AuthCredential.BearerToken("t") else null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val refresher = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private val spotifyPlaylist = """{"id":"P1","name":"Road Trip","images":[{"url":"https://i/cover.jpg","width":640,"height":640}]}"""
    private val spotifyTracks = """{"items":[{"track":{"id":"T1","name":"Song One","artists":[{"name":"Artist A"}],
        "album":{"id":"AL1","name":"Album A","images":[{"url":"https://i/a.jpg","width":300,"height":300}]},"duration_ms":201000}}],"next":null}"""
    private val applePlaylist = """{"data":[{"id":"pl.abc","attributes":{"name":"AM Mix","artwork":{"url":"https://am/{w}x{h}.jpg"}},
        "relationships":{"tracks":{"data":[{"id":"111","attributes":{"name":"Apple Song","artistName":"Artist B","albumName":"Album B","durationInMillis":180000}}]}}}]}"""
    private val xspf = """<?xml version="1.0"?><playlist version="1" xmlns="http://xspf.org/ns/0/"><title>Hosted</title>
        <trackList><track><title>X</title><creator>Y</creator></track></trackList></playlist>"""

    private val saved = mutableListOf<Pair<Playlist, List<Track>>>()
    private val hostedImports = mutableListOf<String>()
    private var spotifyConnected = true

    private fun importer(pluginLookup: (suspend (String) -> String?)? = null): PlaylistUrlImporter {
        val http = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            val body = when {
                url.contains("api.spotify.com/v1/playlists/P1/tracks") -> spotifyTracks
                url.contains("api.spotify.com/v1/playlists/P1") -> spotifyPlaylist
                url.contains("api.music.apple.com/v1/catalog/us/playlists/pl.abc") -> applePlaylist
                else -> return@MockEngine respond("", HttpStatusCode.NotFound)
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { installSharedPlugins(json, appConfig, auth, refresher) }
        return PlaylistUrlImporter(
            providerPlaylists = ProviderPlaylistResolver(
                spotifyClient = SpotifyClient(http, auth),
                appleMusicClient = AppleMusicClient(http),
                httpClient = http,
                appleMusicDeveloperToken = "dev",
                spotifyAccessToken = { if (spotifyConnected) "t" else null },
                pluginLookupPlaylistJson = pluginLookup,
            ),
            importHostedXspf = { u ->
                hostedImports.add(u)
                XspfImportResult("hosted-xspf-1", "Hosted", 1)
            },
            createPlaylist = { p, t -> saved.add(p to t) },
        )
    }

    @Test fun `a Spotify playlist imports with its Spotify ids`() = runTest {
        val r = importer().importFromUrl("https://open.spotify.com/playlist/P1?si=x")
        assertTrue(r.playlistId.startsWith("spotify-import-"))
        val (playlist, tracks) = saved.single()
        assertEquals("Road Trip", playlist.name)
        assertEquals("https://i/cover.jpg", playlist.artworkUrl)
        assertNull(playlist.sourceUrl) // a snapshot: nothing polls it
        val t = tracks.single()
        assertEquals("spotify:T1", t.id)
        assertEquals("spotify", t.resolver)
        assertEquals("spotify:track:T1", t.spotifyUri)
        assertEquals("AL1", t.albumId)
        assertEquals(201000L, t.duration)
        assertTrue(hostedImports.isEmpty())
    }

    @Test fun `a spotify playlist URI is read as its web link`() = runTest {
        importer().importFromUrl("spotify:playlist:P1")
        assertEquals("Road Trip", saved.single().first.name)
    }

    @Test fun `an Apple Music playlist imports with its catalog ids`() = runTest {
        val r = importer().importFromUrl("https://music.apple.com/us/playlist/am-mix/pl.abc")
        assertTrue(r.playlistId.startsWith("applemusic-import-"))
        val t = saved.single().second.single()
        assertEquals("applemusic:111", t.id)
        assertEquals("applemusic", t.resolver)
        assertEquals("111", t.appleMusicId)
        assertEquals(180000L, t.duration)
    }

    @Test fun `a playlist a plugin claims imports through the plugin`() = runTest {
        val lookup: suspend (String) -> String? = { url ->
            if ("soundcloud.com" in url) {
                """{"resolverId":"soundcloud","playlist":{"name":"SC Set","tracks":[{"title":"Cloud","artist":"Artist C"}]}}"""
            } else null
        }
        val r = importer(lookup).importFromUrl("https://soundcloud.com/someone/sets/a-set")
        assertTrue(r.playlistId.startsWith("soundcloud-import-"))
        val (playlist, tracks) = saved.single()
        assertEquals("SC Set", playlist.name)
        assertEquals("Cloud", tracks.single().title)
        assertNull(tracks.single().resolver) // metadata only: resolves when it plays
        assertTrue(hostedImports.isEmpty())
    }

    @Test fun `an Achordion playlist imports as its hosted XSPF`() = runTest {
        importer().importFromUrl("https://achordion.xyz/playlist/0b3f2c6e-1234-4abc-9def-0123456789ab")
        assertEquals(1, hostedImports.size)
        assertTrue(hostedImports.single().contains("achordion.xyz"))
        assertTrue(saved.isEmpty())
    }

    @Test fun `a link no service claims imports as a hosted XSPF`() = runTest {
        importer { null }.importFromUrl("https://example.com/list.xspf")
        assertEquals(listOf("https://example.com/list.xspf"), hostedImports)
        assertTrue(saved.isEmpty())
    }

    @Test fun `a recognised playlist that fails to load throws`() = runTest {
        assertFailsWith<IllegalStateException> {
            importer().importFromUrl("https://music.apple.com/us/playlist/gone/pl.missing")
        }
        assertTrue(saved.isEmpty())
        assertTrue(hostedImports.isEmpty())
    }

    @Test fun `normalizePlaylistUrl turns a playlist URI into its link`() {
        assertEquals("https://open.spotify.com/playlist/ABC", normalizePlaylistUrl(" spotify:playlist:ABC "))
        assertEquals("https://x.y/z", normalizePlaylistUrl("https://x.y/z"))
    }

    // The XSPF document itself isn't parsed here; HostedXspfService does that.
    @Suppress("unused") private val keepXspf = xspf
}
