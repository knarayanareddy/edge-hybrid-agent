package com.edgehybrid.agent.tool

import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.network.SseFrameDecoder
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class McpSettings(
    val enabled: Boolean,
    val endpoint: String,
    val bearerToken: String?
) {
    init {
        if (enabled) {
            require(endpoint.isNotBlank()) {
                "MCP_ENDPOINT is required when MCP is enabled"
            }
        }
    }
}

data class McpCallResult(
    val content: String,
    val isError: Boolean
)

interface McpClient {
    suspend fun listTools(): List<ToolDefinition>

    suspend fun callTool(call: ModelToolCall): McpCallResult
}

class McpException(message: String) : RuntimeException(message)

@Singleton
class StreamableHttpMcpClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
    private val settings: McpSettings,
    private val keyStore: com.edgehybrid.agent.data.local.SecureKeyStore
) : McpClient, OverridableMcpClient {

    private val initializationMutex = Mutex()
    private val requestId = AtomicLong(0)

    @Volatile
    private var initialized = false

    @Volatile
    private var sessionId: String? = null

    /**
     * Per-call endpoint/token override, set by [McpMultiServerRouter] so one instance can
     * serve every enabled server. Null means "use the build-time settings".
     */
    @Volatile
    private var override: Override? = null

    private data class Override(val endpoint: String, val token: String?)

    /**
     * Retargets this client at [endpoint] with [token].
     *
     * Session state is reset because a session id belongs to one server; reusing it across
     * servers would produce protocol errors that look like auth failures.
     */
    override fun withOverride(endpoint: String, token: String?): Boolean {
        if (endpoint.isBlank() || !endpoint.startsWith("https://")) return false
        override = Override(endpoint, token)
        initialized = false
        sessionId = null
        return true
    }

    /** The endpoint currently in use. */
    private val activeEndpoint: String
        get() = override?.endpoint ?: settings.endpoint

    /** True when a specific server has been targeted, even if MCP is off by build default. */
    private val isActive: Boolean
        get() = override != null || settings.enabled

    override suspend fun listTools(): List<ToolDefinition> {
        if (!isActive) {
            return emptyList()
        }

        ensureInitialized()
        val result = rpc(
            method = "tools/list",
            params = buildJsonObject {}
        )

        val tools = result["tools"]?.jsonArray
            ?: throw McpException("MCP tools/list response did not contain a tools array")

        return tools.mapNotNull(::parseTool)
    }

    override suspend fun callTool(call: ModelToolCall): McpCallResult {
        if (!isActive) {
            throw McpException("MCP is disabled")
        }

        ensureInitialized()
        val result = rpc(
            method = "tools/call",
            params = buildJsonObject {
                put("name", call.function.name)
                put("arguments", call.function.arguments)
            }
        )

        val content = result["content"]?.jsonArray
            ?.mapNotNull(::renderMcpContent)
            ?.filter(String::isNotBlank)
            ?.joinToString("\n")
            ?.takeIf(String::isNotBlank)
            ?: result["structuredContent"]?.toString()
            ?: result.toString()

        return McpCallResult(
            content = content,
            isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    private suspend fun ensureInitialized() {
        if (initialized) {
            return
        }

        initializationMutex.withLock {
            if (initialized) {
                return@withLock
            }

            val response = exchange(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", requestId.incrementAndGet())
                    put("method", "initialize")
                    put("params", buildJsonObject {
                        put("protocolVersion", MCP_PROTOCOL_VERSION)
                        put("capabilities", buildJsonObject {})
                        put("clientInfo", buildJsonObject {
                            put("name", "edge-hybrid-agent")
                            put("version", "1.0.0")
                        })
                    })
                }
            )

            val envelope = parseRpcEnvelope(response.body)
            requireRpcSuccess(envelope)

            sessionId = response.sessionId ?: sessionId
            notifyInitialized()
            initialized = true
        }
    }

    private suspend fun notifyInitialized() {
        val httpResponse: HttpResponse = httpClient.post(activeEndpoint) {
            applyHeaders()
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/initialized")
                }
            )
        }
        if (httpResponse.status.value !in 200..299) {
            val detail = runCatching {
                httpResponse.bodyAsText()
            }.getOrDefault("").take(256)

            throw McpException(
                "MCP initialized notification failed with HTTP " +
                    "${httpResponse.status.value}: $detail"
            )
        }
    }

    private suspend fun rpc(
        method: String,
        params: JsonObject
    ): JsonObject {
        val response = exchange(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId.incrementAndGet())
                put("method", method)
                put("params", params)
            }
        )

        val envelope = parseRpcEnvelope(response.body)
        requireRpcSuccess(envelope)
        return envelope["result"]?.jsonObject
            ?: throw McpException("MCP response did not contain a result object")
    }

    private suspend fun exchange(payload: JsonObject): McpHttpResponse {
        val response: HttpResponse = httpClient.post(activeEndpoint) {
            applyHeaders()
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (response.status.value !in 200..299) {
            val detail = runCatching {
                response.bodyAsText()
            }.getOrDefault("")
                .replace('\n', ' ')
                .take(512)

            throw McpException(
                "MCP request failed with HTTP ${response.status.value}: $detail"
            )
        }

        val isEventStream = response.contentType()?.match(ContentType.Text.EventStream) == true
        val body = if (isEventStream) {
            readEventStreamBody(response)
        } else {
            response.bodyAsText()
        }

        return McpHttpResponse(
            body = body,
            sessionId = response.headers[MCP_SESSION_HEADER]
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders() {
        header(
            HttpHeaders.Accept,
            "application/json, text/event-stream"
        )
        header(MCP_PROTOCOL_HEADER, MCP_PROTOCOL_VERSION)
        sessionId?.let { header(MCP_SESSION_HEADER, it) }
        resolveBearerToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /**
     * Resolves the bearer token to authenticate with.
     *
     * A token the user registered at runtime lives in the encrypted keystore and takes
     * precedence; the build-time constant is only a fallback for development builds. The
     * value is never logged and never returned to the model layer.
     */
    private fun resolveBearerToken(): String? {
        // A per-server token supplied by the router takes precedence: it is the credential
        // that was actually issued for the endpoint being called.
        override?.token?.takeIf { it.isNotBlank() }?.let { return it }

        val serverName = settings.serverKey()
        val stored = runCatching { keyStore.getMcpServerToken(serverName) }.getOrNull()
        if (!stored.isNullOrBlank()) {
            return stored
        }
        return settings.bearerToken?.takeIf(String::isNotBlank)
    }

    /** Key used to look up a per-server token in the keystore. */
    private fun McpSettings.serverKey(): String = McpServerKey.forUrl(endpoint)

    private suspend fun readEventStreamBody(response: HttpResponse): String {
        val channel = response.bodyAsChannel()
        val decoder = SseFrameDecoder()
        val frames = mutableListOf<String>()

        while (true) {
            val line = channel.readUTF8Line() ?: break
            decoder.accept(line)?.let(frames::add)
        }
        decoder.close()?.let(frames::add)

        return frames.joinToString("\n")
    }

    private fun parseTool(element: JsonElement): ToolDefinition? {
        val tool = element as? JsonObject ?: return null
        val name = tool["name"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val description = tool["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val inputSchema = tool["inputSchema"] as? JsonObject
            ?: buildJsonObject {
                put("type", "object")
            }

        return ToolDefinition(
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = inputSchema
            )
        )
    }

    private fun renderMcpContent(element: JsonElement): String? {
        val content = element as? JsonObject ?: return element.toString()
        return when (content["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> content["text"]?.jsonPrimitive?.contentOrNull
            "image" -> content.toString()
            "audio" -> content.toString()
            "resource" -> content["resource"]?.toString() ?: content.toString()
            else -> content.toString()
        }
    }

    private fun parseRpcEnvelope(body: String): JsonObject {
        val trimmedBody = body.trim()
        val candidatePayloads = if (trimmedBody.startsWith("{")) {
            listOf(trimmedBody)
        } else {
            trimmedBody
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList()
        }

        candidatePayloads.forEach { payload ->
            val parsed = runCatching {
                json.parseToJsonElement(payload).jsonObject
            }.getOrNull() ?: return@forEach

            if (parsed.containsKey("result") || parsed.containsKey("error")) {
                return parsed
            }
        }

        throw McpException("MCP response did not contain a JSON-RPC result")
    }

    private fun requireRpcSuccess(envelope: JsonObject) {
        val error = envelope["error"]?.jsonObject ?: return
        val message = error["message"]?.jsonPrimitive?.contentOrNull
            ?: "Unknown MCP protocol error"
        val code = error["code"]?.jsonPrimitive?.contentOrNull
        throw McpException(if (code == null) message else "MCP error $code: $message")
    }

    private data class McpHttpResponse(
        val body: String,
        val sessionId: String?
    )

    companion object {
        private const val MCP_PROTOCOL_VERSION = "2025-03-26"
        private const val MCP_SESSION_HEADER = "Mcp-Session-Id"
        private const val MCP_PROTOCOL_HEADER = "MCP-Protocol-Version"
    }
}