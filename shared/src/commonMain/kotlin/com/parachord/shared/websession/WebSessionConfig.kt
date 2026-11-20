package com.parachord.shared.websession

/**
 * One web session's host config: the port of what desktop passes
 * `createWebSessionHost` (`main-process/web-session.js`). The host config is
 * where a service's name lives; [WebSessionHost] and [WebSessionRules] never
 * name one.
 *
 * - [cookieDomains]: the domains the session's cookies live under. `state()`
 *   reports and `clear()` removes ONLY these: Android's `CookieManager` is one
 *   jar for the whole app (Apple Music's MusicKit page shares it), so a
 *   sign-out must be domain-scoped. The hidden page may also navigate freely
 *   under them (a service's identity hops).
 * - [allowedHosts]: the EXACT hosts the session's data-source primitives may
 *   reach (`fetch`/`pageFetch` URLs, every redirect hop, capture patterns,
 *   `load`/`signIn` URLs). Deliberately narrower than the cookie domains.
 * - [signInTitle]: the visible sign-in screen's title. Host config, never a
 *   caller choice (a caller-chosen title on a screen with no URL bar is a
 *   phishing surface).
 * - [userAgent]: the UA the hidden page, the sign-in screen and `fetch` use;
 *   null keeps the platform's default.
 */
data class WebSessionConfig(
    val id: String,
    val cookieDomains: List<String>,
    val allowedHosts: List<String>,
    val signInTitle: String,
    val userAgent: String? = null,
) {
    init {
        require(id.isNotBlank()) { "web-session: config.id is required" }
        require(cookieDomains.isNotEmpty() && cookieDomains.all { it.isNotBlank() }) {
            "web-session $id: cookieDomains is required (state() reports and clear() removes these)"
        }
        require(allowedHosts.isNotEmpty() && allowedHosts.all { it.isNotBlank() }) {
            "web-session $id: allowedHosts is required (the exact hosts fetch may reach)"
        }
    }
}

/** The host's timings: desktop's `DEFAULTS` in `web-session.js`. */
data class WebSessionTimings(
    val loadTimeoutMs: Long = 30_000,
    val warmMs: Long = 30_000,
    val pageFetchTimeoutMs: Long = 12_000,
    val captureQuietMs: Long = 1_000,
    val captureBudgetMs: Long = 8_000,
    val capturePollMs: Long = 250,
    val signInTickMs: Long = 1_000,
    /** How long a true sign-in verdict leaves the screen up before closing it. */
    val signInCloseDelayMs: Long = 600,
    /** How often a writer re-checks the readers/writer gate. */
    val gatePollMs: Long = 50,
)

object WebSessionConfigs {
    /**
     * A desktop Chrome UA (desktop's `chromeUserAgent()` on macOS): services
     * fingerprint the embedded-browser default, and some push their mobile app
     * at a mobile UA.
     */
    const val CHROME_DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Desktop's `main-process/amazon-web-session.js`, value for value. */
    val AMAZON_MUSIC = WebSessionConfig(
        id = "amazon-music",
        cookieDomains = listOf("amazon.com", "music.amazon.com", "www.amazon.com"),
        allowedHosts = listOf("music.amazon.com", "gql.music.amazon.com"),
        signInTitle = "Sign in to Amazon Music",
        userAgent = CHROME_DESKTOP_USER_AGENT,
    )
}
