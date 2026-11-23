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
 * #833 (desktop parachord#1654): an album opened by title and artist, with no
 * release group known, opens the album's pick among the search hits, not an
 * empty placeholder edition that happens to be the top hit.
 */
class MusicBrainzSearchPlaceholderTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val noAuth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val noRefresh = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private val credit = """"artist-credit":[{"name":"Various Artists","artist":{"id":"va","name":"Various Artists"}}]"""
    private val group = """"release-group":{"id":"gta","primary-type":"Album","secondary-types":["Soundtrack"]}"""
    private val search = """{"releases":[""" +
        """{"id":"vinyl-empty","title":"GTA VI","score":100,$credit,$group,"media":[{"format":"12\" Vinyl","track-count":0},{"format":"12\" Vinyl","track-count":0}]},""" +
        """{"id":"digital","title":"GTA VI","score":100,$credit,$group,"date":"2026-11-20","media":[{"format":"Digital Media","track-count":34}]}""" +
        """]}"""
    private val digital = """{"id":"digital","title":"GTA VI",$credit,"date":"2026-11-20",""" +
        """"media":[{"format":"Digital Media","tracks":[{"id":"t1","title":"Song","recording":{"id":"r1","title":"Song"}}]}]}"""

    private val requests = mutableListOf<String>()

    @Test
    fun anAlbumFoundByNameOpensItsPickNotAnEmptyTopHit() = runTest {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            requests += path + (req.url.parameters["limit"]?.let { "?limit=$it" } ?: "")
            val body = when {
                path.endsWith("/release/") -> search
                path.endsWith("/release/digital") -> digital
                else -> error("unexpected $path")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = MusicBrainzClient(
            HttpClient(engine) { installSharedPlugins(json, AppConfig(userAgent = "Parachord/test"), noAuth, noRefresh) },
            nowMs = { testScheduler.currentTime },
        )

        val detail = MusicBrainzProvider(client).getAlbumTracks("GTA VI", "Various Artists")!!

        assertEquals(listOf("Song"), detail.tracks.map { it.title })
        assertEquals(listOf("/ws/2/release/?limit=25", "/ws/2/release/digital"), requests)
    }
}
