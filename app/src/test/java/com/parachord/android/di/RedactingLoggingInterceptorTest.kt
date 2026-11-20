package com.parachord.android.di

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `.axe` fetches go through this client; their URLs and headers carry keys. */
class RedactingLoggingInterceptorTest {

    private fun logOf(debug: Boolean): List<String> {
        val lines = mutableListOf<String>()
        val answer = Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("ok".toResponseBody()).build()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(redactingLoggingInterceptor(debug, HttpLoggingInterceptor.Logger { lines += it }))
            .addInterceptor(answer)
            .build()
        client.newCall(
            Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=QUERYSECRET1&api_key=QUERYSECRET2&t=QUERYSECRET3&s=QUERYSECRET4&alt=json")
                .header("x-goog-api-key", "HEADERSECRET")
                .build(),
        ).execute().close()
        return lines
    }

    @Test
    fun `debug logs the request without its keys`() {
        val log = logOf(debug = true).joinToString("\n")
        assertTrue(log, "generativelanguage.googleapis.com" in log)
        assertTrue(log, "alt=json" in log)
        assertFalse(log, "SECRET" in log)
    }

    @Test
    fun `release logs nothing`() {
        assertEquals(emptyList<String>(), logOf(debug = false))
    }
}
