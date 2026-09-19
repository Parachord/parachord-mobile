package com.parachord.shared.api

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the retry gate for the Sept 2026 "providers failing in lockstep" bug.
 *
 * A device network handoff aborts every in-flight socket and briefly
 * invalidates DNS, so unrelated providers fail together and nothing retried.
 * These are the exact exception types observed on-device in that window —
 * plus the ones that must NOT be retried, which matters more: retrying an
 * HTTP status or a timed-out request could re-poke a rate-limited Spotify
 * account (#176/#177) or replay a request that already reached the server.
 *
 * Lives in androidUnitTest because the actual is JVM-specific and commonTest
 * must also compile for iOS.
 */
class TransientNetworkErrorTest {

    // ── observed on-device during the handoff ────────────────────────

    @Test
    fun `DNS invalidated by a network change is transient`() {
        assertTrue(
            isTransientNetworkError(
                UnknownHostException("Unable to resolve host \"api.spotify.com\": No address associated with hostname"),
            ),
        )
    }

    @Test
    fun `aborted socket is transient`() {
        assertTrue(isTransientNetworkError(SocketException("Software caused connection abort")))
        assertTrue(isTransientNetworkError(SocketException("Connection reset")))
    }

    @Test
    fun `refused and unreachable during handoff are transient`() {
        assertTrue(isTransientNetworkError(ConnectException("Failed to connect")))
        assertTrue(isTransientNetworkError(NoRouteToHostException("No route to host")))
    }

    @Test
    fun `unwraps a cause chain - engines wrap the original`() {
        val wrapped = IOException("request failed", UnknownHostException("api.discogs.com"))
        assertTrue(isTransientNetworkError(wrapped))

        val doubleWrapped = RuntimeException("outer", IOException("mid", SocketException("Connection reset")))
        assertTrue(isTransientNetworkError(doubleWrapped))
    }

    // ── must NOT retry ───────────────────────────────────────────────

    @Test
    fun `read timeout is NOT transient - the request may have reached the server`() {
        assertFalse(isTransientNetworkError(SocketTimeoutException("timeout")))
        // Even buried in a chain, a timeout vetoes the retry.
        assertFalse(isTransientNetworkError(IOException("wrapped", SocketTimeoutException("timeout"))))
    }

    @Test
    fun `TLS and generic IO failures are NOT transient`() {
        assertFalse(isTransientNetworkError(SSLHandshakeException("cert problem")))
        assertFalse(isTransientNetworkError(IOException("unexpected end of stream")))
        assertFalse(isTransientNetworkError(IllegalStateException("not a network error")))
    }

    // ── robustness ───────────────────────────────────────────────────

    @Test
    fun `cyclic cause chain terminates rather than spinning`() {
        // Java forbids initCause(this), but an A->B->A cycle is legal and is
        // what a naive walk would loop on forever. The depth cap is what
        // actually saves us here, not the identity check.
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertFalse(isTransientNetworkError(a))
    }

    @Test
    fun `very deep chain terminates rather than scanning forever`() {
        var t: Throwable = UnknownHostException("deep")
        repeat(30) { t = IOException("layer", t) }
        // Beyond the depth cap we give up and say "not transient" — a bounded
        // wrong answer is preferable to an unbounded walk on a hostile chain.
        assertFalse(isTransientNetworkError(t))
    }
}
