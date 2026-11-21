package com.parachord.shared.websession

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A caller's data-only stop condition (`capture.doneWhen`, rule 5). */
data class DoneWhen(val bodyIncludes: String? = null, val urlIncludes: String? = null)

/**
 * The pure rules of the web-session host, ported from desktop's
 * `main-process/web-session.js` and `main-process/ipc/web-session-bridge.js`.
 * One predicate per judgment, shared by the host and the bridge so the two
 * layers can never drift on what "allowed" means.
 */
object WebSessionRules {

    // The bridge's ceilings on caller budgets (desktop's LOAD_TIMEOUT_CAP_MS etc.).
    const val LOAD_TIMEOUT_CAP_MS = 60_000L
    const val CAPTURE_BUDGET_CAP_MS = 30_000L
    const val CAPTURE_QUIET_CAP_MS = 10_000L

    private val DONE_WHEN_KEYS = listOf("bodyIncludes", "urlIncludes")
    const val DONE_WHEN_MAX_LEN = 512

    /** A parsed URL's scheme (lowercase) and host (lowercase, no port, no userinfo). */
    internal data class Origin(val scheme: String, val host: String)

    /**
     * Scheme + host of a URL string, read the way a browser reads a
     * special-scheme URL: leading/trailing C0 controls and spaces trimmed,
     * tabs and newlines removed, the authority ending at the first `/`, `\`,
     * `?` or `#`, userinfo (up to the last `@`) and the port dropped. Anything
     * else odd (an empty host, a space or control character in it) is not a URL.
     */
    internal fun origin(url: String?): Origin? {
        if (url == null) return null
        val cleaned = url.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
        val sep = cleaned.indexOf("://")
        if (sep <= 0) return null
        val scheme = cleaned.substring(0, sep)
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } || !scheme[0].isLetter()) return null
        val rest = cleaned.substring(sep + 3)
        val end = rest.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }
        var authority = if (end < 0) rest else rest.substring(0, end)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            authority.substring(0, close + 1)
        } else {
            authority.substringBefore(':')
        }
        if (host.isEmpty() || host.any { it <= ' ' || it == '\u007f' }) return null
        return Origin(scheme.lowercase(), host.lowercase())
    }

    /**
     * https on one of [hosts], EXACT host equality, case-insensitive (desktop's
     * `urlOnAllowedHost`). The cookie domains are wider than the service on
     * purpose, so they are never the test here.
     */
    fun urlOnAllowedHost(url: String?, hosts: List<String>): Boolean {
        val o = origin(url) ?: return false
        if (o.scheme != "https") return false
        return hosts.any { it.lowercase() == o.host }
    }

    /** The URL's host is one of [domains] or under one (suffix semantics; desktop's `urlUnderCookieDomains`). */
    fun urlUnderCookieDomains(url: String?, domains: List<String>): Boolean {
        val o = origin(url) ?: return false
        return domains.any { domain ->
            val d = domain.removePrefix(".").lowercase()
            o.host == d || o.host.endsWith(".$d")
        }
    }

    /**
     * Desktop's `captureDoneWhenProblem`: null when [doneWhen] is absent or
     * well-formed, else the refusal. Known keys only, non-empty strings of at
     * most 512 chars, at least one key.
     */
    fun doneWhenProblem(doneWhen: JsonElement?): String? {
        if (doneWhen == null || doneWhen is JsonNull) return null
        if (doneWhen !is JsonObject) {
            return "capture.doneWhen must be an object (a declarative stop condition: { bodyIncludes?, urlIncludes? })"
        }
        if (doneWhen.isEmpty()) return "capture.doneWhen needs at least one of ${DONE_WHEN_KEYS.joinToString(", ")}"
        for ((key, value) in doneWhen) {
            if (key !in DONE_WHEN_KEYS) return "capture.doneWhen has an unknown key ($key; known: ${DONE_WHEN_KEYS.joinToString(", ")})"
            val s = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (s.isNullOrEmpty()) return "capture.doneWhen.$key must be a non-empty string"
            if (s.length > DONE_WHEN_MAX_LEN) return "capture.doneWhen.$key must be at most $DONE_WHEN_MAX_LEN chars"
        }
        return null
    }

    /** A [doneWhen] that passed [doneWhenProblem], as data; null when absent. */
    fun parseDoneWhen(doneWhen: JsonElement?): DoneWhen? {
        if (doneWhen !is JsonObject) return null
        fun str(key: String) = (doneWhen[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return DoneWhen(bodyIncludes = str("bodyIncludes"), urlIncludes = str("urlIncludes"))
    }

    /** Desktop's `captureSatisfiesDoneWhen`: header-complete, never an echo, every present needle found. */
    fun satisfiesDoneWhen(doneWhen: DoneWhen?, capture: WebSessionCapture?): Boolean {
        if (doneWhen == null || capture == null || capture.headers == null) return false
        if (capture.echo) return false
        doneWhen.bodyIncludes?.let { needle -> if (capture.body?.contains(needle) != true) return false }
        doneWhen.urlIncludes?.let { needle -> if (!capture.url.contains(needle)) return false }
        return true
    }

    /**
     * Chromium's webRequest URL-pattern semantics as the capture filter uses
     * them: `*` matches any run of characters (empty included), everything
     * else literally, anchored at both ends.
     */
    fun matchesCapturePattern(url: String, pattern: String): Boolean {
        var u = 0
        var p = 0
        var starP = -1
        var starU = 0
        while (u < url.length) {
            if (p < pattern.length && pattern[p] != '*' && pattern[p] == url[u]) {
                u++; p++
            } else if (p < pattern.length && pattern[p] == '*') {
                starP = p++
                starU = u
            } else if (starP >= 0) {
                p = starP + 1
                u = ++starU
            } else {
                return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    /** The cookie NAMES in `Set-Cookie` lines (values never leave the host). */
    fun setCookieNames(lines: List<String>): List<String> =
        lines.map { it.substringBefore('=').substringBefore(';').trim() }.filter { it.isNotEmpty() }

    /**
     * A caller budget the bridge passes on: absent or non-numeric stays absent
     * (the host default applies), else min(value, cap), never below 0.
     */
    fun capBudget(value: JsonElement?, cap: Long): Long? {
        val p = value as? JsonPrimitive ?: return null
        if (p.isString) return null
        val d = p.content.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return minOf(d, cap.toDouble()).toLong().coerceAtLeast(0)
    }

    /**
     * A URL as the debug log may show it: the path only, never the host's
     * query, fragment or credentials (`https://music.amazon.com/my/purchases?x=1` → `/my/purchases`).
     */
    fun logPath(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return "/$path"
    }
}
