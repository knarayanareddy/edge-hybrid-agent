package com.edgehybrid.agent.jev

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live contract check against the real TypeSafe endpoint.
 *
 * Skipped unless `JEV_API_KEY` is set in the environment, so the suite stays green with no
 * credentials. The key is never read from source, from a gradle property, or from
 * `BuildConfig`; it comes only from the process environment of whoever runs this.
 *
 * Run with:
 * ```
 * JEV_API_KEY=... ./gradlew :app:testDebugUnitTest --tests '*JevLiveApiTest'
 * ```
 *
 * What this guards is the wire format, which is the part that cannot be verified offline:
 * the endpoint path, the request body shape, the `answers` envelope, and the documented
 * asymmetry that a Noul returns no `confidence`.
 */
class JevLiveApiTest {

    private val apiKey: String? = System.getenv("JEV_API_KEY")?.takeIf { it.isNotBlank() }

    private fun requireKey() {
        assumeTrue(
            "Set JEV_API_KEY to run the live Jev contract test",
            apiKey != null
        )
    }

    @Test
    fun `noul request returns the documented answer shape`() = runBlocking {
        requireKey()

        val outcome = JevWireProbe.evaluateNoul(
            apiKey = apiKey!!,
            tool = "send_sms",
            arguments = """{"to":"+15551234567","body":"hello"}"""
        )

        assertTrue("expected a 200 response, got $outcome", outcome.httpStatus == 200)

        val answer = outcome.answer(JevSafetyGate.SAFETY)
        assertNotNull("no answer returned", answer)
        assertEquals("noul", answer!!.type)
        assertNotNull("noul must carry a probability", answer.noul)

        // The documented asymmetry: Noul has two outcomes, so its probability already
        // carries the decisiveness and there is no separate confidence field.
        assertNull("Noul must not report confidence", answer.decisiveness())
    }

    @Test
    fun `sending a message scores as outward-facing`() = runBlocking {
        requireKey()

        val outcome = JevWireProbe.evaluateNoul(
            apiKey = apiKey!!,
            tool = "send_sms",
            arguments = """{"to":"+15551234567","body":"hello"}"""
        )
        val probability = outcome.answer(JevSafetyGate.SAFETY)?.noulProbability()

        assertNotNull(probability)
        assertTrue(
            "an SMS to an external recipient should score high, got $probability",
            probability!! >= 0.7
        )
        assertTrue(
            "the signal should escalate",
            SafetySignal.Scored(probability).shouldEscalate()
        )
    }

    @Test
    fun `a read-only query scores low and does not escalate`() = runBlocking {
        requireKey()

        val outcome = JevWireProbe.evaluateNoul(
            apiKey = apiKey!!,
            tool = "get_battery_status",
            arguments = "{}"
        )
        val probability = outcome.answer(JevSafetyGate.SAFETY)?.noulProbability()

        assertNotNull(probability)
        // Guarding against false positives matters more than catching real risk here: a
        // read that trips the warning would train the user to ignore the warning.
        assertTrue(
            "a battery read should not score as destructive, got $probability",
            probability!! < JevSafetyGate.ESCALATION_THRESHOLD
        )
        assertFalse(SafetySignal.Scored(probability).shouldEscalate())
    }

    @Test
    fun `choice returns a full probability distribution and confidence`() = runBlocking {
        requireKey()

        val outcome = JevWireProbe.evaluateChoice(
            apiKey = apiKey!!,
            prompt = "what is my battery level",
            candidates = mapOf(
                "get_battery_status" to "Battery charge, temperature, and health",
                "send_sms" to "Send a text message to a phone number",
                "toggle_flashlight" to "Turn the camera torch on or off",
                "get_network_status" to "Active network type and signal strength"
            )
        )

        val answer = outcome.answer(JevClient.SELECT_TOOLS)
        assertNotNull(answer)
        assertEquals("choice", answer!!.type)
        assertEquals("get_battery_status", answer.selectedOption())
        assertNotNull("choice must return a distribution", answer.probabilities)
        assertNotNull("choice must return confidence", answer.decisiveness())

        // Probabilities must form a distribution over exactly the offered options.
        val offered = setOf(
            "get_battery_status", "send_sms", "toggle_flashlight", "get_network_status"
        )
        assertEquals(offered, answer.probabilities!!.keys)
        assertEquals(
            "probabilities should sum to 1",
            1.0,
            answer.probabilities!!.values.sum(),
            0.01
        )
    }

    @Test
    fun `an empty candidate set is rejected locally, before any request`() = runBlocking {
        requireKey()

        // Cardinality and emptiness are validated in JevQuestion, so this must never
        // reach the network.
        try {
            JevQuestion.choice("pick", emptyMap())
            throw AssertionError("expected a require() failure for an empty option set")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("1..255"))
        }
    }
}