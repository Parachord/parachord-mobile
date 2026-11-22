package com.parachord.shared.plugin

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One "Sign in to X?" question for the platform to show ([PluginSignInPrompt.pending]). */
data class SignInPromptRequest(val requestId: Long, val resolverId: String, val serviceName: String) {
    val title: String get() = SignInPromptCopy.title(serviceName)
    val message: String get() = SignInPromptCopy.message(serviceName)
}

/** The prompt's words, for both apps. */
object SignInPromptCopy {
    fun title(serviceName: String): String = "Sign in to $serviceName?"
    fun message(serviceName: String): String = "$serviceName needs you to sign in to play this track."
    const val CONFIRM = "Sign in"
    const val DECLINE = "Not now"
}

/** What a play refused as not signed in should do next ([PluginSignInPrompt.afterRefusal]). */
enum class SignInPromptResult {
    /** Play the track again on the same source, once. */
    RETRY,
    /** The person said no, or closed the sign-in: the refused-source rule as before. */
    DECLINED,
    /** No prompt (the plugin can't sign in, no screen, a recent "Not now"): the refused-source rule. */
    NOT_OFFERED,
}

/** [result], with the line to show (the sign-in's own words), if any. */
data class SignInPromptOutcome(val result: SignInPromptResult, val message: String? = null)

/**
 * Desktop's "this plug-in needs sign-in" prompt (parachord#1523,
 * `promptPluginSignIn` + `manageAmazonMusic`), for both apps: a play a plugin
 * refused with `not-signed-in` asks the person to sign in to THAT plugin, then
 * plays the track again. Routed by capability, never by id: only a plugin on
 * the status surface (`capabilities.status`) with a `signIn` method is asked
 * ([canPrompt]).
 *
 * - **Hiccup rule first.** When the plugin implements `signInPromptDecision`
 *   and its status says signed in, the plugin decides whether this is a
 *   session-refresh hiccup; if so the play retries quietly after
 *   [QUIET_RETRY_MS], with no prompt.
 * - **Only with a screen** ([screenShowing]): with the phone locked or in the
 *   car, nothing is asked.
 * - **A "Not now"** (or a sign-in closed without one) quiets the prompt for
 *   [DECLINE_QUIET_MS] for plays the queue starts by itself; a play the person
 *   asks for always asks.
 * - **One at a time.** A second refusal while a prompt is up waits for it.
 *
 * The platform draws [pending] as a dialog and calls [answer].
 */
