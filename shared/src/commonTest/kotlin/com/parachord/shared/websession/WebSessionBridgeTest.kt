package com.parachord.shared.websession

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Desktop's `web-session-bridge.js` contract: payloads, validation, caps and reasons. */
class WebSessionBridgeTest {

    private fun TestScope.setup(): Pair<FakeWebSessionPlatform, WebSessionBridge> {
        val fake = FakeWebSessionPlatform(backgroundScope)
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond("body", HttpStatusCode.OK, headersOf("Set-Cookie" to listOf("s=1"), "X-A" to listOf("b"))) }
        })
        val host = WebSessionHost(WebSessionConfigs.AMAZON_MUSIC, fake, HttpClient(engine), backgroundScope, nowMs = { testScheduler.currentTime })
        return fake to WebSessionBridge(mapOf("amazon-music" to host))
    }

    private suspend fun WebSessionBridge.json(method: String, args: String, decide: (suspend (SignInTick) -> Boolean)? = null): JsonObject =
        Json.parseToJsonElement(call(method, args, decide)).jsonObject

    private fun JsonObject.reason() = this["reason"]?.jsonPrimitive?.content
    private fun JsonObject.ok() = this["success"]!!.jsonPrimitive.boolean

    @Test
    fun `an unknown session or an unavailable platform is not-ready`() = runTest {
        val (fake, bridge) = setup()
        assertEquals("not-ready", bridge.json("state", """{"id":"nope"}""").reason())
        fake.available = false
        val out = bridge.json("load", """{"id":"amazon-music","url":"https://music.amazon.com/"}""")
        assertFalse(out.ok())
        assertEquals("not-ready", out.reason())
    }

    @Test
    fun `URLs and capture patterns off the allowed hosts are invalid`() = runTest {
        val (fake, bridge) = setup()
        assertEquals("invalid", bridge.json("load", """{"id":"amazon-music","url":"https://www.amazon.com/"}""").reason())
        assertEquals("invalid", bridge.json("load", """{"id":"amazon-music","url":"https://music.amazon.com/","opts":{"capture":{"urlPrefixes":["https://www.amazon.com/*"]}}}""").reason())
        assertEquals("invalid", bridge.json("load", """{"id":"amazon-music","url":"https://music.amazon.com/","opts":{"capture":{"urlPrefixes":"https://gql.music.amazon.com/*"}}}""").reason())
        assertEquals("invalid", bridge.json("pageFetch", """{"id":"amazon-music","request":{"url":"https://www.amazon.com/gp"}}""").reason())
        assertEquals("invalid", bridge.json("fetch", """{"id":"amazon-music","request":{"url":"http://music.amazon.com/"}}""").reason())
        assertEquals("invalid", bridge.json("signIn", """{"id":"amazon-music","url":"https://www.amazon.com/ap/signin"}""").reason())
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun `a bad doneWhen or a non-boolean returnOnTimeout is invalid`() = runTest {
        val (fake, bridge) = setup()
        val base = """{"id":"amazon-music","url":"https://music.amazon.com/","opts":{"capture":{"urlPrefixes":["https://gql.music.amazon.com/*"],"""
        assertEquals("invalid", bridge.json("load", base + """"doneWhen":{}}}}""").reason())
        assertEquals("invalid", bridge.json("load", base + """"doneWhen":{"bodyIncludes":"x","typo":"y"}}}}""").reason())
        assertEquals("invalid", bridge.json("load", base + """"returnOnTimeout":"yes"}}}""").reason())
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun `budgets are capped at the bridge`() = runTest {
        val (fake, bridge) = setup()
        val out = bridge.json(
            "load",
            """{"id":"amazon-music","url":"https://music.amazon.com/","opts":{"timeoutMs":999999,"capture":{"urlPrefixes":["https://gql.music.amazon.com/*"],"budgetMs":999999,"quietMs":"slow"}}}""",
        )
        assertTrue(out.ok())
        assertEquals(listOf(60_000L), fake.loadTimeouts)
        // Zero captures: the window ran to the capped 30 s budget.
        val t = testScheduler.currentTime
        assertTrue(t in 30_100..30_400, "ended at $t")
    }

    @Test
    fun `a load answers its captures in the desktop shape`() = runTest {
        val (fake, bridge) = setup()
        fake.fireAt(150, gql("{\"q\":1}"))
        val out = bridge.json(
            "load",
            """{"id":"amazon-music","url":"https://music.amazon.com/","opts":{"capture":{"urlPrefixes":["https://gql.music.amazon.com/*"],"doneWhen":{"bodyIncludes":"q"}}}}""",
        )
        assertTrue(out.ok())
        assertEquals("https://music.amazon.com/", out["finalUrl"]!!.jsonPrimitive.content)
        val capture = out["captures"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("url", "method", "headers", "body", "at", "echo"), capture.keys)
        assertEquals("{\"q\":1}", capture["body"]!!.jsonPrimitive.content)
        assertFalse(capture["echo"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("x-amzn-csrf"), capture["headers"]!!.jsonObject.keys)
    }

    @Test
    fun `a load failure carries the host reason`() = runTest {
        val (fake, bridge) = setup()
        fake.loadFailure = IllegalStateException("boom")
        val out = bridge.json("load", """{"id":"amazon-music","url":"https://music.amazon.com/"}""")
        assertEquals("load-failed", out.reason())
    }

    @Test
    fun `pageFetch misses are success with an error key`() = runTest {
        val (fake, bridge) = setup()
        val miss = bridge.json("pageFetch", """{"id":"amazon-music","request":{"url":"https://music.amazon.com/api"}}""")
        assertTrue(miss.ok())
        assertEquals("no-view", miss["error"]!!.jsonPrimitive.content)
        fake.url = "https://music.amazon.com/"
        val hit = bridge.json("pageFetch", """{"id":"amazon-music","request":{"url":"https://music.amazon.com/api","method":"POST","body":{"a":1}}}""")
        assertEquals(200, hit["status"]!!.jsonPrimitive.int)
        assertEquals("ok", hit["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fetch returns headers with set-cookie names only`() = runTest {
        val (_, bridge) = setup()
        val out = bridge.json("fetch", """{"id":"amazon-music","request":{"url":"https://music.amazon.com/x","headers":{"Cookie":"no"}}}""")
        assertTrue(out.ok())
        assertEquals(200, out["status"]!!.jsonPrimitive.int)
        val headers = out["headers"]!!.jsonObject
        assertEquals(listOf("s"), (headers["set-cookie"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("b", headers["x-a"]!!.jsonPrimitive.content)
        assertEquals("body", out["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `state and clear answer the desktop shapes`() = runTest {
        val (fake, bridge) = setup()
        fake.jar["amazon.com"] = mutableMapOf("at-main" to "secret-value")
        val state = bridge.json("state", """{"id":"amazon-music"}""")
        assertEquals(setOf("success", "url", "signInUrl", "cookieNames", "generation"), state.keys)
        assertEquals(listOf("at-main"), state["cookieNames"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(state.toString().contains("secret-value"))
        assertEquals(JsonObject(mapOf("success" to kotlinx.serialization.json.JsonPrimitive(true))), bridge.json("clear", """{"id":"amazon-music"}"""))
        assertEquals(1, bridge.json("state", """{"id":"amazon-music"}""")["generation"]!!.jsonPrimitive.int)
        fake.clearResult = false
        assertEquals("clear-failed", bridge.json("clear", """{"id":"amazon-music"}""").reason())
    }

    @Test
    fun `signIn runs the platform decide and answers signedIn`() = runTest {
        val (_, bridge) = setup()
        val out = bridge.json("signIn", """{"id":"amazon-music","url":"https://music.amazon.com/","window":{"width":980,"height":760}}""") { it.stableTicks >= 1 }
        assertTrue(out.ok())
        assertTrue(out["signedIn"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a malformed payload is invalid and never throws`() = runTest {
        val (_, bridge) = setup()
        assertEquals("invalid", bridge.json("load", "not json").reason())
        assertEquals("invalid", bridge.json("frobnicate", """{"id":"amazon-music"}""").reason())
    }
}
