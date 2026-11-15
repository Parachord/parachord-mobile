package com.parachord.shared.deeplink

/**
 * SSRF guard for protocol-fetched URLs (Phase 2 `play/playlist?url=`,
 * Phase 3 `play/radio?url=` / `play/radio?refill=`, etc.).
 *
 * Mirrors desktop's `isPublicHttpUrl` exactly. Rejects:
 *
 * - **Non-HTTPS schemes** — only `https://` is allowed. (XSPF / JSPF /
 *   JSON tracklists must be served over TLS; we do not fetch `http://`
 *   even when the host is otherwise public.)
 * - **Loopback / private / link-local IPv4** — `0.0.0.0/8`, `10/8`,
 *   `127/8`, `169.254/16` (link-local + AWS metadata `169.254.169.254`),
 *   `172.16/12`, `192.168/16`.
 * - **CGNAT** — `100.64.0.0/10` (covers Tailscale + ISP CGNAT).
 *   *(Missing in `XspfHashing.validateXspfUrl`; covered here.)*
 * - **Loopback / private / link-local IPv6** — `::1`, `fc00::/7`
 *   (unique-local), `fe80::/10` (link-local).
 * - **IPv4-mapped IPv6** — `::ffff:0:0/96`. An attacker can otherwise
 *   reach `127.0.0.1` via `[::ffff:127.0.0.1]`.
 *   *(Missing in `XspfHashing.validateXspfUrl`; covered here.)*
 * - **Localhost name forms** — `localhost`, `localhost.`, `*.local`,
 *   `*.local.` (mDNS).
 * - **Decimal-int / octal IPv4 forms** — `http://2130706433/`,
 *   `http://0177.0.0.1/`. We reject any host that isn't either a
 *   resolvable DNS name or a canonical dotted-quad / `[bracketed]` IPv6.
 *
 * **What this does NOT defend against** (matches desktop's caveat):
 * - **DNS rebinding.** A resolvable hostname that returns a public IP
 *   on lookup but a private IP on subsequent fetch (or post-redirect)
 *   slips through. The defense is socket-level post-resolution checking,
 *   which neither Ktor nor OkHttp expose portably across KMP. Document
 *   this; revisit if/when the threat becomes relevant.
 * - **3xx redirects to private addresses** on their own: a caller that
 *   follows redirects must re-check each `Location` here first. Fetch
 *   playlist URLs with [fetchPublicPlaylistText], which does that and caps
 *   the body (GHSA-gfvj-rh43-vmp9).
 *
 * @throws IllegalArgumentException with a user-readable message on
 *   any rejection. Caller is expected to convert this to a toast or
 *   silent fallback per command's UX policy.
 */
fun validatePublicHttpsUrl(url: String) {
    val parsed = parseSimpleUrl(url)
        ?: throw IllegalArgumentException("Invalid URL: $url")

    if (parsed.scheme != "https") {
        throw IllegalArgumentException("URL must use https:// (got ${parsed.scheme})")
    }
    val rawHost = parsed.host
    if (rawHost.isBlank()) {
        throw IllegalArgumentException("URL is missing a host")
    }

    // Strip optional trailing dot (`example.com.`) before classification —
    // it changes the textual host but is functionally identical for resolution.
    val host = rawHost.lowercase().trimEnd('.')
    if (isLocalNameForm(host)) {
        throw IllegalArgumentException("URL targets a local hostname: $rawHost")
    }
    if (isPrivateIpv4(host)) {
        throw IllegalArgumentException("URL targets a private / loopback IPv4 address: $rawHost")
    }
    if (isPrivateIpv6(rawHost)) {
        throw IllegalArgumentException("URL targets a private / loopback IPv6 address: $rawHost")
    }
}

// ── Internal helpers ──────────────────────────────────────────────────

/** Minimal `scheme://host[:port]/path` extractor. KMP-clean (no OkHttp). */
internal data class SimpleUrl(val scheme: String, val host: String)

internal fun parseSimpleUrl(url: String): SimpleUrl? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = url.substring(0, schemeEnd).lowercase()
    val rest = url.substring(schemeEnd + 3)
    if (rest.isEmpty()) return null

    // Authority ends at the first `/`, `?`, or `#`.
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    if (authority.isEmpty()) return null

    // Strip optional userinfo (`user:pass@host`).
    val hostStart = authority.indexOf('@').let { if (it < 0) 0 else it + 1 }
    val hostAndPort = authority.substring(hostStart)

    // IPv6 literal: `[::1]:port` — bracketed.
    val host = if (hostAndPort.startsWith("[")) {
        val close = hostAndPort.indexOf(']')
        if (close < 0) return null
        hostAndPort.substring(0, close + 1)  // keep brackets for downstream
    } else {
        hostAndPort.substringBefore(':')
    }
    if (host.isEmpty()) return null
    return SimpleUrl(scheme, host)
}

