package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.serialization.json.Json

actual fun createHttpClient(
    json: Json,
    appConfig: AppConfig,
    authProvider: AuthTokenProvider,
    tokenRefresher: OAuthTokenRefresher,
    lbTokenProvider: suspend () -> String?,
): HttpClient = HttpClient(Darwin) {
    installSharedPlugins(json, appConfig, authProvider, tokenRefresher, lbTokenProvider)
    engine {
        useResponseTypeGuard()
        configureRequest {
            setAllowsCellularAccess(true)
        }
    }
}
