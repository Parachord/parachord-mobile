package com.parachord.shared.api.auth

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The loopback trampoline for signing in to Parachord's built-in SoundCloud
 * app on a phone: what it answers, and the real listener on a socket.
 */
class SoundCloudLoopbackTest {

    // ── Which app, which redirect ────────────────────────────────────

    @Test
    fun own_credentials_win() {
        val c = resolveSoundCloudCredentials("MINE", "MYSECRET", "BUILD", "BUILDSECRET")
        assertEquals(SoundCloudCredentials("MINE", "MYSECRET"), c)
    }

    @Test
    fun a_half_saved_pair_is_not_mixed_with_another_app() {
        assertEquals(SoundCloudCredentials("BUILD", "BUILDSECRET"), resolveSoundCloudCredentials("MINE", null, "BUILD", "BUILDSECRET"))
        assertEquals(SoundCloudBuiltInApp.credentials, resolveSoundCloudCredentials(null, "MYSECRET", "", ""))
    }

    @Test
    fun no_credentials_means_the_built_in_app() {
        assertEquals(SoundCloudBuiltInApp.CLIENT_ID, resolveSoundCloudCredentials(null, null).clientId)
    }

    @Test
    fun every_sign_in_uses_desktops_redirect() {
        // One SoundCloud app registered with this URI works on desktop, Android and iOS.
        assertEquals("http://127.0.0.1:8888/callback/soundcloud", SoundCloudLoopback.REDIRECT_URI)
        assertEquals(SoundCloudLoopback.REDIRECT_URI, SoundCloudAuth.REDIRECT_URI)
    }

    // ── What the listener answers ────────────────────────────────────

    @Test
    fun soundclouds_redirect_is_forwarded_to_the_app_scheme() {
        val reply = SoundCloudLoopback.reply("GET /callback/soundcloud?code=abc%2B1&state=S1 HTTP/1.1", "S1")
        assertIs<SoundCloudLoopback.Reply.Forward>(reply)
        assertEquals("parachord://auth/callback/soundcloud?code=abc%2B1&state=S1", reply.location)
        assertTrue(reply.http.startsWith("HTTP/1.1 302 Found\r\n"))
        assertTrue("\r\nLocation: parachord://auth/callback/soundcloud?code=abc%2B1&state=S1\r\n" in reply.http)
        assertTrue("\r\nConnection: close\r\n" in reply.http)
    }

    @Test
    fun a_denied_sign_in_is_forwarded_with_its_error() {
        val reply = SoundCloudLoopback.reply("GET /callback/soundcloud?error=access_denied&state=S1 HTTP/1.1", "S1")
        assertIs<SoundCloudLoopback.Reply.Forward>(reply)
        assertEquals("parachord://auth/callback/soundcloud?state=S1&error=access_denied", reply.location)
    }

    @Test
    fun only_known_parameters_are_carried() {
        val reply = SoundCloudLoopback.reply("GET /callback/soundcloud?code=c&state=S1&next=https%3A%2F%2Fevil.example HTTP/1.1", "S1")
        assertIs<SoundCloudLoopback.Reply.Forward>(reply)
        assertEquals("parachord://auth/callback/soundcloud?code=c&state=S1", reply.location)
    }

    @Test
    fun another_state_is_refused() {
        val reply = SoundCloudLoopback.reply("GET /callback/soundcloud?code=c&state=OTHER HTTP/1.1", "S1")
        assertIs<SoundCloudLoopback.Reply.Refuse>(reply)
        assertEquals(400, reply.status)
        assertTrue("Location:" !in reply.http)
    }

    @Test
    fun other_paths_methods_and_garbage_are_refused() {
        assertEquals(404, (SoundCloudLoopback.reply("GET /favicon.ico HTTP/1.1", "S1") as SoundCloudLoopback.Reply.Refuse).status)
        assertEquals(404, (SoundCloudLoopback.reply("GET /callback/soundcloudx?state=S1 HTTP/1.1", "S1") as SoundCloudLoopback.Reply.Refuse).status)
        assertEquals(405, (SoundCloudLoopback.reply("POST /callback/soundcloud?state=S1 HTTP/1.1", "S1") as SoundCloudLoopback.Reply.Refuse).status)
        assertIs<SoundCloudLoopback.Reply.Refuse>(SoundCloudLoopback.reply(null, "S1"))
        assertIs<SoundCloudLoopback.Reply.Refuse>(SoundCloudLoopback.reply("", "S1"))
    }

