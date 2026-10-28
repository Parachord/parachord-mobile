package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plugin host's Apple Music catalog reads: developer-token auth,
 * `/v1/catalog/` paths only, a null answer (so the plugin hook falls back to
 * iTunes) for anything it can't serve, and one retry through the shared
 * limiter on a 429.
 */
class AppleMusicCatalogTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Rig(val catalog: AppleMusicCatalog, val urls: MutableList<String>, val auth: MutableList<String?>)

    private fun rig(token: String = "DEVTOKEN", vararg statuses: HttpStatusCode): Rig {
        val urls = mutableListOf<String>()
        val auth = mutableListOf<String?>()
        var i = 0
        val engine = MockEngine { req ->
            urls += req.url.toString()
            auth += req.headers[HttpHeaders.Authorization]
            val status = statuses.getOrElse(i++) { HttpStatusCode.OK }
            val body = if (status == HttpStatusCode.OK) """{"data":[{"id":"1880091921"}]}""" else """{"errors":[{"status":"${status.value}"}]}"""
            respond(body, status, json)
        }
        return Rig(AppleMusicCatalog(HttpClient(engine), token, MusicKitCatalogLimiter()), urls, auth)
    }

    @Test
    fun readsACatalogPathWithTheDeveloperToken() = runTest {
        val r = rig()
        val body = r.catalog.get("/v1/catalog/us/songs/1880091921")
        assertEquals("""{"data":[{"id":"1880091921"}]}""", body)
        assertEquals(listOf("https://api.music.apple.com/v1/catalog/us/songs/1880091921"), r.urls)
        assertEquals("Bearer DEVTOKEN", r.auth.single())
    }

    @Test
    fun onlyCatalogPathsAreServed() = runTest {
        val r = rig()
        assertNull(r.catalog.get("/v1/me/library/songs"))
        assertNull(r.catalog.get("/v1/catalog/../me/library/songs"))
        assertNull(r.catalog.get("https://evil.example/v1/catalog/us/songs/1"))
        assertTrue(r.urls.isEmpty(), "nothing should be requested: ${r.urls}")
        assertTrue(AppleMusicCatalog.isCatalogPath("/v1/catalog/us/search?types=songs&term=x"))
        assertFalse(AppleMusicCatalog.isCatalogPath("/v1/catalog/us/../../me"))
    }

    @Test
    fun noTokenMeansNoRequest() = runTest {
        val r = rig(token = "")
        assertFalse(r.catalog.isAvailable)
        assertNull(r.catalog.get("/v1/catalog/us/songs/1"))
        assertTrue(r.urls.isEmpty())
    }

    @Test
    fun aRateLimitIsRetriedOnce() = runTest {
        val r = rig("DEVTOKEN", HttpStatusCode.TooManyRequests, HttpStatusCode.OK)
        assertEquals("""{"data":[{"id":"1880091921"}]}""", r.catalog.get("/v1/catalog/us/songs/1880091921"))
        assertEquals(2, r.urls.size)
    }

    @Test
    fun aClientErrorIsNotRetried() = runTest {
        val r = rig("DEVTOKEN", HttpStatusCode.NotFound)
        assertNull(r.catalog.get("/v1/catalog/us/songs/0"))
        assertEquals(1, r.urls.size)
    }
}
