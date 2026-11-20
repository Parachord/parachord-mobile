package com.parachord.shared.ai

import com.parachord.shared.deeplink.ChatPrompt
import com.parachord.shared.deeplink.PendingChatPrompt

import com.parachord.shared.ai.tools.DjToolDefinitions
import com.parachord.shared.ai.tools.DjToolExecutor
import com.parachord.shared.db.dao.ChatMessageDao
import com.parachord.shared.model.ChatMessageRecord
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Orchestrates AI chat conversations with tool-call looping.
 * Mirrors the desktop app's ai-chat.js service logic.
 *
 * Messages are persisted to Room and retained for 30 days.
 *
 * KMP migration notes:
 *  - [DjToolExecutor] is now a shared interface; the concrete tool
 *    dispatch (which reaches into PlaybackController / MCP / Parachord
 *    controls) is platform-specific. iOS supplies its own implementation.
 *  - JSON helpers `mapToJsonElement` / `jsonElementToMap` were extracted
 *    from `ChatGptProvider` to shared (`JsonHelpers.kt`) so the chat
 *    service can encode tool results without depending on Android provider
 *    impls.
 *  - `Dispatchers.IO` (commonMain doesn't have it) → all DAO calls are
 *    already `suspend` and run on `Dispatchers.Default` internally; the
 *    explicit `withContext(Dispatchers.IO)` wrapper is no longer needed.
 */
class AiChatService(
    private val toolExecutor: DjToolExecutor,
    private val systemPrompt: suspend () -> String,
    private val historyStore: ChatHistoryStore,
    private val json: Json,
) {

    /** The app's wiring: the system prompt from [contextProvider], history in [chatMessageDao]. */
    constructor(
        toolExecutor: DjToolExecutor,
        contextProvider: ChatContextProvider,
        chatMessageDao: ChatMessageDao,
        json: Json,
    ) : this(toolExecutor, { contextProvider.buildSystemPrompt() }, chatMessageDao.asChatHistoryStore(), json)

    companion object {
        private const val TAG = "AiChatService"
        private const val MAX_HISTORY_LENGTH = 50
        private const val MAX_TOOL_ITERATIONS = 5
        /** 30 days in milliseconds. */
        private const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }

    /**
     * The prompt the chat screen sends when it opens: from a confirmed
     * `parachord://chat?prompt=` link, or from Home's Ask box (`fromApp`).
     * Each chat screen sends it through [takeSendableChatPrompt] once it's
     * open with a provider whose messages are loaded and no reply is in
     * flight, re-checking whenever any of those change. iOS's chat takes only
     * an in-app one (`inAppOnly`) and pre-fills a link's into the input
     * instead.
     */
    private val pending = PendingChatPrompt()
    val pendingChatPrompt: StateFlow<String?> = pending.prompt

    /**
     * Queue [prompt] to be sent when the chat screen opens. [fromApp] marks a
     * prompt the user typed in the app (Home's Ask), which is sent without the
     * external-link confirmation; only the in-app entry points set it, never
     * anything read from a URL.
     */
    fun setPendingChatPrompt(prompt: String?, fromApp: Boolean = false) {
        pending.set(prompt, fromApp)
    }

    /** Whether the queued prompt came from the app (Home's Ask), not a link. */
    val pendingChatPromptFromApp: Boolean get() = pending.fromApp

    /** Counts the prompts queued; see [PendingChatPrompt.generation]. */
    val pendingChatPromptGeneration: Long get() = pending.generation

    /** Drops the queued prompt only if it's still the one queued as [generation]. */
    fun dropPendingChatPrompt(generation: Long) = pending.dropIfGeneration(generation)

    /**
     * Takes the queued prompt when the chat can send it now: open, with a
     * provider whose messages are loaded, and no reply in flight (shared
     * [ChatPrompt.shouldSend]). Both chat screens call it whenever any of
     * those change, as desktop's effect over `[pending, aiChatOpen,
     * selectedChatProvider, aiChatLoading]` does, so a prompt queued while
     * the chat is already open, or while a reply loads, still goes out.
     */
    fun takeSendableChatPrompt(chatOpen: Boolean, providerId: String?, loading: Boolean, inAppOnly: Boolean = false): String? =
        pending.takeSendable(chatOpen, providerId, loading, inAppOnly)

    /** Consume the pending prompt (returns it and clears it). */
    fun consumePendingChatPrompt(): String? = pending.consume()

    /** Consume the pending prompt only if it came from the app; a link's stays. */
    fun consumeInAppChatPrompt(): String? = pending.consumeInApp()

    /**
     * The chat screen went from [wasOpen] to [chatOpen]: a prompt still queued
     * when it closes is dropped (shared [ChatPrompt.shouldDrop]).
     */
    fun onChatVisibilityChanged(wasOpen: Boolean, chatOpen: Boolean) {
        if (ChatPrompt.shouldDrop(wasOpen, chatOpen)) pending.set(null, false)
    }

    /** In-memory cache of per-provider histories, lazily populated from the database. */
    private val histories = mutableMapOf<String, MutableList<ChatMessage>>()

    /**
     * Guards [histories] and every list in it. A provider's list is created
     * once, by the first load, and never replaced while in use: the chat
     * screen loads the messages while it sends Home's Ask prompt, and two
     * unguarded first loads each built a list, the second replacing the one
     * the prompt (and later its reply) had been added to. The chat then
     * showed the old conversation without the prompt.
     */
    private val historyLock = Mutex()

    /**
     * The in-memory history for [providerId], loaded from the database the
     * first time (which also prunes messages older than 30 days).
     */
    private suspend fun historyFor(providerId: String): MutableList<ChatMessage> = historyLock.withLock {
        histories[providerId]?.let { return@withLock it }

        // Prune messages older than 30 days
        val cutoff = currentTimeMillis() - RETENTION_MILLIS
        historyStore.deleteOlderThan(cutoff)

        // Load from DB
        val entities = historyStore.getByProvider(providerId)
        val messages = entities.map { it.toChatMessage() }.toMutableList()
        // Remove orphaned TOOL messages that lost their parent ASSISTANT message
        // (e.g. from 30-day pruning or previous trimming bugs)
        sanitizeHistory(messages)
        histories[providerId] = messages
        messages
    }

    /**
     * Add [message] to [history] (under the lock) and persist it. Nothing is
     * added or saved once [history] is no longer the provider's list: the
     * chat was cleared while this send was under way, and a late reply must
     * not leave a row in the cleared conversation.
     */
    private suspend fun append(providerId: String, history: MutableList<ChatMessage>, message: ChatMessage) {
        // Checked and saved under the lock, so a clear can't land between the
        // check and the insert.
        historyLock.withLock {
            if (histories[providerId] === history) {
                history.add(message)
                persistMessage(providerId, message)
            }
        }
    }

    /** The system prompt followed by a copy of [history], for one provider call. */
    private suspend fun requestMessages(history: List<ChatMessage>): List<ChatMessage> {
        val systemMessage = ChatMessage(role = ChatRole.SYSTEM, content = systemPrompt())
        return listOf(systemMessage) + historyLock.withLock { history.toList() }
    }

    /** Persist a single message to Room. */
    private suspend fun persistMessage(providerId: String, message: ChatMessage) {
        historyStore.insert(message.toEntity(providerId))
    }

    /**
     * Send a user message to the given AI provider and return the final assistant response.
     * Handles tool-call loops automatically.
     *
     * @param onProgress Called with progress text when tools are being executed.
     */
    suspend fun sendMessage(
        provider: AiChatProvider,
        config: AiProviderConfig,
        userMessage: String,
        onProgress: (String) -> Unit = {},
    ): String {
        val history = historyFor(provider.id)

        // Add user message
        append(provider.id, history, ChatMessage(role = ChatRole.USER, content = userMessage))

        // Trim history: keep first message + last (N-1) if over limit
        historyLock.withLock { trimHistory(history) }

        // Build system prompt and prepend for the API call
        val messagesWithSystem = requestMessages(history)

        val tools = DjToolDefinitions.all()

        val response = try {
            provider.chat(messagesWithSystem, tools, config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorMessage = formatProviderError(provider.id, e)
            // Add error as assistant message so it's visible in the UI
            append(provider.id, history, ChatMessage(role = ChatRole.ASSISTANT, content = errorMessage))
            return errorMessage
        }

        // If no tool calls, just add the assistant message and return
        if (response.toolCalls.isNullOrEmpty()) {
            // Plain-text music references as cards (desktop converts before it
            // stores the reply, so later turns see the cards too).
            val content = ChatContent.convertPlainTextToCards(response.content)
            append(provider.id, history, ChatMessage(role = ChatRole.ASSISTANT, content = content))
            return content
        }

        // Enter tool loop
        return handleToolCalls(provider, config, history, tools, response, onProgress)
    }

    /**
     * Process tool calls in a loop until the AI returns a plain text response
     * or we hit MAX_TOOL_ITERATIONS.
     */
    private suspend fun handleToolCalls(
        provider: AiChatProvider,
        config: AiProviderConfig,
        history: MutableList<ChatMessage>,
        tools: List<DjToolDefinition>,
        initialResponse: AiChatResponse,
        onProgress: (String) -> Unit,
    ): String {
        var currentResponse = initialResponse
        var iterations = 0

        while (!currentResponse.toolCalls.isNullOrEmpty() && iterations < MAX_TOOL_ITERATIONS) {
            iterations++

            // Add assistant message with tool calls to history
            val assistantMsg = ChatMessage(
                role = ChatRole.ASSISTANT,
                content = currentResponse.content,
                toolCalls = currentResponse.toolCalls,
            )
            append(provider.id, history, assistantMsg)

            // Execute each tool call and add results to history
            for (toolCall in currentResponse.toolCalls.orEmpty()) {
                onProgress(toolProgressText(toolCall.name))

                // A tool that throws answers with an error result, so the model
                // can say what went wrong; the exception never reaches the screen.
                val result = try {
                    toolExecutor.execute(toolCall.name, toolCall.arguments)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Redacted before it's logged, sent to the model or saved.
                    val reason = AiProviderErrors.redactSecrets(e.message ?: "The ${toolCall.name} tool failed.")
                    Log.w(TAG, "Tool ${toolCall.name} failed: $reason")
                    mapOf("success" to false, "error" to reason)
                }
                val resultJson = json.encodeToString(
                    JsonElement.serializer(),
                    mapToJsonElement(result),
                )

                val toolMsg = ChatMessage(
                    role = ChatRole.TOOL,
                    content = resultJson,
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                )
                append(provider.id, history, toolMsg)
            }

            // Send follow-up with updated history
            val messagesWithSystem = requestMessages(history)

            currentResponse = try {
                provider.chat(messagesWithSystem, tools, config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logProviderFailure(provider.id, e)
                // Shown and kept, as desktop does (`app.js` ~L3020).
                val failure = "I encountered an error while processing. Please try again."
                append(provider.id, history, ChatMessage(role = ChatRole.ASSISTANT, content = failure))
                return failure
            }
        }

        // Add final assistant message to history
        val finalContent = ChatContent.convertPlainTextToCards(currentResponse.content)
        append(provider.id, history, ChatMessage(role = ChatRole.ASSISTANT, content = finalContent))
        return finalContent
    }

    /** Returns only USER and ASSISTANT messages for display in the UI. */
    suspend fun getDisplayMessages(providerId: String): List<ChatMessage> {
        val history = historyFor(providerId)
        return historyLock.withLock {
            history.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        }
    }

    /** Returns the full conversation history including tool messages. */
    suspend fun getFullHistory(providerId: String): List<ChatMessage> {
        val history = historyFor(providerId)
        return historyLock.withLock { history.toList() }
    }

    /** Clear history for a specific provider. */
    suspend fun clearHistory(providerId: String) {
        historyLock.withLock {
            histories.remove(providerId)
            historyStore.clearByProvider(providerId)
        }
    }

    /** Clear all provider histories. */
    suspend fun clearAllHistories() {
        historyLock.withLock {
            histories.clear()
            historyStore.clearAll()
        }
    }

    /**
     * Trim history to MAX_HISTORY_LENGTH.
     * Keeps the first message (often important context) and the last (N-1) messages.
     * Never splits an assistant+tool_calls message from its corresponding tool result
     * messages — OpenAI requires tool messages to follow their tool_calls message.
     */
    private fun trimHistory(history: MutableList<ChatMessage>) {
        if (history.size <= MAX_HISTORY_LENGTH) return

        val first = history.first()
        // Find a safe cut index in the tail — walk backward to find a boundary
        // that isn't inside a tool-call sequence.
        var cutIndex = history.size - (MAX_HISTORY_LENGTH - 1)
        // If the message at cutIndex is a TOOL message, we'd orphan it.
        // Walk backward to include the preceding ASSISTANT+tool_calls message.
        while (cutIndex > 1 && history[cutIndex].role == ChatRole.TOOL) {
            cutIndex--
        }
        // If we landed on an ASSISTANT message with tool_calls, its TOOL results
        // follow — include them all. But if that pushes us to keep too many, just
        // drop the whole tool-call group by skipping past it.
        if (cutIndex > 1 &&
            history[cutIndex].role == ChatRole.ASSISTANT &&
            !history[cutIndex].toolCalls.isNullOrEmpty()
        ) {
            // Skip past this assistant+tools group
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

    /**
     * Remove orphaned TOOL messages from the start of a loaded history.
     * These can appear when DB pruning (deleteOlderThan) removes the preceding
     * ASSISTANT message with tool_calls but leaves the tool result messages.
     */
    private fun sanitizeHistory(history: MutableList<ChatMessage>) {
        // Drop leading TOOL messages that have no preceding ASSISTANT with tool_calls
        while (history.isNotEmpty() && history.first().role == ChatRole.TOOL) {
            history.removeAt(0)
        }
        // Scan for orphaned TOOL messages in the middle of the history
        var i = 0
        while (i < history.size) {
            if (history[i].role == ChatRole.TOOL) {
                // Check that the nearest preceding ASSISTANT message has matching tool_calls
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

    /**
     * What the chat says for a failed provider call (shared
     * [AiProviderErrors.describe], desktop's `describeProviderError`), after
     * logging the failure.
     */
    private fun formatProviderError(providerId: String, e: Exception): String {
        logProviderFailure(providerId, e)
        return AiProviderErrors.describe(e.message ?: "Unknown error")
    }

    /**
     * The raw failure at warn level: provider, HTTP status and the provider's
     * own message, so the real cause (a quota, a bad model, a real rate
     * limit) can be read in the device log. Credentials are redacted: Gemini
     * puts its key in the URL, and a timeout's message carries the URL.
     */
    private fun logProviderFailure(providerId: String, e: Exception) {
        val detail = if (e is AiProviderHttpException) {
            "status=${e.status} message=${AiProviderErrors.redactSecrets(e.providerMessage)}"
        } else {
            "${e::class.simpleName}: ${AiProviderErrors.redactSecrets(e.message ?: "")}"
        }
        Log.w(TAG, "AI provider $providerId failed: $detail")
    }

    /** Map tool names to user-facing progress text. */
    private fun toolProgressText(name: String): String = when (name) {
        "play" -> "Playing..."
        "control" -> "Controlling playback..."
        "search" -> "Searching..."
        "queue_add" -> "Adding to queue..."
        "queue_clear" -> "Clearing queue..."
        "create_playlist" -> "Creating playlist..."
        "shuffle" -> "Toggling shuffle..."
        "block_recommendation" -> "Updating blocklist..."
        else -> "Processing..."
    }
}

/**
 * Where [AiChatService] keeps each provider's messages: the app's
 * [ChatMessageDao] (SQLDelight), or a fake in tests.
 */
interface ChatHistoryStore {
    suspend fun deleteOlderThan(cutoffMillis: Long)
    suspend fun getByProvider(providerId: String): List<ChatMessageRecord>
    suspend fun insert(record: ChatMessageRecord)
    suspend fun clearByProvider(providerId: String)
    suspend fun clearAll()
}

private fun ChatMessageDao.asChatHistoryStore(): ChatHistoryStore = object : ChatHistoryStore {
    override suspend fun deleteOlderThan(cutoffMillis: Long) = this@asChatHistoryStore.deleteOlderThan(cutoffMillis)
    override suspend fun getByProvider(providerId: String) = this@asChatHistoryStore.getByProvider(providerId)
    override suspend fun insert(record: ChatMessageRecord) { this@asChatHistoryStore.insert(record) }
    override suspend fun clearByProvider(providerId: String) = this@asChatHistoryStore.clearByProvider(providerId)
    override suspend fun clearAll() = this@asChatHistoryStore.clearAll()
}

// ── Entity ↔ ChatMessage Mapping ────────────────────────────────────

private fun ChatMessageRecord.toChatMessage(): ChatMessage {
    val toolCalls = toolCallsJson?.let { jsonStr ->
        try {
            val json = Json { ignoreUnknownKeys = true }
            val array = json.parseToJsonElement(jsonStr) as? JsonArray
            array?.map { element ->
                val obj = element as JsonObject
                val id = (obj["id"] as? JsonPrimitive)?.content ?: ""
                val name = (obj["name"] as? JsonPrimitive)?.content ?: ""
                val argsObj = obj["arguments"] as? JsonObject
                val args = argsObj?.let { jsonElementToMap(it) } ?: emptyMap()
                ToolCall(id = id, name = name, arguments = args)
            }
        } catch (_: Exception) {
            null
        }
    }

    return ChatMessage(
        role = ChatRole.valueOf(role),
        content = content,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        toolName = toolName,
    )
}

private fun ChatMessage.toEntity(providerId: String): ChatMessageRecord {
    val toolCallsJsonStr = toolCalls?.let { calls ->
        val array = buildJsonArray {
            for (call in calls) {
                add(buildJsonObject {
                    put("id", JsonPrimitive(call.id))
                    put("name", JsonPrimitive(call.name))
                    put("arguments", mapToJsonElement(call.arguments))
                })
            }
        }
        Json.encodeToString(JsonElement.serializer(), array)
    }

    return ChatMessageRecord(
        providerId = providerId,
        role = role.name,
        content = content,
        toolCallsJson = toolCallsJsonStr,
        toolCallId = toolCallId,
        toolName = toolName,
        // MUST stamp the real time — ChatMessageRecord defaults to 0L, and
        // historyFor() prunes timestamp < now-30d on every load, so a 0
        // timestamp means the message is deleted on the next load (history is
        // never remembered across sessions, on either platform).
        timestamp = currentTimeMillis(),
    )
}
