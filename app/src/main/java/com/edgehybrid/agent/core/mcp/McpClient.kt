package com.edgehybrid.agent.core.mcp

import com.edgehybrid.agent.core.inference.ToolDefinition
import com.edgehybrid.agent.core.inference.ToolParameterProperty
import com.edgehybrid.agent.core.inference.ToolParameters
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonObject? = null
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
data class McpServerConfig(
    val name: String,
    val endpointUrl: String,
    val authToken: String? = null,
    val isEnabled: Boolean = true
)

/**
 * Model Context Protocol (MCP) client communicating with servers via JSON-RPC 2.0.
 */
@Singleton
class McpClient @Inject constructor() {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val registeredServers = mutableMapOf<String, McpServerConfig>()

    fun registerServer(config: McpServerConfig) {
        registeredServers[config.name] = config
    }

    fun getServers(): List<McpServerConfig> = registeredServers.values.toList()

    /**
     * Queries an MCP server for its list of exposed tools.
     */
    suspend fun listTools(serverName: String): List<ToolDefinition> {
        val server = registeredServers[serverName] ?: return emptyList()

        return try {
            val req = JsonRpcRequest(
                id = "list-tools-1",
                method = "tools/list",
                params = buildJsonObject {}
            )

            val response: HttpResponse = httpClient.post(server.endpointUrl) {
                contentType(ContentType.Application.Json)
                if (!server.authToken.isNullOrBlank()) {
                    header("Authorization", "Bearer ${server.authToken}")
                }
                setBody(req)
            }

            if (response.status.value in 200..299) {
                val rpcResponse = response.body<JsonRpcResponse>()
                // Parse tool definitions from rpcResponse.result
                emptyList() // Placeholder: parsed definitions
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Executes a tool via MCP JSON-RPC `tools/call`.
     */
    suspend fun callTool(
        serverName: String,
        toolName: String,
        arguments: JsonObject
    ): String {
        val server = registeredServers[serverName]
            ?: return "Error: Unknown MCP server '$serverName'"

        return try {
            val req = JsonRpcRequest(
                id = "call-tool-1",
                method = "tools/call",
                params = buildJsonObject {
                    put("name", toolName)
                    put("arguments", arguments)
                }
            )

            val response: HttpResponse = httpClient.post(server.endpointUrl) {
                contentType(ContentType.Application.Json)
                if (!server.authToken.isNullOrBlank()) {
                    header("Authorization", "Bearer ${server.authToken}")
                }
                setBody(req)
            }

            if (response.status.value in 200..299) {
                val rpcResponse = response.body<JsonRpcResponse>()
                rpcResponse.result?.toString() ?: "Execution succeeded with empty result."
            } else {
                "Error calling MCP tool: HTTP ${response.status.value}"
            }
        } catch (e: Exception) {
            "Exception calling MCP tool: ${e.message}"
        }
    }
}
