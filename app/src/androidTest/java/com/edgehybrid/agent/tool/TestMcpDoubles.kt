package com.edgehybrid.agent.tool

import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolCallFunction
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.jev.JevEvaluator
import com.edgehybrid.agent.jev.JevOutcome
import com.edgehybrid.agent.jev.JevRequest
import com.edgehybrid.agent.mcp.McpServerEntry
import com.edgehybrid.agent.mcp.McpServerSource
import com.edgehybrid.agent.mcp.McpTokenSource

/**
 * A no-op MCP client that reports no tools, used by instrumentation tests.
 *
 * The instrumented gate must exercise the real [com.edgehybrid.agent.agent.ConfirmationGate],
 * not a test-only reimplementation, but the MCP transport still has to be inert so a test
 * never reaches the network.
 */
class EmptyEnabledMcp : McpClient {
    override suspend fun listTools(): List<ToolDefinition> = emptyList()

    override suspend fun callTool(call: ModelToolCall): McpCallResult =
        McpCallResult(content = """{"error":"MCP disabled in test"}""", isError = true)
}

/** Router with no enabled servers, so discovery and dispatch are inert. */
fun emptyMcpRouterForTest(): McpMultiServerRouter = McpMultiServerRouter(
    object : McpServerSource {
        override fun enabledServers(): List<McpServerEntry> = emptyList()
        override fun tokenFor(serverId: String): String? = null
    },
    McpTokenSource { null },
    EmptyEnabledMcp()
)

/** Jev evaluator that is not configured, matching a device with no Jev key stored. */
object NoJevForInstrumentation : JevEvaluator {
    override fun isConfigured(): Boolean = false

    override suspend fun evaluate(request: JevRequest): JevOutcome =
        JevOutcome.Unavailable("Jev not configured in instrumentation test")

    override suspend fun selectTools(
        prompt: String,
        candidates: Map<String, String>,
        limit: Int
    ): List<String> = emptyList()
}