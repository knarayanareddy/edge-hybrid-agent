package com.edgehybrid.agent.core.inference

import kotlinx.coroutines.flow.Flow

/**
 * Common abstraction for all inference backends.
 *
 * Both local (LiteRT) and remote (OpenRouter, Gemini, Groq) engines
 * implement this interface. The JEV router selects which engine to
 * dispatch to based on intent classification.
 */
interface InferenceEngine {

    /** Human-readable engine name for logging and UI display. */
    val engineName: String

    /** Whether this engine requires an active network connection. */
    val requiresNetwork: Boolean

    /**
     * Generate a streaming response for the given conversation.
     *
     * @param messages  Full conversation history in OpenAI-compatible format.
     * @param tools     Optional tool definitions for function calling.
     * @param config    Generation parameters (temperature, max_tokens, etc.).
     * @return A [Flow] that emits token-by-token [StreamChunk] events.
     */
    fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        config: GenerationConfig = GenerationConfig(),
    ): Flow<StreamChunk>

    /**
     * Non-streaming single-shot completion. Used for tool calls
     * and JEV evaluations where streaming is unnecessary.
     */
    suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        config: GenerationConfig = GenerationConfig(),
    ): CompletionResult

    /** Check if the engine is currently available and configured. */
    suspend fun isAvailable(): Boolean
}

/**
 * A single message in the conversation.
 * Follows the OpenAI chat completions message schema.
 */
data class ChatMessage(
    val role: String,           // "system" | "user" | "assistant" | "tool"
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    /** Base64-encoded image data for multimodal messages. */
    val imageData: String? = null,
    val imageMimeType: String? = null,
)

/**
 * A tool call requested by the model.
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

data class FunctionCall(
    val name: String,
    val arguments: String, // JSON string
)

/**
 * Tool definition following the OpenAI tools schema.
 * Edge Gallery SKILL.md definitions are converted to this format.
 */
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction,
)

data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>, // JSON Schema
)

/**
 * Configuration for text generation.
 */
data class GenerationConfig(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val topP: Float = 0.95f,
    val frequencyPenalty: Float = 0f,
    val presencePenalty: Float = 0f,
    val stop: List<String>? = null,
    /** Force JSON output mode if supported by the provider. */
    val jsonMode: Boolean = false,
)

/**
 * A single chunk in a streaming response.
 */
sealed class StreamChunk {
    /** A text delta (partial token). */
    data class TextDelta(val text: String) : StreamChunk()

    /** The model is requesting a tool call. */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val functionName: String?,
        val argumentsDelta: String?,
    ) : StreamChunk()

    /** Stream finished. Contains final usage stats. */
    data class Done(
        val finishReason: String,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    ) : StreamChunk()

    /** An error occurred during streaming. */
    data class Error(val message: String, val isRetryable: Boolean = false) : StreamChunk()
}

/**
 * Result of a non-streaming completion.
 */
data class CompletionResult(
    val content: String?,
    val toolCalls: List<ToolCall>?,
    val finishReason: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val model: String = "",
)
