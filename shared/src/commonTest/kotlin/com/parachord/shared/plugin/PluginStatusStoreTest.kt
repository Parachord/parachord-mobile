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
        val store = PluginStatusStore(
            statusPluginIds = { this.replies.keys.toList() },
            readStatus = { id -> this.replies.getValue(id)() },
            callSession = { id, method, _ -> sessionCalls += "$id.$method"; session(id, method) },
            getPlaybackOff = { off.value },
            playbackOffFlow = off,
            scope = scope.backgroundScope,
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
}
