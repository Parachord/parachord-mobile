package com.parachord.android.playback.handlers

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Message
import com.parachord.shared.platform.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.PermissionRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.ref.WeakReference
import android.util.Base64
import android.view.WindowManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridge between Kotlin and Apple's MusicKit JS running inside a hidden WebView.
 *
 * This mirrors the desktop Parachord approach of using MusicKit JS as a
 * cross-platform fallback for Apple Music playback and catalog search.
 * The WebView hosts musickit-bridge.html which loads MusicKit v3 from Apple's CDN.
 *
 * Communication:
 * - Kotlin -> JS: via [evaluate] wrapping calls in async IIFEs
 * - JS -> Kotlin: via [MusicKitJsInterface] @JavascriptInterface callbacks
 */
class MusicKitWebBridge constructor(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    /**
     * Throttle gate for Apple Music catalog API calls. All three call sites
     * ([search], [getPlaylist], [preload]) wrap their JS evaluate() in
     * [catalogLimiter.runThrottled] so a burst of MBID-enrichment fallback
     * searches (large library import / hosted-XSPF refresh) can't trip
     * Apple's per-token throttle and cascade into `MusicDataRequest.Error 1`
     * on the active play path. Mirrors desktop's `nativeMusicKitLimiter`.
     */
    private val catalogLimiter = MusicKitCatalogLimiter()
    companion object {
        private const val TAG = "MusicKitWebBridge"
        /** Served via WebViewAssetLoader so the bridge has an https:// origin,
         *  which is required for cross-origin postMessage with Apple's auth page. */
        private const val BRIDGE_URL = "https://appassets.androidplatform.net/assets/js/musickit-bridge.html"
        private const val APP_NAME = "Parachord"

        /**
         * Hosts allowed to load in the MusicKit WebView and auth popup.
         * Anything not matching is blocked by shouldOverrideUrlLoading.
         * security: H3, H4
         */
        private val ALLOWED_HOSTS = setOf(
            "appassets.androidplatform.net",  // Our bridge page
            "js-cdn.music.apple.com",         // MusicKit JS library CDN
            "idmsa.apple.com",                // Apple ID sign-in
            "appleid.apple.com",              // Apple ID auth
            "music.apple.com",                // Apple Music web
            "api.music.apple.com",            // MusicKit REST API
            "buy.itunes.apple.com",           // iTunes auth/purchase (used by MusicKit)
        )

        /** Suffix match for CDN subdomains (mzstatic serves artwork + MusicKit assets). */
        private val ALLOWED_HOST_SUFFIXES = listOf(
            ".mzstatic.com",
            ".apple.com",
        )

        fun isAllowedMusicKitHost(host: String): Boolean {
            if (host in ALLOWED_HOSTS) return true
            return ALLOWED_HOST_SUFFIXES.any { host.endsWith(it) }
        }
    }

    private var webView: WebView? = null
    private var pageLoaded = CompletableDeferred<Unit>()
    /** Completes when MusicKit JS library has loaded and is callable. */
    private var musicKitReady = CompletableDeferred<Unit>()

    /** Weak reference to the current Activity for showing auth dialogs. */
    private var activityRef: WeakReference<Activity>? = null

    /** Dialog showing the Apple ID login popup during authorization. */
    private var authDialog: AlertDialog? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _authorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = _authorized.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    /** Raw MusicKit playback state name (e.g. "playing", "stalled", "paused"). */
    @Volatile
    var playbackStateName: String = "none"
        private set

    /** Callback invoked when the current track finishes playing (for auto-advance). */
    var onTrackEnded: (() -> Unit)? = null

    /**
     * Called from [onPlaybackStateChange] whenever MusicKit transitions
     * to an `isPlaying = true` state from any non-playing state (paused,
     * stopped, loading, etc.) — including the auto-resume-on-audio-session-
     * interruption-recovery behavior that fires when a Bluetooth speaker
     * powers off while we're paused.
     *
     * PlaybackController wires this to force-pause via [pause] whenever the
     * resume happens inside the user-pause suppression window. The push
     * event runs even when our polling loop has been stopped (which it has,
     * during a user pause), so this is the only reliable signal.
     */
    var onResumedToPlaying: (() -> Unit)? = null

    // Actual track metadata from MusicKit JS (what's REALLY playing)
    /** Title of the track MusicKit reports as currently playing. */
    var actualTitle: String? = null; private set
    /** Artist of the track MusicKit reports as currently playing. */
    var actualArtist: String? = null; private set
    /** Album name of the track MusicKit reports as currently playing. */
    var actualAlbum: String? = null; private set
    /** Album artwork URL of the track MusicKit reports as currently playing. */
    var actualArtworkUrl: String? = null; private set

    /** Emitted when playback requires Apple ID sign-in. UI should prompt the user. */
    private val _signInRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signInRequired: SharedFlow<Unit> = _signInRequired.asSharedFlow()

    // ── Lifecycle ─────────────────────────────────────────────────

    /**
     * Set the current Activity reference for showing auth popups.
     * Call from Activity.onResume() and clear in onPause().
     */
    fun setActivity(activity: Activity?) {
        activityRef = activity?.let { WeakReference(it) }
    }

    /**
     * Keep the WebView alive when the Activity goes to background (screen off).
     * Android automatically pauses WebViews when the hosting Activity pauses,
     * which stops JS timers, audio playback, and network activity. During Apple
     * Music playback, we must explicitly counteract this to keep MusicKit streaming.
     *
     * Call [keepAlive] from Activity.onPause() when Apple Music is playing, and
     * [allowSuspend] from Activity.onResume() to return to normal lifecycle.
     */
    fun keepAlive() {
        webView?.let { wv ->
            wv.onResume()
            wv.resumeTimers()
            Log.d(TAG, "WebView kept alive (onResume + resumeTimers) for background playback")
        }
    }

    /**
     * Allow the WebView to follow normal Activity lifecycle (pause when backgrounded).
     * No-op if called when not kept alive — safe to call unconditionally.
     */
    fun allowSuspend() {
        // Don't explicitly call onPause()/pauseTimers() — let the system manage
        // the WebView normally. We only need keepAlive() to counteract system pauses.
    }

    /** Emit a sign-in required event for the UI to observe. */
    fun emitSignInRequired() {
        _signInRequired.tryEmit(Unit)
    }

    /** Create the hidden WebView and load the MusicKit bridge HTML. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun initialize() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext

        pageLoaded = CompletableDeferred()
        musicKitReady = CompletableDeferred()

        // Serve assets from https:// origin so Apple's auth page can postMessage back
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            // Use LAYER_TYPE_NONE — this WebView is never added to the view
            // hierarchy (it runs headlessly for MusicKit JS). LAYER_TYPE_HARDWARE
            // caused audio stutters on screen-off: Android reclaims GPU resources
            // from hardware-accelerated views when the screen turns off, which
            // briefly disrupted the WebView's audio pipeline (visible as
            // state:"unknown"/isPlaying:false flickers in MusicKit). Widevine DRM
            // decryption works through MediaDrm/MediaCodec at the platform level,
            // not the View's rendering layer, so hardware acceleration isn't needed.
            setLayerType(View.LAYER_TYPE_NONE, null)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(MusicKitJsInterface(), "MusicKitBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    return request?.url?.let { assetLoader.shouldInterceptRequest(it) }
                        ?: super.shouldInterceptRequest(view, request)
                }

                /**
                 * URL allowlist for the MusicKit WebView. Only Apple-related
                 * hosts and our own asset-loader origin are permitted. If
                 * MusicKit or its auth flow is coerced into navigating to a
                 * third-party URL, the navigation is blocked — preventing an
                 * attacker page from gaining access to the MusicKitBridge JS
                 * interface.
                 *
                 * security: H3
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val host = request?.url?.host?.lowercase() ?: return false
                    if (isAllowedMusicKitHost(host)) return false // Allow
                    Log.w(TAG, "Blocked navigation to disallowed host: $host")
                    return true // Block
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Bridge page loaded: $url")
                    pageLoaded.complete(Unit)
                }
            }
            // Handle MusicKit authorize() popup — show Apple ID login in a Dialog
            webChromeClient = object : WebChromeClient() {
                /**
                 * Grant protected media ID permission so MusicKit JS can use
                 * Widevine DRM via EME (Encrypted Media Extensions).
                 * Without this, EME silently fails and MusicKit falls back
                 * to 30-second preview playback.
                 */
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.let {
                        Log.d(TAG, "Permission request from ${it.origin}: ${it.resources.joinToString()}")
                        // Only grant protected media ID from our own bridge origin.
                        // security: M3
                        val origin = it.origin?.host?.lowercase()
                        if (origin == "appassets.androidplatform.net" &&
                            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in it.resources
                        ) {
                            Log.d(TAG, "Granting RESOURCE_PROTECTED_MEDIA_ID for DRM")
                            it.grant(it.resources)
                            return
                        }
                        Log.w(TAG, "Denying permission request from origin: $origin")
                    }
                    super.onPermissionRequest(request)
                }

                @SuppressLint("SetJavaScriptEnabled")
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    val activity = activityRef?.get()
                    if (activity == null || activity.isFinishing) {
                        Log.w(TAG, "No activity available for auth popup")
                        return false
                    }
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

                    Log.d(TAG, "Opening Apple ID auth popup in dialog")

                    // Dismiss any previous auth dialog before opening a new one
                    authDialog?.dismiss()
                    authDialog = null

                    // Reference to bridge WebView for relaying postMessage
                    val bridgeWv = webView

                    val authWebView = WebView(activity).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        setBackgroundColor(android.graphics.Color.WHITE)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true

                        // JS interface to relay window.opener.postMessage from
                        // auth popup back to the bridge WebView. Android WebView
                        // doesn't support window.opener between separate WebViews.
                        addJavascriptInterface(object {
                            /**
                             * Relay postMessage from the auth popup to the bridge
                             * WebView. Both [data] and [origin] come from the
                             * untrusted popup page, so both must be escaped to
                             * prevent JS injection. security: H4
                             */
                            @JavascriptInterface
                            fun relay(data: String, origin: String) {
                                Log.d(TAG, "Relaying postMessage to bridge: origin=$origin")
                                bridgeWv?.post {
                                    // Use Base64 encoding for both data and origin
                                    // to eliminate any injection risk from string
                                    // interpolation into evaluateJavascript.
                                    val dataB64 = android.util.Base64.encodeToString(
                                        data.toByteArray(), android.util.Base64.NO_WRAP,
                                    )
                                    val originB64 = android.util.Base64.encodeToString(
                                        origin.toByteArray(), android.util.Base64.NO_WRAP,
                                    )
                                    bridgeWv.evaluateJavascript(
                                        "(function(){var d=atob('$dataB64');var o=atob('$originB64');window.postMessage(JSON.parse(d),o);})()",
                                        null,
                                    )
                                }
                            }
                        }, "AuthRelay")

                        webViewClient = object : WebViewClient() {
                            /**
                             * Restrict auth popup navigation to Apple auth domains.
                             * Prevents a malicious redirect from gaining the AuthRelay
                             * JS interface. security: H4
                             */
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val host = request?.url?.host?.lowercase() ?: return false
                                if (isAllowedMusicKitHost(host)) return false
                                Log.w(TAG, "Auth popup: blocked navigation to $host")
                                return true
                            }

                            override fun onPageFinished(v: WebView?, url: String?) {
                                super.onPageFinished(v, url)
                                Log.d(TAG, "Auth WebView page: $url")
                                // Patch window.opener to relay postMessage to bridge
                                v?.evaluateJavascript("""
                                    (function() {
                                        if (!window.opener) { window.opener = {}; }
                                        var origPM = window.opener.postMessage;
                                        window.opener.postMessage = function(data, origin) {
                                            AuthRelay.relay(
                                                typeof data === 'string' ? data : JSON.stringify(data),
                                                origin || '*'
                                            );
                                            if (origPM) origPM.call(window.opener, data, origin);
                                        };
                                    })()
                                """.trimIndent(), null)
                            }
                        }
                        // Handle window.close() from Apple's auth page after Allow
                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) {
                                Log.d(TAG, "Auth popup window.close()")
                                authDialog?.dismiss()
                                authDialog = null
                            }
                        }
                    }

                    transport.webView = authWebView
                    resultMsg.sendToTarget()

                    val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
                        .setView(authWebView)
                        .setOnDismissListener {
                            Log.d(TAG, "Auth dialog dismissed")
                            authWebView.destroy()
                            authDialog = null
                        }
                        .create()
                    authDialog = dialog
                    dialog.show()
                    dialog.window?.apply {
                        // Fill entire screen
                        setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // White background to match Apple's auth page
                        setBackgroundDrawableResource(android.R.color.white)
                        // Allow soft keyboard for Apple ID input fields
                        setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                        )
                        clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                        clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    }
                    authWebView.requestFocus()

                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    Log.d(TAG, "Auth popup closed by MusicKit")
                    authDialog?.dismiss()
                    authDialog = null
                }
            }
        }
        webView = wv
        wv.loadUrl(BRIDGE_URL)
        _ready.value = true
    }

    /** Destroy the WebView and release resources. */
    fun teardown() {
        authDialog?.dismiss()
        authDialog = null
        webView?.destroy()
        webView = null
        pageLoaded = CompletableDeferred()
        musicKitReady = CompletableDeferred()
        _ready.value = false
        _configured.value = false
        _authorized.value = false
        _isPlaying.value = false
        _position.value = 0L
        _duration.value = 0L
    }

    /**
     * Full disconnect — clears saved tokens and signs out of Apple Music.
     * Use this when the user explicitly disconnects in Settings.
     */
    suspend fun disconnect() {
        // Clear saved music user token
        settingsStore.clearAppleMusicUserToken()
        // Tell MusicKit to unauthorize if the WebView is alive
        if (webView != null && _configured.value) {
            try { evaluate("unauthorize()") } catch (_: Exception) {}
        }
        teardown()
    }

    // ── Configuration & Auth ──────────────────────────────────────

    /**
     * Check if a JWT developer token has expired.
     * Returns true if expired or unparseable.
     */
    private fun isTokenExpired(token: String): Boolean {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return true
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING))
            val expMatch = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)
            val exp = expMatch?.groupValues?.get(1)?.toLongOrNull() ?: return true
            val nowSec = System.currentTimeMillis() / 1000
            (nowSec >= exp).also { expired ->
                if (expired) Log.w(TAG, "Developer token expired (exp=$exp, now=$nowSec)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check token expiration: ${e.message}")
            false // Assume valid if we can't parse — let MusicKit reject it
        }
    }

    /**
     * Configure MusicKit with the developer token from settings.
     * Automatically initializes the WebView if needed.
     * Returns true if configuration succeeded.
     */
    suspend fun configure(): Boolean {
        if (webView == null) initialize()
        pageLoaded.await()
        Log.d(TAG, "Page loaded, waiting for MusicKit JS library...")
        musicKitReady.await()
        Log.d(TAG, "MusicKit JS ready, configuring...")
        val token = settingsStore.getAppleMusicDeveloperToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No Apple Music developer token configured")
            return false
        }
        if (isTokenExpired(token)) {
            Log.w(TAG, "Apple Music developer token has expired — needs regeneration")
            _signInRequired.tryEmit(Unit)
            return false
        }
        // Base64-encode tokens to eliminate injection risk from string
        // interpolation. security: M4/M5
        val savedMut = settingsStore.getAppleMusicUserToken()
        val mutArg = if (savedMut != null) "atob('${b64(savedMut)}')" else "null"
        val result = evaluate("configure(atob('${b64(token)}'), atob('${b64(APP_NAME)}'), $mutArg)")
        Log.d(TAG, "configure() JS result: $result")
        if (result == null) return false
        return try {
            val parsed = json.decodeFromString<ConfigureResponse>(cleanJsString(result))
            val success = parsed.success
            if (!success) Log.w(TAG, "configure() failed: ${parsed.error}")
            if (success) {
                _configured.value = true
                if (parsed.authorized) {
                    Log.d(TAG, "Restored auth from saved music user token")
                    _authorized.value = true
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse configure response: $result", e)
            false
        }
    }

    /**
     * Prompt Apple ID sign-in via MusicKit's authorize flow.
     * This opens a popup (handled by onCreateWindow as a visible Dialog)
     * where the user signs in with their Apple ID.
     * Returns true if the user authorized successfully.
     *
     * Call [setActivity] before this so the auth popup can be shown.
     */
    suspend fun authorize(): Boolean {
        if (activityRef?.get() == null) {
            Log.w(TAG, "No activity set — authorize() popup will not be visible")
        }

        // Ensure WebView is initialized and configured
        if (webView == null || !_configured.value) {
            // Tear down stale state if the WebView exists but isn't configured
            if (webView != null) teardown()
            initialize()
            val configured = configure()
            Log.d(TAG, "Pre-authorize configure() result: $configured")
            if (!configured) return false
            // configure() may have restored auth from saved MUT
            if (_authorized.value) {
                Log.d(TAG, "Already authorized after configure (saved MUT valid)")
                return true
            }
        }

        musicKitReady.await()
        val result = evaluate("authorize()") ?: return false
        Log.d(TAG, "authorize() JS result: $result")
        return try {
            val parsed = json.decodeFromString<AuthorizeResponse>(cleanJsString(result))
            val authorized = parsed.authorized
            Log.d(TAG, "authorize() authorized=$authorized")
            if (authorized) {
                _authorized.value = true
                // Persist the music user token for future sessions
                parsed.musicUserToken?.let { mut ->
                    Log.d(TAG, "Saving music user token (${mut.length} chars)")
                    settingsStore.setAppleMusicUserToken(mut)
                }
            }
            authorized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse authorize response: $result", e)
            false
        }
    }

    // ── Search ────────────────────────────────────────────────────

    /**
     * Search the Apple Music catalog for songs.
     * Returns a list of search results, or empty on failure.
     */
    suspend fun search(query: String, limit: Int = 10): List<AppleMusicSearchResult> {
        musicKitReady.await()
        val storefront = settingsStore.getAppleMusicStorefront() ?: "us"
        return catalogLimiter.runThrottled {
            val result = evaluate("search(atob('${b64(query)}'), $limit, atob('${b64(storefront)}'))")
                ?: return@runThrottled emptyList()
            try {
                val parsed = json.decodeFromString<MusicKitSearchResponse>(cleanJsString(result))
                if (!parsed.success) {
                    if (catalogLimiter.looksLikeRateLimit(parsed.error)) {
                        catalogLimiter.reportRateLimit()
                    }
                    Log.w(TAG, "Search failed: ${parsed.error}")
                    emptyList()
                } else {
                    catalogLimiter.reportSuccess()
                    parsed.results
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse search response: $result", e)
                emptyList()
            }
        } ?: emptyList()
    }

    // ── Playlist Fetch ─────────────────────────────────────────────

    /**
     * Result from fetching an Apple Music playlist.
     */
    data class AppleMusicPlaylistResult(
        val name: String,
        val tracks: List<AppleMusicSearchResult>,
    )

    /**
     * Fetch an Apple Music playlist's tracks by catalog playlist ID.
     * Returns null if MusicKit is not configured or the playlist can't be found.
     */
    suspend fun getPlaylist(playlistId: String): AppleMusicPlaylistResult? {
        musicKitReady.await()
        val storefront = settingsStore.getAppleMusicStorefront() ?: "us"
        return catalogLimiter.runThrottled {
            val result = evaluate("getPlaylist(atob('${b64(playlistId)}'), atob('${b64(storefront)}'))")
                ?: return@runThrottled null
            try {
                val response = json.decodeFromString<GetPlaylistResponse>(cleanJsString(result))
                if (!response.success) {
                    if (catalogLimiter.looksLikeRateLimit(response.error)) {
                        catalogLimiter.reportRateLimit()
                    }
                    return@runThrottled null
                }
                if (response.tracks.isNullOrEmpty()) return@runThrottled null
                catalogLimiter.reportSuccess()
                AppleMusicPlaylistResult(
                    name = response.name ?: "Playlist",
                    tracks = response.tracks.map { t ->
                        AppleMusicSearchResult(
                            id = t.id,
                            title = t.title,
                            artist = t.artist,
                            album = t.album,
                            duration = t.duration,
                            artworkUrl = t.artworkUrl,
                            isrc = null,
                            previewUrl = null,
                            appleMusicUrl = null,
                        )
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse getPlaylist response: ${e.message}")
                null
            }
        }
    }

    // ── Playback Control ──────────────────────────────────────────

    /**
     * Preload a song's catalog data so a subsequent [play] starts faster.
     * Non-blocking and best-effort — failures are silently ignored.
     */
    suspend fun preload(songId: String) {
        if (!musicKitReady.isCompleted) return
        // Throttled — preload calls fan out per-track during background source
        // resolution. Best-effort: if the limiter's cooldown is open, the
        // preload is silently skipped (playback's catalog lookup will reload
        // it lazily anyway).
        catalogLimiter.runThrottled {
            evaluate("preload(atob('${b64(songId)}'))")
        }
    }

    /** Play a song by Apple Music catalog ID. Returns true on success. */
    suspend fun play(songId: String): Boolean {
        musicKitReady.await()
        // Ensure the WebView is active before playing. When the screen is off,
        // Android pauses WebViews (JS timers, audio, network). If we try to play
        // a new track without waking the WebView first, MusicKit starts playback
        // but the system immediately re-pauses the audio pipeline, causing the
        // track to pause ~30ms after it reports "playing".
        keepAlive()
        // Clear actual metadata until MusicKit reports what's really playing
        actualTitle = null
        actualArtist = null
        actualAlbum = null
        actualArtworkUrl = null
        val result = evaluate("play(atob('${b64(songId)}'))") ?: return false
        return parseSuccessResponse(result)
    }

    // Note: MusicKit v3 music.volume has no effect on Android WebView —
    // DRM audio goes through MediaDrm/MediaCodec, bypassing the JS audio pipeline.
    // Volume normalization is handled on the Spotify side instead.

    /** Pause playback. */
    suspend fun pause() {
        musicKitReady.await()
        evaluate("pause()")
    }

    /** Resume playback. */
    suspend fun resume() {
        musicKitReady.await()
        evaluate("resume()")
    }

    /** Stop playback. */
    suspend fun stop() {
        musicKitReady.await()
        evaluate("stop()")
    }

    /** Seek to position in milliseconds. */
    suspend fun seekTo(positionMs: Long) {
        musicKitReady.await()
        evaluate("seekTo($positionMs)")
    }

    // ── State Accessors ───────────────────────────────────────────

    fun getPosition(): Long = _position.value
    fun getDuration(): Long = _duration.value
    fun getIsPlaying(): Boolean = _isPlaying.value

    /**
     * Actively poll playback state from JS.
     * The playbackStateDidChange event only fires on state transitions
     * (play→pause, loading→playing), NOT continuously during playback.
     * This method evaluates getPlaybackState() in JS to get fresh
     * position/duration values for the progress bar.
     */
    suspend fun pollPlaybackState() {
        val result = evaluate("getPlaybackState()") ?: return
        try {
            val state = json.decodeFromString<MusicKitPlaybackState>(cleanJsString(result))
            _isPlaying.value = state.isPlaying
            _position.value = state.position.toLong()
            _duration.value = state.duration.toLong()
            playbackStateName = state.state ?: "unknown"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse polled playback state: $result", e)
        }
    }

    // ── JS Interface (JS -> Kotlin callbacks) ─────────────────────

    inner class MusicKitJsInterface {

        @JavascriptInterface
        fun onBridgeReady(status: String) {
            Log.d(TAG, "MusicKit JS library loaded: $status")
            musicKitReady.complete(Unit)
        }

        @JavascriptInterface
        fun onPlaybackStateChange(jsonStr: String) {
            Log.d(TAG, "Playback state change: $jsonStr")
            try {
                val state = json.decodeFromString<MusicKitPlaybackState>(jsonStr)
                val prevPlaying = _isPlaying.value
                _isPlaying.value = state.isPlaying
                // JS bridge already sends values in milliseconds
                _position.value = state.position.toLong()
                _duration.value = state.duration.toLong()
                playbackStateName = state.state ?: "unknown"
                // Fire whenever MusicKit transitions into playing — the
                // listener decides whether to override (e.g. force-pause
                // when the user just paused). See [onResumedToPlaying] doc.
                if (!prevPlaying && state.isPlaying) {
                    onResumedToPlaying?.invoke()
                }
                // Detect ended state as a safety net — the JS side also fires
                // onTrackEnded for this, but duplicates are harmless and this
                // ensures we catch it even if the JS callback is missed.
                // Guard: on spotty networks MusicKit may report "ended" when
                // buffering fails mid-song. Only fire if position is within
                // 15 seconds of the reported duration (or duration is unknown).
                if (state.state == "ended" || state.state == "completed") {
                    val pos = state.position.toLong()
                    val dur = state.duration.toLong()
                    val nearEnd = dur <= 0 || dur - pos < 15_000
                    if (nearEnd) {
                        Log.d(TAG, "Playback state is '${state.state}', firing onTrackEnded (pos=$pos dur=$dur)")
                        onTrackEnded?.invoke()
                    } else {
                        Log.w(TAG, "Ignoring '${state.state}' state mid-song (pos=$pos dur=$dur) — likely network stall")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse playback state: $jsonStr", e)
            }
        }

        @JavascriptInterface
        fun onAuthChange(jsonStr: String) {
            Log.d(TAG, "Auth change: $jsonStr")
            try {
                val state = json.decodeFromString<MusicKitAuthState>(jsonStr)
                _configured.value = state.configured
                _authorized.value = state.authorized
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse auth state: $jsonStr", e)
            }
        }

        @JavascriptInterface
        fun onNowPlayingChange(jsonStr: String) {
            Log.d(TAG, "Now playing change: $jsonStr")
            try {
                val data = json.decodeFromString<MusicKitNowPlayingInfo>(jsonStr)
                actualTitle = data.title
                actualArtist = data.artist
                actualAlbum = data.album
                actualArtworkUrl = data.artworkUrl
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse now playing change: ${e.message}")
            }
        }

        @JavascriptInterface
        fun onTrackEnded(jsonStr: String) {
            Log.d(TAG, "Track ended: $jsonStr")
            // The JS side already validates near-end before calling this.
            // Don't re-check with _position/_duration from StateFlows — polling
            // may have updated them (position resets to 0 at track end, making
            // stale duration comparisons fail and blocking the signal).
            onTrackEnded?.invoke()
        }

        @JavascriptInterface
        fun onEvalResult(callId: String, result: String) {
            evalCallbacks.remove(callId)?.complete(result)
        }

        /** Keep-alive ping from the JS setInterval — keeps the WebView JS
         *  thread scheduled when the screen is off. Also provides a fallback
         *  position/duration update independent of the polling loop. */
        // Track keep-alive timing for diagnostics — helps determine
        // whether JS is still running when the screen is off.
        private var lastKeepAliveTime = 0L
        private var keepAliveCount = 0L

        @JavascriptInterface
        fun onKeepAlive(positionDuration: String) {
            // Parse "posMs/durMs" and update cached state as a side channel
            // when the polling loop can't reach the Main thread.
            val parts = positionDuration.split("/")
            if (parts.size == 2) {
                val pos = parts[0].toLongOrNull() ?: return
                val dur = parts[1].toLongOrNull() ?: return
                _position.value = pos
                if (dur > 0) _duration.value = dur

                // Diagnostic: detect JS keep-alive gaps (WebView JS thread suspended)
                val now = System.currentTimeMillis()
                keepAliveCount++
                if (lastKeepAliveTime > 0) {
                    val gap = now - lastKeepAliveTime
                    if (gap > 5000) {
                        Log.w(TAG, "JS keep-alive gap: ${gap}ms (screen off? WebView suspended?) pos=$pos dur=$dur count=$keepAliveCount")
                    }
                }
                lastKeepAliveTime = now
            }
        }
    }

    // ── Private Helpers ───────────────────────────────────────────

    private val evalCallbacks = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val evalCounter = AtomicInteger(0)

    /**
     * Evaluate a JS expression in the WebView and await its result.
     *
     * Uses a callback via the JS interface to get the resolved value of async
     * functions. Android's evaluateJavascript does NOT await Promises — it returns
     * the Promise object itself ({}) — so we route the resolved value through
     * [MusicKitJsInterface.onEvalResult] instead.
     */
    private suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: run {
            Log.e(TAG, "WebView not initialized")
            return@withContext null
        }
        val callId = "eval_${evalCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<String?>()
        evalCallbacks[callId] = deferred
        val wrapped = """
            (function() {
                try {
                    var p = $script;
                    if (p && typeof p.then === 'function') {
                        p.then(function(r) { MusicKitBridge.onEvalResult('$callId', r || ''); })
                         .catch(function(e) { MusicKitBridge.onEvalResult('$callId', JSON.stringify({success:false,error:e.message||String(e)})); });
                    } else {
                        MusicKitBridge.onEvalResult('$callId', p || '');
                    }
                } catch(e) {
                    MusicKitBridge.onEvalResult('$callId', JSON.stringify({success:false,error:e.message||String(e)}));
                }
            })()
        """.trimIndent()
        wv.evaluateJavascript(wrapped) { /* ignore direct result */ }
        deferred.await()
    }

    /**
     * Strip the outer quotes that evaluateJavascript wraps around string results.
     * e.g. "\"{ ... }\"" -> "{ ... }"
     * Also unescapes inner quotes.
     */
    private fun cleanJsString(raw: String): String {
        var s = raw
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        s = s.replace("\\\"", "\"")
        s = s.replace("\\\\", "\\")
        return s
    }

    /**
     * Base64-encode a string for safe interpolation into JS expressions.
     * The JS side decodes with `atob('...')`. This eliminates injection risks
     * from single quotes, backslashes, newlines, U+2028/U+2029, and any other
     * special characters in user input, tokens, or song IDs.
     * security: M4/M5
     */
    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)

    private fun parseSuccessResponse(raw: String): Boolean {
        return try {
            val parsed = json.decodeFromString<GenericResponse>(cleanJsString(raw))
            parsed.success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: $raw", e)
            false
        }
    }

    // ── Data Classes ──────────────────────────────────────────────

    @Serializable
    private data class GenericResponse(
        val success: Boolean = false,
        val error: String? = null,
    )

    @Serializable
    private data class ConfigureResponse(
        val success: Boolean = false,
        val authorized: Boolean = false,
        val error: String? = null,
    )

    @Serializable
    private data class AuthorizeResponse(
        val success: Boolean = false,
        val authorized: Boolean = false,
        val musicUserToken: String? = null,
        val error: String? = null,
    )

    @Serializable
    data class MusicKitSearchResponse(
        val success: Boolean = false,
        val results: List<AppleMusicSearchResult> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    data class AppleMusicSearchResult(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val duration: Long? = null,
        val artworkUrl: String? = null,
        val isrc: String? = null,
        val previewUrl: String? = null,
        val appleMusicUrl: String? = null,
    )

    @Serializable
    private data class GetPlaylistResponse(
        val success: Boolean,
        val name: String? = null,
        val tracks: List<GetPlaylistTrack>? = null,
        val error: String? = null,
    )

    @Serializable
    private data class GetPlaylistTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val duration: Long? = null,
        val artworkUrl: String? = null,
    )

    @Serializable
    data class MusicKitPlaybackState(
        val state: String? = null,
        val position: Double = 0.0,
        val duration: Double = 0.0,
        val isPlaying: Boolean = false,
    )

    @Serializable
    data class MusicKitAuthState(
        val configured: Boolean = false,
        val authorized: Boolean = false,
    )

    @Serializable
    private data class MusicKitNowPlayingInfo(
        val id: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val duration: Long? = null,
        val artworkUrl: String? = null,
    )
}
