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
import com.parachord.shared.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Google Gemini provider using Google AI API.
 * Mirrors desktop's gemini.axe chat implementation.
 *
 * Key differences:
 * - API key in URL query parameter, not header
 * - System prompt as `system_instruction`
 * - Roles: `user`/`model` (not `assistant`)
 * - Tool calls as `functionCall` parts, results as `functionResponse`
 *
 * Migrated from OkHttp to the shared Ktor HttpClient. The shared client
 * already runs with a 60s `requestTimeoutMillis` (see HttpClientFactory)
 * which covers the long AI generation latencies — no per-request timeout
 * override needed.
 */
class GeminiProvider(
    private val httpClient: HttpClient,
    private val json: Json,
) : AiChatProvider {

    override val id = "gemini"
    override val name = "Google Gemini"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse {
        if (config.apiKey.isBlank()) throw Exception("Google AI API key not configured")

        val model = config.model.ifBlank { "gemini-2.5-flash" }

        // Convert messages to Gemini format
        var systemInstruction: JsonElement? = null
        val contents = buildJsonArray {
            for (msg in messages) {
                when (msg.role) {
                    ChatRole.SYSTEM -> {
                        systemInstruction = buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", msg.content) })
                            })
                        }
                    }
                    ChatRole.USER -> add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", msg.content) })
                        })
                    })
                    ChatRole.ASSISTANT -> add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            // Include text part only if there's actual text content
                            if (msg.content.isNotBlank()) {
                                add(buildJsonObject { put("text", msg.content) })
                            }
                            // Echo back functionCall parts so Gemini sees its own tool calls
                            msg.toolCalls?.forEach { toolCall ->
                                add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", toolCall.name)
                                        put("args", mapToJsonElement(toolCall.arguments))
                                    })
                                })
                            }
                            // If no text and no tool calls, send empty text to avoid empty parts array
                            if (msg.content.isBlank() && msg.toolCalls.isNullOrEmpty()) {
                                add(buildJsonObject { put("text", "") })
                            }
                        })
                    })
                    ChatRole.TOOL -> add(buildJsonObject {
                        put("role", "function")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", msg.toolName ?: msg.toolCallId ?: "")
                                    put("response", buildJsonObject {
                                        // Parse result as JSON object; fall back to wrapping as string
                                        val parsed = try {
                                            json.parseToJsonElement(msg.content)
                                        } catch (_: Exception) {
                                            buildJsonObject { put("result", msg.content) }
                                        }
                                        put("content", parsed)
                                    })
                                })
                            })
                        })
                    })
                }
            }
        }

        // Convert tools to Gemini format
        val geminiTools = buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", buildJsonArray {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters?.let { put("parameters", it) }
                        })
                    }
                })
            })
        }

        // Detect if the system prompt requests JSON-only output and enable structured output mode
        val wantsJson = messages.any { it.role == ChatRole.SYSTEM && it.content.contains("JSON", ignoreCase = true) && it.content.contains("no markdown", ignoreCase = true) }

        val body = buildJsonObject {
            put("contents", contents)
            put("generationConfig", buildJsonObject {
                put("temperature", 0.7)
                if (wantsJson && tools.isEmpty()) {
                    put("responseMimeType", "application/json")
                }
            })
            systemInstruction?.let { put("system_instruction", it) }
            if (tools.isNotEmpty()) put("tools", geminiTools)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${config.apiKey}"
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        val responseBody = response.bodyAsText()

        if (!response.status.isSuccess()) {
            val errorData = try { json.parseToJsonElement(responseBody).jsonObject } catch (_: Exception) { null }
            val errorMsg = errorData?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: "Gemini API error: ${response.status.value}"
            throw AiProviderHttpException(
                providerId = id,
                status = response.status.value,
                providerMessage = errorMsg,
                message = errorMsg,
            )
        }

        val data = json.parseToJsonElement(responseBody).jsonObject

        // Check for prompt-level safety blocks (no candidates returned)
        val promptFeedback = data["promptFeedback"]?.jsonObject
        val blockReason = promptFeedback?.get("blockReason")?.jsonPrimitive?.content
        if (blockReason != null) {
            throw Exception("Gemini blocked the request: $blockReason")
        }

        val candidate = data["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw Exception("Gemini returned no response candidates")

        // Check candidate-level finish reason for safety blocks
        val finishReason = candidate["finishReason"]?.jsonPrimitive?.content
        if (finishReason == "SAFETY") {
            throw Exception("Gemini blocked the response due to safety filters")
        }

        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: buildJsonArray {}

        var content = ""
        val toolCalls = mutableListOf<ToolCall>()

        for (part in parts) {
            val partObj = part.jsonObject
            partObj["text"]?.jsonPrimitive?.content?.let { content += it }
            partObj["functionCall"]?.jsonObject?.let { fc ->
                toolCalls.add(ToolCall(
                    id = "call_${currentTimeMillis()}_${toolCalls.size}",
                    name = fc["name"]?.jsonPrimitive?.content ?: "",
                    arguments = jsonElementToMap(fc["args"] ?: buildJsonObject {}),
                ))
            }
        }

        return AiChatResponse(content = content, toolCalls = toolCalls.takeIf { it.isNotEmpty() })
    }
}
