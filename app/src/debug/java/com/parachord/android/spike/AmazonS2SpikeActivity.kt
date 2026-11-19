package com.parachord.android.spike

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Spike S2 for #443: can a WebView capture the GraphQL request Amazon Music's own
 * search page fires (body + headers), and replay it from the page with a new
 * searchText, as desktop's amazonmusic.axe does through Electron's webRequest?
 *
 * Debug build only, never merged. Drive it with adb:
 *   am start -n com.parachord.android.debug/com.parachord.android.spike.AmazonS2SpikeActivity --es url <url>
 *   am start -n ... --es replay "<new search text>"
 * Logcat tag: S2SPIKE. Header values are never logged, only names and lengths.
 */
class AmazonS2SpikeActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        // Desktop's host uses a Chrome desktop UA; Amazon's mobile site may push the app.
        web.settings.userAgentString =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "PcSpike")
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                if (m.message().startsWith("[S2]")) Log.i(TAG, "console: ${m.message()}")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "pageFinished: $url")
                val names = CookieManager.getInstance().getCookie("https://music.amazon.com")
                    ?.split(";")?.map { it.substringBefore("=").trim() }.orEmpty()
                Log.i(TAG, "cookieNames(music.amazon.com): $names")
            }
        }
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        Log.i(TAG, "DOCUMENT_START_SCRIPT supported=$docStart")
        if (docStart) {
            WebViewCompat.addDocumentStartJavaScript(web, CAPTURE_JS, setOf("https://music.amazon.com"))
        }
        handle(intent, firstLoad = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent, firstLoad = false)
    }

    private fun handle(intent: Intent, firstLoad: Boolean) {
        intent.getStringExtra("url")?.let { Log.i(TAG, "load: $it"); web.loadUrl(it); return }
        intent.getStringExtra("replay")?.let { replay(it); return }
        intent.getStringExtra("eval")?.let { js -> web.evaluateJavascript(js) { Log.i(TAG, "eval -> $it") }; return }
        if (firstLoad) web.loadUrl("https://music.amazon.com/")
    }

    private fun replay(text: String) {
        val q = org.json.JSONObject.quote(text)
        web.evaluateJavascript("window.__pcReplay($q); 'started'") { Log.i(TAG, "replay started: $it") }
        // Unique-key poll, as the plugin hosts do: an async result never comes back
        // from evaluateJavascript directly.
        fun poll(n: Int) {
            web.postDelayed({
                web.evaluateJavascript("window.__pcReplayResult || ''") { r ->
                    if (r == null || r == "\"\"" || r == "null") {
                        if (n < 40) poll(n + 1) else Log.w(TAG, "replay: no answer in 20s")
                    } else Log.i(TAG, "REPLAY RESULT: $r")
                }
            }, 500)
        }
        poll(0)
    }

    inner class Bridge {
        @JavascriptInterface
        fun capture(summary: String) = Log.i(TAG, "CAPTURE: $summary")
    }

    companion object {
        const val TAG = "S2SPIKE"

        // Runs before any page script in every frame on music.amazon.com.
        private val CAPTURE_JS = """
(function () {
  if (window.__pcCaps) return;
  window.__pcCaps = [];
  var GQL = 'https://gql.music.amazon.com/';
  var WATCH = ['https://gql.music.amazon.com', 'https://na.web.skill.music.a2z.com/api/', 'https://music.amazon.com/NA/api/'];
  function watched(u) { for (var i = 0; i < WATCH.length; i++) if (u.indexOf(WATCH[i]) === 0) return true; return false; }
  function hdrObj(h) {
    var out = {};
    if (!h) return out;
    try {
      if (typeof Headers !== 'undefined' && h instanceof Headers) { h.forEach(function (v, k) { out[k] = v; }); return out; }
      if (Array.isArray(h)) { h.forEach(function (p) { out[String(p[0]).toLowerCase()] = String(p[1]); }); return out; }
      Object.keys(h).forEach(function (k) { out[k.toLowerCase()] = String(h[k]); });
    } catch (e) {}
    return out;
  }
  function record(via, url, method, headers, body) {
    if (typeof url !== 'string' || !watched(url)) return;
    var cap = { via: via, url: url, method: method, headers: headers, body: typeof body === 'string' ? body : null, bodyType: typeof body, at: Date.now() };
    window.__pcCaps.push(cap);
    var op = null, vars = null, hash = null, keys = null;
    try { var b = JSON.parse(cap.body); var r = Array.isArray(b) ? b[0] : b; op = r.operationName; vars = r.variables; hash = r.extensions && r.extensions.persistedQuery && String(r.extensions.persistedQuery.sha256Hash).slice(0, 12); keys = Object.keys(r || {}).slice(0, 20); } catch (e) {}
    var hn = Object.keys(headers).map(function (k) { return k + '(' + String(headers[k]).length + ')'; });
    try { PcSpike.capture(JSON.stringify({ via: via, url: url, method: method, op: op, vars: vars, hash: hash, keys: keys, headers: hn, bodyType: cap.bodyType, bodyLen: cap.body ? cap.body.length : 0, frame: location.href })); } catch (e) {}
  }
  var origFetch = window.fetch;
  window.__pcAll = [];
  window.fetch = function (input, init) {
    try { window.__pcAll.push((typeof input === 'string' ? 'str ' + input : (input && input.url ? 'req ' + input.url : 'obj ' + String(input))).slice(0, 90) + ' init=' + (init ? Object.keys(init).join('|') : '-')); } catch (e) {}
    try {
      var url = typeof input === 'string' ? input : (input && input.url) || String(input);
      var method = (init && init.method) || (input && input.method) || 'GET';
      var h = hdrObj((init && init.headers) || (input && input.headers));
      var body = init && init.body;
      if (body === undefined && input && typeof input.clone === 'function' && watched(url)) {
        input.clone().text().then(function (t) { record('fetch(Request)', url, method, h, t); }, function (e) { console.log('[S2] clone text failed ' + e); });
      } else record('fetch', url, method, h, body);
    } catch (e) {}
    return origFetch.apply(this, arguments);
  };
  var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send, XH = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function (m, u) { this.__pc = { m: m, u: String(u), h: {} }; return XO.apply(this, arguments); };
  XMLHttpRequest.prototype.setRequestHeader = function (k, v) { if (this.__pc) this.__pc.h[String(k).toLowerCase()] = String(v); return XH.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function (b) { try { if (this.__pc) record('xhr', this.__pc.u, this.__pc.m, this.__pc.h, b); } catch (e) {} return XS.apply(this, arguments); };
  if (typeof Worker !== 'undefined') {
    var OW = Worker;
    window.Worker = function (u, o) { console.log('[S2] Worker created: ' + u); return new OW(u, o); };
    window.Worker.prototype = OW.prototype;
  }
  window.__pcReplay = function (text) {
    window.__pcReplayResult = '';
    var cap = null;
    for (var i = window.__pcCaps.length - 1; i >= 0; i--) {
      var c = window.__pcCaps[i];
      try { var b = JSON.parse(c.body); var r = Array.isArray(b) ? b[0] : b; if (r && r.variables && typeof r.variables.searchText === 'string') { cap = { c: c, req: r, batch: Array.isArray(b) }; break; } } catch (e) {}
    }
    if (!cap) { window.__pcReplayResult = JSON.stringify({ error: 'no captured request with variables.searchText', caps: window.__pcCaps.length }); return; }
    var req = JSON.parse(JSON.stringify(cap.req));
    req.variables.searchText = text;
    var headers = {};
    Object.keys(cap.c.headers).forEach(function (k) { if (['content-length', 'cookie', 'host', 'origin', 'referer'].indexOf(k) < 0) headers[k] = cap.c.headers[k]; });
    origFetch.call(window, cap.c.url, { method: 'POST', headers: headers, body: JSON.stringify(cap.batch ? [req] : req), credentials: 'include' })
      .then(function (res) { return res.text().then(function (t) { return { status: res.status, text: t }; }); })
      .then(function (o) {
        var summary = { status: o.status, op: req.operationName, specType: req.variables.specType, len: o.text.length };
        try {
          var j = JSON.parse(o.text); var d = Array.isArray(j) ? j[0] : j;
          summary.hasSpecificationSearch = !!(d && d.data && d.data.specificationSearch);
          summary.errors = d && d.errors ? JSON.stringify(d.errors).slice(0, 300) : null;
          var titles = []; var s = JSON.stringify(d && d.data || {});
          var re = /"(?:primaryText|title)":"([^"]{1,80})"/g, m; while ((m = re.exec(s)) && titles.length < 8) titles.push(m[1]);
          summary.sampleText = titles;
        } catch (e) { summary.parseError = String(e); summary.head = o.text.slice(0, 200); }
        window.__pcReplayResult = JSON.stringify(summary);
      })
      .catch(function (e) { window.__pcReplayResult = JSON.stringify({ error: String(e) }); });
  };
  console.log('[S2] capture installed in ' + location.href);
})();
""".trimIndent()
    }
}
