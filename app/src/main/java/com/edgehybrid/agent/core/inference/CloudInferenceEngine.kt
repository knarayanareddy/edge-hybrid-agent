package com.edgehybrid.agent.core.inference

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * OpenRouter-compatible cloud inference engine.
 *
 * Handles:
 * - SSE (Server-Sent Events) token streaming
 * - Tool/function calling with OpenAI-compatible schema
 * - Multimodal payloads (text + base64 images)
 * - Automatic retry on transient failures
 *
 * Compatible with: OpenRouter, OpenAI, Groq, Google AI Studio,
 * and any provider exposing /v1/chat/completions.
 */
class CloudInferenceEngine(
    private val apiKey: String,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    private val modelId: String = "google/gemini-2.5-flash",
    private val appName: String = "EdgeHybridAgent",
) : InferenceEngine {

    override val engineName: String = "Cloud ($modelId)"
    override val requiresNetwork: Boolean = true

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(this@CloudInferenceEngine.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000
        }
    }

    /**
     * Stream tokens via SSE from the /chat/completions endpoint.
     */
    override fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        config: GenerationConfig,
    ): Flow<StreamChunk> = flow {
        val requestBody = buildRequestBody(messages, tools, config, stream = true)

        try {
            client.preparePost("$baseUrl/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("HTTP-Referer", "https://edgehybridagent.app")
                header("X-Title", appName)
                setBody(requestBody.toString())
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    val errorBody = response.bodyAsText()
                    emit(StreamChunk.Error(
                        "API error ${response.status.value}: $errorBody",
                        isRetryable = response.status.value in listOf(429, 500, 502, 503)
                    ))
                    return@execute
                }

                val channel: ByteReadChannel = response.bodyAsChannel()
                val buffer = StringBuilder()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break

                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") {
                            emit(StreamChunk.Done(finishReason = "stop"))
                            break
                        }

                        try {
                            val chunk = json.parseToJsonElement(data).jsonObject
                            val choices = chunk["choices"]?.jsonArray ?: continue
                            if (choices.isEmpty()) continue

                            val choice = choices[0].jsonObject
                            val delta = choice["delta"]?.jsonObject ?: continue
                            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

                            // Text content delta
                            val contentDelta = delta["content"]?.jsonPrimitive?.contentOrNull
                            if (contentDelta != null) {
                                emit(StreamChunk.TextDelta(contentDelta))
                            }

                            // Tool call delta
                            val toolCallDeltas = delta["tool_calls"]?.jsonArray
                            toolCallDeltas?.forEachIndexed { idx, tcElement ->
                                val tc = tcElement.jsonObject
                                emit(StreamChunk.ToolCallDelta(
                                    index = tc["index"]?.jsonPrimitive?.int ?: idx,
                                    id = tc["id"]?.jsonPrimitive?.contentOrNull,
                                    functionName = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull,
                                    argumentsDelta = tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull,
                                ))
                            }

                            // Stream finished
                            if (finishReason != null) {
                                val usage = chunk["usage"]?.jsonObject
                                emit(StreamChunk.Done(
                                    finishReason = finishReason,
                                    promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0,
                                    completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0,
                                ))
                                break
                            }
                        } catch (e: Exception) {
                            // Skip malformed SSE chunks silently
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit(StreamChunk.Error(
                "Network error: ${e.message}",
                isRetryable = true
            ))
        }
    }

    /**
     * Non-streaming completion for tool calls and JEV evaluations.
     */
    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        config: GenerationConfig,
    ): CompletionResult {
        val requestBody = buildRequestBody(messages, tools, config, stream = false)

        val response = client.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            header("HTTP-Referer", "https://edgehybridagent.app")
            header("X-Title", appName)
            setBody(requestBody.toString())
        }

        val responseText = response.bodyAsText()
        val responseJson = json.parseToJsonElement(responseText).jsonObject

        val choices = responseJson["choices"]?.jsonArray
            ?: return CompletionResult(content = null, toolCalls = null, finishReason = "error")

        val choice = choices[0].jsonObject
        val message = choice["message"]?.jsonObject
            ?: return CompletionResult(content = null, toolCalls = null, finishReason = "error")

        val content = message["content"]?.jsonPrimitive?.contentOrNull
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "stop"

        // Parse tool calls
        val toolCalls = message["tool_calls"]?.jsonArray?.map { tcElement ->
            val tc = tcElement.jsonObject
            ToolCall(
                id = tc["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                type = tc["type"]?.jsonPrimitive?.contentOrNull ?: "function",
                function = FunctionCall(
                    name = tc["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                    arguments = tc["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content,
                )
            )
        }

        val usage = responseJson["usage"]?.jsonObject
        return CompletionResult(
            content = content,
            toolCalls = toolCalls,
            finishReason = finishReason,
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0,
            model = responseJson["model"]?.jsonPrimitive?.contentOrNull ?: modelId,
        )
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            apiKey.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    // ─── Request Body Builder ────────────────────────────────────────

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        config: GenerationConfig,
        stream: Boolean,
    ): JsonObject {
        return buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("top_p", config.topP)

            if (config.frequencyPenalty != 0f) put("frequency_penalty", config.frequencyPenalty)
            if (config.presencePenalty != 0f) put("presence_penalty", config.presencePenalty)
            if (config.jsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            config.stop?.let { stops ->
                putJsonArray("stop") { stops.forEach { add(it) } }
            }

            // Messages array
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)

                        // Multimodal content (text + image)
                        if (msg.imageData != null && msg.content != null) {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                }
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:${msg.imageMimeType ?: "image/jpeg"};base64,${msg.imageData}")
                                    }
                                }
                            }
                        } else {
                            msg.content?.let { put("content", it) }
                        }

                        // Tool call results
                        msg.toolCallId?.let { put("tool_call_id", it) }
                        msg.name?.let { put("name", it) }

                        // Assistant tool call requests
                        msg.toolCalls?.let { calls ->
                            putJsonArray("tool_calls") {
                                calls.forEach { tc ->
                                    addJsonObject {
                                        put("id", tc.id)
                                        put("type", tc.type)
                                        putJsonObject("function") {
                                            put("name", tc.function.name)
                                            put("arguments", tc.function.arguments)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Tools (function calling definitions)
            tools?.let { toolDefs ->
                putJsonArray("tools") {
                    toolDefs.forEach { tool ->
                        addJsonObject {
                            put("type", tool.type)
                            putJsonObject("function") {
                                put("name", tool.function.name)
                                put("description", tool.function.description)
                                // Parameters as raw JSON
                                put("parameters", json.encodeToJsonElement(tool.function.parameters))
                            }
                        }
                    }
                }
            }
        }
    }
}
