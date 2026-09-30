package com.edgehybrid.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edgehybrid.agent.agent.ConfirmationGate
import com.edgehybrid.agent.jev.JevSafetyGate
import com.edgehybrid.agent.jev.SafetySignal
import com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry
import com.edgehybrid.agent.nativeactions.ActionRiskTier
import com.edgehybrid.agent.tool.NoJevForInstrumentation
import com.edgehybrid.agent.tool.TestMcpGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the strict confirmation dialog carries everything a user needs to
 * make an informed decision about an irreversible action.
 *
 * The unit suite asserts this too, but only against the data structure. Running it here
 * proves the values survive the real Android build: no R8 stripping, no DI-wired
 * `SecureKeyStore` interference, no ProGuard-obfuscated enum drift.
 *
 * The case that matters is a phone number and body the *model* chose. If either were
 * missing from the dialog, the user would be approving a blind action.
 */
@RunWith(AndroidJUnit4::class)
class StrictConfirmationContentTest {

    @Test
    fun strictDialogShowsRecipientAndBodyForAModelSuppliedSms() {
        val loader = TestMcpGraph.smsLoader()
        val registry = ActionConfirmationRegistry()
        val gate = TestMcpGraph.gate(loader, registry)

        val call = TestMcpGraph.modelSuppliedSms(
            phone = "+15551234567",
            body = "Hospital update: test results are ready, call when you can."
        )

        val result = gate.gate(call)
        assertTrue(
            "an SMS must require approval",
            result is ConfirmationGate.GateResult.NeedsApproval
        )

        val confirmation = (result as ConfirmationGate.GateResult.NeedsApproval).confirmation

        assertEquals(
            "an external send must use the strict tier",
            ActionRiskTier.CONFIRM_STRICT,
            confirmation.tier
        )

        val byLabel = confirmation.details.associate { it.label to it.value }
        assertTrue(
            "the dialog must show the destination, got $byLabel",
            byLabel.values.any { it.contains("15551234567") }
        )
        assertTrue(
            "the dialog must show the message body, got $byLabel",
            byLabel.values.any { it.contains("test results are ready") }
        )

        assertNotNull("the action needs an id", confirmation.id)
        assertTrue("the action needs a title", confirmation.title.isNotBlank())
        assertTrue("the action needs a summary", confirmation.summary.isNotBlank())
        assertTrue(
            "a strict action needs a warning",
            !confirmation.warning.isNullOrBlank()
        )

        assertTrue(
            "no credential may appear in a confirmation dialog",
            byLabel.values.none { it.contains("apikey_") || it.contains("sk-or-") }
        )
    }

    @Test
    fun aConfirmationIsSingleUseEvenUnderRepeatedProbing() = runBlocking {
        val loader = TestMcpGraph.smsLoader()
        val registry = ActionConfirmationRegistry()
        val gate = TestMcpGraph.gate(loader, registry)

        val call = TestMcpGraph.modelSuppliedSms("+15559998888", "test")
        val confirmation = (gate.gate(call) as ConfirmationGate.GateResult.NeedsApproval)
            .confirmation

        assertEquals("exactly one action should be pending", 1, registry.pendingCount)

        val first = gate.runApproved(call, confirmation.id)
        assertTrue(first is ConfirmationGate.GateResult.Approved)
        (first as ConfirmationGate.GateResult.Approved).execute()
        assertEquals("the side effect must run once", 1, loader.executionCount)
        assertEquals("consuming must clear the entry", 0, registry.pendingCount)

        repeat(3) { attempt ->
            val replay = gate.runApproved(call, confirmation.id)
            assertTrue(
                "replay #$attempt must be refused",
                replay is ConfirmationGate.GateResult.Rejected
            )
        }
        assertEquals(
            "the side effect must not run again",
            1,
            loader.executionCount
        )
    }

    @Test
    fun anUnknownConfirmationIdIsRejected() = runBlocking {
        val loader = TestMcpGraph.smsLoader()
        val registry = ActionConfirmationRegistry()
        val gate = TestMcpGraph.gate(loader, registry)

        val call = TestMcpGraph.modelSuppliedSms("+15551112222", "test")

        val result = gate.runApproved(call, "not-a-real-confirmation-id")
        assertTrue(
            "an id that was never registered must be refused",
            result is ConfirmationGate.GateResult.Rejected
        )
        assertEquals("nothing may execute", 0, loader.executionCount)
    }

    @Test
    fun peekDoesNotConsumeTheEntry() = runBlocking {
        val loader = TestMcpGraph.smsLoader()
        val registry = ActionConfirmationRegistry()
        val gate = TestMcpGraph.gate(loader, registry)

        val call = TestMcpGraph.modelSuppliedSms("+15550001111", "test")
        val confirmation = (gate.gate(call) as ConfirmationGate.GateResult.NeedsApproval)
            .confirmation

        // Peeking is what the UI does when re-rendering; it must not burn the approval.
        assertNotNull(registry.peek(confirmation.id))
        assertNotNull(registry.peek(confirmation.id))
        assertEquals("peek must not consume", 1, registry.pendingCount)

        val approved = gate.runApproved(call, confirmation.id)
        assertTrue(approved is ConfirmationGate.GateResult.Approved)
    }

    @Test
    fun cancelRemovesThePendingEntry() {
        val registry = ActionConfirmationRegistry()

        val confirmation = registry.register(
            tool = "send_sms",
            title = "Send SMS",
            summary = "Opens the composer",
            tier = ActionRiskTier.CONFIRM_STRICT,
            details = emptyList(),
            warning = "Check the recipient",
            run = { "ok" }
        )

        assertEquals(1, registry.pendingCount)

        registry.cancel(confirmation.id)

        assertEquals("cancel must clear the entry", 0, registry.pendingCount)
        assertNull(registry.peek(confirmation.id))
    }

    @Test
    fun unavailableJevYieldsUnknownRatherThanApproval() = runBlocking {
        // Proves the fail-safe default on a real device: a Jev call that cannot run must
        // not be interpreted as "safe". The gate still requires approval either way.
        val signal = JevSafetyGate(NoJevForInstrumentation).assess("send_sms", "{}")

        assertTrue(
            "unavailable Jev must yield Unknown, got ${signal::class.java.name}",
            signal is SafetySignal.Unknown
        )
        assertTrue(
            "Unknown must not claim an escalation",
            !signal.shouldEscalate()
        )
    }
}