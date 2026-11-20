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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AI chat provider for OpenAI's ChatGPT (`/v1/chat/completions`).
 *
 * Migrated from OkHttp to the shared Ktor HttpClient. The shared client
 * already runs with a 60s `requestTimeoutMillis` (see HttpClientFactory)
 * which covers the long AI generation latencies — no per-request timeout
 * override needed.
 */
class ChatGptProvider(
    private val httpClient: HttpClient,
    private val json: Json,
) : AiChatProvider {

    override val id: String = "chatgpt"
    override val name: String = "ChatGPT"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse {
        val endpoint = config.endpoint.ifBlank { "https://api.openai.com/v1/chat/completions" }
        val model = config.model.ifBlank { "gpt-4o-mini" }

        // Detect if the system prompt requests JSON-only output and enable structured output mode
        val wantsJson = messages.any { it.role == ChatRole.SYSTEM && it.content.contains("JSON", ignoreCase = true) && it.content.contains("no markdown", ignoreCase = true) }

        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                for (msg in messages) {
                    add(messageToJson(msg))
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in tools) {
                        add(toolToJson(tool))
                    }
                })
            }
            if (wantsJson && tools.isEmpty()) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }

        val response = httpClient.post(endpoint) {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        val responseBody = response.bodyAsText()

        if (!response.status.isSuccess()) {
            val errorMessage = try {
                val errorJson = json.parseToJsonElement(responseBody).jsonObject
                errorJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: responseBody
            } catch (_: Exception) {
                responseBody
            }
            throw AiProviderHttpException(
                providerId = id,
                status = response.status.value,
                providerMessage = errorMessage,
                message = "ChatGPT API error (${response.status.value}): $errorMessage",
            )
        }

        val responseJson = json.parseToJsonElement(responseBody).jsonObject
        val choice = responseJson["choices"]!!.jsonArray[0].jsonObject
        val message = choice["message"]!!.jsonObject

        val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val toolCalls = message["tool_calls"]?.jsonArray?.map { tc ->
            val tcObj = tc.jsonObject
            val function = tcObj["function"]!!.jsonObject
            val argsStr = function["arguments"]!!.jsonPrimitive.content
            val argsMap = try {
                jsonElementToMap(json.parseToJsonElement(argsStr))
            } catch (_: Exception) {
                emptyMap()
            }
            ToolCall(
                id = tcObj["id"]!!.jsonPrimitive.content,
                name = function["name"]!!.jsonPrimitive.content,
                arguments = argsMap,
            )
        }

        return AiChatResponse(
            content = content,
            toolCalls = toolCalls?.takeIf { it.isNotEmpty() },
        )
    }

    private fun messageToJson(msg: ChatMessage): JsonObject = buildJsonObject {
        when (msg.role) {
            ChatRole.USER -> {
                put("role", "user")
                put("content", msg.content)
            }
            ChatRole.SYSTEM -> {
                put("role", "system")
                put("content", msg.content)
            }
            ChatRole.ASSISTANT -> {
                put("role", "assistant")
                val calls = msg.toolCalls
                if (calls != null && calls.isNotEmpty()) {
                    put("content", JsonNull)
                    put("tool_calls", buildJsonArray {
                        for (tc in calls) {
                            add(buildJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", json.encodeToString(
                                        JsonElement.serializer(),
                                        mapToJsonElement(tc.arguments),
                                    ))
                                })
                            })
                        }
                    })
                } else {
                    put("content", msg.content)
                }
            }
            ChatRole.TOOL -> {
                put("role", "tool")
                put("tool_call_id", msg.toolCallId ?: "")
                put("content", msg.content)
            }
        }
    }

    private fun toolToJson(tool: DjToolDefinition): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            tool.parameters?.let { put("parameters", it) }
        })
    }
}
