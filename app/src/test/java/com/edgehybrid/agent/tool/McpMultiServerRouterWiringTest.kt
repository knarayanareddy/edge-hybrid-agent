package com.edgehybrid.agent.tool

import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolCallFunction
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.mcp.McpServerEntry
import com.edgehybrid.agent.mcp.McpServerSource
import com.edgehybrid.agent.mcp.McpTokenSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proof that the MCP registry is on the live dispatch path.
 *
 * This guards the regression that actually shipped earlier in this codebase:
 * [McpMultiServerRouter] existed, compiled, passed its own tests, and was never injected —
 * so enabling a server in Settings changed nothing about what the agent could call.
 */
class McpMultiServerRouterWiringTest {

    private fun definition(name: String) = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = "test tool",
            parameters = buildJsonObject { put("type", "object") }
        )
    )

    private fun call(name: String) = ModelToolCall(
        id = "call-1",
        function = ToolCallFunction(name = name, arguments = buildJsonObject {})
    )

    /** Records which endpoint each call was actually routed to. */
    private class RecordingMcpClient(
        private val toolsPerHost: Map<String, List<ToolDefinition>>
    ) : McpClient, OverridableMcpClient {
        var currentHost: String? = null
        val calledOn = mutableListOf<String>()

        override fun withOverride(endpoint: String, token: String?): Boolean {
            if (!endpoint.startsWith("https://")) return false
            currentHost = endpoint.substringAfter("https://").substringBefore('/')
            return true
        }

        override suspend fun listTools(): List<ToolDefinition> =
            toolsPerHost[currentHost].orEmpty()

        override suspend fun callTool(call: ModelToolCall): McpCallResult {
            currentHost?.let { calledOn.add(it) }
            return McpCallResult(content = "{\"ok\":true}", isError = false)
        }
    }

    private fun source(servers: List<McpServerEntry>, tokens: Map<String, String> = emptyMap()) =
        object : McpServerSource {
            override fun enabledServers() = servers.filter { it.enabled }
            override fun tokenFor(serverId: String) = tokens[serverId]
        }

    private val noTokens = McpTokenSource { null }

    private fun router(client: McpClient, servers: List<McpServerEntry>) =
        McpMultiServerRouter(source(servers), noTokens, client)

    // --------------------------------------------------------------- wiring

    @Test
    fun `ToolGateway takes the router as a real constructor dependency`() {
        val params = ToolGateway::class.java.constructors
            .first { it.parameterCount == 4 }
            .parameterTypes
            .map { it.simpleName }

        assertTrue(
            "ToolGateway must depend on McpMultiServerRouter, had $params",
            params.contains("McpMultiServerRouter")
        )
        assertTrue(
            "ToolGateway must depend on JevEvaluator for pruning, had $params",
            params.contains("JevEvaluator")
        )
    }

    @Test
    fun `only enabled servers contribute tools`() = runTest {
        val client = RecordingMcpClient(
            mapOf(
                "docs.example.org" to listOf(definition("read_docs")),
                "search.example.org" to listOf(definition("web_search"))
            )
        )
        val discovered = router(
            client,
            listOf(
                McpServerEntry("docs", "Docs", "https://docs.example.org/mcp", enabled = true),
                McpServerEntry("search", "Search", "https://search.example.org/mcp", enabled = false)
            )
        ).discoverToolsByServer()

        assertEquals("only the enabled server should appear", 1, discovered.size)
        assertTrue(discovered.containsKey("docs"))
        assertFalse("a disabled server must contribute nothing", discovered.containsKey("search"))
    }

    @Test
    fun `a tool is dispatched to the server that advertised it`() = runTest {
        val client = RecordingMcpClient(
            mapOf(
                "docs.example.org" to listOf(definition("read_docs")),
                "other.example.org" to listOf(definition("something_else"))
            )
        )
        router(
            client,
            listOf(
                McpServerEntry("docs", "Docs", "https://docs.example.org/mcp", enabled = true),
                McpServerEntry("other", "Other", "https://other.example.org/mcp", enabled = true)
            )
        ).callOn("docs", call("read_docs"))

        assertEquals(listOf("docs.example.org"), client.calledOn)
    }

    @Test
    fun `an auth-requiring server with no credential is refused, not called`() = runTest {
        val client = RecordingMcpClient(
            mapOf("private.example.org" to listOf(definition("private_tool")))
        )
        val result = router(
            client,
            listOf(
                McpServerEntry(
                    "private", "Private", "https://private.example.org/mcp",
                    enabled = true, requiresApiKey = true
                )
            )
        ).callOn("private", call("private_tool"))

        assertNotNull(result)
        assertTrue("must be an error", result!!.isError)
        assertTrue(
            "message must point the user at Settings: ${result.content}",
            result.content.contains("MCP Servers")
        )
        assertTrue("the server must not have been called", client.calledOn.isEmpty())
    }

    @Test
    fun `a stored token satisfies an auth-requiring server`() = runTest {
        val client = RecordingMcpClient(
            mapOf("private.example.org" to listOf(definition("private_tool")))
        )
        val result = McpMultiServerRouter(
            source(
                listOf(
                    McpServerEntry(
                        "private", "Private", "https://private.example.org/mcp",
                        enabled = true, requiresApiKey = true
                    )
                ),
                tokens = mapOf("private" to "secret-token")
            ),
            noTokens,
            client
        ).callOn("private", call("private_tool"))

        assertNotNull(result)
        assertFalse("should have been called: ${result!!.content}", result.isError)
        assertEquals(listOf("private.example.org"), client.calledOn)
    }

    @Test
    fun `a disabled server is never dispatched to`() = runTest {
        val client = RecordingMcpClient(
            mapOf("off.example.org" to listOf(definition("hidden_tool")))
        )
        val result = router(
            client,
            listOf(
                McpServerEntry("off", "Off", "https://off.example.org/mcp", enabled = false)
            )
        ).callOn("off", call("hidden_tool"))

        assertNull("a disabled server must not be called", result)
        assertTrue(client.calledOn.isEmpty())
    }

    @Test
    fun `an unreachable server does not hide the others' tools`() = runTest {
        val client = object : McpClient, OverridableMcpClient {
            var host: String? = null
            override fun withOverride(endpoint: String, token: String?) = true.also {
                host = endpoint.substringAfter("https://").substringBefore('/')
            }
            override suspend fun listTools(): List<ToolDefinition> =
                if (host == "broken.example.org") throw McpException("connection refused")
                else listOf(definition("read_docs"))

            override suspend fun callTool(call: ModelToolCall) = McpCallResult("ok", false)
        }

        val discovered = router(
            client,
            listOf(
                McpServerEntry("broken", "Broken", "https://broken.example.org/mcp", enabled = true),
                McpServerEntry("docs", "Docs", "https://docs.example.org/mcp", enabled = true)
            )
        ).discoverToolsByServer()

        assertEquals("the healthy server must still be discovered", 1, discovered.size)
        assertTrue(discovered.containsKey("docs"))
    }

    @Test
    fun `a non-https endpoint is refused by the client override`() {
        val client = RecordingMcpClient(emptyMap())
        assertFalse(
            "retargeting to http must be refused",
            client.withOverride("http://insecure.example.org/mcp", null)
        )
        assertTrue(client.withOverride("https://ok.example.org/mcp", null))
    }
}