internal fun isLocalNameForm(host: String): Boolean {
    if (host == "localhost") return true
    if (host == "ip6-localhost" || host == "ip6-loopback") return true
    if (host.endsWith(".local")) return true
    return false
}

/**
 * True if [host] is a canonical dotted-quad IPv4 in a private / reserved
 * range. Returns true for any *malformed* dotted-quad to fail closed
 * (e.g. `999.999.999.999`, `0177.0.0.1`).
 *
 * Decimal-integer hosts (`http://2130706433/` → 127.0.0.1) and
 * non-canonical octal forms (`http://0177.0.0.1/`) are NOT canonical
 * IPv4 in this regex sense, but they're also not legal DNS names — most
 * DNS resolvers reject leading digits or non-letter chars in segments,
 * so they fail the implicit "must be resolvable" gate. We don't try to
 * canonicalize them here; we just refuse anything that looks like an
 * IP-but-not-quite. Specifically, a host of all-digits (`2130706433`)
 * is rejected as malformed quad.
 */
internal fun isPrivateIpv4(host: String): Boolean {
    // All-digits "host" — single-integer IPv4 form (e.g. 2130706433).
    if (host.all { it.isDigit() }) return true

    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = IntArray(4)
    for (i in 0..3) {
        val p = parts[i]
        // Reject leading-zero non-canonical forms (`0177` octal would map
        // to 127 if we honored it; we just reject leading-zero non-empty).
        if (p.isEmpty()) return true
        if (p.length > 1 && p[0] == '0') return true
        val n = p.toIntOrNull() ?: return true
        if (n < 0 || n > 255) return true
        octets[i] = n
    }
    val (a, b, _, _) = octets
    return a == 0 ||
        a == 10 ||
        a == 127 ||
        (a == 100 && b in 64..127) ||      // 100.64.0.0/10 — CGNAT
        (a == 169 && b == 254) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168)
}

/**
 * True if [host] is a private / loopback / link-local IPv6 (or
 * IPv4-mapped IPv6 covering an IPv4 private range).
 *
 * Accepts both bracketed (`[::1]`) and bare (`::1`) forms — RFC 3986
 * URI syntax requires brackets in URL authority position, but callers
 * sometimes pass unbracketed.
 */
internal fun isPrivateIpv6(host: String): Boolean {
    val stripped = host.removePrefix("[").removeSuffix("]").lowercase()
    if (!stripped.contains(':')) return false  // not IPv6 at all
    if (stripped == "::1") return true
    if (stripped.startsWith("fe80:") || stripped == "fe80::" || stripped.startsWith("fe8")) {
        // fe80::/10 — link-local. Match fe80, fe81–febf prefix.
        val firstWord = stripped.substringBefore(':')
        val n = firstWord.toIntOrNull(16) ?: return false
        if (n in 0xfe80..0xfebf) return true
    }
    if (stripped.startsWith("fc") || stripped.startsWith("fd")) {
        // fc00::/7 — unique-local.
        val firstWord = stripped.substringBefore(':')
        val n = firstWord.toIntOrNull(16) ?: return false
        if (n in 0xfc00..0xfdff) return true
    }
    // IPv4-mapped IPv6: ::ffff:a.b.c.d (recommended) or ::ffff:HHHH:HHHH.
    if (stripped.startsWith("::ffff:") || stripped.startsWith("0:0:0:0:0:ffff:")) {
        val v4Part = stripped.substringAfterLast(':').takeIf { it.contains('.') }
        if (v4Part != null && isPrivateIpv4(v4Part)) return true
        // Hex form: ::ffff:7f00:0001 → 127.0.0.1. Convert.
        val tail = stripped.removePrefix("::ffff:").removePrefix("0:0:0:0:0:ffff:")
        val words = tail.split(':')
        if (words.size == 2) {
            val hi = words[0].toIntOrNull(16) ?: return false
            val lo = words[1].toIntOrNull(16) ?: return false
            val a = (hi ushr 8) and 0xff
            val b = hi and 0xff
            val c = (lo ushr 8) and 0xff
            val d = lo and 0xff
            return isPrivateIpv4("$a.$b.$c.$d")
        }
    }
    return false
}
