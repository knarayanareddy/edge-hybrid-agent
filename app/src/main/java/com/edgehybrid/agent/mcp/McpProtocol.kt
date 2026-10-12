package com.edgehybrid.agent.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject? = null
)

@Serializable
data class McpCallToolResult(
    val content: List<McpContentItem> = emptyList(),
    val isError: Boolean = false
)

@Serializable
data class McpContentItem(
    val type: String = "text",
    val text: String? = null
)

// ---- MCP 1.0 Resources specification ----

@Serializable
data class McpResource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)

@Serializable
data class McpResourceContent(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null
)

// ---- MCP 1.0 Prompts specification ----

@Serializable
data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false
)

@Serializable
data class McpPrompt(
    val name: String,
    val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList()
)

@Serializable
data class McpPromptMessage(
    val role: String,
    val content: McpContentItem
)

@Serializable
data class McpGetPromptResult(
    val description: String? = null,
    val messages: List<McpPromptMessage> = emptyList()
)
