package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A port of desktop's tests/protocol/chat-origin.test.js (the pure helpers). */
class ChatPromptTest {
    private val prefix = "parachord://chat?prompt="

    /** Decodes the link's prompt the way a URL parser does (UTF-8 percent escapes). */
    private fun promptOf(url: String): String {
        assertTrue(url.startsWith(prefix), url)
        val encoded = url.removePrefix(prefix)
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%') {
                bytes.add(encoded.substring(i + 1, i + 3).toInt(16).toByte())
                i += 3
            } else {
                bytes.addAll(c.toString().encodeToByteArray().toList())
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    @Test
    fun `round-trips awkward characters through the URL`() {
        val prompt = "rock & roll? #1 \"yes\" 🎸"
        val url = ChatPrompt.url(prompt)
        assertEquals(prompt, promptOf(url))
    }

    @Test
    fun `encodes as encodeURIComponent does`() {
        assertEquals(
            prefix + "rock%20%26%20roll%3F%20%231%20%22yes%22%20%F0%9F%8E%B8",
            ChatPrompt.url("rock & roll? #1 \"yes\" 🎸"),
        )
        // encodeURIComponent leaves letters, digits and - _ . ! ~ * ' ( ) alone.
        assertEquals(prefix + "aZ09-_.!~*'()", ChatPrompt.url("aZ09-_.!~*'()"))
        assertEquals(prefix + "%2B%3D%2F%3A%40%2C%3B%24%5B%5D%E2%80%99", ChatPrompt.url("+=/:@,;$[]’"))
    }

    @Test
    fun `a null prompt gives an empty prompt`() {
        assertEquals(prefix, ChatPrompt.url(null))
    }

    @Test
    fun `caps the prompt at 500 characters`() {
        assertEquals(500, promptOf(ChatPrompt.url("a".repeat(600))).length)
        assertEquals(500, ChatPrompt.MAX_CHARS)
    }

    @Test
    fun `a cut that splits a surrogate pair drops the lone half`() {
        assertEquals("a".repeat(499), promptOf(ChatPrompt.url("a".repeat(499) + "🎸")))
    }

    @Test
    fun `a pair that fits whole is kept`() {
        assertEquals("a".repeat(498) + "🎸", promptOf(ChatPrompt.url("a".repeat(498) + "🎸")))
    }

    @Test
    fun `cap trims to the same text the link carries`() {
        assertEquals("a".repeat(499), ChatPrompt.cap("a".repeat(499) + "🎸"))
        assertEquals("", ChatPrompt.cap(null))
    }

    @Test
    fun `sends once the panel is open with a provider and idle`() {
        assertTrue(ChatPrompt.shouldSend("hi", chatOpen = true, providerId = "ollama", loading = false))
    }

    @Test
    fun `waits when nothing is pending`() {
        assertFalse(ChatPrompt.shouldSend(null, chatOpen = true, providerId = "ollama", loading = false))
    }

    @Test
    fun `waits when the prompt is blank`() {
        assertFalse(ChatPrompt.shouldSend("  ", chatOpen = true, providerId = "ollama", loading = false))
    }

    @Test
    fun `waits when the panel is not open`() {
        assertFalse(ChatPrompt.shouldSend("hi", chatOpen = false, providerId = "ollama", loading = false))
    }

    @Test
    fun `waits when there is no provider yet`() {
        assertFalse(ChatPrompt.shouldSend("hi", chatOpen = true, providerId = null, loading = false))
    }

    @Test
    fun `waits while a reply is in flight`() {
        assertFalse(ChatPrompt.shouldSend("hi", chatOpen = true, providerId = "ollama", loading = true))
    }

    @Test
    fun `drops only on an open-to-closed change`() {
        assertTrue(ChatPrompt.shouldDrop(wasOpen = true, chatOpen = false))
    }

    @Test
    fun `keeps it while the panel is opening or stays open or stays closed`() {
        assertFalse(ChatPrompt.shouldDrop(wasOpen = false, chatOpen = true))
        assertFalse(ChatPrompt.shouldDrop(wasOpen = true, chatOpen = true))
        assertFalse(ChatPrompt.shouldDrop(wasOpen = false, chatOpen = false))
    }

    @Test
    fun `the url round-trips through the deep-link parser`() {
        val prompt = "Make me a playlist for right now"
        val url = ChatPrompt.url(prompt)
        val uri = SimpleDeepLinkUri("parachord", "chat", emptyList(), mapOf("prompt" to promptOf(url)))
        val action = DeepLinkParser.parse(uri)
        assertEquals(DeepLinkAction.NavigateChat(prompt), action)
    }

    @Test
    fun `a queued prompt keeps its origin until it is consumed`() {
        val pending = PendingChatPrompt()
        pending.set("from home", fromApp = true)
        assertTrue(pending.fromApp)
        assertEquals("from home", pending.prompt.value)
        assertEquals("from home", pending.consume())
        assertEquals(null, pending.prompt.value)
        assertFalse(pending.fromApp)
    }

    @Test
    fun `a link's prompt is not taken as an in-app one`() {
        val pending = PendingChatPrompt()
        pending.set("from a link", fromApp = false)
        assertEquals(null, pending.consumeInApp())
        assertEquals("from a link", pending.prompt.value)
        pending.set("from home", fromApp = true)
        assertEquals("from home", pending.consumeInApp())
        assertEquals(null, pending.prompt.value)
    }

    @Test
    fun `clearing the prompt clears its origin`() {
        val pending = PendingChatPrompt()
        pending.set("from home", fromApp = true)
        pending.set(null, fromApp = true)
        assertFalse(pending.fromApp)
    }
}
