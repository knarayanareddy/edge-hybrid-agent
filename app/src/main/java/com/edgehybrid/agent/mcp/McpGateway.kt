package com.edgehybrid.agent.mcp

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Model Context Protocol (MCP) gateway coordinating discovery and dispatch across remote servers.
 */
@Singleton
class McpGateway @Inject constructor(
    private val transportFactory: KtorMcpTransportFactory
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val servers = ConcurrentHashMap<String, McpServerConfig>()

    fun registerServer(config: McpServerConfig) {
        servers[config.id] = config
    }

    fun getRegisteredServers(): List<McpServerConfig> = servers.values.toList()

    suspend fun listTools(serverId: String): Result<List<McpToolDefinition>> = withContext(Dispatchers.IO) {
        val server = servers[serverId]
            ?: return@withContext Result.failure(IllegalArgumentException("Server '$serverId' not registered"))

        val client = transportFactory.createClient(server)
        try {
            val request = JsonRpcRequest(
                id = UUID.randomUUID().toString(),
                method = "tools/list",
                params = buildJsonObject {}
            )

            val response: HttpResponse = client.post(server.baseUrl) {
                setBody(request)
            }

            // Capture session header if server provided one
            response.headers["Mcp-Session-Id"]?.let { sid ->
                transportFactory.updateSessionId(sid)
            }

            if (response.status.value in 200..299) {
                val rpcResponse = response.body<JsonRpcResponse>()
                if (rpcResponse.error != null) {
                    Result.failure(IllegalStateException("MCP error: ${rpcResponse.error.message}"))
                } else {
                    val toolsElement = (rpcResponse.result as? JsonObject)?.get("tools")
                    val tools = if (toolsElement != null) {
                        json.decodeFromJsonElement<List<McpToolDefinition>>(toolsElement)
                    } else {
                        emptyList()
                    }
                    Result.success(tools)
                }
            } else {
                Result.failure(IllegalStateException("HTTP ${response.status.value}: ${response.status.description}"))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    suspend fun callTool(
        serverId: String,
        toolName: String,
        arguments: JsonObject
    ): Result<String> = withContext(Dispatchers.IO) {
        val server = servers[serverId]
            ?: return@withContext Result.failure(IllegalArgumentException("Server '$serverId' not registered"))

        val client = transportFactory.createClient(server)
        try {
            val paramsObj = buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            }
            val request = JsonRpcRequest(
                id = UUID.randomUUID().toString(),
                method = "tools/call",
                params = paramsObj
            )

            val response: HttpResponse = client.post(server.baseUrl) {
                setBody(request)
            }

            response.headers["Mcp-Session-Id"]?.let { sid ->
                transportFactory.updateSessionId(sid)
            }

            if (response.status.value in 200..299) {
                val rpcResponse = response.body<JsonRpcResponse>()
                if (rpcResponse.error != null) {
                    Result.failure(IllegalStateException("MCP Call Error (${rpcResponse.error.code}): ${rpcResponse.error.message}"))
                } else {
                    val resultString = rpcResponse.result?.toString() ?: "{}"
                    Result.success(resultString)
                }
            } else {
                Result.failure(IllegalStateException("HTTP call error ${response.status.value}"))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }
}
