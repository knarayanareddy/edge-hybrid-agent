package com.edgehybrid.agent.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object ChatRoles {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    val imageDataUrl: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ModelToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
)

@Serializable
data class ModelToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: JsonObject
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
internal data class ApiChatMessage(
    val role: String,
    val content: kotlinx.serialization.json.JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ApiToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
)

@Serializable
internal data class ApiToolCall(
    val id: String,
    val type: String,
    val function: ApiToolCallFunction
)

@Serializable
internal data class ApiToolCallFunction(
    val name: String,
    val arguments: String
)

internal fun ChatMessage.toApiMessage(): ApiChatMessage {
    val serializedContent: kotlinx.serialization.json.JsonElement? = when {
        imageDataUrl != null -> kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", "text")
                put("text", content ?: "")
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", "image_url")
                put("image_url", kotlinx.serialization.json.buildJsonObject {
                    put("url", imageDataUrl)
                })
            })
        }
        content != null -> kotlinx.serialization.json.JsonPrimitive(content)
        else -> null
    }

    return ApiChatMessage(
        role = role,
        content = serializedContent,
        name = name,
        toolCalls = toolCalls?.map { call ->
            ApiToolCall(
                id = call.id,
                type = call.type,
                function = ApiToolCallFunction(
                    name = call.function.name,
                    arguments = call.function.arguments.toString()
                )
            )
        },
        toolCallId = toolCallId
    )
}

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val stream: Boolean = true,
    @SerialName("stream_options")
    val streamOptions: StreamOptions = StreamOptions(includeUsage = true),
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = 4096
)

@Serializable
internal data class StreamOptions(
    @SerialName("include_usage")
    val includeUsage: Boolean
)

@Serializable
internal data class ProviderUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null
)