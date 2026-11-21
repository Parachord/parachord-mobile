package com.parachord.shared.websession

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `webSession` host bridge a plugin reaches as `this.host.webSession`:
 * desktop's `main-process/ipc/web-session-bridge.js` over registered
 * [WebSessionHost]s, minus the IPC. [call] takes the method name and its
 * payload as JSON (desktop's invoke payloads: `{id, url, opts}`,
 * `{id, request}`, `{id}`, `{id, url, window}`) and answers desktop's result
 * JSON. It never throws: a refusal is `{success: false, reason, error}`, with
 * the host's reason verbatim (`load-failed`, `not-ready`, `invalid`,
 * `superseded`), and an unknown session id is `not-ready`.
 *
 * URL scoping (the protection; desktop's "honest scope" note applies — any
 * plugin can name any session): `fetch`/`pageFetch`/`load`/`signIn` URLs and
 * every capture pattern must be https on one of the session's allowed hosts;
 * caller budgets are capped (timeoutMs 60 s, capture budgetMs 30 s, quietMs
 * 10 s); `capture.doneWhen` and `capture.returnOnTimeout` are validated.
 */
class WebSessionBridge(private val sessions: Map<String, WebSessionHost>) {

    private val json = Json { ignoreUnknownKeys = true }

    /** One open sign-in per session; joined calls share it and each one's decide sees every tick. */
    private class SignInRun(val decides: MutableList<suspend (SignInTick) -> Boolean>, val result: CompletableDeferred<JsonObject>)
    private val signInRuns = mutableMapOf<String, SignInRun>()

    suspend fun call(
        method: String,
        argsJson: String,
        decide: (suspend (SignInTick) -> Boolean)? = null,
    ): String = try {
        dispatch(method, argsJson, decide).toString()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        fail("invalid", e.message ?: "webSession call failed").toString()
    }

    private suspend fun dispatch(method: String, argsJson: String, decide: (suspend (SignInTick) -> Boolean)?): JsonObject {
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return fail("invalid", "webSession $method needs an object payload")
        val id = args.string("id")
        val host = id?.let { sessions[it] } ?: return fail("not-ready", "no web session registered for $id")
        if (!host.available) return fail("not-ready", "web sessions aren't supported on this device")
        return withContext(host.dispatcher) {
            when (method) {
                "load" -> load(host, args)
                "pageFetch" -> pageFetch(host, args)
                "fetch" -> fetch(host, args)
                "state" -> state(host)
                "clear" -> clear(host)
                "signIn" -> signIn(id, host, args, decide)
                else -> fail("invalid", "unknown webSession method $method")
            }
        }
    }

    private fun urlRefusal(kind: String, host: WebSessionHost) =
        fail("invalid", "$kind URLs must be https on one of the session's allowed hosts (${host.config.allowedHosts.joinToString(", ")})")

