package com.parachord.shared.ai.providers

import com.parachord.shared.ai.AiChatProvider
import com.parachord.shared.ai.AiProviderHttpException
import com.parachord.shared.ai.AiChatResponse
import com.parachord.shared.ai.AiProviderConfig
import com.parachord.shared.ai.ChatMessage
import com.parachord.shared.ai.ChatRole
import com.parachord.shared.ai.DjToolDefinition
import com.parachord.shared.ai.ToolCall
import com.parachord.shared.ai.jsonElementToMap
import com.parachord.shared.ai.mapToJsonElement
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val DEFAULT_MODEL = "claude-sonnet-4-6-20250320"
private const val DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val MAX_TOKENS = 4096

/**
 * AI chat provider for Anthropic's Claude API.
 * Handles the Claude-specific message format where system prompts are top-level,
 * tool calls use `tool_use` content blocks, and tool results are user messages
 * with `tool_result` content blocks.
 *
 * Migrated from OkHttp to the shared Ktor HttpClient. The shared client
 * already runs with a 60s `requestTimeoutMillis` (see HttpClientFactory)
 * which covers the long AI generation latencies — no per-request timeout
 * override needed.
 */
class ClaudeProvider(
    private val httpClient: HttpClient,
    private val json: Json,
) : AiChatProvider {

    override val id: String = "claude"
    override val name: String = "Claude"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse {
        val apiKey = config.apiKey.ifBlank {
            throw Exception("Claude API key not configured")
        }
        val model = config.model.ifBlank { DEFAULT_MODEL }
        val endpoint = config.endpoint.ifBlank { DEFAULT_ENDPOINT }

        val requestBody = buildRequestBody(messages, tools, model)
        val requestJson = json.encodeToString(JsonObject.serializer(), requestBody)

        val response = httpClient.post(endpoint) {
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            contentType(ContentType.Application.Json)
            setBody(requestJson)
        }
        val responseBody = response.bodyAsText()

        if (!response.status.isSuccess()) {
            throw AiProviderHttpException(
                providerId = id,
                status = response.status.value,
                providerMessage = responseBody.take(500),
                message = parseError(response.status.value, responseBody),
            )
        }

        return parseResponse(responseBody)
    }

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        model: String,
    ): JsonObject {
        // Extract system prompt from messages (Claude uses a top-level system field)
        var systemPrompt: String? = null
        val apiMessages = mutableListOf<JsonObject>()

        for (msg in messages) {
            when (msg.role) {
                ChatRole.SYSTEM -> {
                    systemPrompt = msg.content
                }
                ChatRole.USER -> {
                    apiMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", msg.content)
                    })
                }
                ChatRole.ASSISTANT -> {
                    val toolCalls = msg.toolCalls
                    if (toolCalls.isNullOrEmpty()) {
                        // Plain text response
                        apiMessages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", msg.content)
                        })
                    } else {
                        // Assistant message with tool_use blocks
                        val contentBlocks = buildJsonArray {
                            if (msg.content.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                            }
                            for (tc in toolCalls) {
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("input", mapToJsonElement(tc.arguments))
                                })
                            }
                        }
                        apiMessages.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", contentBlocks)
                        })
                    }
                }
                ChatRole.TOOL -> {
                    // Tool results go as user messages with tool_result content blocks
                    apiMessages.add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", msg.toolCallId ?: "")
                                put("content", msg.content)
                            })
                        })
                    })
                }
            }
        }

        return buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            if (systemPrompt != null) {
                put("system", systemPrompt)
            }
            put("messages", JsonArray(apiMessages))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters?.let { put("input_schema", it) }
                        })
                    }
                })
            }
        }
    }

    private fun parseResponse(responseBody: String): AiChatResponse {
        val responseObj = json.parseToJsonElement(responseBody).jsonObject
        val contentBlocks = responseObj["content"]?.jsonArray ?: return AiChatResponse(content = "")

        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()

        for (block in contentBlocks) {
            val blockObj = block.jsonObject
            when (blockObj["type"]?.jsonPrimitive?.content) {
                "text" -> {
                    val text = blockObj["text"]?.jsonPrimitive?.content ?: ""
                    if (text.isNotBlank()) textParts.add(text)
                }
                "tool_use" -> {
                    val id = blockObj["id"]?.jsonPrimitive?.content ?: ""
                    val name = blockObj["name"]?.jsonPrimitive?.content ?: ""
                    val input = blockObj["input"]?.jsonObject ?: JsonObject(emptyMap())
                    toolCalls.add(
                        ToolCall(
                            id = id,
                            name = name,
                            arguments = jsonElementToMap(input),
                        )
                    )
                }
            }
        }

        return AiChatResponse(
            content = textParts.joinToString("\n"),
            toolCalls = toolCalls.ifEmpty { null },
        )
    }

    private fun parseError(statusCode: Int, responseBody: String): String {
        return try {
            val errorObj = json.parseToJsonElement(responseBody).jsonObject
            val errorType = errorObj["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content
            val errorMessage = errorObj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content

            when {
                errorType == "authentication_error" ->
                    "Invalid API key. Please check your Claude API key in settings."
                statusCode == 429 ->
                    "Rate limit reached. Please wait a moment and try again."
                statusCode == 529 ->
                    "Claude is currently overloaded. Please try again in a few moments."
                errorMessage != null -> errorMessage
                else -> "Claude API error ($statusCode)"
            }
        } catch (_: Exception) {
            "Claude API error ($statusCode)"
        }
    }
}
