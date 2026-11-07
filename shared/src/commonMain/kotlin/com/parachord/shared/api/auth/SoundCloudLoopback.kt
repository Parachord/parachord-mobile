package com.parachord.shared.api.auth

import com.parachord.shared.platform.Log
import io.ktor.http.formUrlEncode
import io.ktor.http.parseQueryString
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parachord's own SoundCloud app, the one desktop ships as
 * `FALLBACK_SOUNDCLOUD_CLIENT_ID` / `_SECRET` in `parachord-desktop/main.js`.
 * Both values are already public there; a SoundCloud client secret in an
 * installed app can't be kept secret anyway, and PKCE is what protects the
 * sign-in. SoundCloud allows one redirect URI per app and this app's is
 * desktop's loopback address, so the phones sign in with it through
 * [SoundCloudLoopback]. Never change the app's registered redirect: desktop
 * would stop signing in.
 */
object SoundCloudBuiltInApp {
    const val CLIENT_ID = "O2HcIaRQu87Kbf4CP34FpDi87nR2XTcr"
    const val CLIENT_SECRET = "ylYKJ1OW7YKqd3iSPreTc1wTeHcZRAMD"
    val credentials = SoundCloudCredentials(CLIENT_ID, CLIENT_SECRET)
}

/**
 * The SoundCloud app a sign-in uses: the person's own Client ID + Secret when
 * both are saved, else a pair the build supplies (Android's `local.properties`),
 * else Parachord's built-in app. A pair is used whole, never mixed, so the
 * authorize step and the token exchange always name the same app.
 */
fun resolveSoundCloudCredentials(
    userClientId: String?,
    userClientSecret: String?,
    buildClientId: String? = null,
    buildClientSecret: String? = null,
): SoundCloudCredentials {
    if (!userClientId.isNullOrBlank() && !userClientSecret.isNullOrBlank()) {
        return SoundCloudCredentials(userClientId, userClientSecret)
    }
    if (!buildClientId.isNullOrBlank() && !buildClientSecret.isNullOrBlank()) {
        return SoundCloudCredentials(buildClientId, buildClientSecret)
    }
    return SoundCloudBuiltInApp.credentials
}

/**
 * Signing in to SoundCloud on a phone with desktop's redirect URI,
 * `http://127.0.0.1:8888/callback/soundcloud`. It's the only one Parachord's
 * built-in app has, and SoundCloud matches it exactly: another port,
 * `localhost` or the app's `parachord://` address leaves its sign-in page
 * blank. The person's own app uses it too, so one SoundCloud app registered
 * with this URI works on desktop, Android and iOS.
 *
 * So during sign-in the app listens on 127.0.0.1:8888, the way desktop does.
 * The browser (Chrome Custom Tab, ASWebAuthenticationSession) runs in another
 * process, but loopback is shared on the device, so SoundCloud's redirect
 * reaches us. The listener is only a trampoline: it answers with a redirect to
 * `parachord://auth/callback/soundcloud` carrying the same `code` and `state`,
 * and each platform's existing callback handling takes it from there (Android's
 * `OAuthRedirectActivity`, iOS's ASWebAuthenticationSession). The token
 * exchange must then send [REDIRECT_URI] as `redirect_uri`.
 */
object SoundCloudLoopback {
    const val HOST = "127.0.0.1"
    const val PORT = 8888
    const val PATH = "/callback/soundcloud"
    const val REDIRECT_URI = "http://$HOST:$PORT$PATH"

    /** Where the listener sends SoundCloud's redirect on: the app's own callback. */
    const val APP_CALLBACK_URI = "parachord://auth/callback/soundcloud"

    /** What the listener answers one request with. */
    sealed class Reply {
        abstract val http: String

        /** SoundCloud's redirect for the sign-in in progress: pass it to the app; the listener is done. */
        data class Forward(val location: String, override val http: String) : Reply()

        /** Anything else (a favicon, a stale sign-in, another app): refuse it and keep listening. */
        data class Refuse(val status: Int, override val http: String) : Reply()
    }

    /**
     * The reply to an HTTP request whose first line is [requestLine], while the
     * sign-in with OAuth state [expectedState] is in progress. Only
     * `GET /callback/soundcloud` with that state is forwarded; only `code`,
     * `state`, `error` and `error_description` are carried, re-encoded, to
     * the fixed `parachord://` address, so the listener redirects nowhere else.
     */
    fun reply(requestLine: String?, expectedState: String): Reply {
        val parts = requestLine?.trim()?.split(' ').orEmpty()
        if (parts.size < 2 || parts[0] != "GET") return refuse(405, "Method Not Allowed", "Parachord can't handle that request.")
        val target = parts[1]
        val path = target.substringBefore('?')
        if (path != PATH) return refuse(404, "Not Found", "Nothing here.")
        val params = parseQueryString(target.substringAfter('?', ""))
        if (params["state"] != expectedState) {
            return refuse(
                400, "Bad Request",
                "This SoundCloud sign-in isn't the one Parachord is waiting for. Go back to Parachord and tap Connect again.",
            )
        }
        val carried = FORWARDED_PARAMS.mapNotNull { key -> params[key]?.let { key to it } }
        val location = APP_CALLBACK_URI + "?" + carried.formUrlEncode()
        return Reply.Forward(location, redirectResponse(location))
    }

