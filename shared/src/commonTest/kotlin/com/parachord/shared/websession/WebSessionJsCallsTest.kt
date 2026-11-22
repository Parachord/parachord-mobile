package com.parachord.shared.websession

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The NativeBridge side of `this.host.webSession`, shared by both apps. */
class WebSessionJsCallsTest {

    private fun TestScope.bridge(): Pair<FakeWebSessionPlatform, WebSessionBridge> {
        val fake = FakeWebSessionPlatform(backgroundScope)
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond("body", HttpStatusCode.OK) }
        })
        val host = WebSessionHost(WebSessionConfigs.AMAZON_MUSIC, fake, HttpClient(engine), backgroundScope, nowMs = { testScheduler.currentTime })
        return fake to WebSessionBridge(mapOf("amazon-music" to host))
    }

    @Test
    fun `an answer goes back to the call's callback`() = runTest {
        val scripts = mutableListOf<String>()
        val calls = WebSessionJsCalls(bridge().second, backgroundScope, evaluate = { scripts += it })
        assertTrue(calls.call("ws_1", "state", """{"id":"amazon-music"}"""))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, scripts.size)
        assertTrue(scripts[0].startsWith("window.__wsCallbacks && window.__wsCallbacks['ws_1'] && window.__wsCallbacks['ws_1']({"))
        assertTrue(scripts[0].contains("\"success\":true"))
    }

    @Test
    fun `a malformed callback id never reaches a script`() = runTest {
        val scripts = mutableListOf<String>()
        val calls = WebSessionJsCalls(bridge().second, backgroundScope, evaluate = { scripts += it })
        assertFalse(calls.call("ws_1']);alert(1);//", "state", "{}"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(scripts.isEmpty())
    }

    @Test
    fun `no bridge answers not-ready`() = runTest {
        val scripts = mutableListOf<String>()
        val calls = WebSessionJsCalls(null, backgroundScope, evaluate = { scripts += it })
        calls.call("ws_2", "state", "{}")
        advanceTimeBy(10_000); runCurrent()
        assertTrue(scripts.single().contains("\"reason\":\"not-ready\""))
    }

    @Test
    fun `a sign-in tick asks the plugin's decide and takes its verdict`() = runTest {
        val (fake, bridge) = bridge()
        val scripts = mutableListOf<String>()
        lateinit var calls: WebSessionJsCalls
        calls = WebSessionJsCalls(bridge, backgroundScope, evaluate = { script ->
            scripts += script
            // The bootstrap's __pcWsSignInTick answers through NativeBridge.webSessionDecide.
            if (script.startsWith("window.__pcWsSignInTick")) calls.decide("ws_3", true)
        })
        calls.call("ws_3", "signIn", """{"id":"amazon-music","url":"https://music.amazon.com/"}""")
        advanceTimeBy(10_000); runCurrent()
        assertTrue(scripts.any { it.startsWith("window.__pcWsSignInTick && window.__pcWsSignInTick('ws_3', {\"url\":") })
        val answer = scripts.last()
        assertTrue(answer.startsWith("window.__wsCallbacks && window.__wsCallbacks['ws_3']"), answer)
        assertTrue(answer.contains("\"success\":true"), answer)
        assertTrue(fake.signInWindow != null)
    }
}
