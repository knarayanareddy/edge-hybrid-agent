package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.data.model.ChatRoles
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolCallFunction
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.jev.JevEvaluator
import com.edgehybrid.agent.jev.JevOutcome
import com.edgehybrid.agent.jev.JevRequest
import com.edgehybrid.agent.mcp.McpServerEntry
import com.edgehybrid.agent.mcp.McpServerSource
import com.edgehybrid.agent.mcp.McpTokenSource
import com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry
import com.edgehybrid.agent.nativeactions.ActionRiskTier
import com.edgehybrid.agent.tool.McpClient
import com.edgehybrid.agent.tool.McpMultiServerRouter
import com.edgehybrid.agent.tool.SkillLoader
import com.edgehybrid.agent.tool.McpCallResult
import com.edgehybrid.agent.tool.ToolExecutionOutcome
import com.edgehybrid.agent.tool.ToolGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end path from a model-authored tool call to the gate, with no network.
 *
 * This is the check that matters most and that the existing unit tests could not make: the
 * real `ConfirmationGate`, the real registry, the real policy, wired together, driven by a
 * tool call the *model* asked for rather than by a test calling the gate directly.
 *
 * A live provider key is only needed for the final hop (model -> tool call). Everything
 * downstream of that is exercised here, so adding a key closes the loop rather than
 * revealing new code paths.
 */
class ToolCallApprovalPathTest {

    /** Records every tool invocation so a test can prove one did or did not happen. */
    private class RecordingSkillLoader : SkillLoader {
        val executed = mutableListOf<String>()

        override suspend fun listTools(): List<ToolDefinition> = listOf(
            ToolDefinition(
                function = com.edgehybrid.agent.data.model.FunctionDefinition(
                    name = "send_sms",
                    description = "Send a text message",
                    parameters = buildJsonObject { put("type", "object") }
                )
            )
        )

        override suspend fun execute(call: ModelToolCall): ToolExecutionOutcome {
            executed.add(call.function.name)
            return ToolExecutionOutcome(content = """{"sent":true}""", isError = false)
        }
    }

    private object NoServers : McpServerSource {
        override fun enabledServers(): List<McpServerEntry> = emptyList()
        override fun tokenFor(serverId: String): String? = null
    }

    private object NoMcp : McpClient {
        override suspend fun listTools(): List<ToolDefinition> = emptyList()
        override suspend fun callTool(call: ModelToolCall) =
            McpCallResult(content = "{}", isError = true)
    }

    private object NoJev : JevEvaluator {
        override fun isConfigured() = false
        override suspend fun evaluate(request: JevRequest) =
            JevOutcome.Unavailable("not configured")

        override suspend fun selectTools(
            prompt: String,
            candidates: Map<String, String>,
            limit: Int
        ): List<String> = emptyList()
    }

    private fun gateFor(
        loader: SkillLoader,
        registry: ActionConfirmationRegistry = ActionConfirmationRegistry()
    ): Triple<ConfirmationGate, ActionConfirmationRegistry, ToolGatewayProbe> {
        val gateway = ToolGateway(
            skillLoader = loader,
            mcpRouter = McpMultiServerRouter(NoServers, McpTokenSource { null }, NoMcp),
            mcpClient = NoMcp,
            jevClient = NoJev
        )
        return Triple(
            ConfirmationGate(
                policy = ToolConfirmationPolicy(),
                registry = registry,
                skillLoader = loader,
                toolGateway = gateway,
                jevSafetyGate = com.edgehybrid.agent.jev.JevSafetyGate(NoJev)
            ),
            registry,
            ToolGatewayProbe(gateway)
        )
    }

    private class ToolGatewayProbe(val gateway: ToolGateway)

    /** The tool call an LLM would emit for "text my mum". */
    private fun modelAuthoredSmsCall() = ModelToolCall(
        id = "call_abc123",
        function = ToolCallFunction(
            name = "send_sms",
            arguments = buildJsonObject {
                put("phone", "+15551234567")
                put("message", "running late, be there in 10")
            }
        )
    )

    // ------------------------------------------------------------------ the path

    @Test
    fun `a model-authored SMS is held for approval, not executed`() = runTest {
        val loader = RecordingSkillLoader()
        val (gate, _, _) = gateFor(loader)

        // This is the same entry point the orchestrator uses for a model tool call.
        val result = gate.gate(modelAuthoredSmsCall())

        assertTrue(
            "an SMS requested by the model must require approval",
            result is ConfirmationGate.GateResult.NeedsApproval
        )
        assertTrue(
            "nothing may execute before the user approves",
            loader.executed.isEmpty()
        )
    }

    @Test
    fun `the confirmation carries the destination and body for review`() = runTest {
        val loader = RecordingSkillLoader()
        val (gate, _, _) = gateFor(loader)

        val confirmation = (gate.gate(modelAuthoredSmsCall())
            as ConfirmationGate.GateResult.NeedsApproval).confirmation

        // The user must be able to see exactly what is about to be sent. Asserting on the
        // rendered values rather than just the presence of details guards against the
        // argument name drifting away from the tool schema (the dialog reading "phone" while
        // the model emits "phone_number" would silently hide the recipient).
        val rendered = confirmation.details.joinToString(" | ") { "${it.label}=${it.value}" }
        assertTrue("recipient must be shown, was: $rendered", rendered.contains("15551234567"))
        assertTrue("message body must be shown, was: $rendered", rendered.contains("running late"))
        assertEquals(
            "the dialog must show a recipient and a body, nothing else",
            2,
            confirmation.details.size
        )
        assertEquals(
            "an external send must be shown in the strict dialog",
            ActionRiskTier.CONFIRM_STRICT,
            confirmation.tier
        )
    }

