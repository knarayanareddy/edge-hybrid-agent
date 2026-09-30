package com.edgehybrid.agent.jev

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the Jev integration that must hold regardless of what the remote service
 * returns.
 *
 * The two properties that matter for safety are:
 *  - an absent or undecided Jev answer is never read as approval, and
 *  - a Jev signal can escalate friction but can never substitute for, or weaken, the
 *    consent the confirmation gate collects.
 */
class JevSafetyGateTest {

    private fun evaluator(
        configured: Boolean = true,
        outcome: JevOutcome
    ): JevEvaluator = object : JevEvaluator {
        override fun isConfigured(): Boolean = configured
        override suspend fun evaluate(request: JevRequest): JevOutcome = outcome
        override suspend fun selectTools(
            prompt: String,
            candidates: Map<String, String>,
            limit: Int
        ): List<String> = emptyList()
    }

    private fun noulAnswer(probability: Double?) = JevOutcome.Answers(
        JevResponse(
            model = "jev-latest",
            answers = mapOf(
                JevSafetyGate.SAFETY to JevAnswer(
                    type = JevQuestion.TYPE_NOUL,
                    noul = probability
                )
            )
        )
    )

    // ------------------------------------------------------------ fail-safe defaults

    @Test
    fun `unconfigured Jev yields Unknown, not safe`() = runTest {
        val signal = JevSafetyGate(evaluator(configured = false, outcome = noulAnswer(0.99)))
            .assess("send_sms", "{}")

        assertTrue("must be Unknown", signal is SafetySignal.Unknown)
        assertFalse("Unknown must not escalate on its own", signal.shouldEscalate())
    }

    @Test
    fun `unavailable Jev yields Unknown`() = runTest {
        val signal = JevSafetyGate(
            evaluator(outcome = JevOutcome.Unavailable("rate limited"))
        ).assess("send_sms", "{}")

        assertTrue(signal is SafetySignal.Unknown)
        assertEquals("rate limited", (signal as SafetySignal.Unknown).reason)
    }

    @Test
    fun `an empty answer set yields Unknown rather than a default probability`() = runTest {
        val signal = JevSafetyGate(
            evaluator(outcome = JevOutcome.Answers(JevResponse()))
        ).assess("send_sms", "{}")

        assertTrue(signal is SafetySignal.Unknown)
    }

    @Test
    fun `a missing noul field yields Unknown`() = runTest {
        // A Choice answer must never be read as a probability.
        val choiceAnswer = JevOutcome.Answers(
            JevResponse(
                answers = mapOf(
                    JevSafetyGate.SAFETY to JevAnswer(
                        type = JevQuestion.TYPE_CHOICE,
                        choice = "yes",
                        confidence = 0.99
                    )
                )
            )
        )

        val signal = JevSafetyGate(evaluator(outcome = choiceAnswer)).assess("send_sms", "{}")

        assertTrue("a choice answer must not become a probability", signal is SafetySignal.Unknown)
    }

    // ------------------------------------------------------------ calibration

    @Test
    fun `an indeterminate noul is Unknown, not approval`() = runTest {
        // 0.52 means the model has no idea; treating it as "probably fine" is the exact
        // failure a calibrated model exists to prevent.
        listOf(0.45, 0.5, 0.52, 0.55).forEach { probability ->
            val signal = JevSafetyGate(evaluator(outcome = noulAnswer(probability)))
                .assess("bulk_delete", "{}")
            assertTrue(
                "p=$probability must be indeterminate",
                signal is SafetySignal.Unknown
            )
            assertFalse("p=$probability must not escalate", signal.shouldEscalate())
        }
    }

    @Test
    fun `a decisive high probability escalates`() = runTest {
        val signal = JevSafetyGate(evaluator(outcome = noulAnswer(0.93))).assess("x", "{}")

        assertTrue(signal is SafetySignal.Scored)
        assertTrue("0.93 must escalate", signal.shouldEscalate())
    }

    @Test
    fun `a decisive low probability does not escalate`() = runTest {
        val signal = JevSafetyGate(evaluator(outcome = noulAnswer(0.05))).assess("x", "{}")

        assertTrue(signal is SafetySignal.Scored)
        assertFalse("0.05 must not escalate", signal.shouldEscalate())
    }

    @Test
    fun `the escalation threshold is the documented one`() {
        assertEquals(0.7, JevSafetyGate.ESCALATION_THRESHOLD, 0.0001)
    }

    // ------------------------------------------------------------ API-shape invariants

    @Test
    fun `the endpoint is the documented one`() {
        assertEquals(
            "https://api.typesafe.ai/v1/systemone",
            JevClient.EVALUATION_ENDPOINT
        )
    }

    @Test
    fun `noul exposes no confidence`() {
        val answer = JevAnswer(type = JevQuestion.TYPE_NOUL, noul = 0.9, confidence = 0.9)
        // Noul has two outcomes, so its probability already carries the decisiveness.
        assertNull("Noul must not report a confidence", answer.decisiveness())
        assertEquals(0.9, answer.noulProbability()!!, 0.0001)
    }

    @Test
    fun `choice exposes confidence separately from probability`() {
        val answer = JevAnswer(
            type = JevQuestion.TYPE_CHOICE,
            choice = "a",
            probabilities = mapOf("a" to 0.85, "b" to 0.15),
            confidence = 0.78
        )
        assertEquals("a", answer.selectedOption())
        // These are different axes and must not be conflated.
        assertEquals(0.78, answer.decisiveness()!!, 0.0001)
        assertEquals(0.85, answer.probabilities!!["a"]!!, 0.0001)
    }

    @Test
    fun `choice cardinality is capped at the documented 255`() {
        val tooMany = (1..256).associate { "opt_$it" to "option $it" }
        try {
            JevQuestion.choice("pick", tooMany)
            throw AssertionError("expected a require() failure above 255 options")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("255"))
        }
    }

    @Test
    fun `score requires between two and ten levels`() {
        try {
            JevQuestion.score("rate", listOf("0" to "low"))
            throw AssertionError("expected a require() failure for a single level")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("2..10"))
        }
    }

    @Test
    fun `the request carries the documented top-level fields`() {
        val request = JevRequest(
            state = buildJsonObject { put("tool", "send_sms") },
            model = JevRequest.JEV_MODEL,
            questions = mapOf("q" to JevQuestion.noul("Is this destructive?"))
        )
        assertEquals("jev-latest", request.model)
        assertEquals(1, request.questions.size)
        assertEquals("noul", request.questions.getValue("q").type)
    }
}