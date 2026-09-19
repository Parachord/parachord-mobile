package com.parachord.shared.api

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Android: walk the cause chain, since Ktor/OkHttp wrap the original.
 *
 * [SocketTimeoutException] is checked FIRST and rejected because it extends
 * `InterruptedIOException`, not [SocketException] — but engines sometimes
 * surface timeouts through socket-shaped types, and a timed-out request may
 * already have reached the server. Connection-establishment failures only.
 */
actual fun isTransientNetworkError(cause: Throwable): Boolean {
    var t: Throwable? = cause
    var depth = 0
    while (t != null && depth < 8) {
        if (t is SocketTimeoutException) return false
        if (t is UnknownHostException ||      // DNS invalidated by a network change
            t is ConnectException ||          // refused / unreachable during handoff
            t is NoRouteToHostException ||    // interface went away
            t is SocketException              // "Software caused connection abort" / reset
        ) {
            return true
        }
        t = t.cause?.takeIf { it !== t }
        depth++
    }
    return false
}
