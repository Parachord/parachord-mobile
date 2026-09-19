package com.parachord.shared.api

/**
 * iOS: Ktor's Darwin engine surfaces NSURLError codes wrapped in generic
 * exceptions, so the concrete JVM socket types aren't available to match on.
 * Match the stable NSURLError identifiers instead — these are the handoff /
 * DNS codes, and they appear in the wrapped message text.
 *
 *  -1009 NSURLErrorNotConnectedToInternet
 *  -1003 NSURLErrorCannotFindHost
 *  -1004 NSURLErrorCannotConnectToHost
 *  -1005 NSURLErrorNetworkConnectionLost
 *  -1020 NSURLErrorDataNotAllowed
 *
 * NSURLErrorTimedOut (-1001) is deliberately absent: same reasoning as Android.
 */
private val TRANSIENT_MARKERS = listOf(
    "-1009", "-1003", "-1004", "-1005", "-1020",
    "NotConnectedToInternet", "CannotFindHost", "CannotConnectToHost",
    "NetworkConnectionLost", "DataNotAllowed",
)

actual fun isTransientNetworkError(cause: Throwable): Boolean {
    var t: Throwable? = cause
    var depth = 0
    while (t != null && depth < 8) {
        val msg = t.message
        if (msg != null) {
            if (msg.contains("-1001") || msg.contains("TimedOut")) return false
            if (TRANSIENT_MARKERS.any { msg.contains(it) }) return true
        }
        t = t.cause?.takeIf { it !== t }
        depth++
    }
    return false
}
