package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #368 / KTOR-9807: a non-HTTP response must fail the request, not abort the
 * app. A `file://` URL gets a plain `NSURLResponse` from URLSession, the same
 * kind of response Ktor's unchecked `as NSHTTPURLResponse` cast crashes on.
 * Without [useResponseTypeGuard] this test aborts the test process (SIGABRT).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class DarwinResponseGuardTest {

    private fun tempFileUrl(): String {
        val path = NSTemporaryDirectory() + "darwin-response-guard.txt"
        NSString.create(string = "not an HTTP response")
            .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        return "file://$path"
    }

    @Test
    fun aNonHttpResponseFailsTheRequestInsteadOfAborting() = runBlocking {
        val client = HttpClient(Darwin) { engine { useResponseTypeGuard() } }
        try {
            val body = client.get(tempFileUrl()).bodyAsText()
            fail("expected the request to fail, got: $body")
        } catch (e: Exception) {
            assertTrue(e !is AssertionError, "unexpected: $e")
        } finally {
            client.close()
        }
    }
}
