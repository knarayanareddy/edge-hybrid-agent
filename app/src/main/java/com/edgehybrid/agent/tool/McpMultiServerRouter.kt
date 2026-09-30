package com.edgehybrid.agent.tool

import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.mcp.McpServerSource
import com.edgehybrid.agent.mcp.McpTokenSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Routes tool calls to whichever enabled MCP server advertises that tool name.
 *
 * Discovery and dispatch both read the live registry — the same one the Settings UI writes
 * to — so enabling a server in Settings makes its tools reachable without a restart, and
 * disabling it removes them again.
 *
 * Credential resolution per server: an OAuth access token (refreshed when expired) is
 * preferred, then a stored personal access token. A server that needs credentials and has
 * none is refused with a clear message instead of being called unauthenticated, so a 401
 * is never mistaken for a tool fault.
 */
@Singleton
class McpMultiServerRouter @Inject constructor(
    private val registry: McpServerSource,
    private val tokenSource: McpTokenSource,
    private val singleClient: McpClient
) {

    /**
     * Tool definitions grouped by the enabled server that advertised them.
     *
     * Grouping lets the caller record which server owns a tool name so a call can be
     * dispatched to that same server instead of being resolved again per call.
     *
     * An unreachable server is skipped, so one broken endpoint does not take the other
     * servers' tools offline with it.
     */
    suspend fun discoverToolsByServer(): Map<String, List<ToolDefinition>> {
        val servers = registry.enabledServers()
        if (servers.isEmpty()) return emptyMap()

        val discovered = LinkedHashMap<String, List<ToolDefinition>>()
        servers.forEach { server ->
            val tools = runCatching { listToolsFrom(server.id, server.url) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            discovered[server.id] = tools
        }
        return discovered
    }

    /** Flattened [discoverToolsByServer] for callers that do not care about ownership. */
    suspend fun discoverTools(): List<ToolDefinition> =
        discoverToolsByServer().values.flatten().distinctBy { it.function.name }

    /** Ids of enabled servers that currently expose [toolName]. */
    suspend fun serversOffering(toolName: String): List<String> =
        registry.enabledServers()
            .filter { server ->
                runCatching { listToolsFrom(server.id, server.url) }
                    .getOrNull()
                    ?.any { it.function.name == toolName } == true
            }
            .map { it.id }

    /**
     * Calls [call] on a specific enabled server.
     *
     * @param serverId the server that advertised the tool, or null to search every enabled
     *   server in catalog order.
     * @return null when [serverId] is null and no enabled server offers the tool, so the
     *   caller can fall through to another transport.
     */
    suspend fun callOn(serverId: String?, call: ModelToolCall): McpCallResult? {
        val servers = registry.enabledServers()
        if (servers.isEmpty()) return null

        val candidates = if (serverId != null) {
            servers.filter { it.id == serverId }
        } else {
            servers
        }
        if (candidates.isEmpty()) return null

        for (server in candidates) {
            val offers = runCatching { listToolsFrom(server.id, server.url) }
                .getOrNull()
                ?.any { it.function.name == call.function.name } == true
            if (!offers) continue

            val token = credentialFor(server.id)
            if (server.requiresApiKey && token.isNullOrBlank()) {
                return McpCallResult(
                    content = buildJsonObject {
                        put(
                            "error",
                            "${server.name} needs you to sign in or add an access token " +
                                "first. Open Tools → MCP Servers."
                        )
                    }.toString(),
                    isError = true
                )
            }

            return runCatching { invoke(server.url, token, call) }
                .getOrElse { failure ->
                    McpCallResult(
                        content = buildJsonObject {
                            put("error", "MCP call to ${server.name} failed: ${failure.message}")
                        }.toString(),
                        isError = true
                    )
                }
        }

        return if (serverId != null) {
            McpCallResult(
                content = buildJsonObject {
                    put(
                        "error",
                        "$serverId is enabled but no longer advertises ${call.function.name}."
                    )
                }.toString(),
                isError = true
            )
        } else {
            null
        }
    }

    /**
     * OAuth token (refreshed when expired), then a stored personal access token.
     *
     * Both are attempted for every server: an OAuth connection is preferred because it
     * renews itself, but an "open" server may use a token purely for higher rate limits.
     */
    private suspend fun credentialFor(serverId: String): String? =
        tokenSource.accessTokenFor(serverId) ?: registry.tokenFor(serverId)

    private suspend fun listToolsFrom(serverId: String, url: String): List<ToolDefinition> {
        val token = credentialFor(serverId)
        return runCatching { invokeListTools(url, token) }.getOrElse {
            // An unauthenticated attempt is still worth making on a server that needs no
            // credentials; only surface the failure when that also fails.
            if (token.isNullOrBlank()) invokeListTools(url, null) else throw it
        }
    }

    private suspend fun invokeListTools(
        url: String,
        token: String?
    ): List<ToolDefinition> = retarget(url, token) { singleClient.listTools() }

    private suspend fun invoke(
        url: String,
        token: String?,
        call: ModelToolCall
    ): McpCallResult = retarget(url, token) { singleClient.callTool(call) }

    /**
     * Points the shared client at [url]/[token] for the duration of [block].
     *
     * One HTTP and session implementation serves every server; the client resets its MCP
     * session when retargeted, because a session id belongs to exactly one endpoint.
     */
    private suspend fun <T> retarget(
        url: String,
        token: String?,
        block: suspend () -> T
    ): T {
        val client = singleClient as? OverridableMcpClient
            ?: throw McpException("The MCP client cannot be retargeted per server")
        if (!client.withOverride(url, token)) {
            throw McpException("Refusing to use MCP endpoint '$url': https is required")
        }
        return block()
    }
}

/**
 * Implemented by the concrete client so the router can retarget it per server.
 */
interface OverridableMcpClient {
    /** Overrides endpoint and token for subsequent calls on this instance. */
    fun withOverride(endpoint: String, token: String?): Boolean
}