package com.parachord.android.websession

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.parachord.shared.platform.Log
import com.parachord.shared.websession.CapturedRequest
import com.parachord.shared.websession.PageFetchOutcome
import com.parachord.shared.websession.SignInWindow
import com.parachord.shared.websession.WebSessionConfig
import com.parachord.shared.websession.WebSessionLoadTimeout
import com.parachord.shared.websession.WebSessionPlatform
import com.parachord.shared.websession.WebSessionRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "WebSession"

/**
 * Android's half of a web session ([WebSessionPlatform]): a hidden WebView the
 * shared [com.parachord.shared.websession.WebSessionHost] drives.
 *
 * - Never attached to a window, `LAYER_TYPE_NONE`, but measured and laid out
 *   at 1100×800: a single-page app in a 0×0 view collapses its widgets and
 *   never fires the requests the plugin waits for (desktop's OFFSCREEN_BOUNDS).
 * - The request capture is a document-start script (wrapping `fetch` and
 *   `XMLHttpRequest`) in every frame on the session's cookie domains, reporting
 *   to a web message listener scoped to the same origins. WebView has no
 *   `webRequest`; without document-start scripts the session is unavailable.
 * - Cookies live in the app's one `CookieManager` (Apple Music's MusicKit page
 *   shares it), so reads and clears are scoped to the declared domains.
 * - A main-frame navigation off the cookie domains is refused.
 *
 * Every member runs on the main thread (the host's dispatcher).
 */
