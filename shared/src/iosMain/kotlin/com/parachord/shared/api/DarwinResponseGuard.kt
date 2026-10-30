package com.parachord.shared.api

import com.parachord.shared.platform.Log
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLErrorBadServerResponse
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketTask
import platform.darwin.NSObject

private const val TAG = "DarwinResponseGuard"

/**
 * Run the Darwin engine on a session whose delegate first checks that each
 * response is an HTTP response, then hands every callback to Ktor's own
 * delegate (#368, KTOR-9807).
 *
 * Ktor's `DarwinTaskHandler` casts the task's response `as NSHTTPURLResponse`
 * in the `didReceiveData` and `didCompleteWithError` callbacks. When the
 * response is null or not HTTP, the cast throws on a CFNetwork thread where
 * nothing can catch it, and the app aborts (SIGABRT). TestFlight testers hit
 * it opening the app after it sat locked overnight. Unfixed upstream as of
 * Ktor 3.6.1; remove this once a Ktor release guards the cast.
 *
 * Replaces Ktor's own session, so it mirrors what Ktor's `createSession` sets:
 * the default configuration with no cookie storage, and its own delegate queue.
 * `configureSession` / `handleChallenge` are ignored with a preconfigured
 * session; `configureRequest` still applies.
 */
@OptIn(UnsafeNumber::class)
fun DarwinClientEngineConfig.useResponseTypeGuard() {
    val ktorDelegate = KtorNSURLSessionDelegate()
    val configuration = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
        setHTTPCookieStorage(null)
    }
    val session = NSURLSession.sessionWithConfiguration(
        configuration,
        ResponseTypeGuardDelegate(ktorDelegate),
        delegateQueue = NSOperationQueue(),
    )
    usePreconfiguredSession(session, ktorDelegate)
}

/**
 * Forwards every session callback to [ktor], except a non-HTTP response:
 * - **data arrives:** the task is cancelled instead, and Ktor then sees the
 *   cancellation as an error on completion;
 * - **the task completes without an error:** Ktor is told it failed with
 *   `NSURLErrorBadServerResponse`, so the request throws a normal network
 *   error instead of aborting the app.
 *
 * WebSocket tasks are passed through untouched: Ktor doesn't cast theirs.
 */
@OptIn(UnsafeNumber::class)
internal class ResponseTypeGuardDelegate(
    private val ktor: KtorNSURLSessionDelegate,
) : NSObject(), NSURLSessionDataDelegateProtocol, NSURLSessionWebSocketDelegateProtocol {

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        if (dataTask.response !is NSHTTPURLResponse) {
            Log.w(TAG, "Non-HTTP response for ${dataTask.originalRequest?.URL?.host}; cancelling the request")
            dataTask.cancel()
            return
        }
        ktor.URLSession(session, dataTask, didReceiveData)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val error = didCompleteWithError
            ?: if (task !is NSURLSessionWebSocketTask && task.response !is NSHTTPURLResponse) {
                Log.w(TAG, "Request to ${task.originalRequest?.URL?.host} finished without an HTTP response")
                NSError.errorWithDomain(NSURLErrorDomain, NSURLErrorBadServerResponse, null)
            } else {
                null
            }
        ktor.URLSession(session, task, error)
    }

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didOpenWithProtocol: String?,
    ) {
        ktor.URLSession(session, webSocketTask, didOpenWithProtocol)
    }

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didCloseWithCode: NSURLSessionWebSocketCloseCode,
        reason: NSData?,
    ) {
        ktor.URLSession(session, webSocketTask, didCloseWithCode, reason)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        ktor.URLSession(session, task, willPerformHTTPRedirection, newRequest, completionHandler)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) {
        ktor.URLSession(session, task, didReceiveChallenge, completionHandler)
    }
}
