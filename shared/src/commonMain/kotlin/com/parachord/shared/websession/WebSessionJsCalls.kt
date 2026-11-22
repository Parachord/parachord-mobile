package com.parachord.shared.websession

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The plugin runtime's side of `this.host.webSession`, for both apps: what
 * `NativeBridge.webSessionCall` / `webSessionDecide` do (Android's
 * `@JavascriptInterface`, iOS's JavaScriptCore callables).
 *
 * The JS half is the `web-session-bridge` block, the same text in Android's
 * `bootstrap.html` and iOS's `IosJsRuntime`: `__wsCall` sends each call here
 * with a callback id; the answer goes back to `window.__wsCallbacks[id]`. For
 * signIn, each tick goes to `window.__pcWsSignInTick(id, tick)`, which runs
 * the plugin's `decide()` in JS and answers through [decide].
 *
 * [evaluate] runs a script in the plugin runtime (on its thread). No value
 * (cookie, header, body) is logged here.
 */
class WebSessionJsCalls(
    private val bridge: WebSessionBridge?,
    private val scope: CoroutineScope,
    private val evaluate: suspend (String) -> Unit,
    private val decideTimeoutMs: Long = SIGN_IN_DECIDE_TIMEOUT_MS,
) {
    private val mutex = Mutex()
    private val pendingDecides = mutableMapOf<String, CompletableDeferred<Boolean>>()

    /** One `this.host.webSession` call; false when [callbackId] isn't one the bootstrap mints. */
    fun call(callbackId: String, method: String, argsJson: String): Boolean {
        if (!CALLBACK_ID.matches(callbackId)) return false
        scope.launch {
            val result = if (bridge == null) {
                failure("not-ready", "web sessions aren't available")
            } else {
                try {
                    bridge.call(method, argsJson, decide = if (method == "signIn") signInDecide(callbackId) else null)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failure("invalid", e.message ?: "webSession call failed")
                }
            }
            evaluate("window.__wsCallbacks && window.__wsCallbacks['$callbackId'] && window.__wsCallbacks['$callbackId']($result)")
        }
        return true
    }

    /** The plugin's `decide()` verdict for a sign-in tick. */
    fun decide(callbackId: String, verdict: Boolean) {
        scope.launch {
            mutex.withLock { pendingDecides.remove(callbackId) }?.complete(verdict)
        }
    }

    /** The host's decide for one signIn call: ask the plugin, false if it doesn't answer in time. */
    private fun signInDecide(callbackId: String): suspend (SignInTick) -> Boolean = { tick ->
        val answer = CompletableDeferred<Boolean>()
        mutex.withLock { pendingDecides.put(callbackId, answer) }?.complete(false)
        val tickJson = buildJsonObject {
            put("url", JsonPrimitive(tick.url))
            put("cookieNames", JsonArray(tick.cookieNames.map { JsonPrimitive(it) }))
            put("stableTicks", JsonPrimitive(tick.stableTicks))
        }
        try {
            evaluate("window.__pcWsSignInTick && window.__pcWsSignInTick('$callbackId', $tickJson)")
            withTimeoutOrNull(decideTimeoutMs) { answer.await() } ?: false
        } finally {
            mutex.withLock { if (pendingDecides[callbackId] === answer) pendingDecides.remove(callbackId) }
        }
    }

    private fun failure(reason: String, error: String): String = buildJsonObject {
        put("success", JsonPrimitive(false))
        put("reason", JsonPrimitive(reason))
        put("error", JsonPrimitive(error))
    }.toString()

    companion object {
        /** The ids the bootstrap mints (`ws_<n>`); nothing else goes into a script. */
        val CALLBACK_ID = Regex("^[A-Za-z0-9_]{1,64}$")
        const val SIGN_IN_DECIDE_TIMEOUT_MS = 2_000L
    }
}
