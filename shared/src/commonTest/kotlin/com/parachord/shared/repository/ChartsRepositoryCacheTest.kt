package com.parachord.shared.repository

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.LastFmClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pop of the Tops' charts are saved on disk (#570). */
class ChartsRepositoryCacheTest {
    private val feed = """{"feed":{"results":[{"name":"FRESH","artistName":"Lil Baby","artworkUrl100":"https://a/100x100bb.jpg","genres":[]}]}}"""

    private var failing = false
    private var requests = 0
    private var disk: String? = null

    private fun repo(): ChartsRepository {
        val client = HttpClient(MockEngine {
            requests++
            if (failing) error("timeout") else respond(feed, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return ChartsRepository(
            AppleMusicClient(client), LastFmClient(client), lastFmApiKey = "k",
            cacheRead = { disk }, cacheWrite = { disk = it },
        )
    }

    @Test fun `a new launch shows the saved chart without a request`() = runTest {
        assertEquals("FRESH", repo().getAppleMusicAlbums("us").single().title)
        assertEquals(1, requests)
        // The next launch: a new repository over the same file.
        assertEquals("FRESH", repo().getAppleMusicAlbums("us").single().title)
        assertEquals(1, requests)
    }

    @Test fun `a failed request with nothing in memory shows the last saved chart`() = runTest {
        repo().getAppleMusicAlbums("us")
        // Saved more than a day ago: the next launch asks again, and the request fails.
        disk = disk!!.replace(Regex("\"fetchedAt\":\\d+"), "\"fetchedAt\":0")
        failing = true
        assertEquals("FRESH", repo().getAppleMusicAlbums("us").single().title)
        assertEquals(2, requests)
    }

    @Test fun `with nothing saved a failure is an empty chart`() = runTest {
        failing = true
        assertTrue(repo().getAppleMusicAlbums("us").isEmpty())
    }
}
