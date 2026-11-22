package com.parachord.shared.plugin

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginSignInPromptTest {
    private class Fixture {
        var canPrompt = true
        var signedIn: Boolean? = false
        var decision: String? = null
        var decisionInputs: String? = null
        var screen = true
        var signInResult = PluginStatusStore.SessionResult(true, "Signed in to Amazon Music")
        var signIns = 0
        var now = 1_000_000L
        val pauses = mutableListOf<Long>()
        val prompt = PluginSignInPrompt(
            canPrompt = { canPrompt },
            statusSignedIn = { signedIn },
            promptDecision = { _, inputs -> decisionInputs = inputs; decision },
            signIn = { _, _ -> signIns++; signInResult },
            screenShowing = { screen },
            nowMs = { now },
            pause = { pauses += it },
        )
    }

    @Test
    fun `a plugin that can't sign in is not asked`() = runTest {
        val f = Fixture().apply { canPrompt = false }
        assertFalse(f.prompt.wouldAsk("amazonmusic", personAsked = true))
        assertEquals(SignInPromptResult.NOT_OFFERED, f.prompt.afterRefusal("amazonmusic", "Amazon Music", true).result)
        assertNull(f.prompt.pending.value)
    }

    @Test
    fun `no screen — nothing is asked`() = runTest {
        val f = Fixture().apply { screen = false }
        assertFalse(f.prompt.wouldAsk("amazonmusic", personAsked = true))
        assertEquals(SignInPromptResult.NOT_OFFERED, f.prompt.afterRefusal("amazonmusic", "Amazon Music", true).result)
    }

    @Test
    fun `a yes signs in and plays again`() = runTest {
        val f = Fixture()
        val result = async { f.prompt.afterRefusal("amazonmusic", "Amazon Music", true) }
        runCurrent()
        val request = assertNotNull(f.prompt.pending.value)
        assertEquals("Sign in to Amazon Music?", request.title)
        assertEquals("Amazon Music needs you to sign in to play this track.", request.message)
        f.prompt.answer(request.requestId, signIn = true)
        val outcome = result.await()
        assertEquals(SignInPromptResult.RETRY, outcome.result)
        assertEquals("Signed in to Amazon Music", outcome.message)
        assertEquals(1, f.signIns)
        assertNull(f.prompt.pending.value)
    }

    @Test
    fun `not now — declined, quiet for the queue's own plays but not for the person's`() = runTest {
        val f = Fixture()
        val result = async { f.prompt.afterRefusal("amazonmusic", "Amazon Music", true) }
        runCurrent()
        f.prompt.answer(f.prompt.pending.value!!.requestId, signIn = false)
        assertEquals(SignInPromptResult.DECLINED, result.await().result)
        assertEquals(0, f.signIns)
        assertFalse(f.prompt.wouldAsk("amazonmusic", personAsked = false))
        assertTrue(f.prompt.wouldAsk("amazonmusic", personAsked = true))
        f.now += PluginSignInPrompt.DECLINE_QUIET_MS
        assertTrue(f.prompt.wouldAsk("amazonmusic", personAsked = false))
    }

    @Test
    fun `a sign-in that doesn't end signed in is declined with its words`() = runTest {
        val f = Fixture().apply { signInResult = PluginStatusStore.SessionResult(false, "Could not sign in to Amazon Music") }
        val result = async { f.prompt.afterRefusal("amazonmusic", "Amazon Music", true) }
        runCurrent()
        f.prompt.answer(f.prompt.pending.value!!.requestId, signIn = true)
        val outcome = result.await()
        assertEquals(SignInPromptResult.DECLINED, outcome.result)
        assertEquals("Could not sign in to Amazon Music", outcome.message)
    }

    @Test
    fun `the plugin's hiccup rule retries quietly without asking`() = runTest {
        val f = Fixture().apply {
            signedIn = true
            decision = """{"prompt":false,"streak":{"firstAt":1000000,"lastAt":1000000}}"""
        }
        val outcome = f.prompt.afterRefusal("amazonmusic", "Amazon Music", true)
        assertEquals(SignInPromptResult.RETRY, outcome.result)
        assertEquals(listOf(PluginSignInPrompt.QUIET_RETRY_MS), f.pauses)
        assertNull(f.prompt.pending.value)
        assertTrue(f.decisionInputs!!.contains("\"cookiesSignedIn\":true"))
        assertTrue(f.decisionInputs!!.contains("\"reason\":\"not-signed-in\""))
        // The streak the plugin returned goes back to it next time.
        f.prompt.afterRefusal("amazonmusic", "Amazon Music", true)
        assertTrue(f.decisionInputs!!.contains("\"firstAt\":1000000"))
    }

    @Test
    fun `a hiccup verdict of prompt asks`() = runTest {
        val f = Fixture().apply { signedIn = true; decision = """{"prompt":true,"streak":{"firstAt":0,"lastAt":0}}""" }
        val result = async { f.prompt.afterRefusal("amazonmusic", "Amazon Music", true) }
        runCurrent()
        assertNotNull(f.prompt.pending.value)
        f.prompt.answer(f.prompt.pending.value!!.requestId, signIn = false)
        assertEquals(SignInPromptResult.DECLINED, result.await().result)
        assertTrue(f.pauses.isEmpty())
    }

    @Test
    fun `a second refusal while asking waits for the same answer`() = runTest {
        val f = Fixture()
        val first = async { f.prompt.afterRefusal("amazonmusic", "Amazon Music", true) }
        runCurrent()
        val second = async { f.prompt.afterRefusal("amazonmusic", "Amazon Music", false) }
        runCurrent()
        f.prompt.answer(f.prompt.pending.value!!.requestId, signIn = true)
        assertEquals(SignInPromptResult.RETRY, first.await().result)
        assertEquals(SignInPromptResult.RETRY, second.await().result)
        assertEquals(1, f.signIns)
    }

    @Test
    fun `a stale answer is ignored`() = runTest {
        val f = Fixture()
        val result = async { f.prompt.afterRefusal("amazonmusic", "Amazon Music", true) }
        runCurrent()
        val id = f.prompt.pending.value!!.requestId
        f.prompt.answer(id + 99, signIn = true)
        assertNotNull(f.prompt.pending.value)
        f.prompt.answer(id, signIn = false)
        assertEquals(SignInPromptResult.DECLINED, result.await().result)
    }
}
