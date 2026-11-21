package com.parachord.shared.websession

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createWebSessionHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
}
