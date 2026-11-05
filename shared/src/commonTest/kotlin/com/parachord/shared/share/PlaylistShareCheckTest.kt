package com.parachord.shared.share

import com.parachord.shared.api.ListenBrainzClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #527: a port of desktop's `playlistShareTarget` (parachord#1265). On the real clock:
 * under `runTest`'s virtual one, the status timeout expires while the mock engine
 * answers on another thread.
 */
class PlaylistShareCheckTest {

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private fun playlist(public: Boolean) =
        """{"playlist":{"extension":{"https://musicbrainz.org/doc/jspf#playlist":{"public":$public}}}}"""

    private fun check(token: String? = "secret", timeoutMs: Long = 30_000, engine: MockEngine) = PlaylistShareCheck(
        ListenBrainzClient(HttpClient(engine) { install(ContentNegotiation) { json() } }),
        timeoutMs,
    ) { token }

    private fun realTime(block: suspend () -> Unit): Unit = runBlocking { block() }

    private fun answering(body: String, code: HttpStatusCode = HttpStatusCode.OK) =
        MockEngine { respond(body, code, jsonHeaders) }

    @Test
    fun aDeletedPlaylistIsGone() = realTime {
        val target = check(engine = answering("{}", HttpStatusCode.NotFound)).target("abc")
        assertEquals(PlaylistShareTarget.Gone, target)
    }

    @Test
    fun aPrivatePlaylistAsksFirst() = realTime {
        assertEquals(PlaylistShareTarget.Private("abc"), check(engine = answering(playlist(public = false))).target("abc"))
    }

    @Test
    fun aPublicPlaylistShares() = realTime {
        assertEquals(PlaylistShareTarget.Ok("abc"), check(engine = answering(playlist(public = true))).target("abc"))
    }

    @Test
    fun anUnclearAnswerStillShares() = realTime {
        val offline = MockEngine { throw IllegalStateException("offline") }
        assertEquals(PlaylistShareTarget.Ok("abc"), check(engine = offline).target("abc"))
        assertEquals(
            PlaylistShareTarget.Ok("abc"),
            check(engine = answering("{}", HttpStatusCode.Forbidden)).target("abc"),
        )
    }

    /** A slow ListenBrainz, or one telling us to wait out a rate limit, can't hold up the share sheet. */
    @Test
    fun aSlowAnswerStillShares() = realTime {
        val slow = MockEngine {
            delay(60_000)
            respond(playlist(public = false), HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals(PlaylistShareTarget.Ok("abc"), check(timeoutMs = 50, engine = slow).target("abc"))
    }

    @Test
    fun withNoTokenItDoesNotAsk() = realTime {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(playlist(public = false), HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals(PlaylistShareTarget.Ok("abc"), check(token = null, engine = engine).target("abc"))
        assertEquals(0, requests)
    }

    @Test
    fun makingPublicReportsNoErrorWhenItWorks() = realTime {
        val engine = MockEngine { respond(playlist(public = false), HttpStatusCode.OK, jsonHeaders) }
        assertNull(check(engine = engine).makePublic("abc"))
    }

    @Test
    fun makingPublicReportsAFailure() = realTime {
        val engine = MockEngine { request ->
            val code = if (request.method == HttpMethod.Post) HttpStatusCode.Forbidden else HttpStatusCode.OK
            respond(playlist(public = false), code, jsonHeaders)
        }
        assertNotNull(check(engine = engine).makePublic("abc"))
    }

    @Test
    fun makingPublicWithNoTokenReportsAFailure() = realTime {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.OK, jsonHeaders)
        }
        assertNotNull(check(token = null, engine = engine).makePublic("abc"))
        assertEquals(0, requests)
    }
}
