package com.parachord.android.playback.handlers

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.parachord.android.BuildConfig
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Proxy
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * SPIKE (debug builds only, never the default): plays Apple Music through
 * Apple's MusicKit SDK for Android (`MusicKitAuth` + `MediaPlayback` AARs)
 * instead of MusicKit JS in a WebView. See
 * `docs/plans/2026-11-08-musickit-android-sdk-spike.md`.
 *
 * Apple's AARs can't be committed (Apple Developer Program License Agreement
 * §2.6), so they are dropped into `app/libs/musickit/` by hand (gitignored)
 * and reached by reflection: without them the app builds and this handler
 * reports itself unavailable. With them, the toggle in Settings → Developer
 * routes `applemusic` tracks here.
 *
 * It's an [EmbedPlaybackHandler] so the controller's engine-agnostic embed
 * loop (silent ExoPlayer, foreground service, position mirror, `onFinished`
 * advance) drives it, with no change to the MusicKit JS path.
 *
 * Every SDK call runs on the main thread; [tick] caches state every 250 ms so
 * the controller's background poll never touches the SDK.
 */
class MusicKitSdkPlaybackHandler(
    private val context: Context,
    private val settingsStore: SettingsStore,
) : SourceHandler, EmbedPlaybackHandler {

    companion object {
        private const val TAG = "MusicKitSdkSpike"
        private const val PREFS = "musickit_sdk_spike"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SDK_MUT = "sdk_mut"

        private const val AUTH_FACTORY = "com.apple.android.sdk.authentication.AuthenticationFactory"
        private const val AUTH_MANAGER = "com.apple.android.sdk.authentication.AuthenticationManager"
        private const val AUTH_BUILDER = "com.apple.android.sdk.authentication.AuthIntentBuilder"
        private const val TOKEN_RESULT = "com.apple.android.sdk.authentication.TokenResult"
        private const val TOKEN_PROVIDER = "com.apple.android.sdk.authentication.TokenProvider"
        private const val CONTROLLER_FACTORY = "com.apple.android.music.playback.controller.MediaPlayerControllerFactory"
        private const val CONTROLLER = "com.apple.android.music.playback.controller.MediaPlayerController"
        private const val LISTENER = "$CONTROLLER\$Listener"
        private const val CATALOG_BUILDER = "com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider\$Builder"
        private const val LIBRARY_BUILDER = "com.apple.android.music.playback.queue.LibraryPlaybackQueueItemProvider\$Builder"
        private const val QUEUE_PROVIDER = "com.apple.android.music.playback.queue.PlaybackQueueItemProvider"
        private const val MEDIA_ITEM_TYPE = "com.apple.android.music.playback.model.MediaItemType"
        private const val PLAYBACK_STATE = "com.apple.android.music.playback.model.PlaybackState"

        private const val START_TIMEOUT_MS = 15_000L

        private fun cls(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()

        val hasAuthSdk: Boolean get() = cls(AUTH_FACTORY) != null
        val hasPlaybackSdk: Boolean get() = cls(CONTROLLER_FACTORY) != null
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    /** The debug toggle. Off unless a developer turns it on. */
    var enabled: Boolean
        get() = BuildConfig.DEBUG && prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** A Music User Token from the SDK's own sign-in (Apple Music app), if one was done. */
    val sdkUserToken: String? get() = prefs.getString(KEY_SDK_MUT, null)?.takeIf { it.isNotBlank() }

    // SettingsStore's getters suspend; [refreshTokens] caches them for the
    // main-thread SDK calls (the TokenProvider is called synchronously).
    @Volatile private var devTokenCache: String? = null
    @Volatile private var jsUserTokenCache: String? = null

    suspend fun refreshTokens() {
        devTokenCache = settingsStore.getAppleMusicDeveloperToken()
        jsUserTokenCache = settingsStore.getAppleMusicUserToken()
    }

    /** What the spike's settings row shows. */
    fun status(): String = buildString {
        append("auth AAR: ${if (hasAuthSdk) "found" else "missing"}")
        append(" · playback AAR: ${if (hasPlaybackSdk) "found" else "missing"}")
        append(" · Apple Music app: ${appleMusicAppVersion() ?: "not installed"}")
        append(" · token: ${if (sdkUserToken != null) "SDK sign-in" else if (!jsUserTokenCache.isNullOrBlank()) "MusicKit JS" else "none"}")
        lastError?.let { append("\nlast error: $it") }
    }

    fun appleMusicAppVersion(): String? = runCatching {
        context.packageManager.getPackageInfo("com.apple.android.music", 0).versionName
    }.getOrNull()

    // ── Auth ─────────────────────────────────────────────────────────

    private val authManager: Any? by lazy {
        runCatching {
            cls(AUTH_FACTORY)!!.getMethod("createAuthenticationManager", Context::class.java)
                .invoke(null, context.applicationContext)
        }.onFailure { Log.w(TAG, "createAuthenticationManager failed", it) }.getOrNull()
    }

    /** Apple's sign-in intent (handled by the Apple Music app), or null without the SDK. */
    fun signInIntent(): Intent? = runCatching {
        val devToken = devTokenCache ?: error("no developer token")
        val b = cls(AUTH_BUILDER)!!
        var builder: Any = cls(AUTH_MANAGER)!!.getMethod("createIntentBuilder", String::class.java)
            .invoke(authManager, devToken)!!
        builder = runCatching { b.getMethod("setHideStartScreen", Boolean::class.javaPrimitiveType).invoke(builder, true) }
            .getOrNull() ?: builder
        b.getMethod("build").invoke(builder) as Intent
    }.onFailure { Log.w(TAG, "signInIntent failed", it); lastError = "sign-in intent: ${it.rootMessage()}" }.getOrNull()

    /** Reads the sign-in result; stores the token. Returns a line for the UI. */
    fun handleSignInResult(data: Intent?): String = runCatching {
        val r = cls(TOKEN_RESULT)!!
        val result = cls(AUTH_MANAGER)!!.getMethod("handleTokenResult", Intent::class.java).invoke(authManager, data)!!
        if (r.getMethod("isError").invoke(result) as Boolean) {
            val err = r.getMethod("getError").invoke(result)
            "sign-in error: ${(err as? Enum<*>)?.name ?: err}"
        } else {
            val mut = r.getMethod("getMusicUserToken").invoke(result) as String
            prefs.edit().putString(KEY_SDK_MUT, mut).apply()
            controller = null // rebuild with the new token
            "signed in (token ${mut.length} chars)"
        }
    }.getOrElse { "sign-in result failed: ${it.rootMessage()}" }.also { Log.i(TAG, it); lastError = it }

    // ── Controller ───────────────────────────────────────────────────

    private var controller: Any? = null
    @Volatile private var lastError: String? = null

    // Cached by [tick] on the main thread.
    @Volatile private var cachedPosition = 0L
    @Volatile private var cachedDuration = 0L
    @Volatile private var cachedPlaying = false
    @Volatile private var ticking = false

    @Volatile override var volumeFraction: Float = 1f
    @Volatile override var onFinished: (() -> Unit)? = null

    /** Set when a play starts, completed by the listener/tick. */
    @Volatile private var startSignal: CompletableDeferred<String>? = null
    @Volatile private var playCalledAt = 0L
    @Volatile private var currentId: String? = null

    private val statePlaying by lazy { intConst(PLAYBACK_STATE, "PLAYING") }
    private val stateStopped by lazy { intConst(PLAYBACK_STATE, "STOPPED") }

    private fun intConst(c: String, f: String) =
        runCatching { cls(c)!!.getField(f).getInt(null) }.getOrDefault(Int.MIN_VALUE)

    private fun call(name: String): Any? = controller?.let { c ->
        runCatching { c.javaClass.getMethod(name).invoke(c) }
            .onFailure { Log.w(TAG, "$name failed: ${it.rootMessage()}") }
            .getOrNull()
    }

    private fun createController(): Any? {
        if (controller != null) return controller
        val devToken = devTokenCache
        val userToken = sdkUserToken ?: jsUserTokenCache
        if (devToken.isNullOrBlank() || userToken.isNullOrBlank()) {
            lastError = "no developer token or user token"
            return null
        }
        return runCatching {
            // The player's native code isn't loaded for you (Apple's sample app does this too).
            runCatching { System.loadLibrary("c++_shared") }.onFailure { Log.w(TAG, "c++_shared: ${it.message}") }
            runCatching { System.loadLibrary("appleMusicSDK") }.onFailure { Log.w(TAG, "appleMusicSDK: ${it.message}") }
            val providerCls = cls(TOKEN_PROVIDER)!!
            val provider = Proxy.newProxyInstance(providerCls.classLoader, arrayOf(providerCls)) { proxy, m, args ->
                when (m.name) {
                    "getDeveloperToken" -> devToken
                    "getUserToken" -> sdkUserToken ?: jsUserTokenCache
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "ParachordSpikeTokenProvider"
                    else -> null
                }
            }
            val create = cls(CONTROLLER_FACTORY)!!.methods.first {
                it.name == "createLocalController" && it.parameterTypes.size == 2
            }
            val c = create.invoke(null, context.applicationContext, provider)!!
            addListener(c)
            Log.i(TAG, "Local controller created (token: ${if (sdkUserToken != null) "SDK" else "MusicKit JS"}); " +
                "methods with 'olume': ${c.javaClass.methods.filter { it.name.contains("olume") }.map { it.name }}")
            controller = c
            c
        }.onFailure { Log.e(TAG, "createLocalController failed", it); lastError = "controller: ${it.rootMessage()}" }.getOrNull()
    }

    private fun addListener(c: Any) {
        val listenerCls = cls(LISTENER) ?: run { Log.w(TAG, "no Listener interface"); return }
        val listener = Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls)) { proxy, m, args ->
            val since = System.currentTimeMillis() - playCalledAt
            when (m.name) {
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                "toString" -> return@newProxyInstance "ParachordSpikeListener"
                "onPlaybackStateChanged" -> {
                    val cur = (args?.getOrNull(2) as? Number)?.toInt()
                    Log.d(TAG, "+${since}ms onPlaybackStateChanged ${args?.getOrNull(1)} → $cur")
                    if (cur == stateStopped && cachedDuration > 0 && cachedDuration - cachedPosition < 3000) {
                        Log.d(TAG, "stopped at end → onFinished")
                        onFinished?.invoke()
                    }
                }
                "onItemEnded" -> {
                    Log.d(TAG, "+${since}ms onItemEnded pos=${args?.getOrNull(2)}")
                    onFinished?.invoke()
                }
                "onPlaybackError" -> {
                    val e = args?.getOrNull(1) as? Throwable
                    Log.w(TAG, "+${since}ms onPlaybackError: ${e?.rootMessage()}", e)
                    lastError = "playback: ${e?.rootMessage()}"
                    startSignal?.complete("error: ${e?.rootMessage()}")
                }
                else -> Log.d(TAG, "+${since}ms ${m.name}")
            }
            null
        }
        runCatching { c.javaClass.getMethod("addListener", listenerCls).invoke(c, listener) }
            .onFailure { Log.w(TAG, "addListener failed: ${it.rootMessage()}") }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            controller?.let {
                cachedPosition = (call("getCurrentPosition") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0
                cachedDuration = (call("getDuration") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0
                cachedPlaying = (call("getPlaybackState") as? Number)?.toInt() == statePlaying
                if (cachedPosition > 0) startSignal?.complete("audio")
            }
            main.postDelayed(this, 250)
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        main.post(tick)
    }

    // ── Handler ──────────────────────────────────────────────────────

    override fun canHandle(track: TrackEntity): Boolean =
        enabled && hasPlaybackSdk && track.resolver == "applemusic" && !track.appleMusicId.isNullOrBlank()

    override suspend fun createMediaItem(track: TrackEntity) = null // External playback

    override suspend fun play(track: TrackEntity) {
        val id = track.appleMusicId ?: throw EmbedPlaybackRefusedException("Apple Music", null, "no id")
        refreshTokens()
        val signal = CompletableDeferred<String>()
        startSignal = signal
        playCalledAt = System.currentTimeMillis()
        currentId = id
        val prepared = withContext(Dispatchers.Main) {
            val c = createController() ?: return@withContext false
            runCatching {
                // A library id (i./l.) needs the library provider; the catalog
                // provider can't resolve it (forum 838633, FB25452094).
                val library = !id.all { it.isDigit() }
                val builderCls = cls(if (library) LIBRARY_BUILDER else CATALOG_BUILDER)!!
                var b: Any = builderCls.getConstructor().newInstance()
                b = builderCls.getMethod("items", Int::class.javaPrimitiveType, Array<String>::class.java)
                    .invoke(b, intConst(MEDIA_ITEM_TYPE, "SONG"), arrayOf(id)) ?: b
                val provider = builderCls.getMethod("build").invoke(b)
                c.javaClass.getMethod("prepare", cls(QUEUE_PROVIDER), Boolean::class.javaPrimitiveType)
                    .invoke(c, provider, true)
                applyVolume(c)
                Log.d(TAG, "prepare(${if (library) "library" else "catalog"} $id, play=true) for '${track.title}'")
                true
            }.onFailure { Log.e(TAG, "prepare failed", it); lastError = "prepare: ${it.rootMessage()}" }.getOrDefault(false)
        }
        if (!prepared) throw EmbedPlaybackRefusedException("Apple Music", null, lastError ?: "SDK unavailable")
        startTicking()
        val outcome = withTimeoutOrNull(START_TIMEOUT_MS) { signal.await() } ?: "timeout"
        val ms = System.currentTimeMillis() - playCalledAt
        // The number the spike compares against MusicKit JS (see AppleMusicPlaybackHandler).
        Log.i(TAG, "START_LATENCY engine=sdk id=$id outcome=$outcome ms=$ms title='${track.title}'")
        if (outcome != "audio" && currentId == id) {
            throw EmbedPlaybackRefusedException("Apple Music", null, "SDK $outcome")
        }
    }

    private fun applyVolume(c: Any) {
        val m = c.javaClass.methods.firstOrNull {
            it.name == "setVolume" && it.parameterTypes.singleOrNull() == Float::class.javaPrimitiveType
        }
        if (m == null) {
            Log.d(TAG, "no setVolume(float) on the controller; volume offset ($volumeFraction) not applied")
        } else {
            runCatching { m.invoke(c, volumeFraction) }
                .onSuccess { Log.d(TAG, "setVolume($volumeFraction)") }
                .onFailure { Log.w(TAG, "setVolume failed: ${it.rootMessage()}") }
        }
    }

    override suspend fun pause() = onMain { call("pause") }
    override suspend fun resume() = onMain { call("play") }
    override suspend fun seekTo(positionMs: Long) = onMain {
        controller?.let { c -> runCatching { c.javaClass.getMethod("seekToPosition", Long::class.javaPrimitiveType).invoke(c, positionMs) } }
    }
    override suspend fun stop() = onMain {
        call("stop")
        currentId = null
        startSignal?.complete("stopped")
    }

    override val isConnected: Boolean get() = controller != null
    override fun getPosition(): Long = cachedPosition
    override fun getDuration(): Long = cachedDuration
    override fun isPlaying(): Boolean = cachedPlaying

    private suspend fun onMain(block: () -> Unit) = suspendCoroutine { cont ->
        main.post { runCatching(block); cont.resume(Unit) }
    }

    private fun Throwable.rootMessage(): String {
        var t: Throwable = this
        while (t.cause != null && t.cause !== t) t = t.cause!!
        return "${t::class.simpleName}: ${t.message}"
    }
}