    @Test
    fun content_length_counts_bytes() {
        val reply = SoundCloudLoopback.reply("GET /callback/soundcloud?code=c&state=S1 HTTP/1.1", "S1")
        val (head, body) = reply.http.split("\r\n\r\n", limit = 2)
        val length = head.lines().first { it.startsWith("Content-Length: ") }.removePrefix("Content-Length: ").trim().toInt()
        assertEquals(body.encodeToByteArray().size, length)
    }

    // ── The listener on a real socket ────────────────────────────────

    private suspend fun freePort(): Int {
        val selector = SelectorManager(Dispatchers.Default)
        val probe = aSocket(selector).tcp().bind(SoundCloudLoopback.HOST, 0)
        val port = (probe.localAddress as InetSocketAddress).port
        probe.close()
        selector.close()
        return port
    }

    /** One raw HTTP request to the listener; the whole response. */
    private suspend fun get(port: Int, target: String): String {
        val selector = SelectorManager(Dispatchers.Default)
        val socket = aSocket(selector).tcp().connect(SoundCloudLoopback.HOST, port)
        val out = socket.openWriteChannel(autoFlush = false)
        out.writeStringUtf8("GET $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
        out.flush()
        val response = socket.openReadChannel().readRemaining().readString()
        socket.close()
        selector.close()
        return response
    }

    private suspend fun connectionRefused(port: Int): Boolean {
        val selector = SelectorManager(Dispatchers.Default)
        return try {
            aSocket(selector).tcp().connect(SoundCloudLoopback.HOST, port).close()
            false
        } catch (e: Exception) {
            true
        } finally {
            selector.close()
        }
    }

    @Test
    fun listener_forwards_the_sign_in_then_stops() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val port = freePort()
            val server = SoundCloudLoopbackServer(port)
            server.start(scope, "S1")

            val favicon = get(port, "/favicon.ico")
            assertTrue(favicon.startsWith("HTTP/1.1 404"), favicon)
            val stale = get(port, "/callback/soundcloud?code=x&state=OLD")
            assertTrue(stale.startsWith("HTTP/1.1 400"), stale)

            val ok = get(port, "/callback/soundcloud?code=abc&state=S1")
            assertTrue(ok.startsWith("HTTP/1.1 302"), ok)
            assertTrue("Location: parachord://auth/callback/soundcloud?code=abc&state=S1\r\n" in ok, ok)

            var stopped = false
            repeat(100) { if (!stopped) { delay(50); stopped = connectionRefused(port) } }
            assertTrue(stopped, "listener still accepting after forwarding the sign-in")
            scope.cancel()
        }
    }

    @Test
    fun a_silent_connection_does_not_block_the_real_redirect() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val port = freePort()
            val server = SoundCloudLoopbackServer(port)
            server.start(scope, "S1")
            // Like Chrome's speculative preconnect: open, send nothing.
            val selector = SelectorManager(Dispatchers.Default)
            val idle = aSocket(selector).tcp().connect(SoundCloudLoopback.HOST, port)

            val ok = get(port, "/callback/soundcloud?code=abc&state=S1")
            assertTrue(ok.startsWith("HTTP/1.1 302"), ok)
            idle.close()
            selector.close()
            scope.cancel()
        }
    }

    @Test
    fun a_busy_port_fails_before_the_browser_opens() = runTest {
        withContext(Dispatchers.Default) {
            val selector = SelectorManager(Dispatchers.Default)
            val squatter = aSocket(selector).tcp().bind(SoundCloudLoopback.HOST, 0)
            val port = (squatter.localAddress as InetSocketAddress).port
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val e = assertFailsWith<SoundCloudLoopbackPortBusyException> {
                SoundCloudLoopbackServer(port).start(scope, "S1")
            }
            assertTrue("port $port" in e.message.orEmpty())
            squatter.close()
            selector.close()
            scope.cancel()
        }
    }

    @Test
    fun stop_releases_the_port() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val port = freePort()
            val server = SoundCloudLoopbackServer(port)
            server.start(scope, "S1")
            server.stop()
            var stopped = false
            repeat(100) { if (!stopped) { delay(50); stopped = connectionRefused(port) } }
            assertTrue(stopped)
            // A new sign-in can listen again.
            server.start(scope, "S2")
            assertTrue(get(port, "/callback/soundcloud?code=c&state=S2").startsWith("HTTP/1.1 302"))
            scope.cancel()
        }
    }
}
