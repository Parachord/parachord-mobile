package com.parachord.shared.websession

import com.parachord.shared.api.useResponseTypeGuard
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSHTTPCookieAcceptPolicy

actual fun createWebSessionHttpClient(): HttpClient = HttpClient(Darwin) {
    followRedirects = false
    expectSuccess = false
    engine {
        // The response-type guard every Darwin client needs (#368), with the
        // session options set on its own session (configureSession is ignored
        // there). The session's jar is the web view's, attached by the host:
        // the URL session must neither add nor keep cookies of its own.
        useResponseTypeGuard {
            HTTPShouldSetCookies = false
            HTTPCookieStorage = null
            HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
        }
    }
}
