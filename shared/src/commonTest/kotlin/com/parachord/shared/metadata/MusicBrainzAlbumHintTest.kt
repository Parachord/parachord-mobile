package com.parachord.shared.metadata

import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.installSharedPlugins
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop parachord#1199 / #1202: an album a discography or search has shown
 * costs one MusicBrainz request to open, not a search plus a lookup.
 */
class MusicBrainzAlbumHintTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val noAuth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val noRefresh = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private val credit = """"artist-credit":[{"name":"Portishead","artist":{"id":"$ARTIST","name":"Portishead"}}]"""
    private val release = """{"id":"$RELEASE","title":"Dummy",$credit,"date":"1994-08-22",""" +
        """"media":[{"tracks":[{"id":"t1","title":"Mysterons","recording":{"id":"$RECORDING","title":"Mysterons"}}]}]}"""
    private val discography = """{"release-groups":[{"id":"$GROUP","title":"Dummy","primary-type":"Album",$credit}]}"""
    private val search = """{"releases":[{"id":"$RELEASE","title":"Dummy",$credit,"score":100}]}"""

    private val requests = mutableListOf<String>()
    private var officialReleases = true

    private fun kotlinx.coroutines.test.TestScope.provider(): MusicBrainzProvider {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            requests += path + if (req.url.parameters["release-group"] != null) "?release-group" else ""
            val body = when {
                path.endsWith("/release-group") -> discography
                path.endsWith("/release") && req.url.parameters["release-group"] != null -> {
                    assertEquals("official", req.url.parameters["status"])
                    if (officialReleases) """{"releases":[$release]}""" else """{"releases":[]}"""
                }
                path.endsWith("/release/") -> search
                path.endsWith("/release/$RELEASE") -> release
                else -> error("unexpected $path")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = MusicBrainzClient(
            HttpClient(engine) { installSharedPlugins(json, AppConfig(userAgent = "Parachord/test"), noAuth, noRefresh) },
            nowMs = { testScheduler.currentTime },
        )
        return MusicBrainzProvider(client)
    }

    @Test
    fun anAlbumFromADiscographyOpensWithOneRequest() = runTest {
        val mb = provider()
        mb.useArtistMbid("Portishead", ARTIST)
        mb.getArtistAlbums("Portishead", 50)
        requests.clear()

        val detail = mb.getAlbumTracks("Dummy", "Portishead")!!
        assertEquals(listOf("/ws/2/release?release-group"), requests)
        assertEquals(listOf("Mysterons"), detail.tracks.map { it.title })
        assertEquals(RECORDING, detail.tracks.single().mbid)
        assertEquals("album", detail.releaseType)
    }

    @Test
    fun anAlbumFromASearchOpensWithOneRequest() = runTest {
        val mb = provider()
        mb.searchAlbums("dummy", 10)
        requests.clear()

        mb.getAlbumTracks("dummy", "portishead")!!
        assertEquals(listOf("/ws/2/release/$RELEASE"), requests)
    }

    @Test
    fun anAlbumNotSeenBeforeStillSearchesThenLooksUp() = runTest {
        val mb = provider()
        val detail = mb.getAlbumTracks("Dummy", "Portishead")!!
        assertEquals(listOf("/ws/2/release/", "/ws/2/release/$RELEASE"), requests)
        assertEquals("Dummy", detail.title)
    }

    @Test
    fun aGroupWithNoOfficialReleaseFallsBackToTheSearch() = runTest {
        val mb = provider()
        mb.useArtistMbid("Portishead", ARTIST)
        mb.getArtistAlbums("Portishead", 50)
        requests.clear()
        officialReleases = false

        mb.getAlbumTracks("Dummy", "Portishead")!!
        assertEquals(listOf("/ws/2/release?release-group", "/ws/2/release/", "/ws/2/release/$RELEASE"), requests)
    }

    private companion object {
        const val ARTIST = "8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11"
        const val GROUP = "48140466-cff6-3222-bd55-63c27e43190d"
        const val RELEASE = "76df3287-6cda-33eb-8e9a-044b5e15ffdd"
        const val RECORDING = "8cd0b0e3-4bf6-4f5c-9d0a-9f84f8bcd8f4"
    }
}
