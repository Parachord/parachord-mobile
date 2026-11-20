package com.parachord.android.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for AiChatService's history management and sanitization logic.
 * These test the pure functions (trimHistory, sanitizeHistory) by replicating
 * their logic, since the originals are private to AiChatService.
 */
class AiChatServiceTest {

    // -- trimHistory logic --

    private fun trimHistory(history: MutableList<ChatMessage>, maxLength: Int = 50) {
        if (history.size <= maxLength) return
        val first = history.first()
        var cutIndex = history.size - (maxLength - 1)
        while (cutIndex > 1 && history[cutIndex].role == ChatRole.TOOL) {
            cutIndex--
        }
        if (cutIndex > 1 &&
            history[cutIndex].role == ChatRole.ASSISTANT &&
            !history[cutIndex].toolCalls.isNullOrEmpty()
        ) {
            var nextAfterTools = cutIndex + 1
            while (nextAfterTools < history.size && history[nextAfterTools].role == ChatRole.TOOL) {
                nextAfterTools++
            }
            cutIndex = nextAfterTools
        }
        val tail = history.subList(cutIndex, history.size).toList()
        history.clear()
        history.add(first)
        history.addAll(tail)
    }

    // -- sanitizeHistory logic --

    private fun sanitizeHistory(history: MutableList<ChatMessage>) {
        while (history.isNotEmpty() && history.first().role == ChatRole.TOOL) {
            history.removeAt(0)
        }
        var i = 0
        while (i < history.size) {
            if (history[i].role == ChatRole.TOOL) {
                var foundParent = false
                for (j in i - 1 downTo 0) {
                    if (history[j].role == ChatRole.ASSISTANT && !history[j].toolCalls.isNullOrEmpty()) {
                        foundParent = true
                        break
                    }
                    if (history[j].role != ChatRole.TOOL) break
                }
                if (!foundParent) {
                    history.removeAt(i)
                    continue
                }
            }
            i++
        }
    }

    private fun msg(role: ChatRole, content: String = "", toolCalls: List<ToolCall>? = null, toolCallId: String? = null) =
        ChatMessage(role = role, content = content, toolCalls = toolCalls, toolCallId = toolCallId)

    private val dummyToolCall = ToolCall(id = "tc1", name = "search", arguments = emptyMap())

    // -- trimHistory tests --

    @Test
    fun `trimHistory no-op when under limit`() {
        val history = (1..10).map { msg(ChatRole.USER, "msg $it") }.toMutableList()
        trimHistory(history, maxLength = 50)
        assertEquals(10, history.size)
    }

    @Test
    fun `trimHistory keeps first message plus tail`() {
        val history = (1..60).map { msg(ChatRole.USER, "msg $it") }.toMutableList()
        trimHistory(history, maxLength = 50)
        assertEquals("msg 1", history.first().content)
        assertEquals("msg 60", history.last().content)
        assertEquals(50, history.size)
    }

    @Test
    fun `trimHistory does not split tool call group`() {
        val history = mutableListOf<ChatMessage>()
        // Add 48 user messages
        for (i in 1..48) history.add(msg(ChatRole.USER, "msg $i"))
        // Add assistant with tool calls + 2 tool results (messages 49, 50, 51)
        history.add(msg(ChatRole.ASSISTANT, "thinking...", toolCalls = listOf(dummyToolCall)))
        history.add(msg(ChatRole.TOOL, "result1", toolCallId = "tc1"))
        history.add(msg(ChatRole.TOOL, "result2", toolCallId = "tc2"))
        // Add 5 more user messages (52-56)
        for (i in 1..5) history.add(msg(ChatRole.USER, "follow up $i"))

        assertEquals(56, history.size)
        trimHistory(history, maxLength = 50)

        // Should not have orphaned TOOL messages at the start (after first message)
        val afterFirst = history.drop(1)
        if (afterFirst.isNotEmpty()) {
            assertNotEquals(ChatRole.TOOL, afterFirst.first().role)
        }
    }

    // -- sanitizeHistory tests --

    @Test
    fun `sanitizeHistory removes leading orphaned TOOL messages`() {
        val history = mutableListOf(
            msg(ChatRole.TOOL, "orphan1", toolCallId = "tc1"),
            msg(ChatRole.TOOL, "orphan2", toolCallId = "tc2"),
            msg(ChatRole.USER, "hello"),
            msg(ChatRole.ASSISTANT, "hi"),
        )
        sanitizeHistory(history)
        assertEquals(2, history.size)
        assertEquals(ChatRole.USER, history.first().role)
    }

    @Test
    fun `sanitizeHistory preserves valid tool call groups`() {
        val history = mutableListOf(
            msg(ChatRole.USER, "search for something"),
            msg(ChatRole.ASSISTANT, "searching...", toolCalls = listOf(dummyToolCall)),
            msg(ChatRole.TOOL, "search results", toolCallId = "tc1"),
            msg(ChatRole.ASSISTANT, "here are the results"),
        )
        sanitizeHistory(history)
        assertEquals(4, history.size)
    }

    @Test
    fun `sanitizeHistory removes mid-history orphaned TOOL messages`() {
        val history = mutableListOf(
            msg(ChatRole.USER, "hello"),
            msg(ChatRole.ASSISTANT, "hi"), // no tool calls
            msg(ChatRole.TOOL, "orphan", toolCallId = "tc1"), // no parent
            msg(ChatRole.USER, "ok"),
        )
        sanitizeHistory(history)
        assertEquals(3, history.size)
        assertFalse(history.any { it.role == ChatRole.TOOL })
    }

    @Test
    fun `sanitizeHistory handles empty history`() {
        val history = mutableListOf<ChatMessage>()
        sanitizeHistory(history)
        assertTrue(history.isEmpty())
    }

    @Test
    fun `sanitizeHistory handles history with only TOOL messages`() {
        val history = mutableListOf(
            msg(ChatRole.TOOL, "orphan1"),
            msg(ChatRole.TOOL, "orphan2"),
        )
        sanitizeHistory(history)
        assertTrue(history.isEmpty())
    }

    // The provider-error wording is the shared AiProviderErrors, tested in
    // shared commonTest (AiProviderErrorsTest).
}
