package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** API keys never reach a log line of the shared HTTP client. */
class SecretRedactionTest {

    private val stubProvider = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val stubRefresher = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private suspend fun logOf(debug: Boolean): List<String> {
        val lines = mutableListOf<String>()
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }) {
            installSharedPlugins(
                Json, AppConfig(userAgent = "Parachord/test", isDebug = debug), stubProvider, stubRefresher,
                logSink = { lines += it },
            )
        }
        client.get("https://example.test/v1/models?key=QUERYSECRET1&api_key=QUERYSECRET2&alt=json") {
            header("Authorization", "Bearer HEADERSECRET0")
            header("x-api-key", "HEADERSECRET1")
            header("x-goog-api-key", "HEADERSECRET2")
            header("api-key", "HEADERSECRET3")
            header("Music-User-Token", "HEADERSECRET4")
        }
        return lines
    }

    @Test
    fun `the shared client hides secret headers and query keys`() = runTest {
        val log = logOf(debug = true).joinToString("\n")
        assertTrue(log.isNotEmpty(), "debug builds log requests")
        assertTrue("x-goog-api-key" in log.lowercase(), log)
        assertFalse("SECRET" in log, log)
        assertTrue("alt=json" in log, log)
    }

    @Test
    fun `release builds log nothing`() = runTest {
        assertEquals(emptyList(), logOf(debug = false))
    }

    @Test
    fun `query credentials are redacted`() {
        assertEquals(
            "GET https://x.test/rest/stream?u=me&t=REDACTED&s=REDACTED&id=7",
            SecretRedaction.redactSecrets("GET https://x.test/rest/stream?u=me&t=abc123&s=salt9&id=7"),
        )
        assertEquals(
            "https://ws.audioscrobbler.com/2.0/?method=x&api_key=REDACTED&format=json",
            SecretRedaction.redactSecrets("https://ws.audioscrobbler.com/2.0/?method=x&api_key=0123abcd&format=json"),
        )
        assertEquals(
            "--> GET https://g.test/m?key=REDACTED http/1.1",
            SecretRedaction.redactSecrets("--> GET https://g.test/m?key=AIzaSyA1234567890abcdefghijk http/1.1"),
        )
        assertEquals("?apikey=REDACTED&access_token=REDACTED&token=REDACTED", SecretRedaction.redactSecrets("?apikey=a&access_token=b&token=c"))
        // Parameters that only end in a secret name are kept.
        assertEquals("?artist=Cher&format=json", SecretRedaction.redactSecrets("?artist=Cher&format=json"))
    }

    @Test
    fun `secret headers match case-insensitively`() {
        listOf("authorization", "X-Api-Key", "X-GOOG-API-KEY", "Api-Key", "music-user-token").forEach {
            assertTrue(SecretRedaction.isSecretHeader(it), it)
        }
        assertFalse(SecretRedaction.isSecretHeader("Content-Type"))
    }
}
