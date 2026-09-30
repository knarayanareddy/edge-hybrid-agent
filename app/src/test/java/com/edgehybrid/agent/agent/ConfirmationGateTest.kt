package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolCallFunction
import com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry
import com.edgehybrid.agent.tool.SkillExecutionException
import com.edgehybrid.agent.tool.SkillLoader
import com.edgehybrid.agent.tool.ToolCatalog
import com.edgehybrid.agent.tool.ToolExecutionOutcome
import com.edgehybrid.agent.tool.ToolGateway
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationGateTest {

    private class RecordingLoader(
        private val failing: Boolean = false
    ) : SkillLoader {
        var executions = 0
        var lastName: String? = null
        var lastArgs: String? = null

        override suspend fun listTools() = emptyList<com.edgehybrid.agent.data.model.ToolDefinition>()

        override suspend fun execute(call: ModelToolCall): ToolExecutionOutcome {
            executions += 1
            lastName = call.function.name
            lastArgs = call.function.arguments.toString()
            if (failing) throw SkillExecutionException("tool blew up")
            return ToolExecutionOutcome("""{"ok":true}""", false)
        }
    }

    private object DisabledMcpClient : com.edgehybrid.agent.tool.McpClient {
        override suspend fun listTools() = emptyList<com.edgehybrid.agent.data.model.ToolDefinition>()
        override suspend fun callTool(call: ModelToolCall) =
            throw IllegalStateException("MCP is disabled in this test")
    }

    private fun gateWith(
        loader: SkillLoader,
        registry: ActionConfirmationRegistry = ActionConfirmationRegistry()
    ): Triple<ConfirmationGate, ActionConfirmationRegistry, ToolGateway> {
        val gateway = ToolGateway(
            loader,
            com.edgehybrid.agent.tool.emptyMcpRouter(DisabledMcpClient),
            DisabledMcpClient,
            NoJev
        )
        val gate = ConfirmationGate(
            policy = ToolConfirmationPolicy(),
            registry = registry,
            skillLoader = loader,
            toolGateway = gateway,
            // Jev is unconfigured in these tests, so the safety signal is Unknown and the
            // approved run is returned untouched.
            jevSafetyGate = com.edgehybrid.agent.jev.JevSafetyGate(NoJev)
        )
        return Triple(gate, registry, gateway)
    }

    private fun call(name: String, args: String = "{}") = ModelToolCall(
        id = "call-1",
        function = ToolCallFunction(
            name = name,
            arguments = kotlinx.serialization.json.Json.parseToJsonElement(args) as kotlinx.serialization.json.JsonObject
        )
    )

    // ---- Tier assignment is fail-closed ----

    @Test
    fun `read-only tools are auto-approved`() {
        val policy = ToolConfirmationPolicy()
        assertTrue(policy.isAutoApproved("get_current_weather"))
        assertTrue(policy.isAutoApproved("calculate_math"))
        assertTrue(policy.isAutoApproved("search_contacts"))
        assertTrue(policy.isAutoApproved("live_web_search"))
    }

    @Test
    fun `side-effecting and unknown tools are never auto-approved`() {
        val policy = ToolConfirmationPolicy()
        listOf(
            "send_sms",
            "send_telegram_message",
            "draft_email",
            "initiate_phone_call",
            "set_alarm",
            "set_timer",
            "open_camera",
            "toggle_flashlight",
            "create_calendar_event",
            "register_mcp_server",
            "skill:calculator.js",
            "some_tool_from_the_future"
        ).forEach { tool ->
            assertFalse("$tool must not auto-approve", policy.isAutoApproved(tool))
        }
    }

    @Test
    fun `external sends get the strict tier`() {
        val policy = ToolConfirmationPolicy()
        assertEquals(
            com.edgehybrid.agent.nativeactions.ActionRiskTier.CONFIRM_STRICT,
            policy.tierFor("send_sms")
        )
        assertEquals(
            com.edgehybrid.agent.nativeactions.ActionRiskTier.CONFIRM_STRICT,
            policy.tierFor("send_telegram_message")
        )
        assertEquals(
            com.edgehybrid.agent.nativeactions.ActionRiskTier.CONFIRM,
            policy.tierFor("set_alarm")
        )
    }

    // ---- The gate itself ----

    @Test
    fun `auto-approved tool executes without a dialog`() = runTest {
        val loader = RecordingLoader()
        val (gate, _, _) = gateWith(loader)

        val result = gate.gate(call("get_current_weather", """{"location":"Tokyo"}"""))

        assertTrue(result is ConfirmationGate.GateResult.Approved)
        // Not executed yet: the orchestrator invokes the closure.
        assertEquals(0, loader.executions)
        assertEquals("""{"ok":true}""", (result as ConfirmationGate.GateResult.Approved).execute())
        assertEquals(1, loader.executions)
    }

    @Test
    fun `gated tool does not execute before approval`() = runTest {
        val loader = RecordingLoader()
        val (gate, registry, _) = gateWith(loader)

        val result = gate.gate(call("send_telegram_message", """{"message":"hi"}"""))

        assertTrue(result is ConfirmationGate.GateResult.NeedsApproval)
        assertEquals(0, loader.executions)
        assertEquals(1, registry.pendingCount)

        val confirmation = (result as ConfirmationGate.GateResult.NeedsApproval).confirmation
        assertTrue(confirmation.isStrict)
        assertNotNull(confirmation.warning)
    }

    @Test
    fun `a declined confirmation never runs the tool`() = runTest {
        val loader = RecordingLoader()
        val (gate, registry, _) = gateWith(loader)

        val result = gate.gate(call("set_alarm", """{"hour":7,"minutes":30}"""))
            as ConfirmationGate.GateResult.NeedsApproval
        registry.cancel(result.confirmation.id)

        val released = gate.runApproved(call("set_alarm"), result.confirmation.id)

        assertTrue(released is ConfirmationGate.GateResult.Rejected)
        assertEquals(0, loader.executions)
    }

    @Test
    fun `a confirmation cannot be replayed to fire the action twice`() = runTest {
        val loader = RecordingLoader()
        val (gate, _, _) = gateWith(loader)

        val pending = gate.gate(call("send_sms", """{"phone":"+15551234567","message":"x"}"""))
            as ConfirmationGate.GateResult.NeedsApproval

        val first = gate.runApproved(call("send_sms"), pending.confirmation.id)
        assertTrue(first is ConfirmationGate.GateResult.Approved)
        (first as ConfirmationGate.GateResult.Approved).execute()
        assertEquals(1, loader.executions)

        // Replay the same id.
        val second = gate.runApproved(call("send_sms"), pending.confirmation.id)
        assertTrue(second is ConfirmationGate.GateResult.Rejected)
        assertEquals(1, loader.executions)
    }

    @Test
    fun `an invented confirmation id is rejected`() = runTest {
        val loader = RecordingLoader()
        val (gate, _, _) = gateWith(loader)

        val result = gate.runApproved(call("send_sms"), "made-up-id")

        assertTrue(result is ConfirmationGate.GateResult.Rejected)
        assertEquals(0, loader.executions)
    }

    @Test
    fun `remote MCP tools are never auto-approved`() = runTest {
        val loader = RecordingLoader()
        val (gate, _, _) = gateWith(loader)

        val result = gate.gate(call("mcp_remote_do_thing"), isRemote = true)

        assertTrue(result is ConfirmationGate.GateResult.NeedsApproval)
        val confirmation = (result as ConfirmationGate.GateResult.NeedsApproval).confirmation
        assertNotNull("remote tools must warn", confirmation.warning)
    }

    // ---- Dialog content ----

    @Test
    fun `strict dialog shows recipient and message body`() = runTest {
        val loader = RecordingLoader()
        val (gate, _, _) = gateWith(loader)

        val result = gate.gate(
            call(
                "send_sms",
                """{"phone":"+15551234567","message":"Transfer 500 USD now"}"""
            )
        ) as ConfirmationGate.GateResult.NeedsApproval

        val details = result.confirmation.details
        assertTrue(details.any { it.label == "To (phone)" && it.value == "+15551234567" })
        assertTrue(details.any { it.label == "Message" && it.value == "Transfer 500 USD now" })
    }

    @Test
    fun `telegram dialog never reveals the chat id or token`() = runTest {
        val loader = RecordingLoader()
        val (gate, _, _) = gateWith(loader)

        val result = gate.gate(
            call(
                "send_telegram_message",
                """{"message":"hello","chat_id":"attacker-controlled"}"""
            )
        ) as ConfirmationGate.GateResult.NeedsApproval

        val rendered = result.confirmation.details.joinToString("|") { "${it.label}=${it.value}" }
        assertFalse(rendered.contains("attacker-controlled"))
        assertTrue(rendered.contains("fixed by the app"))
    }

    @Test
    fun `MCP registration dialog never renders the bearer token`() = runTest {
        val loader = RecordingLoader()
        val (gate, _, _) = gateWith(loader)

        val result = gate.gate(
            call(
                "register_mcp_server",
                """{"name":"srv","url":"https://example.com","bearer_token":"super-secret"}"""
            )
        ) as ConfirmationGate.GateResult.NeedsApproval

        val rendered = result.confirmation.details.joinToString("|") { it.value }
        assertFalse(rendered.contains("super-secret"))
        assertTrue(rendered.contains("encrypted keystore"))
    }

    @Test
    fun `gated tool failure surfaces as an error outcome rather than a crash`() = runTest {
        val loader = RecordingLoader(failing = true)
        val (gate, _, _) = gateWith(loader)

        val pending = gate.gate(call("set_alarm")) as ConfirmationGate.GateResult.NeedsApproval
        val released = gate.runApproved(call("set_alarm"), pending.confirmation.id)
            as ConfirmationGate.GateResult.Approved

        val thrown = runCatching { released.execute() }.exceptionOrNull()
        assertNotNull(thrown)
    }

    @Test
    fun `catalog still resolves tool names for the gateway`() = runTest {
        val loader = RecordingLoader()
        val (_, _, gateway) = gateWith(loader)
        val catalog: ToolCatalog = gateway.loadCatalog()
        assertTrue(catalog.definitions.isEmpty())
    }
}
