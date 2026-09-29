package com.edgehybrid.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory providing configured Ktor HTTP clients adhering to MCP protocol version 2025-03-26.
 */
@Singleton
class KtorMcpTransportFactory @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val currentSessionId = AtomicReference<String?>(null)

    fun updateSessionId(sessionId: String) {
        currentSessionId.set(sessionId)
    }

    fun getSessionId(): String? = currentSessionId.get()

    fun createClient(config: McpServerConfig): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000L
                connectTimeoutMillis = 10_000L
                socketTimeoutMillis = 30_000L
            }
            defaultRequest {
                contentType(ContentType.Application.Json)
                header("MCP-Protocol-Version", "2025-03-26")
                val session = currentSessionId.get()
                if (!session.isNullOrBlank()) {
                    header("Mcp-Session-Id", session)
                }
                if (!config.bearerToken.isNullOrBlank()) {
                    header("Authorization", "Bearer ${config.bearerToken}")
                }
                config.headers.forEach { (k, v) ->
                    header(k, v)
                }
            }
        }
    }
}
