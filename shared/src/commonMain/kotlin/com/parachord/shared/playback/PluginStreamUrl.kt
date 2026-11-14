package com.parachord.shared.playback

/**
 * The URLs an `html5Stream` plugin's `getStreamUrl` returns, and what the host
 * may do with them (plan parachord-mobile#664).
 *
 * The returned URL carries the plugin's credentials in its query (a Subsonic
 * token+salt pair replays until the password changes), so it lives for one
 * play only — never persist it, and never log it unredacted ([redact]).
 */
object PluginStreamUrl {

    /**
     * Whether the host may play [url]. **HTTPS-only** for now: the app denies
     * cleartext app-wide (`network_security_config`, security H9/M12), and the
     * test server is HTTPS.
     *
     * This is the single choke point for the deferred plain-`http://` allowance
     * (plan PR parachord-mobile#664, decision 1): when home-network servers are
     * supported, this check grows "or http:// to the host in a plugin's saved
     * serverUrl" — the network security config and the clients change with it,
     * nothing else.
     */
    fun isPlayable(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

    /** Query parameters whose values are credentials; redacted by [redact]. */
    private val SECRET_PARAMS = setOf("t", "s", "p", "token", "jwt", "apikey", "api_key", "password")

    /**
     * [url] with every credential-bearing query value replaced with `***`, safe
     * for a log line. Unparseable URLs pass through as-is.
     */
    fun redact(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || queryStart == url.length - 1) return url
        val base = url.substring(0, queryStart)
        val query = url.substring(queryStart + 1)
        val fragmentStart = query.indexOf('#')
        val fragment = if (fragmentStart >= 0) query.substring(fragmentStart) else ""
        val params = if (fragmentStart >= 0) query.substring(0, fragmentStart) else query
        val rebuilt = params.split('&').joinToString("&") { param ->
            val eq = param.indexOf('=')
            if (eq < 0) return@joinToString param
            val key = param.substring(0, eq)
            if (key.lowercase() in SECRET_PARAMS) "$key=***" else param
        }
        return "$base?$rebuilt$fragment"
    }
}
