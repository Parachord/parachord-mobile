package com.parachord.shared.deeplink

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GHSA-gfvj-rh43-vmp9: a playlist URL fetch checks every redirect hop before
 * requesting it and bounds the body while reading it.
 */
class PublicPlaylistFetchTest {
    private val requested = mutableListOf<String>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { req ->
            requested.add(req.url.toString())
            handler(req)
        })

    private fun MockRequestHandleScope.redirectTo(location: String) =
        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))

    private fun MockRequestHandleScope.ok(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xspf+xml"))

    @Test fun `a plain fetch returns the body`() = runTest {
        val c = client { ok("<playlist/>") }
        assertEquals("<playlist/>", fetchPublicPlaylistText(c, "https://example.com/p.xspf", 1024))
    }

    @Test fun `a redirect to loopback is refused without a request to it`() = runTest {
        val c = client { redirectTo("https://127.0.0.1/admin") }
        assertFailsWith<IllegalArgumentException> {
            fetchPublicPlaylistText(c, "https://example.com/p.xspf", 1024)
        }
        assertEquals(listOf("https://example.com/p.xspf"), requested)
    }

    @Test fun `a redirect to the local network is refused without a request to it`() = runTest {
        val c = client { redirectTo("https://192.168.1.1/reboot") }
        assertFailsWith<IllegalArgumentException> {
            fetchPublicPlaylistText(c, "https://example.com/p.xspf", 1024)
        }
        assertTrue(requested.none { "192.168" in it })
    }

    @Test fun `a redirect down to plain http is refused without a request to it`() = runTest {
        val c = client { redirectTo("http://example.com/p.xspf") }
        assertFailsWith<IllegalArgumentException> {
            fetchPublicPlaylistText(c, "https://example.com/p.xspf", 1024)
        }
        assertTrue(requested.none { it.startsWith("http://") })
    }

    @Test fun `a relative redirect to a public host is followed`() = runTest {
        val c = client { req ->
            if (req.url.encodedPath == "/old") redirectTo("/new") else ok("moved")
        }
        assertEquals("moved", fetchPublicPlaylistText(c, "https://example.com/old", 1024))
        assertEquals(listOf("https://example.com/old", "https://example.com/new"), requested)
    }

    @Test fun `too many redirects stop`() = runTest {
        var n = 0
        val c = client { redirectTo("https://example.com/hop${n++}") }
        assertFailsWith<PlaylistFetchException> {
            fetchPublicPlaylistText(c, "https://example.com/start", 1024)
        }
        assertEquals(PLAYLIST_FETCH_MAX_REDIRECTS + 1, requested.size)
    }

    @Test fun `the first URL is checked too`() = runTest {
        val c = client { ok("x") }
        assertFailsWith<IllegalArgumentException> {
            fetchPublicPlaylistText(c, "https://10.0.0.1/p.xspf", 1024)
        }
        assertTrue(requested.isEmpty())
    }

    @Test fun `a Content-Length over the cap is refused before reading`() = runTest {
        val c = client {
            respond("x".repeat(10), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "999999"))
        }
        assertFailsWith<PlaylistFetchException> {
            fetchPublicPlaylistText(c, "https://example.com/p.xspf", 1024)
        }
    }

    @Test fun `a body over the cap with no length is refused while reading`() = runTest {
        // No Content-Length, so only the read loop can catch it. (An endless
        // stream was checked on the JVM; on Kotlin/Native the mock engine's
        // test harness never completes with one.)
        val big = ByteReadChannel(ByteArray(1024 * 1024) { 'a'.code.toByte() })
        val c = client { respond(big, HttpStatusCode.OK) }
        assertFailsWith<PlaylistFetchException> {
            fetchPublicPlaylistText(c, "https://example.com/p.xspf", 16 * 1024)
        }
    }

    @Test fun `a body exactly at the cap is kept`() = runTest {
        val c = client { ok("y".repeat(1024)) }
        assertEquals(1024, fetchPublicPlaylistText(c, "https://example.com/p.xspf", 1024).length)
    }
}
