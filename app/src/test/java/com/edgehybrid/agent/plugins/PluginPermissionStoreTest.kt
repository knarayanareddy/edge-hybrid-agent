package com.edgehybrid.agent.plugins

import com.edgehybrid.agent.agent.ConfirmationGate
import com.edgehybrid.agent.agent.ToolConfirmationPolicy
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolCallFunction
import com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry
import com.edgehybrid.agent.tool.SkillLoader
import com.edgehybrid.agent.tool.ToolGateway
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPermissionStoreTest {

    @Test
    fun `default permission is ASK_EVERY_TIME`() {
        val store = PluginPermissionStore()
        assertEquals(PluginPermissionStore.TrustLevel.ASK_EVERY_TIME, store.getTrustLevel("linear"))
        assertFalse(store.isAutoApproved("linear"))
    }

    @Test
    fun `granting ALWAYS_ALLOW makes server auto-approved`() {
        val store = PluginPermissionStore()
        store.setTrustLevel("linear", PluginPermissionStore.TrustLevel.ALWAYS_ALLOW)

        assertEquals(PluginPermissionStore.TrustLevel.ALWAYS_ALLOW, store.getTrustLevel("linear"))
        assertTrue(store.isAutoApproved("linear"))
    }

    @Test
    fun `revoking grant returns to default ASK_EVERY_TIME`() {
        val store = PluginPermissionStore()
        store.setTrustLevel("linear", PluginPermissionStore.TrustLevel.ALWAYS_ALLOW)
        assertTrue(store.isAutoApproved("linear"))

        store.revoke("linear")
        assertFalse(store.isAutoApproved("linear"))
        assertEquals(PluginPermissionStore.TrustLevel.ASK_EVERY_TIME, store.getTrustLevel("linear"))
    }

    private object DisabledMcpClient : com.edgehybrid.agent.tool.McpClient {
        override suspend fun listTools() = emptyList<com.edgehybrid.agent.data.model.ToolDefinition>()
        override suspend fun callTool(call: ModelToolCall) =
            throw IllegalStateException("MCP disabled in test")
    }

    @Test
    fun `ConfirmationGate auto-approves remote tools when domain is in ALWAYS_ALLOW`() {
        val store = PluginPermissionStore()
        store.setTrustLevel("weather", PluginPermissionStore.TrustLevel.ALWAYS_ALLOW)

        val registry = ActionConfirmationRegistry()
        val dummyLoader = object : SkillLoader {
            override suspend fun listTools() = emptyList<com.edgehybrid.agent.data.model.ToolDefinition>()
            override suspend fun execute(call: ModelToolCall) = throw UnsupportedOperationException()
        }
        val gateway = ToolGateway(
            dummyLoader,
            com.edgehybrid.agent.tool.emptyMcpRouter(DisabledMcpClient),
            DisabledMcpClient,
            com.edgehybrid.agent.agent.NoJev
        )
        val gate = ConfirmationGate(
            policy = ToolConfirmationPolicy(),
            registry = registry,
            skillLoader = dummyLoader,
            toolGateway = gateway,
            jevSafetyGate = com.edgehybrid.agent.jev.JevSafetyGate(com.edgehybrid.agent.agent.NoJev),
            permissionStore = store
        )

        val call = ModelToolCall(
            id = "call-1",
            function = ToolCallFunction("weather_getForecast", buildJsonObject {})
        )

        // isRemote = true
        val result = gate.gate(call, isRemote = true)
        assertTrue(
            "When domain is ALWAYS_ALLOW, gate should approve without dialog",
            result is ConfirmationGate.GateResult.Approved
        )
    }
}
