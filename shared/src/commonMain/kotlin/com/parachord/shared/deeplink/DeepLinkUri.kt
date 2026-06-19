package com.parachord.shared.deeplink

/**
 * Platform-neutral view of a parsed URL, so the `parachord://` parsing logic
 * can live in commonMain. Android wraps `android.net.Uri`; iOS builds a
 * [SimpleDeepLinkUri] from `URLComponents` (which handles percent-decoding
 * reliably for the custom scheme).
 *
 * Mirrors the subset of `android.net.Uri` that [DeepLinkParser] needs:
 * `scheme` / `host` / `pathSegments` (decoded, non-empty segments) /
 * `queryParam(name)` (decoded, first value).
 */
interface DeepLinkUri {
    val scheme: String?
    val host: String?
    val pathSegments: List<String>
    fun queryParam(name: String): String?
}

/**
 * A plain [DeepLinkUri] built from already-decoded components — used by the iOS
 * side, which extracts `scheme` / `host` / path / query via `URLComponents`
 * and hands them to [DeepLinkParser] through the container.
 */
data class SimpleDeepLinkUri(
    override val scheme: String?,
    override val host: String?,
    override val pathSegments: List<String>,
    private val params: Map<String, String>,
) : DeepLinkUri {
    override fun queryParam(name: String): String? = params[name]
}
