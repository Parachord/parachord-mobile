package com.parachord.shared.websession

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor

private const val TAG = "WebSession"

/** One captured request, as a `load` returns it (desktop's capture shape). */
data class WebSessionCapture(
    val url: String,
    val method: String,
    /** The headers the page set, cookie headers removed; null until header-complete. */
    val headers: Map<String, String>?,
    val body: String?,
    val at: Long,
    /** Seen while one of our own pageFetches was in flight (rule 4). */
    val echo: Boolean,
)

data class CaptureOptions(
    val urlPrefixes: List<String>,
    val quietMs: Long? = null,
    val budgetMs: Long? = null,
    val doneWhen: DoneWhen? = null,
    val returnOnTimeout: Boolean = false,
)

data class LoadOptions(val timeoutMs: Long? = null, val capture: CaptureOptions? = null)

data class LoadResult(val finalUrl: String, val captures: List<WebSessionCapture>?, val loadTimedOut: Boolean = false)

data class PageFetchRequest(
    val url: String,
    val method: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class FetchRequest(
    val url: String,
    val method: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMs: Long? = null,
)

/** A [WebSessionHost.fetch] answer. Headers are lower-cased; `Set-Cookie` crosses as [setCookieNames] only. */
data class FetchResponse(
    val status: Int,
    val headers: Map<String, String>,
    val setCookieNames: List<String>,
    val text: String,
)

/** A [WebSessionHost.state] answer: facts only, cookie names never values. */
data class WebSessionState(
    val url: String?,
    val signInUrl: String?,
    val cookieNames: List<String>,
    val generation: Int,
)

/**
 * The generic web-session host: a port of desktop's `createWebSessionHost`
 * (`main-process/web-session.js`; contract in desktop's
 * `docs/architecture/web-session.md`). It never names a service: the plugin
 * owns every URL, wall and request shape, and [WebSessionConfig] holds the
 * session's domains.
 *
 * Everything runs on the dispatcher of [scope] (the main thread on the
 * phones), one coroutine at a time between suspension points, so the shared
 * state below needs no locks: the same model as desktop's single JS thread.
 *
 * The five concurrency rules, desktop's numbering:
 * 1. **Loads serialize** on one FIFO queue ([loadQueue]).
 * 2. **A navigation waits out in-flight pageFetches**: pageFetch is a reader,
 *    a load and the idle park are writers. A pageFetch arriving while a
 *    navigation holds the gate answers `busy`.
 * 3. **The idle park never blanks a busy view**: [WebSessionTimings.warmMs]
 *    after the last load/pageFetch the page parks on `about:blank`, re-armed
 *    while anything is in flight.
 * 4. **Captures flag our own traffic**: a capture seen while a pageFetch is in
 *    flight has `echo = true`.
 * 5. **The capture window is quiet-bounded** (quietMs after the last capture,
 *    the whole budgetMs with none, never past budgetMs), ends early on the
 *    caller's `doneWhen`, and returns its captures on a navigation timeout
 *    only when the caller opted in with `returnOnTimeout`.
 */
class WebSessionHost(
    val config: WebSessionConfig,
    private val platform: WebSessionPlatform,
    httpClient: HttpClient,
    scope: CoroutineScope,
    private val nowMs: () -> Long = ::currentTimeMillis,
    private val timings: WebSessionTimings = WebSessionTimings(),
) {
    internal val dispatcher: CoroutineDispatcher =
        scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Main
    private val scope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + dispatcher)

    /** Redirects are followed by hand (every hop is re-checked), and every status is an answer. */
    private val http: HttpClient = httpClient.config {
        followRedirects = false
        expectSuccess = false
    }

    val available: Boolean get() = platform.available

    private var loadQueue = Mutex()
    private var chainBusy = 0
    private var readers = 0
    private var navigating = false
    private var parkJob: Job? = null
    private var capture: CaptureRegistration? = null
    private var generation = 0
    private var signInRun: CompletableDeferred<Boolean>? = null
    private var signInWindow: SignInWindow? = null

    // ── rule 3: the idle park ───────────────────────────────────────────────

    private fun cancelPark() {
        parkJob?.cancel()
        parkJob = null
    }

    private fun schedulePark() {
        cancelPark()
        parkJob = scope.launch {
            delay(timings.warmMs)
            parkJob = null
            // Checked at FIRE time: a fuse armed by one flow must never blank
            // the page under another.
            if (chainBusy > 0) {
                schedulePark()
                return@launch
            }
            park()
        }
    }

    private suspend fun park() {
        cancelPark()
        if (platform.currentUrl() == null) return
        // Blanking is a navigation: the writer side of the gate (rule 2).
        beginNavigation()
        try {
            val url = platform.currentUrl()
            if (url != null && url != BLANK) platform.park()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            endNavigation()
        }
    }

    // ── rule 2: the readers/writer gate ─────────────────────────────────────

    private suspend fun beginNavigation() {
        while (navigating) delay(timings.gatePollMs)
        // The flag flips BEFORE the reader drain, so a stream of pageFetches
        // can't starve the navigation.
        navigating = true
        try {
            while (readers > 0) delay(timings.gatePollMs)
        } catch (e: Throwable) {
            navigating = false
            throw e
        }
    }

    private fun endNavigation() {
        navigating = false
    }

    // ── rule 4 + 5: the capture ─────────────────────────────────────────────

    private inner class CaptureRegistration(val patterns: List<String>, val doneWhen: DoneWhen?) {
        val captured = mutableListOf<WebSessionCapture>()
        var detached = false
        val done = CompletableDeferred<Unit>()

        fun onRequest(request: CapturedRequest) {
            if (detached) return
            if (!WebSessionRules.urlOnAllowedHost(request.url, config.allowedHosts)) return
            if (patterns.none { WebSessionRules.matchesCapturePattern(request.url, it) }) return
            val headers = request.headers.filterKeys { name ->
                val lower = name.lowercase()
                lower != "cookie" && lower != "cookie2"
            }
            val entry = WebSessionCapture(
                url = request.url,
                method = request.method.ifBlank { "GET" },
                headers = headers,
                body = request.body,
                at = nowMs(),
                echo = readers > 0,
            )
            captured += entry
            if (!done.isCompleted && WebSessionRules.satisfiesDoneWhen(doneWhen, entry)) done.complete(Unit)
        }

        /** Only the CURRENT registration releases the slot: a superseded load must not detach a newer one's. */
        fun detach() {
            if (capture !== this || detached) return
            detached = true
            capture = null
            platform.setCaptureListener(null)
        }

        fun snapshot(): List<WebSessionCapture> = captured.toList()
    }

    private fun attachCapture(options: CaptureOptions): CaptureRegistration {
        val registration = CaptureRegistration(options.urlPrefixes.toList(), options.doneWhen)
        capture = registration
        platform.setCaptureListener { request -> registration.onRequest(request) }
        return registration
    }

    /** Rule 5's quiet window, ending the moment the caller's doneWhen matched. */
    private suspend fun awaitCaptureQuiet(reg: CaptureRegistration, options: CaptureOptions, loadGeneration: Int) {
        val quietMs = options.quietMs ?: timings.captureQuietMs
        val budgetMs = options.budgetMs ?: timings.captureBudgetMs
        val startAt = nowMs()
        while (true) {
            if (reg.done.isCompleted) return
            withTimeoutOrNull(timings.capturePollMs) { reg.done.await() }
            if (loadGeneration != generation) return
            if (reg.done.isCompleted) return
            val elapsed = nowMs() - startAt
            // A page that has fired nothing has not started: no quiet yet.
            if (reg.captured.isEmpty()) {
                if (elapsed >= budgetMs) return
                continue
            }
            if (nowMs() - reg.captured.last().at >= quietMs) return
            if (elapsed >= budgetMs) return
        }
    }

    private suspend fun navigateBudgeted(url: String, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { platform.loadUrl(url, timeoutMs) }
            ?: throw WebSessionLoadTimeout("page did not finish loading within ${timeoutMs}ms")
    }

    // ── the primitives ──────────────────────────────────────────────────────

    /**
     * A budgeted navigation of the hidden page, serialized (rule 1), waiting
     * out in-flight pageFetches (rule 2), with the requests the page fired
     * captured for the caller (rules 4, 5). Throws [WebSessionException] with
     * desktop's reasons.
     */
    suspend fun load(url: String, options: LoadOptions = LoadOptions()): LoadResult = withContext(dispatcher) {
        // Validate before ANY state is touched.
        options.capture?.let { c ->
            if (c.urlPrefixes.any { !WebSessionRules.urlOnAllowedHost(it, config.allowedHosts) }) {
                throw WebSessionException("invalid", "capture.urlPrefixes must be https URL patterns on the session's allowed hosts")
            }
            c.doneWhen?.let { d ->
                val values = listOfNotNull(d.bodyIncludes, d.urlIncludes)
                if (values.isEmpty() || values.any { it.isEmpty() || it.length > WebSessionRules.DONE_WHEN_MAX_LEN }) {
                    throw WebSessionException("invalid", "capture.doneWhen needs non-empty strings of at most ${WebSessionRules.DONE_WHEN_MAX_LEN} chars")
                }
            }
        }
        if (!platform.available) throw WebSessionException("not-ready", "web sessions aren't supported on this device")
        val queue = loadQueue
        val queuedGeneration = generation
        queue.withLock {
            // Queued before a clear(): it belongs to the old account.
            if (generation != queuedGeneration) {
                throw WebSessionException("superseded", "the session was cleared while this load waited")
            }
            chainBusy += 1
            try {
                runLoad(url, options)
            } finally {
                chainBusy -= 1
            }
        }
    }

    private suspend fun runLoad(url: String, options: LoadOptions): LoadResult {
        val startedAt = nowMs()
        try {
            return runLoadTimed(url, options)
        } finally {
            // The debug aid for slow page flows: the path only, never a query or a body.
            com.parachord.shared.platform.Log.d("WebSession", "load ${WebSessionRules.logPath(url)} took ${nowMs() - startedAt}ms")
        }
    }

    private suspend fun runLoadTimed(url: String, options: LoadOptions): LoadResult {
        val loadGeneration = generation
        val timeoutMs = options.timeoutMs ?: timings.loadTimeoutMs
        val captureOptions = options.capture
        cancelPark()
        val reg = captureOptions?.let { attachCapture(it) }
        try {
            var loadTimedOut = false
            beginNavigation()
            try {
                if (reg != null && captureOptions.doneWhen != null) {
                    // The caller's named match can land mid-navigation: the
                    // window ends AT the match, and the navigation keeps
                    // loading in the background.
                    val nav = scope.async { navigateBudgeted(url, timeoutMs) }
                    select<Unit> {
                        nav.onAwait { }
                        reg.done.onAwait { }
                    }
                } else {
                    navigateBudgeted(url, timeoutMs)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is WebSessionLoadTimeout && reg != null && captureOptions.returnOnTimeout && reg.captured.isNotEmpty()) {
                    loadTimedOut = true
                } else {
                    throw (e as? WebSessionException) ?: WebSessionException("load-failed", e.message ?: "the page did not load")
                }
            } finally {
                endNavigation()
            }
            if (reg != null && !loadTimedOut) awaitCaptureQuiet(reg, captureOptions, loadGeneration)
            if (generation != loadGeneration) {
                throw WebSessionException("superseded", "the session was cleared while this load ran")
            }
            return LoadResult(
                finalUrl = platform.currentUrl() ?: "",
                captures = reg?.snapshot(),
                loadTimedOut = loadTimedOut,
            )
        } finally {
            reg?.detach()
            if (generation == loadGeneration) schedulePark()
        }
    }

    /**
     * A fetch from inside the hidden page's world (its origin, cookies, CORS;
     * `redirect: 'error'`). A reader on the gate; never throws.
     */
    suspend fun pageFetch(request: PageFetchRequest): PageFetchOutcome = withContext(dispatcher) {
        if (request.url.isBlank()) return@withContext PageFetchOutcome(error = "pageFetch needs a url")
        if (navigating) return@withContext PageFetchOutcome(error = "busy")
        readers += 1
        chainBusy += 1
        cancelPark()
        try {
            val href = platform.currentUrl() ?: return@withContext PageFetchOutcome(error = "no-view")
            if (href.isBlank() || href == BLANK) return@withContext PageFetchOutcome(error = "no-origin-page")
            // The fetch runs in the page's own world: only a page on an allowed
            // host may host it (a page elsewhere could have replaced fetch).
            if (!WebSessionRules.urlOnAllowedHost(href, config.allowedHosts)) {
                return@withContext PageFetchOutcome(error = "page-off-host")
            }
            val ms = timings.pageFetchTimeoutMs
            val outcome = try {
                withTimeoutOrNull(ms) {
                    platform.evaluatePageFetch(request.url, request.method ?: "GET", request.headers, request.body, ms)
                } ?: PageFetchOutcome(error = "pageFetch exceeded ${ms}ms")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                PageFetchOutcome(error = e.message ?: "pageFetch failed")
            }
            if (!WebSessionRules.urlOnAllowedHost(platform.currentUrl(), config.allowedHosts)) {
                return@withContext PageFetchOutcome(error = "page-off-host")
            }
            if (outcome.error != null) return@withContext PageFetchOutcome(error = outcome.error)
            if (outcome.status == null) return@withContext PageFetchOutcome(error = "pageFetch returned nothing")
            PageFetchOutcome(status = outcome.status, text = outcome.text ?: "")
        } finally {
            chainBusy -= 1
            readers = maxOf(0, readers - 1)
            schedulePark()
        }
    }

    /**
     * A request outside the page carrying the session's cookies. The URL and
     * EVERY redirect hop must be https on an allowed host; a hop off the list
     * is not followed. The caller's Cookie header is dropped, the jar's is
     * attached on every hop, response cookies go back into the jar, and the
     * answer carries `Set-Cookie` names only. Throws `invalid` for an
     * off-list URL; every other failure is `status = -1`.
     */
    suspend fun fetch(request: FetchRequest): FetchResponse = withContext(dispatcher) {
        if (!onAllowedHost(request.url)) {
            throw WebSessionException("invalid", "fetch URLs must be https on one of the session's allowed hosts (${config.allowedHosts.joinToString(", ")})")
        }
        val timeoutMs = request.timeoutMs?.takeIf { it > 0 } ?: timings.loadTimeoutMs
        try {
            withTimeoutOrNull(timeoutMs) { fetchFollowingRedirects(request) }
                ?: FetchResponse(-1, emptyMap(), emptyList(), "web-session fetch exceeded ${timeoutMs}ms")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FetchResponse(-1, emptyMap(), emptyList(), e.message ?: "web-session fetch failed")
        }
    }

    /** Both parsers agree the URL is https on an allowed host. */
    private fun onAllowedHost(url: String): Boolean {
        if (!WebSessionRules.urlOnAllowedHost(url, config.allowedHosts)) return false
        return try {
            val parsed = Url(url)
            parsed.protocol.name == "https" && config.allowedHosts.any { it.lowercase() == parsed.host.lowercase() }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchFollowingRedirects(request: FetchRequest): FetchResponse {
        val callerHeaders = request.headers.filterKeys { it.lowercase() !in DROPPED_REQUEST_HEADERS }
        val contentType = callerHeaders.entries.firstOrNull { it.key.lowercase() == "content-type" }?.value
        val plainHeaders = callerHeaders.filterKeys { it.lowercase() != "content-type" }
        val hasUserAgent = plainHeaders.keys.any { it.lowercase() == "user-agent" }
        var url = request.url
        var method = (request.method ?: "GET").uppercase()
        var body = request.body
        var hops = 0
        while (true) {
            val jar = platform.cookieHeader(url)
            val sendBody = body != null && method != "GET" && method != "HEAD"
            val response = http.request(url) {
                this.method = HttpMethod.parse(method)
                for ((name, value) in plainHeaders) header(name, value)
                if (!hasUserAgent) config.userAgent?.let { header("User-Agent", it) }
                if (!jar.isNullOrEmpty()) header("Cookie", jar)
                if (sendBody) {
                    val type = contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                    setBody(ByteArrayContent(body!!.encodeToByteArray(), type))
                }
            }
            val setCookies = response.headers.getAll("Set-Cookie").orEmpty()
            if (setCookies.isNotEmpty()) platform.storeSetCookies(url, setCookies)
            val status = response.status.value
            val location = response.headers["Location"]
            if (status in REDIRECT_STATUSES && location != null) {
                val next = try {
                    URLBuilder(url).takeFrom(location).buildString()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return FetchResponse(-1, emptyMap(), emptyList(), "web-session ${config.id} fetch got an unreadable redirect")
                }
                if (!onAllowedHost(next)) {
                    return FetchResponse(
                        -1, emptyMap(), emptyList(),
                        "web-session ${config.id} fetch refused a redirect hop off the session's allowed hosts (${config.allowedHosts.joinToString(", ")})",
                    )
                }
                hops += 1
                if (hops > MAX_REDIRECTS) return FetchResponse(-1, emptyMap(), emptyList(), "web-session fetch: too many redirects")
                // Browser semantics: 303, and 301/302 after a POST, continue as GET.
                if (status == 303 && method != "HEAD" || (status == 301 || status == 302) && method == "POST") {
                    method = "GET"
                    body = null
                }
                url = next
                continue
            }
            val headers = linkedMapOf<String, String>()
            for ((name, values) in response.headers.entries()) {
                val lower = name.lowercase()
                if (lower == "set-cookie") continue
                headers[lower] = values.joinToString(", ")
            }
            return FetchResponse(status, headers, WebSessionRules.setCookieNames(setCookies), response.bodyAsText())
        }
    }

    /** The facts a plugin composes its signed-in verdict from. */
    suspend fun state(): WebSessionState = withContext(dispatcher) {
        val window = signInWindow?.takeIf { !it.isClosed }
        if (window == null) signInWindow = null
        WebSessionState(
            url = platform.currentUrl(),
            signInUrl = window?.currentUrl(),
            cookieNames = try { platform.cookieNames(config.cookieDomains) } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            },
            generation = generation,
        )
    }

    /**
     * The visible sign-in screen, ticked every [WebSessionTimings.signInTickMs]
     * with [SignInTick]; [decide] is the plugin's verdict (a throw counts as
     * false). True closes the screen and answers signed in; the person closing
     * it first answers not signed in. A second call while one runs joins it.
     */
    suspend fun signIn(url: String, decide: suspend (SignInTick) -> Boolean): Boolean = withContext(dispatcher) {
        signInRun?.let { return@withContext it.await() }
        val run = CompletableDeferred<Boolean>()
        signInRun = run
        val window = try {
            platform.openSignIn(url, config.signInTitle)
        } catch (e: Throwable) {
            signInRun = null
            run.complete(false)
            throw e
        }
        signInWindow = window
        scope.launch {
            var lastUrl: String? = null
            var stableTicks = 0
            fun finish(signedIn: Boolean) {
                if (signInRun === run) signInRun = null
                run.complete(signedIn)
            }
            try {
                while (true) {
                    delay(timings.signInTickMs)
                    if (window.isClosed) {
                        finish(false)
                        break
                    }
                    val tickUrl = window.currentUrl() ?: ""
                    val names = try { platform.cookieNames(config.cookieDomains) } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        emptyList()
                    }
                    stableTicks = if (tickUrl == lastUrl) stableTicks + 1 else 0
                    lastUrl = tickUrl
                    val verdict = try {
                        decide(SignInTick(tickUrl, names, stableTicks))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "${config.id}: sign-in decide() threw (ignored)")
                        false
                    }
                    if (window.isClosed) {
                        finish(false)
                        break
                    }
                    if (verdict) {
                        finish(true)
                        delay(timings.signInCloseDelayMs)
                        if (!window.isClosed) window.close()
                        break
                    }
                }
            } finally {
                finish(false)
            }
        }
        run.await()
    }

    /**
     * Sign out: drop the hidden page, close the sign-in screen, bump the
     * generation, cut the load queue, detach the capture, cancel the park and
     * clear the cookies under the declared domains only. Null on success,
     * else the error.
     */
    suspend fun clear(): String? = withContext(dispatcher) {
        try { platform.destroy() } catch (e: Exception) { if (e is CancellationException) throw e }
        signInWindow?.let { w -> try { w.close() } catch (e: Exception) { if (e is CancellationException) throw e } }
        signInWindow = null
        loadQueue = Mutex()
        generation += 1
        capture?.detach()
        capture = null
        cancelPark()
        try {
            if (platform.clearCookies(config.cookieDomains)) null else "the cookies could not be cleared"
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.message ?: "clear failed"
        }
    }

    private companion object {
        const val BLANK = "about:blank"
        const val MAX_REDIRECTS = 20
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val DROPPED_REQUEST_HEADERS = setOf("cookie", "cookie2", "content-length", "host", "transfer-encoding", "upgrade", "connection")
    }
}
