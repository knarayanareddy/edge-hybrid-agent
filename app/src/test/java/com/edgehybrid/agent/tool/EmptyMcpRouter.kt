package com.edgehybrid.agent.tool

import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.mcp.McpServerEntry
import com.edgehybrid.agent.mcp.McpServerSource
import com.edgehybrid.agent.mcp.McpTokenSource

/**
 * Builds a router with no enabled servers, for tests that are not exercising MCP.
 *
 * Discovery returns nothing and dispatch returns null, so the gateway falls back to local
 * tools exactly as it would in an app where the user has enabled no servers. A factory is
 * used rather than a subclass because [McpMultiServerRouter] is intentionally final.
 */
fun emptyMcpRouter(client: McpClient): McpMultiServerRouter = McpMultiServerRouter(
    object : McpServerSource {
        override fun enabledServers(): List<McpServerEntry> = emptyList()
        override fun tokenFor(serverId: String): String? = null
    },
    McpTokenSource { null },
    client
)