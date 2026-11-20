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
}
