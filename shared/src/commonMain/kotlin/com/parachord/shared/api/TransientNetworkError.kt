package com.parachord.shared.api

/**
 * True when [cause] is a **transient** network failure — one where the request
 * never reached a server and retrying moments later is likely to succeed.
 *
 * This exists because of a mobile reality the desktop app never had to model:
 * the device changes networks underneath in-flight requests. When a phone hands
 * off (Wi-Fi ↔ LTE, a carrier IPsec/Wi-Fi-calling tunnel re-establishing), every
 * socket open at that instant is aborted AND DNS is briefly unresolvable.
 * Measured on a Pixel 9a (Sept 2026), a single 230ms window produced:
 *
 * ```
 * ia600101.us.archive.org  SocketException: Software caused connection abort
 * api.spotify.com          UnknownHostException: No address associated with hostname
 * www.wikidata.org         UnknownHostException: No address associated with hostname
 * ```
 *
 * — four unrelated hosts, different services, different credentials, failing
 * together with only 3–7 requests in flight. That lockstep is what made the
 * symptom look like a shared client bug or a burst/rate-limit problem; it is
 * neither. The hosts resolve perfectly a second later.
 *
 * **Scope: connection-establishment failures only.** Deliberately excluded:
 * - **HTTP status codes.** Retrying those is handled (or deliberately NOT
 *   handled) elsewhere — `RateLimitGate` owns 429s, and blindly re-poking a
 *   rate-limited Spotify account is what earns an abuse ban (#176/#177).
 * - **Read timeouts.** A request that reached the server and hung may have had
 *   a side effect; replaying it is not safe by default.
 */
expect fun isTransientNetworkError(cause: Throwable): Boolean
