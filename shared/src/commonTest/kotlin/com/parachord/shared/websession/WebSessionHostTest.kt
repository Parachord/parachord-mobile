package com.parachord.shared.websession

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host's five concurrency rules, its URL/cookie scoping and sign-in, on a
 * scripted platform in virtual time (desktop's `tests/main-process/web-session.test.js`).
 */
class WebSessionHostTest {

    private val musicPage = "https://music.amazon.com/"
    private val gqlPattern = "https://gql.music.amazon.com/*"

    private fun TestScope.mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        val config = MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        }
        return HttpClient(MockEngine(config))
    }

    private fun TestScope.newHost(
        fake: FakeWebSessionPlatform,
        client: HttpClient = mockClient { respond("", HttpStatusCode.NotFound) },
    ) = WebSessionHost(WebSessionConfigs.AMAZON_MUSIC, fake, client, backgroundScope, nowMs = { testScheduler.currentTime })

    private fun TestScope.setup(): Pair<FakeWebSessionPlatform, WebSessionHost> {
        val fake = FakeWebSessionPlatform(backgroundScope)
        return fake to newHost(fake)
    }

    // ── rule 1 ──────────────────────────────────────────────────────────────

    @Test
    fun `rule 1 - loads serialize in arrival order`() = runTest {
        val (fake, host) = setup()
        val a = async { host.load("https://music.amazon.com/a") }
        val b = async { host.load("https://music.amazon.com/b") }
        val c = async { host.load("https://music.amazon.com/c") }
        a.await(); b.await(); c.await()
        assertEquals(1, fake.maxActiveLoads)
        assertEquals(
            listOf("a", "b", "c").flatMap { listOf("load-start https://music.amazon.com/$it", "load-end https://music.amazon.com/$it") },
            fake.events,
        )
    }

    // ── rule 2 ──────────────────────────────────────────────────────────────

    @Test
    fun `rule 2 - a pageFetch during a navigation is busy`() = runTest {
        val (fake, host) = setup()
        fake.url = musicPage
        fake.loadDurationMs = 1_000
        val load = async { host.load("https://music.amazon.com/a") }
        delay(10)
        val out = host.pageFetch(PageFetchRequest("https://music.amazon.com/api"))
        assertEquals("busy", out.error)
        load.await()
    }

    @Test
    fun `rule 2 - a navigation waits out an in-flight pageFetch`() = runTest {
        val (fake, host) = setup()
        fake.url = musicPage
        fake.pageFetchDelayMs = 500
        val fetch = async { host.pageFetch(PageFetchRequest("https://music.amazon.com/api")) }
        delay(10)
        val load = async { host.load("https://music.amazon.com/a") }
        assertEquals(200, fetch.await().status)
        load.await()
        assertEquals(listOf("pageFetch-start", "pageFetch-end", "load-start https://music.amazon.com/a"), fake.events.take(3))
    }

    // ── rule 3 ──────────────────────────────────────────────────────────────

    @Test
    fun `rule 3 - the page parks warmMs after the last activity and not while a pageFetch runs`() = runTest {
        val (fake, host) = setup()
        host.load(musicPage) // ends at 100: park due at 30 100
        delay(19_900) // t = 20 000
        fake.pageFetchDelayMs = 15_000
        host.pageFetch(PageFetchRequest("https://music.amazon.com/api")) // t = 35 000: park re-armed to 65 000
        delay(5_000)
        assertFalse("park" in fake.events)
        delay(26_000) // t = 66 000
        assertTrue("park" in fake.events)
        assertEquals("about:blank", fake.url)
        assertEquals("no-origin-page", host.pageFetch(PageFetchRequest("https://music.amazon.com/api")).error)
    }

    // ── rule 4 ──────────────────────────────────────────────────────────────

    @Test
    fun `rule 4 - captures seen during our own pageFetch are echoes`() = runTest {
        val (fake, host) = setup()
        fake.url = musicPage
        fake.fireAt(50, gql("page"))
        val load = async {
            host.load(musicPage, LoadOptions(capture = CaptureOptions(listOf(gqlPattern), quietMs = 2_000, budgetMs = 3_000)))
        }
        delay(300) // the navigation finished at 100; the quiet window runs
        fake.duringPageFetch = { fake.listener?.invoke(gql("replay")) }
        host.pageFetch(PageFetchRequest("https://gql.music.amazon.com/"))
        val captures = load.await().captures!!
        assertEquals(listOf("page" to false, "replay" to true), captures.map { it.body to it.echo })
        // Cookie headers never ride a capture.
        assertEquals(mapOf("x-amzn-csrf" to "t"), captures.first().headers)
    }

    @Test
    fun `captures keep only requests matching a pattern on an allowed host`() = runTest {
        val (fake, host) = setup()
        fake.fireAt(10, gql("one"))
        fake.fireAt(20, gql("other", url = "https://music.amazon.com/x"))
        fake.fireAt(30, gql("offhost", url = "https://www.amazon.com/gql"))
        val r = host.load(musicPage, LoadOptions(capture = CaptureOptions(listOf(gqlPattern))))
        assertEquals(listOf("one"), r.captures!!.map { it.body })
    }

    // ── rule 5 ──────────────────────────────────────────────────────────────

    @Test
    fun `rule 5 - the window ends quietMs after the last capture`() = runTest {
        val (fake, host) = setup()
        fake.fireAt(150, gql())
        val r = host.load(musicPage, LoadOptions(capture = CaptureOptions(listOf(gqlPattern))))
        assertEquals(1, r.captures!!.size)
        val t = testScheduler.currentTime
        assertTrue(t in 1_150..1_400, "ended at $t")
    }

    @Test
    fun `rule 5 - with zero captures the window runs to budgetMs`() = runTest {
        val (_, host) = setup()
        val r = host.load(musicPage, LoadOptions(capture = CaptureOptions(listOf(gqlPattern), budgetMs = 4_000)))
        assertEquals(emptyList(), r.captures)
        val t = testScheduler.currentTime
        assertTrue(t in 4_100..4_400, "ended at $t")
    }

    @Test
    fun `rule 5 - a doneWhen match ends the window before the navigation finishes`() = runTest {
        val (fake, host) = setup()
        fake.loadDurationMs = 10_000
        fake.fireAt(100, gql("{\"op\":\"other\"}"))
        fake.fireAt(200, gql("{\"searchText\":\"x\"}"))
        val r = host.load(
            musicPage,
            LoadOptions(capture = CaptureOptions(listOf(gqlPattern), doneWhen = DoneWhen(bodyIncludes = "searchText"))),
        )
        assertEquals(200L, testScheduler.currentTime)
        assertEquals(2, r.captures!!.size)
        // The navigation kept loading in the background.
        delay(10_000)
        assertTrue("load-end $musicPage" in fake.events)
    }

    @Test
    fun `rule 5 - an echo never satisfies doneWhen`() = runTest {
        val (fake, host) = setup()
        fake.url = musicPage
        val load = async {
            host.load(
                musicPage,
                LoadOptions(capture = CaptureOptions(listOf(gqlPattern), budgetMs = 2_000, doneWhen = DoneWhen(bodyIncludes = "searchText"))),
            )
        }
        delay(150)
        fake.duringPageFetch = { fake.listener?.invoke(gql("{\"searchText\":\"x\"}")) }
        host.pageFetch(PageFetchRequest("https://gql.music.amazon.com/"))
        load.await()
        // The window ran on (quiet after the echo) instead of stopping at it.
        assertTrue(testScheduler.currentTime >= 1_150)
    }

    @Test
    fun `rule 5 - returnOnTimeout returns the held captures on a navigation timeout`() = runTest {
        val (fake, host) = setup()
        fake.loadDurationMs = 5_000
        fake.fireAt(200, gql())
        val r = host.load(
            musicPage,
            LoadOptions(timeoutMs = 1_000, capture = CaptureOptions(listOf(gqlPattern), returnOnTimeout = true)),
        )
        assertTrue(r.loadTimedOut)
        assertEquals(1, r.captures!!.size)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `rule 5 - without the opt-in a navigation timeout is load-failed`() = runTest {
        val (fake, host) = setup()
        fake.loadDurationMs = 5_000
        fake.fireAt(200, gql())
        val e = assertFailsWith<WebSessionException> {
            host.load(musicPage, LoadOptions(timeoutMs = 1_000, capture = CaptureOptions(listOf(gqlPattern))))
        }
        assertEquals("load-failed", e.reason)
    }

    @Test
    fun `rule 5 - an opted-in timeout with nothing captured is still load-failed`() = runTest {
        val (fake, host) = setup()
        fake.loadDurationMs = 5_000
        val e = assertFailsWith<WebSessionException> {
            host.load(musicPage, LoadOptions(timeoutMs = 1_000, capture = CaptureOptions(listOf(gqlPattern), returnOnTimeout = true)))
        }
        assertEquals("load-failed", e.reason)
    }

    @Test
    fun `a failed navigation is load-failed`() = runTest {
        val (fake, host) = setup()
        fake.loadFailure = IllegalStateException("net::ERR_FAILED")
        assertEquals("load-failed", assertFailsWith<WebSessionException> { host.load(musicPage) }.reason)
    }

    @Test
    fun `a bad capture pattern is invalid before any state is touched`() = runTest {
        val (fake, host) = setup()
        val e = assertFailsWith<WebSessionException> {
            host.load(musicPage, LoadOptions(capture = CaptureOptions(listOf("https://www.amazon.com/*"))))
        }
        assertEquals("invalid", e.reason)
        assertTrue(fake.events.isEmpty())
    }

    // ── clear() ─────────────────────────────────────────────────────────────

    @Test
    fun `a load running across a clear is superseded and so is one queued behind it`() = runTest {
        val (fake, host) = setup()
        fake.loadDurationMs = 1_000
        val running = async { runCatching { host.load("https://music.amazon.com/a") } }
        val queued = async { runCatching { host.load("https://music.amazon.com/b") } }
        delay(500)
        assertNull(host.clear())
        assertEquals("superseded", (running.await().exceptionOrNull() as WebSessionException).reason)
        assertEquals("superseded", (queued.await().exceptionOrNull() as WebSessionException).reason)
        assertFalse("load-start https://music.amazon.com/b" in fake.events)
        // A load after the clear runs normally.
        assertEquals("https://music.amazon.com/c", host.load("https://music.amazon.com/c").finalUrl)
    }

    @Test
    fun `clear bumps the generation and clears only the declared cookie domains`() = runTest {
        val (fake, host) = setup()
        fake.jar["amazon.com"] = mutableMapOf("at-main" to "x")
        fake.jar["music.amazon.com"] = mutableMapOf("sess" to "y", "empty" to "")
        fake.jar["apple.com"] = mutableMapOf("media-user-token" to "z")
        val before = host.state()
        assertEquals(listOf("at-main", "sess"), before.cookieNames)
        assertEquals(0, before.generation)
        assertNull(host.clear())
        assertEquals(listOf(WebSessionConfigs.AMAZON_MUSIC.cookieDomains), fake.clearedDomains)
        assertEquals<Map<String, String>?>(mapOf("media-user-token" to "z"), fake.jar["apple.com"])
        val after = host.state()
        assertEquals(1, after.generation)
        assertEquals(emptyList(), after.cookieNames)
        assertTrue("destroy" in fake.events)
    }

    @Test
    fun `a refused cookie clear is reported`() = runTest {
        val (fake, host) = setup()
        fake.clearResult = false
        assertTrue(host.clear() != null)
    }

    // ── pageFetch scoping ───────────────────────────────────────────────────

    @Test
    fun `pageFetch runs only in a page on an allowed host - before and after`() = runTest {
        val (fake, host) = setup()
        assertEquals("no-view", host.pageFetch(PageFetchRequest("https://music.amazon.com/api")).error)
        fake.url = "https://www.amazon.com/ap/signin"
        assertEquals("page-off-host", host.pageFetch(PageFetchRequest("https://music.amazon.com/api")).error)
        assertFalse("pageFetch-start" in fake.events)
        fake.url = musicPage
        fake.duringPageFetch = { fake.url = "https://www.amazon.com/" }
        assertEquals("page-off-host", host.pageFetch(PageFetchRequest("https://music.amazon.com/api")).error)
        fake.url = musicPage
        fake.duringPageFetch = null
        assertEquals(PageFetchOutcome(status = 200, text = "ok"), host.pageFetch(PageFetchRequest("https://music.amazon.com/api")))
    }

    @Test
    fun `a pageFetch with no answer times out as an error`() = runTest {
        val (fake, host) = setup()
        fake.url = musicPage
        fake.pageFetchDelayMs = 60_000
        val out = host.pageFetch(PageFetchRequest("https://music.amazon.com/api"))
        assertTrue(out.error!!.contains("12000"))
        assertEquals(12_000L, testScheduler.currentTime)
    }

    // ── fetch ───────────────────────────────────────────────────────────────

    @Test
    fun `fetch attaches the jar and drops the caller cookie and returns set-cookie names`() = runTest {
        val fake = FakeWebSessionPlatform(backgroundScope)
        fake.jar["gql.music.amazon.com"] = mutableMapOf("sess" to "v")
        val seen = mutableListOf<HttpRequestData>()
        val host = newHost(fake, mockClient { request ->
            seen += request
            when (request.url.encodedPath) {
                "/start" -> respond("", HttpStatusCode.Found, headersOf("Location" to listOf("https://gql.music.amazon.com/next"), "Set-Cookie" to listOf("a=1; Path=/")))
                else -> respond("hello", HttpStatusCode.OK, headersOf("Set-Cookie" to listOf("b=2; Path=/", "c=3"), "X-Thing" to listOf("v")))
            }
        })
        val out = host.fetch(FetchRequest("https://music.amazon.com/start", headers = mapOf("Cookie" to "evil=1", "x-custom" to "k")))
        assertEquals(200, out.status)
        assertEquals("hello", out.text)
        assertEquals(listOf("b", "c"), out.setCookieNames)
        assertEquals("v", out.headers["x-thing"])
        assertFalse(out.headers.keys.any { it.equals("set-cookie", true) })
        assertEquals(2, seen.size)
        assertNull(seen[0].headers["Cookie"]) // the music jar was empty; the caller's cookie never rides
        assertEquals("k", seen[0].headers["x-custom"])
        assertEquals("sess=v", seen[1].headers["Cookie"])
        assertEquals("1", fake.jar["music.amazon.com"]!!["a"])
        assertEquals("2", fake.jar["gql.music.amazon.com"]!!["b"])
        assertEquals(WebSessionConfigs.CHROME_DESKTOP_USER_AGENT, seen[0].headers["User-Agent"])
    }

    @Test
    fun `fetch refuses a redirect hop off the allowed hosts`() = runTest {
        val fake = FakeWebSessionPlatform(backgroundScope)
        var calls = 0
        val host = newHost(fake, mockClient {
            calls += 1
            respond("", HttpStatusCode.Found, headersOf("Location", "https://www.amazon.com/ap/signin"))
        })
        val out = host.fetch(FetchRequest("https://music.amazon.com/start"))
        assertEquals(-1, out.status)
        assertTrue(out.text.contains("refused a redirect hop"))
        assertEquals(1, calls)
    }

    @Test
    fun `fetch follows a relative redirect and a POST 302 continues as GET`() = runTest {
        val fake = FakeWebSessionPlatform(backgroundScope)
        val seen = mutableListOf<HttpRequestData>()
        val host = newHost(fake, mockClient { request ->
            seen += request
            if (request.url.encodedPath == "/start") respond("", HttpStatusCode.Found, headersOf("Location", "/rel"))
            else respond("done", HttpStatusCode.OK)
        })
        val out = host.fetch(FetchRequest("https://music.amazon.com/start", method = "POST", body = "{}", headers = mapOf("Content-Type" to "application/json")))
        assertEquals("done", out.text)
        assertEquals("https://music.amazon.com/rel", seen[1].url.toString())
        assertEquals("POST", seen[0].method.value)
        assertEquals("GET", seen[1].method.value)
        // The GET after the redirect carries no body length either (#784).
        assertNull(seen[1].body.contentLength)
    }

    @Test
    fun `a GET declares no body length - Darwin would send Content-Length 0 and Amazon answers 400`() = runTest {
        val fake = FakeWebSessionPlatform(backgroundScope)
        val seen = mutableListOf<HttpRequestData>()
        val host = newHost(fake, mockClient { request ->
            seen += request
            respond("{}", HttpStatusCode.OK)
        })
        host.fetch(FetchRequest("https://music.amazon.com/config.json", headers = mapOf("Accept" to "application/json")))
        assertNull(seen.single().body.contentLength)
        assertNull(seen.single().headers["Content-Length"])
    }

    @Test
    fun `fetch refuses an off-list URL and times out as status -1`() = runTest {
        val fake = FakeWebSessionPlatform(backgroundScope)
        val host = newHost(fake, mockClient {
            delay(100_000)
            respond("late")
        })
        assertEquals("invalid", assertFailsWith<WebSessionException> { host.fetch(FetchRequest("https://www.amazon.com/")) }.reason)
        assertEquals("invalid", assertFailsWith<WebSessionException> { host.fetch(FetchRequest("http://music.amazon.com/")) }.reason)
        val out = host.fetch(FetchRequest("https://music.amazon.com/slow", timeoutMs = 5_000))
        assertEquals(-1, out.status)
        assertTrue(out.text.contains("exceeded"))
        assertEquals(5_000L, testScheduler.currentTime)
    }

    // ── signIn ──────────────────────────────────────────────────────────────

    @Test
    fun `signIn ticks with stableTicks and a true verdict closes the screen`() = runTest {
        val (fake, host) = setup()
        val ticks = mutableListOf<SignInTick>()
        fake.jar["amazon.com"] = mutableMapOf("at-main" to "x")
        val run = async {
            host.signIn("https://music.amazon.com/") { tick ->
                ticks += tick
                tick.url == musicPage && tick.stableTicks >= 2
            }
        }
        delay(10)
        val window = fake.signInWindow!!
        window.url = "https://www.amazon.com/ap/signin"
        delay(1_500)
        window.url = musicPage
        assertTrue(run.await())
        assertEquals(4_000L, testScheduler.currentTime)
        assertEquals(listOf(0, 0, 1, 2), ticks.map { it.stableTicks })
        assertEquals(listOf("at-main"), ticks.first().cookieNames)
        assertFalse(window.isClosed)
        delay(601)
        assertTrue(window.isClosed)
        assertTrue("signIn-open Sign in to Amazon Music" in fake.events)
    }

    @Test
    fun `the person closing the sign-in screen first is not signed in`() = runTest {
        val (fake, host) = setup()
        val run = async { host.signIn("https://music.amazon.com/") { false } }
        delay(2_500)
        fake.signInWindow!!.close()
        assertFalse(run.await())
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun `a throwing decide counts as false and the ticker goes on`() = runTest {
        val (_, host) = setup()
        var calls = 0
        val signedIn = host.signIn("https://music.amazon.com/") {
            calls += 1
            if (calls < 3) error("plugin bug")
            true
        }
        assertTrue(signedIn)
        assertEquals(3, calls)
    }

    @Test
    fun `a second signIn joins the open one`() = runTest {
        val (fake, host) = setup()
        val first = async { host.signIn("https://music.amazon.com/") { it.stableTicks >= 1 } }
        delay(10)
        val second = async { host.signIn("https://music.amazon.com/other") { false } }
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, fake.events.count { it.startsWith("signIn-open") })
    }

    @Test
    fun `clear closes an open sign-in screen as not signed in`() = runTest {
        val (_, host) = setup()
        val run = async { host.signIn("https://music.amazon.com/") { false } }
        delay(1_500)
        host.clear()
        assertFalse(run.await())
    }

    @Test
    fun `state reports the page and the sign-in screen`() = runTest {
        val (fake, host) = setup()
        host.load(musicPage)
        val run = async { host.signIn("https://music.amazon.com/") { false } }
        delay(10)
        fake.signInWindow!!.url = "https://www.amazon.com/ap/signin"
        val s = host.state()
        assertEquals(musicPage, s.url)
        assertEquals("https://www.amazon.com/ap/signin", s.signInUrl)
        fake.signInWindow!!.close()
        run.await()
        assertNull(host.state().signInUrl)
    }
}