class PluginSignInPrompt(
    /** The plugin has `capabilities.status` and implements `signIn`. */
    private val canPrompt: suspend (resolverId: String) -> Boolean,
    /** Re-reads the plugin's status; true when it says signed in, null when unknown. */
    private val statusSignedIn: suspend (resolverId: String) -> Boolean?,
    /** The plugin's `signInPromptDecision` reply JSON, null when it has none. May throw. */
    private val promptDecision: suspend (resolverId: String, inputsJson: String) -> String?,
    /** Runs the plugin's sign-in (it waits for the person). */
    private val signIn: suspend (resolverId: String, serviceName: String) -> PluginStatusStore.SessionResult,
    /** A Parachord screen is showing, so a question can be asked. */
    private val screenShowing: () -> Boolean,
    private val nowMs: () -> Long = { currentTimeMillis() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val _pending = MutableStateFlow<SignInPromptRequest?>(null)
    /** The question up now, for the platform's dialog. */
    val pending: StateFlow<SignInPromptRequest?> = _pending.asStateFlow()

    private val lock = Mutex()
    private var nextRequestId = 1L
    private var answerFor: Pair<Long, CompletableDeferred<Boolean>>? = null
    private val inFlight = mutableMapOf<String, CompletableDeferred<SignInPromptOutcome>>()
    private val declinedUntil = mutableMapOf<String, Long>()
    private val streaks = mutableMapOf<String, Pair<Long, Long>>()

    /** The person's answer to [requestId]; a stale id is ignored. */
    fun answer(requestId: Long, signIn: Boolean) {
        val current = answerFor ?: return
        if (current.first != requestId) return
        answerFor = null
        _pending.value = null
        current.second.complete(signIn)
    }

    /**
     * Whether [afterRefusal] would ask the person now: the plugin can sign in,
     * a screen shows, and (for a play the queue started) no recent "Not now".
     * The platform checks this before it stops on the track to ask.
     */
    suspend fun wouldAsk(resolverId: String, personAsked: Boolean): Boolean =
        canPrompt(resolverId) && screenShowing() &&
            (personAsked || nowMs() >= (declinedUntil[resolverId] ?: 0L))

    /**
     * A play from [resolverId] was refused as not signed in. [personAsked]:
     * the person asked for this play (a tap, Play), not the queue moving on.
     */
    suspend fun afterRefusal(resolverId: String, serviceName: String, personAsked: Boolean): SignInPromptOutcome {
        val (existing, mine) = lock.withLock {
            inFlight[resolverId]?.let { return@withLock it to null }
            val d = CompletableDeferred<SignInPromptOutcome>()
            inFlight[resolverId] = d
            null to d
        }
        if (existing != null) return existing.await()
        val deferred = mine!!
        val outcome = try {
            decide(resolverId, serviceName, personAsked)
        } catch (e: Exception) {
            if (e is CancellationException) {
                deferred.complete(SignInPromptOutcome(SignInPromptResult.NOT_OFFERED))
                lock.withLock { inFlight.remove(resolverId) }
                throw e
            }
            Log.w(TAG, "Sign-in prompt for $resolverId failed: ${e.message?.take(120)}")
            SignInPromptOutcome(SignInPromptResult.NOT_OFFERED)
        }
        deferred.complete(outcome)
        lock.withLock { inFlight.remove(resolverId) }
        return outcome
    }

    private suspend fun decide(resolverId: String, serviceName: String, personAsked: Boolean): SignInPromptOutcome {
        if (!canPrompt(resolverId)) return SignInPromptOutcome(SignInPromptResult.NOT_OFFERED)
        // The hiccup rule (the plugin's): live session cookies, so retry quietly.
        val signedIn = statusSignedIn(resolverId)
        val verdict = askDecision(resolverId, signedIn == true)
        if (verdict == false) {
            Log.d(TAG, "$resolverId: not signed in, but its session looks live; retrying quietly")
            pause(QUIET_RETRY_MS)
            return SignInPromptOutcome(SignInPromptResult.RETRY)
        }
        if (!screenShowing()) return SignInPromptOutcome(SignInPromptResult.NOT_OFFERED)
        if (!personAsked && nowMs() < (declinedUntil[resolverId] ?: 0L)) {
            return SignInPromptOutcome(SignInPromptResult.NOT_OFFERED)
        }
        val yes = ask(resolverId, serviceName)
        if (!yes) {
            declinedUntil[resolverId] = nowMs() + DECLINE_QUIET_MS
            return SignInPromptOutcome(SignInPromptResult.DECLINED)
        }
        val result = signIn(resolverId, serviceName)
        if (result.success) {
            streaks.remove(resolverId)
            declinedUntil.remove(resolverId)
            return SignInPromptOutcome(SignInPromptResult.RETRY, result.message)
        }
        declinedUntil[resolverId] = nowMs() + DECLINE_QUIET_MS
        return SignInPromptOutcome(SignInPromptResult.DECLINED, result.message)
    }

    /** The plugin's verdict: false = don't prompt (a hiccup), true = prompt, null = no rule. */
    private suspend fun askDecision(resolverId: String, cookiesSignedIn: Boolean): Boolean? {
        val (firstAt, lastAt) = streaks[resolverId] ?: (0L to 0L)
        val inputs = buildJsonObject {
            put("reason", REASON)
            put("cookiesSignedIn", cookiesSignedIn)
            putJsonObject("streak") { put("firstAt", firstAt); put("lastAt", lastAt) }
            put("now", nowMs())
        }
        val raw = try {
            promptDecision(resolverId, inputs.toString())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "signInPromptDecision failed for $resolverId: ${e.message?.take(120)}")
            null
        } ?: return null
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val prompt = (obj["prompt"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
        val streak = obj["streak"] as? JsonObject
        val f = (streak?.get("firstAt") as? JsonPrimitive)?.longOrNull ?: 0L
        val l = (streak?.get("lastAt") as? JsonPrimitive)?.longOrNull ?: 0L
        streaks[resolverId] = f to l
        return prompt
    }

    private suspend fun ask(resolverId: String, serviceName: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val request = SignInPromptRequest(nextRequestId++, resolverId, serviceName)
        answerFor?.second?.complete(false)
        answerFor = request.requestId to deferred
        _pending.value = request
        return try {
            deferred.await()
        } finally {
            if (answerFor?.first == request.requestId) {
                answerFor = null
                _pending.value = null
            }
        }
    }

    companion object {
        private const val TAG = "PluginSignInPrompt"
        private val json = Json { ignoreUnknownKeys = true }
        private const val REASON = "not-signed-in"
        /** Desktop's quiet retry after a hiccup. */
        const val QUIET_RETRY_MS = 2_000L
        /** Desktop's quiet period after a "Not now" or a closed sign-in. */
        const val DECLINE_QUIET_MS = 10L * 60 * 1000

        /** The prompt over [pluginManager] and [statusStore], as both apps wire it. */
        fun create(
            pluginManager: PluginManager,
            statusStore: PluginStatusStore,
            screenShowing: () -> Boolean,
        ): PluginSignInPrompt = PluginSignInPrompt(
            canPrompt = { id ->
                pluginManager.ensureInitialized()
                pluginManager.plugins.value.firstOrNull { it.id == id }
                    ?.let { it.capabilities["status"] == true && it.hasSignIn } == true
            },
            statusSignedIn = { id ->
                statusStore.refresh(id)
                statusStore.statuses.value[id]?.takeIf { !it.readFailed }?.signedIn
            },
            promptDecision = { id, inputs ->
                val info = pluginManager.plugins.value.firstOrNull { it.id == id }
                if (info?.hasSignInPromptDecision == true) pluginManager.signInPromptDecision(id, inputs) else null
            },
            signIn = { id, name -> statusStore.signIn(id, name) },
            screenShowing = screenShowing,
        )
    }
}
