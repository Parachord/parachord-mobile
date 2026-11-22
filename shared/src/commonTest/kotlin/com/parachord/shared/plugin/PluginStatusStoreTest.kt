package com.parachord.shared.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginStatusStoreTest {
    private val prime = """{"signedIn":true,"playbackLimit":{"blocked":true,"planKey":"prime","label":"Prime: no playback","message":"Needs Unlimited."},"statusLabel":null,"statusMessage":"Needs Unlimited."}"""

    private class Harness(scope: TestScope, replies: Map<String, () -> String?>, session: (String, String) -> String? = { _, _ -> null }) {
        val off = MutableStateFlow<Set<String>>(emptySet())
        val sessionCalls = mutableListOf<String>()
        val replies = replies.toMutableMap()
        val limitStates = mutableMapOf<String, String>()
        val cleared = mutableListOf<String>()
        val drmCleared = mutableListOf<String>()
        val store = PluginStatusStore(
            statusPluginIds = { this.replies.keys.toList() },
            readStatus = { id -> this.replies.getValue(id)() },
            callSession = { id, method, _ -> sessionCalls += "$id.$method"; session(id, method) },
            getPlaybackOff = { off.value },
            playbackOffFlow = off,
            scope = scope.backgroundScope,
            getLimitState = { limitStates[it] },
            setLimitState = { id, state -> limitStates[id] = state },
            clearResolver = { id, all -> cleared += "$id:$all" },
            clearDrmPlanBlocks = { drmCleared += it },
        )
    }

    @Test
    fun `a blocked plan joins the not-playing list and a switched-off one does too`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, mapOf("amazonmusic" to { prime }))
        h.store.refreshAll()
        assertTrue(h.store.limits.value.getValue("amazonmusic").blocked)
        assertEquals(listOf("amazonmusic"), h.store.notPlaying.value)
        h.off.value = setOf("youtube")
        assertEquals(listOf("amazonmusic", "youtube"), h.store.notPlaying.value)
        assertEquals(listOf("amazonmusic", "youtube"), h.store.notPlayingNow())
    }

    @Test
    fun `a throwing read is a failed read and never a block`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, mapOf("amazonmusic" to { throw IllegalStateException("Plugin call didn't answer in time") }))
        h.store.refresh("amazonmusic")
        assertTrue(h.store.statuses.value.getValue("amazonmusic").readFailed)
        assertEquals(emptyList(), h.store.notPlaying.value)
    }

    @Test
    fun `a plugin with no status leaves no entry`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, mapOf("bandcamp" to { null }))
        h.store.refresh("bandcamp")
        assertNull(h.store.statuses.value["bandcamp"])
    }

    @Test
    fun `sign out says so in desktop's words and reads the status again`() = runTest(UnconfinedTestDispatcher()) {
        var signedIn = true
        val h = Harness(
            this,
            mapOf("amazonmusic" to { """{"signedIn":$signedIn}""" }),
            session = { _, method -> if (method == "signOut") { signedIn = false; """{"success":true}""" } else null },
        )
        h.store.refresh("amazonmusic")
        val r = h.store.signOut("amazonmusic", "Amazon Music")
        assertEquals(PluginStatusStore.SessionResult(true, "Signed out of Amazon Music"), r)
        assertEquals(false, h.store.statuses.value.getValue("amazonmusic").signedIn)
        assertEquals(listOf("amazonmusic.signOut"), h.sessionCalls)
    }

    @Test
    fun `a failed sign out shows the plugin's error or desktop's fallback`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, mapOf("x" to { """{"signedIn":true}""" }), session = { _, _ -> """{"success":false,"error":"no web-session bridge"}""" })
        assertEquals(PluginStatusStore.SessionResult(false, "no web-session bridge"), h.store.signOut("x", "X"))
        val h2 = Harness(this, mapOf("x" to { """{"signedIn":true}""" }), session = { _, _ -> throw IllegalStateException("boom") })
        assertEquals(PluginStatusStore.SessionResult(false, "Could not sign out of X"), h2.store.signOut("x", "X"))
    }

    @Test
    fun `sign in succeeds when the status then says signed in`() = runTest(UnconfinedTestDispatcher()) {
        var signedIn = false
        val h = Harness(this, mapOf("x" to { """{"signedIn":$signedIn}""" }), session = { _, _ -> signedIn = true; """{"success":true}""" })
        val r = h.store.signIn("x", "X")
        assertTrue(r.success)
        assertEquals("Signed in to X", r.message)
        val h2 = Harness(this, mapOf("x" to { """{"signedIn":false}""" }), session = { _, _ -> """{"success":false}""" })
        assertFalse(h2.store.signIn("x", "X").success)
    }

    private val primeOwned = """{"signedIn":true,"playbackLimit":{"blocked":false,"ownedOnly":true,"planKey":"prime","label":"Prime: purchases only","searchesHeldBack":true}}"""
    private val unlimited = """{"signedIn":true,"playbackLimit":{"blocked":false,"planKey":"unlimited","searchesHeldBack":true}}"""

    @Test
    fun `a purchases-only plan stays playing and entering it drops the saved sources once`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, mapOf("amazonmusic" to { primeOwned }))
        h.limitStates["amazonmusic"] = "0"
        h.store.refresh("amazonmusic")
        assertEquals(emptyList(), h.store.notPlaying.value)
        assertEquals(listOf("amazonmusic:true"), h.cleared)
        assertEquals("owned", h.limitStates["amazonmusic"])
        // Still on it: nothing more.
        h.store.refresh("amazonmusic")
        assertEquals(listOf("amazonmusic:true"), h.cleared)
    }

    @Test
    fun `leaving it for Unlimited asks again without a purge`() = runTest(UnconfinedTestDispatcher()) {
        var reply = primeOwned
        val h = Harness(this, mapOf("amazonmusic" to { reply }))
        h.limitStates["amazonmusic"] = "owned"
        reply = unlimited
        h.store.refresh("amazonmusic")
        assertEquals(listOf("amazonmusic:false"), h.cleared)
        assertEquals("0", h.limitStates["amazonmusic"])
    }

    @Test
    fun `a failed read changes no remembered state`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, mapOf("amazonmusic" to { """{"failed":true}""" }))
        h.limitStates["amazonmusic"] = "0"
        h.store.refresh("amazonmusic")
        assertEquals(emptyList(), h.cleared)
        assertEquals("0", h.limitStates["amazonmusic"])
    }


    @Test
    fun `sign out clears the refused codec families before the plugin signs out`() = runTest(UnconfinedTestDispatcher()) {
        val order = mutableListOf<String>()
        val h = Harness(this, mapOf("amazonmusic" to { """{"signedIn":false}""" }), session = { _, m -> order += m; """{"success":true}""" })
        h.store.signOut("amazonmusic", "Amazon Music")
        assertEquals(listOf("amazonmusic"), h.drmCleared.distinct())
        assertEquals(listOf("signOut"), order)
    }

    @Test
    fun `a successful sign in clears them and a failed one does not`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, mapOf("amazonmusic" to { unlimited }), session = { _, _ -> """{"success":true}""" })
        h.store.signIn("amazonmusic", "Amazon Music")
        assertTrue("amazonmusic" in h.drmCleared)
        val h2 = Harness(this, mapOf("amazonmusic" to { """{"signedIn":false}""" }), session = { _, _ -> """{"success":false}""" })
        h2.store.signIn("amazonmusic", "Amazon Music")
        assertEquals(emptyList(), h2.drmCleared)
    }

    @Test
    fun `a read whose plan changed clears them`() = runTest(UnconfinedTestDispatcher()) {
        var reply = primeOwned
        val h = Harness(this, mapOf("amazonmusic" to { reply }))
        h.store.refresh("amazonmusic")
        assertEquals(emptyList(), h.drmCleared)
        h.store.refresh("amazonmusic")
        assertEquals(emptyList(), h.drmCleared)
        reply = unlimited
        h.store.refresh("amazonmusic")
        assertEquals(listOf("amazonmusic"), h.drmCleared)
    }

    @Test
    fun `a first read against a different remembered plan clears them`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(this, mapOf("amazonmusic" to { unlimited }))
        h.limitStates["amazonmusic"] = LIMIT_STATE_OWNED
        h.store.refresh("amazonmusic")
        assertEquals(listOf("amazonmusic"), h.drmCleared)
        val same = Harness(this, mapOf("amazonmusic" to { unlimited }))
        same.limitStates["amazonmusic"] = LIMIT_STATE_PLAYABLE
        same.store.refresh("amazonmusic")
        assertEquals(emptyList(), same.drmCleared)
    }

    @Test
    fun `a sign in change on a read clears them and a failed read never does`() = runTest(UnconfinedTestDispatcher()) {
        var reply: () -> String? = { """{"signedIn":true}""" }
        val h = Harness(this, mapOf("amazonmusic" to { reply() }))
        h.store.refresh("amazonmusic")
        reply = { throw IllegalStateException("timeout") }
        h.store.refresh("amazonmusic")
        assertEquals(emptyList(), h.drmCleared)
        reply = { """{"signedIn":false}""" }
        h.store.refresh("amazonmusic")
        assertEquals(listOf("amazonmusic"), h.drmCleared)
    }
}
