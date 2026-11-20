package com.parachord.shared.api

/**
 * Credentials never reach a log line or an error message the app shows.
 *
 * [SECRET_HEADERS] are hidden by the shared Ktor client's logging and by
 * Android's OkHttp logging interceptor. [redactSecrets] removes credentials
 * from free text: a URL's credential query values (Gemini's `key=`, Last.fm's
 * `api_key=`, Subsonic's `t=` / `s=` token and salt, an OAuth redirect's
 * `code=` / `state=`), JSON token fields (a token endpoint's answer), Bearer tokens, and
 * OpenAI, Anthropic and Google key literals. Every logged line of both HTTP
 * clients goes through it, so a URL carrying a key is logged without it.
 */
object SecretRedaction {

    /** Request headers whose values are credentials (compared case-insensitively). */
    val SECRET_HEADERS: Set<String> = setOf(
        "Authorization",
        "x-api-key",
        "x-goog-api-key",
        "api-key",
        "Music-User-Token",
    )

    /**
     * Query parameters whose values are credentials. `code` and `state` are an
     * OAuth redirect's (the code is exchanged for a token; the state guards
     * it), `sk` a Last.fm session key.
     */
    val SECRET_QUERY_PARAMS: List<String> = listOf(
        "key", "api_key", "apikey", "access_token", "refresh_token", "token", "t", "s", "sk",
        "code", "state", "client_secret", "code_verifier",
    )

    /** JSON fields whose string values are credentials (a token endpoint's answer, a session). */
    val SECRET_JSON_FIELDS: List<String> = listOf(
        "access_token", "refresh_token", "id_token", "token", "key", "api_key", "session_key", "client_secret", "code",
    )

    fun isSecretHeader(name: String): Boolean = SECRET_HEADERS.any { it.equals(name, ignoreCase = true) }

    private val QUERY_SECRET = Regex(
        """([?&](?:${SECRET_QUERY_PARAMS.joinToString("|")})=)[^&\s\]"'#]+""",
        RegexOption.IGNORE_CASE,
    )
    private val JSON_SECRET = Regex(
        """("(?:${SECRET_JSON_FIELDS.joinToString("|")})"\s*:\s*")[^"]*""",
        RegexOption.IGNORE_CASE,
    )
    private val BEARER = Regex("""(Bearer\s+)[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE)
    private val KEY_LITERAL = Regex("""\b(sk-(?:ant-)?)[A-Za-z0-9_\-]{8,}|\bAIza[A-Za-z0-9_\-]{20,}""")

    /** [text] with credentials replaced by `REDACTED`. */
    fun redactSecrets(text: String): String = text
        .replace(QUERY_SECRET) { it.groupValues[1] + "REDACTED" }
        .replace(JSON_SECRET) { it.groupValues[1] + "REDACTED" }
        .replace(BEARER) { it.groupValues[1] + "REDACTED" }
        .replace(KEY_LITERAL, "REDACTED")
}