class AndroidWebSessionPlatform(
    context: Context,
    private val config: WebSessionConfig,
) : WebSessionPlatform {

    private val appContext = context.applicationContext
    private var view: WebView? = null
    private var pending: CompletableDeferred<String>? = null
    private var pendingStarted = false
    private var listener: ((CapturedRequest) -> Unit)? = null
    private var resultCounter = 0

    override val available: Boolean by lazy {
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        } catch (e: Exception) {
            Log.w(TAG, "${config.id}: WebView features unreadable: ${e.message}")
            false
        }
    }

    /** The origins the capture runs in: the cookie domains, as https. */
    private val originRules: Set<String> =
        config.cookieDomains.map { "https://" + it.removePrefix(".").lowercase() }.toSet()

    // ── the hidden page ─────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureView(): WebView {
        view?.let { return it }
        val v = WebView(appContext)
        v.setLayerType(View.LAYER_TYPE_NONE, null)
        v.settings.javaScriptEnabled = true
        v.settings.domStorageEnabled = true
        v.settings.mediaPlaybackRequiresUserGesture = false
        config.userAgent?.let { v.settings.userAgentString = it }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
        v.webViewClient = HiddenPageClient()
        WebViewCompat.addWebMessageListener(v, CAPTURE_OBJECT, originRules) { _, message, _, _, _ ->
            onCaptureMessage(message.data)
        }
        WebViewCompat.addDocumentStartJavaScript(v, captureScript(), originRules)
        view = v
        return v
    }

    private inner class HiddenPageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            // The page (a script, a link, a server redirect) may move only
            // within the cookie domains, so an open redirect can't carry the
            // session's cookies elsewhere.
            val allowed = url.startsWith("https://", ignoreCase = true) &&
                WebSessionRules.urlUnderCookieDomains(url, config.cookieDomains)
            if (!allowed) Log.w(TAG, "${config.id}: refused a main-frame navigation off the cookie domains")
            return !allowed
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (pending != null) pendingStarted = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val p = pending ?: return
            if (!pendingStarted) return // a finish from the page we navigated away from
            pending = null
            p.complete(url ?: view.url ?: "")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val p = pending ?: return
            pending = null
            p.completeExceptionally(IllegalStateException("navigation failed (${error.errorCode})"))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // An unhandled renderer death kills the app; drop the page instead.
            Log.w(TAG, "${config.id}: hidden page renderer gone (crashed=${detail.didCrash()})")
            if (this@AndroidWebSessionPlatform.view === view) destroy()
            return true
        }
    }

    override suspend fun loadUrl(url: String, timeoutMs: Long): String = withContext(Dispatchers.Main.immediate) {
        val v = ensureView()
        pending?.completeExceptionally(IllegalStateException("navigation replaced"))
        val deferred = CompletableDeferred<String>()
        pending = deferred
        pendingStarted = false
        v.loadUrl(url)
        try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw WebSessionLoadTimeout("page did not finish loading within ${timeoutMs}ms")
        } finally {
            if (pending === deferred) pending = null
        }
    }

    override suspend fun evaluatePageFetch(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): PageFetchOutcome = withContext(Dispatchers.Main.immediate) {
        val v = view ?: return@withContext PageFetchOutcome(error = "no-view")
        // The unique-key poll: an async IIFE's value never comes back from
        // evaluateJavascript on Android (it serializes the Promise as {}).
        val key = "k${++resultCounter}"
        val headersJs = JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString()
        val bodyJs = body?.let { Json.encodeToString(String.serializer(), it) } ?: "undefined"
        val start = """
            (function () {
              window.__pcWsResults = window.__pcWsResults || {};
              var key = ${js(key)};
              window.__pcWsResults[key] = null;
              (async function () {
                var out;
                try {
                  var r = await fetch(${js(url)}, {
                    method: ${js(method)},
                    credentials: 'include',
                    redirect: 'error',
                    headers: $headersJs,
                    body: $bodyJs
                  });
                  out = { status: r.status, text: await r.text() };
                } catch (e) {
                  out = { error: String(e && (e.message || e)) };
                }
                window.__pcWsResults[key] = JSON.stringify(out);
              })();
              return true;
            })()
        """.trimIndent()
        if (evaluate(v, start) != "true") return@withContext PageFetchOutcome(error = "pageFetch could not start")
        val poll = "(function () { var r = window.__pcWsResults && window.__pcWsResults[${js(key)}]; " +
            "if (r) delete window.__pcWsResults[${js(key)}]; return r || null; })()"
        val raw = withTimeoutOrNull(timeoutMs) {
            var answer: String? = null
            while (answer == null) {
                delay(POLL_MS)
                val current = view ?: return@withTimeoutOrNull null
                val r = evaluate(current, poll)
                if (r != null && r != "null") answer = r
            }
            answer
        } ?: return@withContext PageFetchOutcome(error = "pageFetch exceeded ${timeoutMs}ms")
        try {
            // evaluateJavascript JSON-encodes the returned string.
            val outcome = Json.parseToJsonElement(Json.decodeFromString(String.serializer(), raw)).jsonObject
            outcome["error"]?.jsonPrimitive?.content?.let { return@withContext PageFetchOutcome(error = it) }
            PageFetchOutcome(
                status = outcome["status"]?.jsonPrimitive?.content?.toIntOrNull(),
                text = outcome["text"]?.jsonPrimitive?.content ?: "",
            )
        } catch (e: Exception) {
            PageFetchOutcome(error = "pageFetch answer unreadable")
        }
    }

    private suspend fun evaluate(v: WebView, script: String): String? {
        val d = CompletableDeferred<String?>()
        v.evaluateJavascript(script) { d.complete(it) }
        return withTimeoutOrNull(EVAL_TIMEOUT_MS) { d.await() }
    }

    override fun currentUrl(): String? = view?.url

    override suspend fun park() = withContext(Dispatchers.Main.immediate) {
        view?.loadUrl("about:blank")
        Unit
    }

    override fun destroy() {
        val v = view ?: return
        view = null
        pending?.completeExceptionally(IllegalStateException("the page was closed"))
        pending = null
        try {
            v.stopLoading()
            v.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "${config.id}: destroy failed: ${e.message}")
        }
    }

    // ── cookies ─────────────────────────────────────────────────────────────

    override suspend fun cookieHeader(url: String): String? = withContext(Dispatchers.Main.immediate) {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    }

    override suspend fun storeSetCookies(url: String, lines: List<String>) = withContext(Dispatchers.Main.immediate) {
        val cm = CookieManager.getInstance()
        for (line in lines) cm.setCookie(url, line)
        cm.flush()
    }

    private fun namesAt(url: String): List<Pair<String, String>> =
        CookieManager.getInstance().getCookie(url).orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            .filter { it.first.isNotEmpty() }

    override suspend fun cookieNames(domains: List<String>): List<String> = withContext(Dispatchers.Main.immediate) {
        domains.flatMap { d -> namesAt("https://${d.removePrefix(".")}/").filter { it.second.isNotEmpty() }.map { it.first } }
            .distinct()
            .sorted()
    }

    /**
     * Expire every cookie visible under [domains], both as a host-only cookie
     * and as a domain cookie, for each domain and its `www.` host. Never
     * `removeAllCookies`: the jar is the whole app's.
     */
    override suspend fun clearCookies(domains: List<String>): Boolean = withContext(Dispatchers.Main.immediate) {
        val cm = CookieManager.getInstance()
        try {
            repeat(2) { // a second pass catches a name shadowed by a same-named cookie of a wider scope
                for (domain in domains) {
                    val d = domain.removePrefix(".").lowercase()
                    val hosts = listOf(d, "www.$d").distinct()
                    for (host in hosts) {
                        val url = "https://$host/"
                        for ((name, _) in namesAt(url)) {
                            cm.setCookie(url, "$name=; Max-Age=0; Path=/; Secure")
                            cm.setCookie(url, "$name=; Max-Age=0; Path=/; Secure; Domain=.$d")
                            cm.setCookie(url, "$name=; Max-Age=0; Path=/; Secure; Domain=.$host")
                        }
                    }
                }
            }
            cm.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "${config.id}: cookie clear failed: ${e.message}")
            false
        }
    }

    // ── capture ─────────────────────────────────────────────────────────────

    override fun setCaptureListener(listener: ((CapturedRequest) -> Unit)?) {
        this.listener = listener
    }

    private fun onCaptureMessage(data: String?) {
        val l = listener ?: return
        if (data == null) return
        try {
            val o = Json.parseToJsonElement(data).jsonObject
            val url = o["url"]?.jsonPrimitive?.content ?: return
            if (!WebSessionRules.urlOnAllowedHost(url, config.allowedHosts)) return
            val headers = (o["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            val body = (o["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            l(CapturedRequest(url, o["method"]?.jsonPrimitive?.content ?: "GET", headers, body))
        } catch (e: Exception) {
            Log.w(TAG, "${config.id}: unreadable capture (${data.length} chars)")
        }
    }

    private fun captureScript(): String {
        val hosts = config.allowedHosts.joinToString(",") { js(it.lowercase()) }
        return CAPTURE_JS.replace("__ALLOWED_HOSTS__", hosts).replace("__CAPTURE_OBJECT__", CAPTURE_OBJECT)
    }

    // ── sign-in ─────────────────────────────────────────────────────────────

    override suspend fun openSignIn(url: String, title: String): SignInWindow = withContext(Dispatchers.Main.immediate) {
        WebSessionSignInActivity.open(appContext, url, title, config.userAgent)
    }

    private fun js(s: String) = Json.encodeToString(String.serializer(), s)

    private companion object {
        const val VIEW_WIDTH = 1100
        const val VIEW_HEIGHT = 800
        const val POLL_MS = 100L
        const val EVAL_TIMEOUT_MS = 5_000L
        const val CAPTURE_OBJECT = "PcWebSessionCapture"

        /**
         * Runs before any page script in every frame on the cookie domains.
         * Reports each fetch/XHR to an allowed host (https, exact host) with
         * the headers the page set (names lower-cased, cookie headers never)
         * and its body as text, to the web message listener.
         */
        val CAPTURE_JS = """
(function () {
  if (window.__pcWsCapture) return;
  window.__pcWsCapture = true;
  var HOSTS = [__ALLOWED_HOSTS__];
  var sink = window.__CAPTURE_OBJECT__;
  if (!sink || typeof sink.postMessage !== 'function') return;
  function absolute(u) {
    try { return new URL(String(u), document.baseURI).href; } catch (e) { return null; }
  }
  function allowed(u) {
    try {
      var p = new URL(u);
      return p.protocol === 'https:' && HOSTS.indexOf(p.hostname.toLowerCase()) >= 0;
    } catch (e) { return false; }
  }
  function headerObject(h, out) {
    out = out || {};
    if (!h) return out;
    try {
      if (typeof Headers !== 'undefined' && h instanceof Headers) { h.forEach(function (v, k) { out[String(k).toLowerCase()] = String(v); }); return out; }
      if (Array.isArray(h)) { h.forEach(function (p) { out[String(p[0]).toLowerCase()] = String(p[1]); }); return out; }
      Object.keys(h).forEach(function (k) { out[k.toLowerCase()] = String(h[k]); });
    } catch (e) {}
    return out;
  }
  function bodyText(b) {
    if (b === undefined || b === null) return null;
    if (typeof b === 'string') return b;
    try {
      if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) return b.toString();
      if (b instanceof ArrayBuffer) return new TextDecoder().decode(b);
      if (ArrayBuffer.isView(b)) return new TextDecoder().decode(b);
    } catch (e) {}
    return null;
  }
  function report(url, method, headers, body) {
    if (!url || !allowed(url)) return;
    delete headers.cookie; delete headers.cookie2;
    try {
      sink.postMessage(JSON.stringify({ url: url, method: String(method || 'GET').toUpperCase(), headers: headers, body: body }));
    } catch (e) {}
  }
  var origFetch = window.fetch;
  if (typeof origFetch === 'function') {
    window.fetch = function (input, init) {
      try {
        var isRequest = typeof Request !== 'undefined' && input instanceof Request;
        var url = absolute(isRequest ? input.url : input);
        if (url && allowed(url)) {
          var method = (init && init.method) || (isRequest ? input.method : 'GET');
          var headers = headerObject(isRequest ? input.headers : null);
          headerObject(init && init.headers, headers);
          if (init && init.body !== undefined && init.body !== null) {
            report(url, method, headers, bodyText(init.body));
          } else if (isRequest && typeof input.clone === 'function') {
            input.clone().text().then(function (t) { report(url, method, headers, t || null); }, function () { report(url, method, headers, null); });
          } else {
            report(url, method, headers, null);
          }
        }
      } catch (e) {}
      return origFetch.apply(this, arguments);
    };
  }
  var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send, XH = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function (m, u) {
    try { this.__pcWs = { m: m, u: absolute(u), h: {} }; } catch (e) {}
    return XO.apply(this, arguments);
  };
  XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
    try { if (this.__pcWs) this.__pcWs.h[String(k).toLowerCase()] = String(v); } catch (e) {}
    return XH.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (b) {
    try { if (this.__pcWs) report(this.__pcWs.u, this.__pcWs.m, this.__pcWs.h, bodyText(b)); } catch (e) {}
    return XS.apply(this, arguments);
  };
})();
""".trimIndent()
    }
}