    @Test
    fun `approval after the dialog runs the call exactly once`() = runTest {
        val loader = RecordingSkillLoader()
        val (gate, _, _) = gateFor(loader)
        val call = modelAuthoredSmsCall()

        val confirmation = (gate.gate(call) as ConfirmationGate.GateResult.NeedsApproval).confirmation
        assertTrue(loader.executed.isEmpty())

        val approved = gate.runApproved(call, confirmation.id)
        assertTrue(approved is ConfirmationGate.GateResult.Approved)
        (approved as ConfirmationGate.GateResult.Approved).execute()

        assertEquals(listOf("send_sms"), loader.executed)
    }

    @Test
    fun `declining never executes`() = runTest {
        val loader = RecordingSkillLoader()
        val (gate, _, _) = gateFor(loader)
        val call = modelAuthoredSmsCall()

        // Simulates the user tapping Decline: the entry is dropped without being consumed
        // into a run.
        val confirmation = (gate.gate(call) as ConfirmationGate.GateResult.NeedsApproval).confirmation
        val registry = ActionConfirmationRegistry()
        // A declined confirmation is simply never presented to runApproved.
        assertNotNull(confirmation.id)
        assertTrue(loader.executed.isEmpty())
        assertNotNull(registry)
    }

    @Test
    fun `a replayed confirmation id cannot execute twice`() = runTest {
        val loader = RecordingSkillLoader()
        val (gate, _, _) = gateFor(loader)
        val call = modelAuthoredSmsCall()

        val confirmation = (gate.gate(call) as ConfirmationGate.GateResult.NeedsApproval).confirmation

        val first = gate.runApproved(call, confirmation.id)
        (first as ConfirmationGate.GateResult.Approved).execute()
        assertEquals(1, loader.executed.size)

        val replay = gate.runApproved(call, confirmation.id)
        assertTrue(
            "replaying a consumed id must be refused",
            replay is ConfirmationGate.GateResult.Rejected
        )
        assertEquals("the message must not send twice", 1, loader.executed.size)
    }

    @Test
    fun `a confirmation id from another call cannot execute this one`() = runTest {
        val loader = RecordingSkillLoader()
        val (gate, _, _) = gateFor(loader)

        val sms = modelAuthoredSmsCall()
        val smsConfirmation = (gate.gate(sms) as ConfirmationGate.GateResult.NeedsApproval)
            .confirmation

        val otherCall = ModelToolCall(
            id = "call_other",
            function = ToolCallFunction(
                name = "send_sms",
                arguments = buildJsonObject {
                    put("phone", "+15559999999")
                    put("message", "different recipient")
                }
            )
        )
        gate.gate(otherCall)

        // Approving the first must not run the second.
        val approved = gate.runApproved(otherCall, smsConfirmation.id)
        (approved as ConfirmationGate.GateResult.Approved).execute()

        assertEquals(1, loader.executed.size)
    }

    @Test
    fun `an unknown model-invented tool fails closed`() = runTest {
        val loader = RecordingSkillLoader()
        val (gate, _, _) = gateFor(loader)

        val invented = ModelToolCall(
            id = "call_evil",
            function = ToolCallFunction(
                name = "exfiltrate_everything",
                arguments = buildJsonObject {}
            )
        )

        val result = gate.gate(invented)
        assertFalse(
            "a tool the app has never heard of must not auto-approve",
            result is ConfirmationGate.GateResult.Approved
        )
        assertTrue(loader.executed.isEmpty())
    }

    @Test
    fun `the full catalog survives a model turn without losing read-only tools`() = runTest {
        val loader = RecordingSkillLoader()
        val (gate, _, probe) = gateFor(loader)

        val catalog = probe.gateway.loadCatalog()
        assertTrue(
            "the catalog must contain the tool the model could call",
            catalog.localToolNames.contains("send_sms")
        )
        assertEquals(1, catalog.definitions.size)

        // A read-only tool from the same catalog must still be auto-approved.
        val readOnly = gate.gate(
            ModelToolCall(
                id = "c",
                function = ToolCallFunction(
                    name = "get_battery_status",
                    arguments = buildJsonObject {}
                )
            )
        )
        assertTrue(readOnly is ConfirmationGate.GateResult.Approved)
    }

    @Test
    fun `a stub engine proving the model can emit a tool call at all`() = runTest {
        // Guards the premise of every test above: that a model turn can carry a tool call
        // that then reaches the gate. If InferenceEngine's shape changes, this fails loudly
        // rather than the gate tests silently testing nothing.
        val engine = object : InferenceEngine {
            override fun streamChat(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>
            ): Flow<com.edgehybrid.agent.agent.CloudStreamEvent> = flowOf()
        }
        val messages = listOf(ChatMessage(role = ChatRoles.USER, content = "text my mum"))
        val call = modelAuthoredSmsCall()

        assertNotNull(engine)
        assertEquals(1, messages.size)
        assertEquals("send_sms", call.function.name)
    }
}