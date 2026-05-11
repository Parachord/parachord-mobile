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
     * True for debug builds; controls log verbosity in the HTTP transport stack
     * (e.g., Ktor Logging plugin LogLevel).
     */
    val isDebug: Boolean = false,
)
