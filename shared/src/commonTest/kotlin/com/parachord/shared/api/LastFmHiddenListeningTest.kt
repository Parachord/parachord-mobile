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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Last.fm profile that hides its recent listening answers
 * `user.getRecentTracks` with 403 + error 17. That's a state ("Listening
 * hidden"), not a broken account (desktop parachord#1140, cases from its
 * `lastfm-hidden-listening.test.js`).
 */
class LastFmHiddenListeningTest {

    @Test
    fun error17On403IsAHiddenProfile() {
        assertTrue(isLastFmHiddenListening(403, """{"error":17,"message":"Login: User required to be logged in"}"""))
        assertTrue(isLastFmHiddenListening(403, """{"error":"17"}"""))
    }

    @Test
    fun otherFailuresAreNot() {
        assertFalse(isLastFmHiddenListening(403, """{"error":10,"message":"Invalid API key"}"""))
        assertFalse(isLastFmHiddenListening(404, """{"error":6,"message":"User not found"}"""))
        assertFalse(isLastFmHiddenListening(500, """{"error":17}"""))
        assertFalse(isLastFmHiddenListening(403, null))
        assertFalse(isLastFmHiddenListening(403, "not json"))
        assertFalse(isLastFmHiddenListening(200, """{"recenttracks":{}}"""))
    }

    private fun client(status: HttpStatusCode, body: String) = LastFmClient(HttpClient(MockEngine {
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }))

    @Test
    fun getUserRecentTracksThrowsForAHiddenProfile() = runTest {
        val c = client(HttpStatusCode.Forbidden, """{"error":17,"message":"Login: User required to be logged in"}""")
        val e = assertFailsWith<LastFmHiddenListeningException> { c.getUserRecentTracks("someone", "KEY", limit = 2) }
        assertEquals("someone", e.user)
    }

    @Test
    fun getUserRecentTracksStillDecodesANormalAnswer() = runTest {
        val c = client(HttpStatusCode.OK, """{"recenttracks":{"track":[{"name":"Song","artist":{"#text":"A"}}]}}""")
        assertEquals("Song", c.getUserRecentTracks("someone", "KEY").recenttracks?.track?.single()?.name)
    }
}
