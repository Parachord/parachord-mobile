package com.parachord.shared.websession

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A scripted [WebSessionPlatform]: virtual-time navigations, a host-keyed cookie jar, a fake sign-in screen. */
class FakeWebSessionPlatform(private val scope: CoroutineScope) : WebSessionPlatform {
    override var available = true
    var url: String? = null
    var loadDurationMs = 100L
    var loadFailure: Exception? = null
    /** The final URL a navigation lands on (default: the requested one). */
    var landOn: ((String) -> String)? = null
    val loadTimeouts = mutableListOf<Long>()
    val events = mutableListOf<String>()
    var activeLoads = 0
    var maxActiveLoads = 0

    var pageFetchDelayMs = 0L
    var pageFetchOutcome = PageFetchOutcome(status = 200, text = "ok")
    /** Runs inside a page fetch (e.g. the replay hitting the capture listener, or the page navigating). */
    var duringPageFetch: (() -> Unit)? = null

    var listener: ((CapturedRequest) -> Unit)? = null

    /** host -> (name -> value) */
    val jar = mutableMapOf<String, MutableMap<String, String>>()
    val clearedDomains = mutableListOf<List<String>>()
    var clearResult = true

    var signInWindow: FakeSignInWindow? = null

    override suspend fun loadUrl(url: String, timeoutMs: Long): String {
        loadTimeouts += timeoutMs
        events += "load-start $url"
        activeLoads += 1
        maxActiveLoads = maxOf(maxActiveLoads, activeLoads)
        try {
            delay(loadDurationMs)
            loadFailure?.let { throw it }
            val final = landOn?.invoke(url) ?: url
            this.url = final
            events += "load-end $url"
            return final
        } finally {
            activeLoads -= 1
        }
    }

    override suspend fun evaluatePageFetch(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): PageFetchOutcome {
        events += "pageFetch-start"
        duringPageFetch?.invoke()
        delay(pageFetchDelayMs)
        events += "pageFetch-end"
        return pageFetchOutcome
    }

    override fun currentUrl(): String? = url

    override suspend fun park() {
        events += "park"
        url = "about:blank"
    }

    override fun destroy() {
        events += "destroy"
        url = null
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').lowercase()

    override suspend fun cookieHeader(url: String): String? =
        jar[hostOf(url)]?.entries?.joinToString("; ") { "${it.key}=${it.value}" }?.ifEmpty { null }

    override suspend fun storeSetCookies(url: String, lines: List<String>) {
        val cookies = jar.getOrPut(hostOf(url)) { mutableMapOf() }
        for (line in lines) {
            val pair = line.substringBefore(';')
            cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=', "")
        }
    }

    override suspend fun cookieNames(domains: List<String>): List<String> =
        domains.flatMap { d -> jar[d]?.filterValues { it.isNotEmpty() }?.keys.orEmpty() }.distinct().sorted()

    override suspend fun clearCookies(domains: List<String>): Boolean {
        clearedDomains += domains
        domains.forEach { jar.remove(it) }
        return clearResult
    }

    override fun setCaptureListener(listener: ((CapturedRequest) -> Unit)?) {
        this.listener = listener
    }

    override suspend fun openSignIn(url: String, title: String): SignInWindow {
        events += "signIn-open $title"
        return FakeSignInWindow(url).also { signInWindow = it }
    }

    /** The page fires [request] [atMs] from now. */
    fun fireAt(atMs: Long, request: CapturedRequest) {
        scope.launch {
            delay(atMs)
            listener?.invoke(request)
        }
    }

    class FakeSignInWindow(var url: String?) : SignInWindow {
        override var isClosed = false
        val closedAt = CompletableDeferred<Unit>()
        override fun currentUrl(): String? = url
        override fun close() {
            isClosed = true
            closedAt.complete(Unit)
        }
    }
}

fun gql(body: String = "{\"operationName\":\"search\"}", url: String = "https://gql.music.amazon.com/") =
    CapturedRequest(url = url, method = "POST", headers = mapOf("x-amzn-csrf" to "t", "cookie" to "secret"), body = body)
