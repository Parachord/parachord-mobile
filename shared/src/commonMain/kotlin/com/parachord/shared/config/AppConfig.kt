package com.parachord.shared.config

/**
 * Platform-agnostic access to API keys and build configuration.
 *
 * On Android these come from BuildConfig (generated from local.properties).
 * On iOS these come from Info.plist or a similar configuration mechanism.
 *
 * Populated at startup via DI. Not expect/actual because BuildConfig
 * is generated in the :app module, which :shared can't reference.
 */
data class AppConfig(
    val achordionBearerToken: String = "",
    val lastFmApiKey: String = "",
    val lastFmSharedSecret: String = "",
    val spotifyClientId: String = "",
    val soundCloudClientId: String = "",
    val soundCloudClientSecret: String = "",
    val appleMusicDeveloperToken: String = "",
    val ticketmasterApiKey: String = "",
    val seatGeekClientId: String = "",
    /**
     * User-Agent header value for outbound HTTP requests.
     * Format: "Parachord/<version> (<platform>; <homepage>)"
     * Required by MusicBrainz (rejects default OkHttp UA with 403). See CLAUDE.md mistake #32.
     */
    val userAgent: String = "",
    /**
     * Platform identifier sent as the `X-Parachord-Client` header on Achordion
     * contribution requests (`POST /api/track-links/submit`) so the server can
     * split contribution metrics across clients. Achordion's allowlist is
     * `desktop | android | ios`; anything else (including blank) normalizes to
     * `"unknown"`. Set per-platform: "android" (AndroidModule), "ios"
     * (IosContainer). The `.axe` plugin path already sends the same value via
     * `window.__parachordClient`; this covers the NATIVE
     * [com.parachord.shared.api.AchordionClient] submit, which otherwise sent no
     * header → "unknown" (parachord-mobile#237).
     */
    val parachordClient: String = "",
    /**
     * True for debug builds; controls log verbosity in the HTTP transport stack
     * (e.g., Ktor Logging plugin LogLevel).
     */
    val isDebug: Boolean = false,
)
