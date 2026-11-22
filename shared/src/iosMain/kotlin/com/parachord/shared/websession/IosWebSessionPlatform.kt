package com.parachord.shared.websession

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The web view half of an iOS web session, written in Swift
 * (`IosWebSessionWebView`, `iosApp/Parachord/WebSessionWebView.swift`): a
 * hidden `WKWebView` on the default data store, its navigation policy, the
 * capture's script message handler and the visible sign-in screen. Callback
 * style, never suspend: [IosWebSessionPlatform] turns each into a suspend
 * call with its budget. Every member is called on the main thread, and every
 * callback must come back on it.
 */
interface IosWebSessionView {
    /** False when the web view can't be made (no WebKit page). */
    val isAvailable: Boolean

    /**
     * Navigate the hidden page (creating it, with [captureScript] at document
     * start and the session's UA) and call [completion] once with the final
     * URL, or with an error message. A newer load fails the older one.
     */
    fun load(url: String, captureScript: String, userAgent: String?, completion: (String?, String?) -> Unit)

    /**
     * `callAsyncJavaScript([functionBody], arguments: [argumentsJson] as an
     * object, in: the page world)`; [completion] gets the function's string
     * answer, or an error message.
     */
    fun callAsync(functionBody: String, argumentsJson: String, completion: (String?, String?) -> Unit)

    fun currentUrl(): String?

    /** Load `about:blank`. */
    fun park()

    /** Drop the page; a load still waiting fails. */
    fun destroy()

    /** Every cookie in the data store's jar. */
    fun allCookies(completion: (List<WebCookie>) -> Unit)

    /** Store each `Set-Cookie` line as if [url] answered it. */
    fun setCookies(url: String, setCookieLines: List<String>, completion: () -> Unit)

    /** Delete exactly these cookies (matched by name, domain and path). */
    fun deleteCookies(cookies: List<WebCookie>, completion: (Boolean) -> Unit)

    /** Each capture report (the script's JSON, from a frame on the cookie domains); null detaches. */
    fun setCaptureHandler(handler: ((String) -> Unit)?)

    /** Present the visible sign-in screen at [url]; an already-closed window when it can't be shown. */
    fun openSignIn(url: String, title: String, userAgent: String?): SignInWindow
}

/**
 * iOS's [WebSessionPlatform] over a Swift [IosWebSessionView]. The rules stay
 * in Kotlin: the budgets, the capture script and its parsing
 * ([WebSessionScripts]), and which cookies a request sends, `state()` names
 * and `clear()` removes ([WebSessionCookies]; the jar is the app's one
 * `WKWebsiteDataStore`, shared with the SoundCloud and Bandcamp pages, so a
 * clear is scoped to the declared domains).
 */
class IosWebSessionPlatform(
    private val config: WebSessionConfig,
    private val view: IosWebSessionView,
    private val nowMs: () -> Long = ::currentTimeMillis,
) : WebSessionPlatform {

    override val available: Boolean get() = view.isAvailable

    private val captureScript: String by lazy {
        WebSessionScripts.captureScript(config, WebSessionScripts.IOS_CAPTURE_SINK)
    }

    override suspend fun loadUrl(url: String, timeoutMs: Long): String {
        val done = CompletableDeferred<String>()
        view.load(url, captureScript, config.userAgent) { finalUrl, error ->
            if (error != null) done.completeExceptionally(IllegalStateException(error))
            else done.complete(finalUrl ?: "")
        }
        return withTimeoutOrNull(timeoutMs) { done.await() }
            ?: throw WebSessionLoadTimeout("page did not finish loading within ${timeoutMs}ms")
    }

    override suspend fun evaluatePageFetch(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): PageFetchOutcome {
        if (view.currentUrl() == null) return PageFetchOutcome(error = "no-view")
        val args = buildJsonObject {
            put("url", JsonPrimitive(url))
            put("method", JsonPrimitive(method))
            put("headers", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
            put("body", body?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()
        val done = CompletableDeferred<Pair<String?, String?>>()
        view.callAsync(WebSessionScripts.PAGE_FETCH_FUNCTION_BODY, args) { answer, error -> done.complete(answer to error) }
        val (answer, error) = withTimeoutOrNull(timeoutMs) { done.await() }
            ?: return PageFetchOutcome(error = "pageFetch exceeded ${timeoutMs}ms")
        if (answer == null) return PageFetchOutcome(error = error ?: "pageFetch failed")
        return try {
            val outcome = Json.parseToJsonElement(answer).jsonObject
            outcome["error"]?.jsonPrimitive?.content?.let { return PageFetchOutcome(error = it) }
            PageFetchOutcome(
                status = outcome["status"]?.jsonPrimitive?.content?.toIntOrNull(),
                text = outcome["text"]?.jsonPrimitive?.content ?: "",
            )
        } catch (e: Exception) {
            PageFetchOutcome(error = "pageFetch answer unreadable")
        }
    }

    override fun currentUrl(): String? = view.currentUrl()

    override suspend fun park() = view.park()

    override fun destroy() = view.destroy()

    private suspend fun cookies(): List<WebCookie> {
        val done = CompletableDeferred<List<WebCookie>>()
        view.allCookies { done.complete(it) }
        return withTimeoutOrNull(COOKIE_TIMEOUT_MS) { done.await() } ?: emptyList()
    }

    override suspend fun cookieHeader(url: String): String? =
        WebSessionCookies.header(cookies(), url, nowMs())

    override suspend fun storeSetCookies(url: String, lines: List<String>) {
        if (lines.isEmpty()) return
        val done = CompletableDeferred<Unit>()
        view.setCookies(url, lines) { done.complete(Unit) }
        withTimeoutOrNull(COOKIE_TIMEOUT_MS) { done.await() }
    }

    override suspend fun cookieNames(domains: List<String>): List<String> =
        WebSessionCookies.namesUnder(cookies(), domains, nowMs())

    override suspend fun clearCookies(domains: List<String>): Boolean {
        val doomed = WebSessionCookies.under(cookies(), domains)
        if (doomed.isEmpty()) return true
        val done = CompletableDeferred<Boolean>()
        view.deleteCookies(doomed) { done.complete(it) }
        return withTimeoutOrNull(COOKIE_TIMEOUT_MS) { done.await() } ?: false
    }

    override fun setCaptureListener(listener: ((CapturedRequest) -> Unit)?) {
        if (listener == null) {
            view.setCaptureHandler(null)
            return
        }
        view.setCaptureHandler { data ->
            val request = try {
                WebSessionScripts.parseCapture(data, config.allowedHosts)
            } catch (e: Exception) {
                Log.w(TAG, "${config.id}: unreadable capture (${data.length} chars)")
                null
            }
            if (request != null) listener(request)
        }
    }

    override suspend fun openSignIn(url: String, title: String): SignInWindow =
        view.openSignIn(url, title, config.userAgent)

    private companion object {
        const val TAG = "WebSession"
        const val COOKIE_TIMEOUT_MS = 5_000L
    }
}
