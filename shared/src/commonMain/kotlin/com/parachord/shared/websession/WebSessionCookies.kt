package com.parachord.shared.websession

/**
 * One cookie as a web view's jar holds it (iOS's `WKHTTPCookieStore`).
 * [domain] keeps the jar's form: a leading dot is a domain cookie (sent to
 * the domain and its subdomains), none is host-only. [expiresAtMs] is null
 * for a session cookie. Values are never logged.
 */
data class WebCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = false,
    val expiresAtMs: Long? = null,
)

/**
 * The cookie rules for a jar the platform hands over whole (iOS's
 * `WKHTTPCookieStore` has no "cookies for this URL" call): which cookies a
 * request sends ([header]), the names `state()` reports ([namesUnder]) and
 * the ones `clear()` removes ([under]). Android's `CookieManager` answers
 * these itself.
 */
object WebSessionCookies {

    /** The `Cookie` header a request to [url] carries, or null when none applies. */
    fun header(cookies: List<WebCookie>, url: String, nowMs: Long): String? {
        val o = WebSessionRules.origin(url) ?: return null
        val path = requestPath(url)
        val sent = cookies
            .filter { live(it, nowMs) && domainMatches(it.domain, o.host) && pathMatches(it.path, path) && (!it.secure || o.scheme == "https") }
            .sortedByDescending { it.path.length }
        if (sent.isEmpty()) return null
        return sent.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Names of the non-empty cookies a request to each of [domains]' roots
     * (`https://<domain>/`) would send: what Android's `cookieNames` reads.
     * Sorted and distinct; values never leave.
     */
    fun namesUnder(cookies: List<WebCookie>, domains: List<String>, nowMs: Long): List<String> =
        domains.flatMap { d ->
            val host = d.removePrefix(".").lowercase()
            cookies.filter { live(it, nowMs) && it.value.isNotEmpty() && domainMatches(it.domain, host) && pathMatches(it.path, "/") }
                .map { it.name }
        }.distinct().sorted()

    /** The cookies whose domain is one of [domains] or under one: what `clear()` removes, and nothing else. */
    fun under(cookies: List<WebCookie>, domains: List<String>): List<WebCookie> {
        val ds = domains.map { it.removePrefix(".").lowercase() }.filter { it.isNotEmpty() }
        return cookies.filter { c ->
            val cd = c.domain.removePrefix(".").lowercase()
            cd.isNotEmpty() && ds.any { d -> cd == d || cd.endsWith(".$d") }
        }
    }

    private fun live(c: WebCookie, nowMs: Long) = c.expiresAtMs == null || c.expiresAtMs > nowMs

    /** RFC 6265 domain-match: host-only exact, a domain cookie also its subdomains. */
    internal fun domainMatches(cookieDomain: String, host: String): Boolean {
        val d = cookieDomain.lowercase()
        if (d.startsWith(".")) {
            val bare = d.removePrefix(".")
            return host == bare || host.endsWith(".$bare")
        }
        return host == d
    }

    /** RFC 6265 path-match. */
    internal fun pathMatches(cookiePath: String, requestPath: String): Boolean {
        val cp = cookiePath.ifEmpty { "/" }
        if (requestPath == cp) return true
        if (!requestPath.startsWith(cp)) return false
        return cp.endsWith("/") || requestPath[cp.length] == '/'
    }

    /** The URL's path ("/" when it has none), without its query or fragment. */
    internal fun requestPath(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return "/"
        val p = afterScheme.substring(slash).substringBefore('?').substringBefore('#')
        return p.ifEmpty { "/" }
    }
}