    private suspend fun load(host: WebSessionHost, args: JsonObject): JsonObject {
        val url = args.string("url") ?: return fail("invalid", "load needs a url")
        if (!WebSessionRules.urlOnAllowedHost(url, host.config.allowedHosts)) return urlRefusal("load", host)
        val options = when (val sanitized = sanitizeLoadOptions(args["opts"], host.config.allowedHosts)) {
            is Sanitized.Invalid -> return fail("invalid", sanitized.message)
            is Sanitized.Ok -> sanitized.options
        }
        return try {
            val result = host.load(url, options)
            buildJsonObject {
                put("success", true)
                put("finalUrl", result.finalUrl)
                result.captures?.let { captures -> put("captures", JsonArray(captures.map { it.toJson() })) }
                if (result.loadTimedOut) put("loadTimedOut", true)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail((e as? WebSessionException)?.reason ?: "load-failed", e.message ?: "load failed")
        }
    }

    private suspend fun pageFetch(host: WebSessionHost, args: JsonObject): JsonObject {
        val request = args["request"] as? JsonObject ?: JsonObject(emptyMap())
        val url = request.string("url")
        if (!WebSessionRules.urlOnAllowedHost(url, host.config.allowedHosts)) return urlRefusal("pageFetch", host)
        val outcome = host.pageFetch(
            PageFetchRequest(
                url = url!!,
                method = request.string("method"),
                headers = request.headerMap(),
                body = request.bodyString(),
            ),
        )
        return buildJsonObject {
            put("success", true)
            if (outcome.error != null) {
                put("error", outcome.error)
            } else {
                put("status", outcome.status)
                put("text", outcome.text ?: "")
            }
        }
    }

    private suspend fun fetch(host: WebSessionHost, args: JsonObject): JsonObject {
        val request = args["request"] as? JsonObject ?: JsonObject(emptyMap())
        val url = request.string("url")
        if (!WebSessionRules.urlOnAllowedHost(url, host.config.allowedHosts)) return urlRefusal("fetch", host)
        return try {
            val response = host.fetch(
                FetchRequest(
                    url = url!!,
                    method = request.string("method"),
                    headers = request.headerMap(),
                    body = request.bodyString(),
                    timeoutMs = request.number("timeoutMs"),
                ),
            )
            buildJsonObject {
                put("success", true)
                put("status", response.status)
                putJsonObject("headers") {
                    for ((name, value) in response.headers) put(name, value)
                    if (response.setCookieNames.isNotEmpty()) {
                        putJsonArray("set-cookie") { response.setCookieNames.forEach { add(JsonPrimitive(it)) } }
                    }
                }
                put("text", response.text)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail((e as? WebSessionException)?.reason ?: "fetch-failed", e.message ?: "fetch failed")
        }
    }

    private suspend fun state(host: WebSessionHost): JsonObject {
        val s = host.state()
        return buildJsonObject {
            put("success", true)
            put("url", s.url)
            put("signInUrl", s.signInUrl)
            putJsonArray("cookieNames") { s.cookieNames.forEach { add(JsonPrimitive(it)) } }
            put("generation", s.generation)
        }
    }

    private suspend fun clear(host: WebSessionHost): JsonObject {
        val error = host.clear()
        return if (error == null) buildJsonObject { put("success", true) } else fail("clear-failed", error)
    }

    private suspend fun signIn(
        id: String,
        host: WebSessionHost,
        args: JsonObject,
        decide: (suspend (SignInTick) -> Boolean)?,
    ): JsonObject {
        val url = args.string("url") ?: return fail("invalid", "signIn needs a url")
        if (!WebSessionRules.urlOnAllowedHost(url, host.config.allowedHosts)) return urlRefusal("signIn", host)
        val ownDecide = decide ?: { false }
        // A second call joins the open run: one screen, one ticker. The joined
        // call's url is ignored; its decide sees every tick, and the verdict is
        // true when any joined decide says true.
        signInRuns[id]?.let { open ->
            open.decides += ownDecide
            return open.result.await()
        }
        val run = SignInRun(mutableListOf(ownDecide), CompletableDeferred())
        signInRuns[id] = run
        CoroutineScope(SupervisorJob() + host.dispatcher).launch {
            val out = try {
                val signedIn = host.signIn(url) { tick ->
                    var verdict = false
                    for (d in run.decides.toList()) {
                        val v = try { d(tick) } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            false
                        }
                        verdict = verdict || v
                    }
                    verdict
                }
                buildJsonObject {
                    put("success", true)
                    put("signedIn", signedIn)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                buildJsonObject {
                    put("success", true)
                    put("signedIn", false)
                    put("error", e.message ?: "sign-in failed")
                }
            } finally {
                if (signInRuns[id] === run) signInRuns.remove(id)
            }
            run.result.complete(out)
        }
        return run.result.await()
    }

    // ── payload helpers ─────────────────────────────────────────────────────

    private sealed class Sanitized {
        class Ok(val options: LoadOptions) : Sanitized()
        class Invalid(val message: String) : Sanitized()
    }

    /** Desktop's `sanitizeLoadOpts`: scope the capture patterns, cap the budgets, validate the data. */
    private fun sanitizeLoadOptions(opts: JsonElement?, allowedHosts: List<String>): Sanitized {
        val src = opts as? JsonObject ?: return Sanitized.Ok(LoadOptions())
        val timeoutMs = WebSessionRules.capBudget(src["timeoutMs"], WebSessionRules.LOAD_TIMEOUT_CAP_MS)
        val capture = src["capture"] as? JsonObject ?: return Sanitized.Ok(LoadOptions(timeoutMs = timeoutMs))
        val prefixes = (capture["urlPrefixes"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (prefixes == null || prefixes.any { it == null || !WebSessionRules.urlOnAllowedHost(it, allowedHosts) }) {
            return Sanitized.Invalid("capture.urlPrefixes must be https URL patterns on the session's allowed hosts (${allowedHosts.joinToString(", ")})")
        }
        val doneWhenElement = capture["doneWhen"]
        WebSessionRules.doneWhenProblem(doneWhenElement)?.let { return Sanitized.Invalid(it) }
        val returnOnTimeout = when (val r = capture["returnOnTimeout"]) {
            null, JsonNull -> false
            is JsonPrimitive -> if (!r.isString && (r.content == "true" || r.content == "false")) r.content == "true" else
                return Sanitized.Invalid("capture.returnOnTimeout must be a boolean (the opt-in to rule 5's budget escape)")
            else -> return Sanitized.Invalid("capture.returnOnTimeout must be a boolean (the opt-in to rule 5's budget escape)")
        }
        return Sanitized.Ok(
            LoadOptions(
                timeoutMs = timeoutMs,
                capture = CaptureOptions(
                    urlPrefixes = prefixes.filterNotNull(),
                    quietMs = WebSessionRules.capBudget(capture["quietMs"], WebSessionRules.CAPTURE_QUIET_CAP_MS),
                    budgetMs = WebSessionRules.capBudget(capture["budgetMs"], WebSessionRules.CAPTURE_BUDGET_CAP_MS),
                    doneWhen = WebSessionRules.parseDoneWhen(doneWhenElement),
                    returnOnTimeout = returnOnTimeout,
                ),
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.content.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }?.toLong()
    }

    /** Header values as strings (a number or boolean value is stringified, as `fetch` does). */
    private fun JsonObject.headerMap(): Map<String, String> {
        val h = this["headers"] as? JsonObject ?: return emptyMap()
        return h.mapNotNull { (name, value) ->
            val p = value as? JsonPrimitive ?: return@mapNotNull null
            if (p is JsonNull) null else name to p.content
        }.toMap()
    }

    /** A string body as given; an object body as its JSON (desktop's pageFetch/fetch rule). */
    private fun JsonObject.bodyString(): String? = when (val b = this["body"]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (b.isString) b.content else b.toString()
        else -> b.toString()
    }

    private fun WebSessionCapture.toJson(): JsonObject = buildJsonObject {
        put("url", url)
        put("method", method)
        if (headers != null) putJsonObject("headers") { for ((k, v) in headers) put(k, v) } else put("headers", JsonNull)
        put("body", body)
        put("at", at)
        put("echo", echo)
    }

    private fun fail(reason: String, error: String): JsonObject = buildJsonObject {
        put("success", false)
        put("reason", reason)
        put("error", error)
    }
}
