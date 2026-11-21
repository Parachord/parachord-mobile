package com.parachord.shared.websession

import io.ktor.client.HttpClient

/**
 * The client a [WebSessionHost]'s `fetch` runs on: a plain one, not the app's
 * shared client. The shared client logs headers in debug builds (this one
 * carries the session's cookies) and attaches other services' auth. Redirects
 * are off: the host follows each hop itself, after checking it.
 */
expect fun createWebSessionHttpClient(): HttpClient
