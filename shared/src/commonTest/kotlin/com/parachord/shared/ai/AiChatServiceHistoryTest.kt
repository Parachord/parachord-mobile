package com.parachord.shared.ai

import com.parachord.shared.ai.tools.DjToolExecutor
import com.parachord.shared.model.ChatMessageRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chat history for a provider is loaded once and never replaced while
 * in use. Home's Ask opened the chat, the chat screen loaded the provider's
 * messages while it sent the prompt, and the second of the two concurrent
 * first loads replaced the list the prompt had been added to: the chat
 * showed the old conversation without the prompt or its reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiChatServiceHistoryTest {

    /** A store whose reads wait for the test to release them, one gate per read. */
    private class GatedStore(private val saved: List<ChatMessageRecord>) : ChatHistoryStore {
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val inserted = mutableListOf<ChatMessageRecord>()
        var reads = 0

        override suspend fun deleteOlderThan(cutoffMillis: Long) {}
        override suspend fun getByProvider(providerId: String): List<ChatMessageRecord> {
            reads++
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            gate.await()
            return saved
        }
        override suspend fun insert(record: ChatMessageRecord) { inserted += record }
        override suspend fun clearByProvider(providerId: String) {}
        override suspend fun clearAll() {}
    }

    private object NoTools : DjToolExecutor {
        override suspend fun execute(name: String, args: Map<String, Any?>): Map<String, Any?> = emptyMap()
    }

    private object EchoProvider : AiChatProvider {
        override val id = "fake"
        override val name = "Fake"
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<DjToolDefinition>,
            config: AiProviderConfig,
        ): AiChatResponse = AiChatResponse(content = "reply to " + messages.last().content)
    }

    private val earlier = ChatMessageRecord(
        providerId = "fake", role = "USER", content = "earlier", timestamp = Long.MAX_VALUE / 2,
    )

    private fun service(store: ChatHistoryStore) =
        AiChatService(NoTools, systemPrompt = { "system" }, historyStore = store, json = Json)

    @Test
    fun `a send racing the first load keeps the prompt and the reply`() = runTest {
        val store = GatedStore(listOf(earlier))
        val chat = service(store)

        // The send's load starts first (selectProvider suspends on the
        // settings write before it reads the history), then the display load.
        launch { chat.sendMessage(EchoProvider, AiProviderConfig(apiKey = ""), "new prompt") }
        runCurrent()
        launch { chat.getDisplayMessages("fake") }
        runCurrent()

        // Release whichever reads are waiting, the send's first.
        while (store.gates.isNotEmpty()) {
            store.gates.removeFirst().complete(Unit)
            runCurrent()
        }

        val shown = chat.getDisplayMessages("fake").map { it.content }
        assertEquals(listOf("earlier", "new prompt", "reply to new prompt"), shown)
        assertEquals(1, store.reads)
    }

    /** A store that answers at once and keeps what's inserted. */
    private class MemoryStore : ChatHistoryStore {
        val inserted = mutableListOf<ChatMessageRecord>()
        override suspend fun deleteOlderThan(cutoffMillis: Long) {}
        override suspend fun getByProvider(providerId: String): List<ChatMessageRecord> = emptyList()
        override suspend fun insert(record: ChatMessageRecord) { inserted += record }
        override suspend fun clearByProvider(providerId: String) { inserted.clear() }
        override suspend fun clearAll() { inserted.clear() }
    }

    /** Asks for one tool, then answers [then] (or throws when it's null). */
    private class ToolThenProvider(private val then: String?) : AiChatProvider {
        override val id = "fake"
        override val name = "Fake"
        var calls = 0
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<DjToolDefinition>,
            config: AiProviderConfig,
        ): AiChatResponse {
            calls++
            if (calls == 1) return AiChatResponse(content = "", toolCalls = listOf(ToolCall("t1", "play", emptyMap())))
            return AiChatResponse(content = then ?: throw IllegalStateException("ChatGPT API error (500): boom"))
        }
    }

    private object ThrowingTools : DjToolExecutor {
        override suspend fun execute(name: String, args: Map<String, Any?>): Map<String, Any?> =
            throw IllegalStateException("playback controller gone")
    }

    @Test
    fun `a tool that throws answers with an error result and the reply still comes`() = runTest {
        val store = MemoryStore()
        val chat = AiChatService(ThrowingTools, systemPrompt = { "system" }, historyStore = store, json = Json)
        val reply = chat.sendMessage(ToolThenProvider("Sorry, I couldn't play that"), AiProviderConfig(), "play x")
        assertEquals("Sorry, I couldn't play that", reply)
        val tool = chat.getFullHistory("fake").single { it.role == ChatRole.TOOL }
        assertTrue("playback controller gone" in tool.content, tool.content)
    }

    @Test
    fun `a failed tool round is shown and kept`() = runTest {
        val store = MemoryStore()
        val chat = service(store)
        val reply = chat.sendMessage(ToolThenProvider(null), AiProviderConfig(), "play x")
        assertEquals(reply, chat.getDisplayMessages("fake").last().content)
        assertEquals(reply, store.inserted.last().content)
    }

    @Test
    fun `clearing the chat during a send leaves no row from that send`() = runTest {
        val store = MemoryStore()
        val chat = service(store)
        val gate = CompletableDeferred<Unit>()
        val slow = object : AiChatProvider {
            override val id = "fake"
            override val name = "Fake"
            override suspend fun chat(messages: List<ChatMessage>, tools: List<DjToolDefinition>, config: AiProviderConfig): AiChatResponse {
                gate.await()
                return AiChatResponse(content = "late reply")
            }
        }
        launch { chat.sendMessage(slow, AiProviderConfig(), "hello") }
        runCurrent()
        chat.clearHistory("fake")
        gate.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), store.inserted)
        assertEquals(emptyList(), chat.getDisplayMessages("fake"))
    }

    @Test
    fun `the chat shows the user's messages and each turn's final reply only`() = runTest {
        // Shaped like a device's gemini rows 19-28: interim tool-loop replies
        // (a preamble, an apology about a search, blank ones) with tool calls,
        // tool results, then the final answer and a failed later round.
        val calls = """[{"id":"c1","name":"search","arguments":{"query":"indie guitar rock"}}]"""
        fun row(role: String, content: String, toolCalls: String? = null, toolCallId: String? = null) =
            ChatMessageRecord(providerId = "fake", role = role, content = content, toolCallsJson = toolCalls,
                toolCallId = toolCallId, toolName = if (toolCallId != null) "search" else null, timestamp = Long.MAX_VALUE / 2)
        val saved = listOf(
            row("USER", "a mix of new indie guitar rock"),
            row("ASSISTANT", "Gotcha! Here's a mix…", calls),
            row("TOOL", """{"results":[]}""", toolCallId = "c1"),
            row("ASSISTANT", "My apologies! It looks like my last search didn't quite hit the mark…", calls),
            row("TOOL", """{"results":[]}""", toolCallId = "c1"),
            row("ASSISTANT", "", calls),
            row("TOOL", """{"results":[]}""", toolCallId = "c1"),
            row("ASSISTANT", "   ", calls),
            row("TOOL", """{"results":[]}""", toolCallId = "c1"),
            row("ASSISTANT", "{{artist|IDLES}}\n{{artist|Fontaines D.C.}}"),
            row("USER", "more like that"),
            row("ASSISTANT", "I encountered an error while processing. Please try again."),
        )
        val store = object : ChatHistoryStore {
            override suspend fun deleteOlderThan(cutoffMillis: Long) {}
            override suspend fun getByProvider(providerId: String) = saved
            override suspend fun insert(record: ChatMessageRecord) {}
            override suspend fun clearByProvider(providerId: String) {}
            override suspend fun clearAll() {}
        }
        val chat = service(store)
        assertEquals(
            listOf(
                "a mix of new indie guitar rock",
                "{{artist|IDLES}}\n{{artist|Fontaines D.C.}}",
                "more like that",
                "I encountered an error while processing. Please try again.",
            ),
            chat.getDisplayMessages("fake").map { it.content },
        )
        // The model still sees the whole loop.
        assertEquals(saved.size, chat.getFullHistory("fake").size)
    }
}
