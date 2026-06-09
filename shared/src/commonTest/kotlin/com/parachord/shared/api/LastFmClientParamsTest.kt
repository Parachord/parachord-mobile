package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Regression test for the Kotlin/Native `crossinline build()` receiver-binding
 * bug in [LastFmClient.guardedGet]: a bare `build()` silently dropped every
 * query param on Native, so Last.fm replied `error 6` and all iOS Last.fm
 * features came back empty. The fix binds the receiver via `apply(build)`.
 *
 * This is a `commonTest`, so it runs on BOTH the JVM (Android) and Native
 * (iOS) targets — proving the params survive on each platform.
 */
class LastFmClientParamsTest {

    @Test
    fun getArtistTopTracks_sendsAllParams() = runTest {
        var capturedUrl = ""
        val mock = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = """{"toptracks":{"track":[]}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = LastFmClient(HttpClient(mock))

        client.getArtistTopTracks(artist = "The National", apiKey = "TESTKEY123", limit = 5)

        // Before the fix these were ALL missing on Native (only format=json went out).
        assertContains(capturedUrl, "method=artist.gettoptracks")
        assertContains(capturedUrl, "api_key=TESTKEY123")
        assertContains(capturedUrl, "artist=The")
        assertContains(capturedUrl, "limit=5")
        assertContains(capturedUrl, "format=json")
    }
}
