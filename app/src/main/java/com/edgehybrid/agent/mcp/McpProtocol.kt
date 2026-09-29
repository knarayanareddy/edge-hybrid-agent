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
