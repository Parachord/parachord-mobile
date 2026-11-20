package com.parachord.shared.ai

/**
 * What Shuffleupagus says when the AI provider's request fails. A port of
 * desktop's `src/renderer/lib/ai-provider-errors.js` (`describeProviderError`),
 * in its order and with its words: the plug-in's own wording first, then a
 * connection failure, then quota ("quota" / "exceeded") BEFORE a plain 429,
 * then a bad key, a missing model, a content-policy block, and last the
 * provider's own message cut to 100 characters.
 *
 * The order matters for OpenAI: a key with no credit answers 429
 * `insufficient_quota` ("You exceeded your current quota…"), which is a
 * billing problem, not a wait. The old mapping read every "429" as "Rate limit
 * exceeded. Please wait a moment and try again.", so a key that could never
 * work looked like a busy service.
 *
 * Change desktop's file first; `AiProviderErrorsTest` holds its vectors.
 */
object AiProviderErrors {

    const val CONNECTION = "Can't connect to the AI service. Check your internet connection."
    const val QUOTA = "API quota exceeded. Check your plan limits or try again later."
    const val RATE_LIMIT = "Rate limit reached. Please wait a moment and try again."
    const val INVALID_KEY = "Invalid API key. Please check your settings."
    const val MODEL_NOT_FOUND = "AI model not found. Check your settings or try a different model."
    const val CONTENT_POLICY = "Request was blocked by content policy. Try rephrasing."

    /**
     * Desktop matches the browser's "Failed to fetch"; on the phones a
     * connection failure is a JVM or Darwin exception with its own words, so
     * those join desktop's markers for the same branch.
     */
    private val CONNECTION_MARKERS = listOf(
        // desktop
        "failed to fetch", "econnrefused", "network",
        // JVM / OkHttp
        "unable to resolve host", "failed to connect", "connect timed out", "no address associated",
        // Darwin
        "internet connection appears to be offline", "could not connect to the server",
    )

    /**
     * The message for a failed request whose error reads [message].
     * [pluginDescription] is the plug-in's own wording (desktop's
     * `describeError`), used as is when present.
     */
    fun describe(message: String?, pluginDescription: String? = null): String {
        if (!pluginDescription.isNullOrEmpty()) return pluginDescription
        val text = redactSecrets(message ?: "")
        val lower = text.lowercase()

        if (CONNECTION_MARKERS.any { it in lower }) return CONNECTION
        if ("quota" in lower || "exceeded" in lower) return QUOTA
        if ("429" in text || "rate limit" in lower) return RATE_LIMIT
        if ("401" in text || "unauthorized" in lower || "invalid api key" in lower) return INVALID_KEY
        if ("404" in text || "not found" in lower || "does not exist" in lower) return MODEL_NOT_FOUND
        if ("content policy" in lower || "safety" in lower) return CONTENT_POLICY

        val short = if (text.length > 100) text.substring(0, 100) + "…" else text
        return "Error: $short"
    }

    private val QUERY_SECRET = Regex("""([?&](?:key|api_key|apikey|access_token|token)=)[^&\s\]"']+""", RegexOption.IGNORE_CASE)
    private val BEARER = Regex("""(Bearer\s+)[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE)
    private val KEY_LITERAL = Regex("""\b(sk-(?:ant-)?)[A-Za-z0-9_\-]{8,}|\bAIza[A-Za-z0-9_\-]{20,}""")

    /**
     * [text] with credentials removed: a URL's `key=` (Gemini sends its key in
     * the query), `api_key=`, `token=`, a Bearer token, and OpenAI, Anthropic
     * and Google key literals. A timeout's message carries the request URL, so
     * this runs before anything is shown, saved or logged.
     */
    fun redactSecrets(text: String): String = text
        .replace(QUERY_SECRET) { it.groupValues[1] + "REDACTED" }
        .replace(BEARER) { it.groupValues[1] + "REDACTED" }
        .replace(KEY_LITERAL, "REDACTED")
}

/**
 * A provider's HTTP error, with what it said kept apart from the message the
 * chat reads, so the failure can be logged as provider, status and the
 * provider's own words. [message] keeps each provider's existing text.
 */
class AiProviderHttpException(
    val providerId: String,
    val status: Int,
    val providerMessage: String,
    message: String,
) : Exception(message)
