package com.parachord.shared.websession

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSHTTPCookieAcceptPolicy

actual fun createWebSessionHttpClient(): HttpClient = HttpClient(Darwin) {
    followRedirects = false
    expectSuccess = false
    engine {
        // The session's jar is the web view's, attached by the host: the
        // URL session must neither add nor keep cookies of its own.
        configureSession {
            HTTPShouldSetCookies = false
            HTTPCookieStorage = null
            HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
        }
    }
}
