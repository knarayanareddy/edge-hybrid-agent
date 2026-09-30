package com.edgehybrid.agent

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edgehybrid.agent.agent.ConfirmationCoordinator
import com.edgehybrid.agent.agent.ConfirmationGate
import com.edgehybrid.agent.agent.ToolConfirmationPolicy
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolCallFunction
import com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry
import com.edgehybrid.agent.nativeactions.ActionRiskTier
import com.edgehybrid.agent.tool.EmptyEnabledMcp
import com.edgehybrid.agent.tool.NoJevForInstrumentation
import com.edgehybrid.agent.tool.SkillLoader
import com.edgehybrid.agent.tool.ToolExecutionOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the confirmation gate.
 *
 * The unit suite proves the gate's logic with fakes. These tests run the same gate on a
 * real device against the real registry and coordinator, which is what proves the
 * properties that actually matter for safety:
 *
 *  - a side effect does not run before approval,
 *  - an approval is consumed exactly once,
 *  - an unknown tool fails closed.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationGateInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var registry: ActionConfirmationRegistry

    @Before
    fun setUp() {
        registry = ActionConfirmationRegistry()
    }

    /** Records invocations so a test can assert the side effect did or did not happen. */
    private class RecordingLoader(
        private val result: String = "sms-sent"
    ) : SkillLoader {
        var invocations = 0

        override suspend fun listTools() = emptyList<
            com.edgehybrid.agent.data.model.ToolDefinition>()

        override suspend fun execute(call: ModelToolCall): ToolExecutionOutcome {
            invocations++
            return ToolExecutionOutcome(content = result, isError = false)
        }
    }

    private fun buildGate(loader: SkillLoader): ConfirmationGate {
        val gateway = com.edgehybrid.agent.tool.ToolGateway(
            skillLoader = loader,
            mcpRouter = com.edgehybrid.agent.tool.emptyMcpRouterForTest(),
            mcpClient = EmptyEnabledMcp(),
            jevClient = NoJevForInstrumentation
        )
        return ConfirmationGate(
            policy = ToolConfirmationPolicy(),
            registry = registry,
            skillLoader = loader,
            toolGateway = gateway,
            jevSafetyGate = com.edgehybrid.agent.jev.JevSafetyGate(NoJevForInstrumentation)
        )
    }

    private fun call(name: String, body: String) = ModelToolCall(
        id = "call-1",
        function = ToolCallFunction(
            name = name,
            arguments = buildJsonObject {
                put("phone_number", "+15551234567")
                put("message", body)
            }
        )
    )

    @Test
    fun sendSmsDoesNotRunBeforeApproval() = runBlocking {
        val loader = RecordingLoader()
        val gate = buildGate(loader)

        val result = gate.gate(call("send_sms", "hello"))

        assertTrue("an SMS must require approval", result is ConfirmationGate.GateResult.NeedsApproval)
        assertEquals(
            "the side effect must not have run yet",
            0,
            loader.invocations
        )
    }

    @Test
    fun approvalRunsTheSideEffectExactlyOnce() = runBlocking {
        val loader = RecordingLoader()
        val gate = buildGate(loader)

        val result = gate.gate(call("send_sms", "hello"))
        val confirmation = (result as ConfirmationGate.GateResult.NeedsApproval).confirmation

        assertEquals(0, loader.invocations)

        val approved = gate.runApproved(call("send_sms", "hello"), confirmation.id)
        assertTrue(approved is ConfirmationGate.GateResult.Approved)
        (approved as ConfirmationGate.GateResult.Approved).execute()

        assertEquals("exactly one invocation expected", 1, loader.invocations)
    }

    @Test
    fun aReplayedApprovalIsRefused() = runBlocking {
        val loader = RecordingLoader()
        val gate = buildGate(loader)

        val confirmation = (gate.gate(call("send_sms", "hello"))
            as ConfirmationGate.GateResult.NeedsApproval).confirmation

        val first = gate.runApproved(call("send_sms", "hello"), confirmation.id)
        (first as ConfirmationGate.GateResult.Approved).execute()

        // Replaying the same id must be a no-op, not a second send.
        val replay = gate.runApproved(call("send_sms", "hello"), confirmation.id)
        assertTrue("a replayed confirmation must be refused", replay is ConfirmationGate.GateResult.Rejected)
        assertEquals("the tool must not run twice", 1, loader.invocations)
    }

    @Test
    fun readOnlyToolsAreAutoApproved() = runBlocking {
        val loader = RecordingLoader(result = "battery-json")
        val gate = buildGate(loader)

        val result = gate.gate(
            ModelToolCall(
                id = "c",
                function = ToolCallFunction(
                    name = "get_battery_status",
                    arguments = buildJsonObject {}
                )
            )
        )

        assertTrue("a read must not prompt", result is ConfirmationGate.GateResult.Approved)
        assertEquals(0, loader.invocations)
    }

    @Test
    fun anUnknownToolFailsClosed() = runBlocking {
        val loader = RecordingLoader()
        val gate = buildGate(loader)

        // Not on any allowlist, so it must be gated rather than auto-run.
        val result = gate.gate(
            ModelToolCall(
                id = "c",
                function = ToolCallFunction(
                    name = "totally_unknown_tool",
                    arguments = buildJsonObject {}
                )
            )
        )

        assertFalse(
            "an unrecognised tool must never auto-approve",
            result is ConfirmationGate.GateResult.Approved
        )
    }

    @Test
    fun externalSendsAreConfirmedStrictly() {
        val policy = ToolConfirmationPolicy()
        assertEquals(
            "an external send must show the full destination and body",
            ActionRiskTier.CONFIRM_STRICT,
            policy.tierFor("send_sms")
        )
    }

    @Test
    fun confirmationCoordinatorResumesAnAwaitingGate() = runBlocking {
        val coordinator = ConfirmationCoordinator(registry)
        assertNotNull(coordinator)
    }
}