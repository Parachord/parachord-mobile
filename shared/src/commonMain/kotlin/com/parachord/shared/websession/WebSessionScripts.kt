package com.parachord.shared.websession

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JavaScript a web session's hidden page runs, one copy for both apps.
 *
 * [captureScript] is the request capture: a document-start script that wraps
 * `fetch` and `XMLHttpRequest` and reports each request to one of the
 * session's allowed hosts, with the headers the page set (names lower-cased,
 * cookie headers never) and its body as text. It runs only in an https frame
 * on the cookie domains (Android also scopes it there by origin; WebKit's
 * user scripts can't be). The platform names where the report goes
 * ([sink]): Android's web message listener (`window.PcWebSessionCapture`),
 * iOS's script message handler (`window.webkit.messageHandlers.…`); each has
 * `postMessage(string)`.
 *
 * [parseCapture] reads one report back, keeping only requests to an allowed host.
 */
object WebSessionScripts {

    /** Android's capture sink: the web message listener's object. */
    const val ANDROID_CAPTURE_OBJECT = "PcWebSessionCapture"

    /** iOS's capture sink: the `WKScriptMessageHandler`'s name. */
    const val IOS_CAPTURE_HANDLER = "pcWebSessionCapture"

    /** The JS expression for Android's sink. */
    const val ANDROID_CAPTURE_SINK = "window.$ANDROID_CAPTURE_OBJECT"

    /** The JS expression for iOS's sink. */
    const val IOS_CAPTURE_SINK =
        "(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.$IOS_CAPTURE_HANDLER)"

    /** The capture script for [config], reporting to [sink]. */
    fun captureScript(config: WebSessionConfig, sink: String): String {
        val hosts = config.allowedHosts.joinToString(",") { js(it.lowercase()) }
        val domains = config.cookieDomains.joinToString(",") { js(it.removePrefix(".").lowercase()) }
        return CAPTURE_JS
            .replace("__ALLOWED_HOSTS__", hosts)
            .replace("__COOKIE_DOMAINS__", domains)
            .replace("__CAPTURE_SINK__", sink)
    }

    /**
     * One capture report ([data], the script's JSON) as a [CapturedRequest];
     * null when it names a host outside [allowedHosts]. Throws when [data]
     * isn't a report (the caller logs its length, never its text).
     */
    fun parseCapture(data: String, allowedHosts: List<String>): CapturedRequest? {
        val o = Json.parseToJsonElement(data).jsonObject
        val url = o["url"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("no url")
        if (!WebSessionRules.urlOnAllowedHost(url, allowedHosts)) return null
        val headers = (o["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val body = (o["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return CapturedRequest(url, o["method"]?.jsonPrimitive?.content ?: "GET", headers, body)
    }

    /**
     * iOS's page-world fetch, the body of an async function `callAsyncJavaScript`
     * runs with `url`, `method`, `headers` and `body` as arguments. Answers the
     * same JSON as Android's poll: `{status, text}` or `{error}`.
     */
    const val PAGE_FETCH_FUNCTION_BODY = """
var out;
try {
  var r = await fetch(url, {
    method: method,
    credentials: 'include',
    redirect: 'error',
    headers: headers,
    body: body === null ? undefined : body
  });
  out = { status: r.status, text: await r.text() };
} catch (e) {
  out = { error: String(e && (e.message || e)) };
}
return JSON.stringify(out);
"""

    private fun js(s: String) = JsonPrimitive(s).toString()

    private val CAPTURE_JS = """
(function () {
  if (window.__pcWsCapture) return;
  var DOMAINS = [__COOKIE_DOMAINS__];
  try {
    var here = String(location.hostname || '').toLowerCase();
    var onDomain = DOMAINS.some(function (d) { return here === d || here.slice(-(d.length + 1)) === '.' + d; });
    if (location.protocol !== 'https:' || !onDomain) return;
  } catch (e) { return; }
  window.__pcWsCapture = true;
  var HOSTS = [__ALLOWED_HOSTS__];
  var sink = __CAPTURE_SINK__;
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
