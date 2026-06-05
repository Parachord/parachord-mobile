package com.parachord.shared.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

/**
 * iOS [JsRuntime] implementation backed by JavaScriptCore (JSC) — the
 * same `.axe` plugin host as desktop (Electron) and Android (WebView).
 *
 * ## The cross-platform plugin host
 *
 * Where Android needs a headless WebView (its standalone JSC binding
 * isn't usable without one), iOS uses JSC directly. A `JSContext` IS the
 * runtime. [initialize] reproduces what Android's `bootstrap.html` +
 * `JsBridge` do:
 *   1. Create the JSContext, alias `window = globalThis` (JSC has no
 *      `window`; `.axe` plugins + resolver-loader.js assume browser-style
 *      globals).
 *   2. Let Swift attach a `NativeBridge` object (log / fetchAsync /
 *      storageGet / storageSet) — Kotlin/Native's JSC bindings can't
 *      attach JS callables, so the platform side provides it via
 *      [nativeBridgeInstaller].
 *   3. Run the bootstrap polyfills ([BOOTSTRAP_JS]) — console / fetch /
 *      createPluginStorage / nativeStorage / a minimal Headers shim,
 *      all backed by `NativeBridge`. Byte-compatible with Android's
 *      bootstrap so the SAME `.axe` files run unmodified.
 *   4. Load the bundled `resolver-loader.js` (unchanged from desktop)
 *      and instantiate `window.__resolverLoader = new ResolverLoader()`.
 *
 * After init, `PluginManager` (shared, common to all platforms) loads
 * the `.axe` files into the resolver-loader and drives resolve / search.
 *
 * ## Without a NativeBridge installer
 *
 * If [nativeBridgeInstaller] is null, [initialize] only stands up a bare
 * JSContext (no polyfills, no resolver-loader). That's the smoke-test
 * path used by `IosSmokeTest` — it attaches its own ad-hoc polyfills
 * after the fact. The full plugin host is the installer-present path,
 * wired by `IosContainer`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosJsRuntime : JsRuntime {

    /**
     * Swift hook to attach the `NativeBridge` JS object to the context
     * BEFORE the bootstrap polyfills run (they reference `NativeBridge`).
     * Set by `IosContainer`'s Swift caller. When null, [initialize] does
     * the bare-context path only.
     */
    var nativeBridgeInstaller: ((JSContext) -> Unit)? = null

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var context: JSContext? = null

    /** Exposed for Swift-side ad-hoc polyfill injection (smoke tests). */
    val nativeContext: JSContext? get() = context

    override suspend fun initialize() {
        if (context != null) return
        val ctx = JSContext()
        ctx.setExceptionHandler { _, exception ->
            println("[IosJsRuntime] uncaught JS exception: ${exception?.toString() ?: "<unknown>"}")
        }
        ctx.evaluateScript("if (typeof window === 'undefined') { globalThis.window = globalThis; }")
        context = ctx

        val installer = nativeBridgeInstaller
        if (installer != null) {
            // Full plugin-host bootstrap.
            installer(ctx)                       // Swift attaches NativeBridge
            ctx.evaluateScript(BOOTSTRAP_JS)     // console/fetch/storage polyfills
            val loaderJs = readBundledResource("resolver-loader", "js")
            if (loaderJs != null) {
                ctx.evaluateScript(loaderJs)
                ctx.evaluateScript(
                    "if (typeof ResolverLoader !== 'undefined') { window.__resolverLoader = new ResolverLoader(); }"
                )
            } else {
                println("[IosJsRuntime] resolver-loader.js not found in bundle — plugin host disabled")
            }
        }
        _ready.value = true
    }

    override suspend fun evaluate(script: String): String? {
        val ctx = context ?: return null
        val result: JSValue? = ctx.evaluateScript(script)
        if (result == null || result.isUndefined || result.isNull) return null
        return result.toString()
    }

    override fun teardown() {
        context = null
        _ready.value = false
    }

    /** Read a bundled resource file as a UTF-8 string. */
    private fun readBundledResource(name: String, ext: String): String? {
        val path = NSBundle.mainBundle.pathForResource(name, ext) ?: return null
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: return null
        @Suppress("CAST_NEVER_SUCCEEDS")
        return NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String
    }

    private companion object {
        /**
         * Bootstrap polyfills mirroring Android's `bootstrap.html` so the
         * same `.axe` plugins run unmodified. `NativeBridge` (log /
         * fetchAsync / storageGet / storageSet) is attached from Swift
         * before this runs.
         *
         * `Headers` is shimmed minimally — JSC has no DOM. (Plugins that
         * need `DOMParser`, e.g. bandcamp, still can't run on JSC; that's
         * a documented limitation, not something this bootstrap fixes.)
         */
        val BOOTSTRAP_JS = """
            (function() {
                if (typeof window === 'undefined') { globalThis.window = globalThis; }

                // atob / btoa — JSC (unlike WebView / Node) has no base64
                // globals, but PluginManager decodes each .axe via
                // `JSON.parse(atob(b64))`. Pure-JS Latin-1 implementations,
                // matching the WebView behavior the plugins were written
                // against.
                var __b64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
                if (typeof atob === 'undefined') {
                    window.atob = function(input) {
                        var str = String(input).replace(/=+${'$'}/, '');
                        var output = '';
                        for (var bc = 0, bs = 0, buffer, i = 0; (buffer = str.charAt(i++)); ) {
                            buffer = __b64.indexOf(buffer);
                            if (buffer === -1) continue;
                            bs = bc % 4 ? bs * 64 + buffer : buffer;
                            if (bc++ % 4) {
                                output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
                            }
                        }
                        return output;
                    };
                }
                if (typeof btoa === 'undefined') {
                    window.btoa = function(input) {
                        var str = String(input);
                        var output = '';
                        for (var block = 0, charCode, i = 0, map = __b64;
                             str.charAt(i | 0) || (map = '=', i % 1);
                             output += map.charAt(63 & (block >> (8 - (i % 1) * 8)))) {
                            charCode = str.charCodeAt(i += 3 / 4);
                            if (charCode > 0xFF) throw new Error('btoa: char out of range');
                            block = (block << 8) | charCode;
                        }
                        return output;
                    };
                }

                // console → NativeBridge.log
                window.console = {
                    log:   function() { NativeBridge.log('debug', Array.prototype.slice.call(arguments).map(String).join(' ')); },
                    info:  function() { NativeBridge.log('info',  Array.prototype.slice.call(arguments).map(String).join(' ')); },
                    warn:  function() { NativeBridge.log('warn',  Array.prototype.slice.call(arguments).map(String).join(' ')); },
                    error: function() { NativeBridge.log('error', Array.prototype.slice.call(arguments).map(String).join(' ')); },
                    debug: function() { NativeBridge.log('debug', Array.prototype.slice.call(arguments).map(String).join(' ')); }
                };

                // Minimal Headers shim (JSC has no DOM Headers).
                if (typeof Headers === 'undefined') {
                    window.Headers = function() { this._h = {}; };
                    window.Headers.prototype.get = function(k) { return this._h[String(k).toLowerCase()] || null; };
                    window.Headers.prototype.set = function(k, v) { this._h[String(k).toLowerCase()] = v; };
                }

                // async fetch → NativeBridge.fetchAsync, resolved via __fetchCallbacks
                window.__fetchCallbacks = {};
                var __fetchIdCounter = 0;
                window.fetch = function(url, options) {
                    options = options || {};
                    var method = (options.method || 'GET').toUpperCase();
                    var headers = JSON.stringify(options.headers || {});
                    var body = typeof options.body === 'string' ? options.body
                             : options.body ? JSON.stringify(options.body) : '';
                    var callbackId = 'fetch_' + (++__fetchIdCounter);
                    return new Promise(function(resolve, reject) {
                        window.__fetchCallbacks[callbackId] = function(envelopeStr) {
                            delete window.__fetchCallbacks[callbackId];
                            try {
                                var parsed = JSON.parse(envelopeStr);
                                var responseBody = parsed.body || '';
                                resolve({
                                    ok: parsed.ok,
                                    status: parsed.status,
                                    statusText: parsed.ok ? 'OK' : 'Error',
                                    text: function() { return Promise.resolve(responseBody); },
                                    json: function() {
                                        try { return Promise.resolve(JSON.parse(responseBody)); }
                                        catch (e) { return Promise.reject(new Error('Invalid JSON')); }
                                    },
                                    headers: new Headers()
                                });
                            } catch (e) {
                                reject(new Error('Failed to parse fetch response: ' + e.message));
                            }
                        };
                        try {
                            NativeBridge.fetchAsync(callbackId, url, method, headers, body);
                        } catch (e) {
                            delete window.__fetchCallbacks[callbackId];
                            reject(e);
                        }
                    });
                };
                window.nativeFetch = window.fetch;

                // iTunesRateLimiter — applemusic.axe calls
                // `window.iTunesRateLimiter.fetch(url)` for its no-auth iTunes
                // Search lookups. The desktop/Android hosts provide this global;
                // it is NOT defined in any .axe or resolver-loader.js. On Android
                // it never surfaced because the NATIVE applemusic resolver runs
                // (native-first), so the .axe resolve() is never invoked. iOS is
                // .axe-only, so the plugin host must supply it. Passthrough —
                // JSC has no setTimeout for an inter-request gap, and request
                // throttling is handled a layer up by IosTrackResolverCache's
                // concurrency cap.
                window.iTunesRateLimiter = {
                    fetch: function(url, options) { return window.fetch(url, options); }
                };

                // Storage — scoped per-plugin via createPluginStorage; the
                // native side enforces the plugin.* / parachord.* allowlist.
                window.createPluginStorage = function(pluginId) {
                    var prefix = 'plugin.' + pluginId + '.';
                    return {
                        get: function(key) { return NativeBridge.storageGet(prefix + key); },
                        set: function(key, value) { NativeBridge.storageSet(prefix + key, value); }
                    };
                };
                window.nativeStorage = {
                    get: function(key) { return NativeBridge.storageGet('plugin._global.' + key); },
                    set: function(key, value) { NativeBridge.storageSet('plugin._global.' + key, value); }
                };

                window.__parachordClient = 'ios';
            })();
        """.trimIndent()
    }
}