    private val FORWARDED_PARAMS = listOf("code", "state", "error", "error_description")

    private fun redirectResponse(location: String): String {
        val href = location.replace("&", "&amp;").replace("\"", "&quot;")
        val body = page(
            "Returning to Parachord…",
            "<p>Returning to Parachord…</p><p><a href=\"$href\">Open Parachord</a></p>",
        )
        return response(302, "Found", body, extraHeaders = "Location: $location\r\n")
    }

    private fun refuse(status: Int, reason: String, message: String): Reply.Refuse =
        Reply.Refuse(status, response(status, reason, page("Parachord", "<p>$message</p>")))

    private fun page(title: String, content: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>$title</title></head>" +
            "<body style=\"font-family:-apple-system,Roboto,sans-serif;padding:24px\">$content</body></html>"

    private fun response(status: Int, reason: String, body: String, extraHeaders: String = ""): String =
        "HTTP/1.1 $status $reason\r\n" +
            extraHeaders +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.encodeToByteArray().size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            body
}

/** Another app already listens on SoundCloud's sign-in port. */
class SoundCloudLoopbackPortBusyException(port: Int, cause: Throwable?) : Exception(
    "SoundCloud sign-in needs port $port on this device, and another app is using it. " +
        "Close that app and try again.",
    cause,
)

/**
 * The listener behind [SoundCloudLoopback]: bound only to 127.0.0.1, so
 * nothing off the device can reach it, and only during one sign-in. It stops
 * once SoundCloud's redirect for that sign-in has been forwarded, after
 * [timeoutMs], on [stop], or when another sign-in starts.
 *
 * A request carrying another state is refused and the listener keeps waiting,
 * so another app on the device can't end the sign-in early. One that sees
 * the code first still can't use it: the token exchange needs the PKCE
 * verifier, which never leaves the app.
 */
class SoundCloudLoopbackServer(
    private val port: Int = SoundCloudLoopback.PORT,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val lock = Mutex()
    private var job: Job? = null

    /**
     * Bind now, then serve in [scope] until the sign-in with [expectedState]
     * comes back. Throws [SoundCloudLoopbackPortBusyException] when the port
     * can't be bound; the browser shouldn't be opened then. Starting again
     * replaces the previous sign-in's listener.
     */
    suspend fun start(scope: CoroutineScope, expectedState: String) {
        lock.withLock {
            job?.let { it.cancel(); it.join() }
            val selector = SelectorManager(Dispatchers.Default)
            val server = try {
                aSocket(selector).tcp().bind(SoundCloudLoopback.HOST, port)
            } catch (e: CancellationException) {
                selector.close()
                throw e
            } catch (e: Exception) {
                selector.close()
                Log.w(TAG, "Couldn't listen on ${SoundCloudLoopback.HOST}:$port: ${e.message}")
                throw SoundCloudLoopbackPortBusyException(port, e)
            }
            Log.d(TAG, "Listening on ${SoundCloudLoopback.HOST}:$port for SoundCloud's redirect")
            val launched = scope.launch { serve(server, expectedState) }
            // Close here, not in serve(): a job cancelled before it starts
            // never runs its body, and the port would stay bound.
            launched.invokeOnCompletion {
                server.close()
                selector.close()
            }
            job = launched
        }
    }

    /** Stop listening (sign-in finished, cancelled or abandoned). */
    fun stop() {
        job?.cancel()
    }

    private suspend fun serve(server: ServerSocket, expectedState: String) {
        try {
            val forwarded = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val done = CompletableDeferred<Unit>()
                    val acceptLoop = launch {
                        while (true) {
                            val socket = server.accept()
                            // Each connection on its own: Chrome opens speculative
                            // connections that send nothing.
                            launch { if (handle(socket, expectedState)) done.complete(Unit) }
                        }
                    }
                    done.await()
                    acceptLoop.cancel()
                }
            }
            if (forwarded == null) Log.w(TAG, "SoundCloud sign-in timed out; stopped listening")
            else Log.d(TAG, "Forwarded SoundCloud's redirect to the app; stopped listening")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SoundCloud sign-in listener failed: ${e.message}")
        }
    }

    /** Answer one connection; true when it carried the sign-in's redirect. */
    private suspend fun handle(socket: Socket, expectedState: String): Boolean = try {
        val requestLine = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            socket.openReadChannel().readUTF8Line(MAX_REQUEST_LINE)
        }
        val reply = SoundCloudLoopback.reply(requestLine, expectedState)
        val out = socket.openWriteChannel(autoFlush = false)
        out.writeStringUtf8(reply.http)
        out.flushAndClose()
        if (reply is SoundCloudLoopback.Reply.Refuse && requestLine != null) {
            Log.d(TAG, "Refused ${requestLine.substringBefore('?')} (${reply.status})")
        }
        reply is SoundCloudLoopback.Reply.Forward
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "SoundCloud sign-in listener: connection failed: ${e.message}")
        false
    } finally {
        socket.close()
    }

    companion object {
        private const val TAG = "SoundCloudLoopback"
        /** Matches the pending-flow pruning in Android's OAuthManager. */
        const val DEFAULT_TIMEOUT_MS = 10L * 60 * 1000
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val MAX_REQUEST_LINE = 8192
    }
}
