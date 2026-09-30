package com.edgehybrid.agent.mcp

import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Performs a real MCP handshake against an endpoint so the UI can report whether a server
 * actually works, rather than reporting success because a toggle was flipped.
 *
 * The probe is the Android equivalent of `tools/probe_mcp.sh`: it sends
 * `initialize`, and on success follows with `tools/list` to count what the server offers.
 *
 * Deliberately separate from [StreamableHttpMcpClient] so testing a server never mutates the
 * shared session state used by live tool calls.
 */
@Singleton
class McpProbeRunner @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed class Result {
        /** Handshake succeeded. [toolCount] comes from a follow-up `tools/list`. */
        data class Ok(val serverName: String, val toolCount: Int) : Result()

        /** Endpoint exists but requires credentials (HTTP 401/403). */
        data class NeedsAuth(val detail: String) : Result()

        /** Endpoint unreachable, wrong protocol, or returned an error. */
        data class Failed(val detail: String) : Result()
    }

    suspend fun probe(endpoint: String, token: String? = null): Result =
        withContext(Dispatchers.IO) {
            // Refuse anything but https: the probe sends an Authorization header.
            if (!endpoint.startsWith("https://")) {
                return@withContext Result.Failed("Only https:// endpoints are allowed")
            }

            try {
                val initialized = post(endpoint, token, initializePayload(), sessionId = null)
                when (initialized) {
                    is Attempt.HttpFailure -> {
                        if (initialized.status == 401 || initialized.status == 403) {
                            return@withContext Result.NeedsAuth(
                                "This server requires sign-in (HTTP ${initialized.status})"
                            )
                        }
                        return@withContext Result.Failed(
                            "HTTP ${initialized.status}: ${initialized.body.take(200)}"
                        )
                    }

                    is Attempt.NetworkFailure -> {
                        return@withContext Result.Failed(
                            "Could not reach the server: ${initialized.detail}"
                        )
                    }

                    is Attempt.Failed -> {
                        return@withContext Result.Failed(initialized.detail)
                    }

                    is Attempt.Success -> {
                        val serverName = initialized.serverName
                        val tools = post(
                            endpoint,
                            token,
                            toolsListPayload(),
                            sessionId = initialized.sessionId
                        )
                        val toolCount = when (tools) {
                            is Attempt.Success -> tools.toolCount
                            else -> 0
                        }
                        return@withContext Result.Ok(serverName, toolCount)
                    }
                }
            } catch (failure: Exception) {
                Result.Failed(failure.message ?: "Probe failed")
            }
        }

    // ------------------------------------------------------------------ transport

    private sealed class Attempt {
        data class Success(
            val serverName: String,
            val sessionId: String?,
            val toolCount: Int
        ) : Attempt()

        data class HttpFailure(val status: Int, val body: String) : Attempt()
        data class NetworkFailure(val detail: String) : Attempt()
        data class Failed(val detail: String) : Attempt()
    }

    private fun initializePayload(): String = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"edge-hybrid-agent","version":"1.0.0"}}}
    """.trimIndent()

    private fun toolsListPayload(): String =
        """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""

    private fun post(
        endpoint: String,
        token: String?,
        body: String,
        sessionId: String?
    ): Attempt {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            connection.setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
            sessionId?.let { connection.setRequestProperty(SESSION_HEADER, it) }
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                return Attempt.HttpFailure(status, responseBody)
            }

            val envelope = parseEnvelope(responseBody)
                ?: return Attempt.Failed("Response was not a JSON-RPC result")

            envelope["error"]?.let { error ->
                val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                    ?: "unknown error"
                return Attempt.HttpFailure(-1, message)
            }

            val result = envelope["result"]?.jsonObject
                ?: return Attempt.Failed("Response had no result object")

            val serverName = result["serverInfo"]?.jsonObject
                ?.get("name")?.jsonPrimitive?.contentOrNull
                ?: "unknown"

            val toolCount = (result["tools"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0

            Attempt.Success(
                serverName = serverName,
                sessionId = connection.getHeaderField(SESSION_HEADER),
                toolCount = toolCount
            )
        } catch (failure: Exception) {
            Attempt.NetworkFailure(failure.message ?: failure::class.java.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /** Parses either a plain JSON body or an SSE frame carrying one. */
    private fun parseEnvelope(body: String): JsonObject? {
        val candidates = buildList {
            val trimmed = body.trim()
            if (trimmed.startsWith("{")) {
                add(trimmed)
            } else {
                addAll(
                    trimmed.lineSequence()
                        .map { it.trim() }
                        .filter { it.startsWith("data:") }
                        .map { it.removePrefix("data:").trim() }
                )
            }
        }

        candidates.forEach { candidate ->
            val parsed = runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull()
            if (parsed != null && (parsed.containsKey("result") || parsed.containsKey("error"))) {
                return parsed
            }
        }
        return null
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val SESSION_HEADER = "Mcp-Session-Id"
    }
}