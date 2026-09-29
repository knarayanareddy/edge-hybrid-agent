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
    val content: String? = null,
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

internal fun ChatMessage.toApiMessage(): ApiChatMessage =
    ApiChatMessage(
        role = role,
        content = content,
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

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val stream: Boolean = true,
    @SerialName("stream_options")
    val streamOptions: StreamOptions = StreamOptions(includeUsage = true),
    @SerialName("tool_choice")
    val toolChoice: String? = null
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