package com.parachord.shared.websession

/**
 * The per-platform half of one web session: the hidden page, its request
 * capture, its cookie jar and the visible sign-in screen. Everything else
 * (the five concurrency rules, the URL scoping, the budgets) is
 * [WebSessionHost]'s and is shared.
 *
 * Threading: [WebSessionHost] calls every member on its own dispatcher (the
 * main thread on Android and iOS), and the platform must invoke the capture
 * listener on that same dispatcher.
 */
interface WebSessionPlatform {
    /** False when this device can't host the session (Android: no document-start scripts). */
    val available: Boolean

    /**
     * Navigate the hidden page (creating it when needed) and return the final
     * URL once the requested navigation finished. Throws [WebSessionLoadTimeout]
     * when [timeoutMs] runs out, another exception when the navigation failed.
     */
    suspend fun loadUrl(url: String, timeoutMs: Long): String

    /**
     * `fetch(url, { method, headers, body, credentials: 'include', redirect: 'error' })`
     * run in the hidden page's own world. Never throws: every failure is a
     * [PageFetchOutcome] with [PageFetchOutcome.error].
     */
    suspend fun evaluatePageFetch(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): PageFetchOutcome

    /** The hidden page's URL, or null when there is no page. */
    fun currentUrl(): String?

    /** Blank the hidden page (`about:blank`): the idle park. */
    suspend fun park()

    /** Drop the hidden page; a navigation still waiting fails. */
    fun destroy()

    /** The `Cookie` header the jar holds for [url], or null. */
    suspend fun cookieHeader(url: String): String?

    /** Write a response's `Set-Cookie` lines into the jar, as from [url]. */
    suspend fun storeSetCookies(url: String, lines: List<String>)

    /** Names (never values) of the non-empty cookies under [domains], sorted and distinct. */
    suspend fun cookieNames(domains: List<String>): List<String>

    /** Remove the cookies under [domains] and nothing else (the jar may be app-wide). */
    suspend fun clearCookies(domains: List<String>): Boolean

    /**
     * Every fetch/XHR the hidden page fires to one of the session's allowed
     * hosts, with the headers the page set and its body; null detaches.
     */
    fun setCaptureListener(listener: ((CapturedRequest) -> Unit)?)

    /** Show the visible sign-in screen at [url], titled [title]. */
    suspend fun openSignIn(url: String, title: String): SignInWindow
}

/** A request the hidden page fired, as the platform saw it. */
data class CapturedRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
)

/** A page-world fetch's answer: a response ([status] + [text]) or an [error]. */
data class PageFetchOutcome(val status: Int? = null, val text: String? = null, val error: String? = null)

/** The visible sign-in screen. */
interface SignInWindow {
    fun currentUrl(): String?
    val isClosed: Boolean
    fun close()
}

/** What a sign-in `decide()` sees each tick (desktop's tick shape). */
data class SignInTick(val url: String, val cookieNames: List<String>, val stableTicks: Int)

/** The navigation budget ran out. */
class WebSessionLoadTimeout(message: String) : Exception(message)

/** A host failure with desktop's `.reason` (`load-failed`, `not-ready`, `invalid`, `superseded`). */
class WebSessionException(val reason: String, message: String) : Exception(message)
