package com.edgehybrid.agent.jev

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Calibrated safety signal for a proposed tool call, using Jev's `noul` primitive.
 *
 * Why this exists separately from [JevRiskClassifier]: the classifier matches a fixed list
 * of known tool names, so it says nothing about a call the app has not seen before. A
 * `noul` question reads the *arguments* and returns a probability that the action is
 * destructive or irreversible, which covers unknown tools and unexpected arguments.
 *
 * This is advisory, by design. It never replaces [com.edgehybrid.agent.agent.ConfirmationGate]:
 *  - the gate is the authority on whether user consent was given, and a probability cannot
 *    substitute for an approval that was actually collected;
 *  - this signal can only *add* friction (escalate a tier, request an explicit reason), and
 *    can never downgrade one;
 *  - when Jev is unavailable or undecided, the answer is [SafetySignal.Unknown] and the gate
 *    applies its normal policy. An absent signal is never read as "safe".
 */
@Singleton
class JevSafetyGate @Inject constructor(
    private val jevClient: JevEvaluator
) {
    /**
     * Asks whether a proposed action is destructive, irreversible, or sends data outside
     * the device.
     *
     * @param toolName the tool the model wants to call.
     * @param argumentsJson the arguments it supplied, as a JSON string. Passed to Jev as
     *   structured state so it can read values, not just the tool name.
     */
    suspend fun assess(toolName: String, argumentsJson: String): SafetySignal {
        if (!jevClient.isConfigured()) return SafetySignal.Unknown("Jev not configured")

        val question = JevQuestion.noul(
            instructions = "Does this proposed device action delete or overwrite user data, " +
                "change state that cannot be undone, place a call, send a message, make a " +
                "payment, or transmit private data to an external service?",
            yesMeans = "The action has destructive, irreversible, or outward-facing effects.",
            noMeans = "The action is read-only or its effect is local and reversible."
        )

        val state = buildJsonObject {
            put("tool", toolName)
            put("arguments", argumentsJson)
        }

        return when (val outcome = jevClient.evaluate(JevRequest(
                state = state,
                model = JevRequest.JEV_MODEL,
                questions = mapOf(SAFETY to question)
            ))) {
            is JevOutcome.Unavailable -> SafetySignal.Unknown(outcome.reason)

            is JevOutcome.Answers -> {
                val answer = outcome.body.answer(SAFETY) ?: return SafetySignal.Unknown("No answer")
                val probability = answer.noulProbability()
                    ?: return SafetySignal.Unknown("Answer was not a noul")

                // A probability near 0.5 is indifference. Treating it as "safe" is exactly
                // the failure a calibrated model is meant to prevent, so it maps to Unknown.
                if (answer.isIndeterminate(INDETERMINATE_BAND)) {
                    SafetySignal.Unknown(
                        "Jev was undecided (p=$probability)",
                        probability = probability
                    )
                } else {
                    SafetySignal.Scored(probability)
                }
            }
        }
    }

    companion object {
        const val SAFETY = "destructive"

        /**
         * Distance from 0.5 below which a Noul answer counts as undecided.
         *
         * 0.6 means anything in 0.4–0.6 is treated as no usable signal.
         */
        const val INDETERMINATE_BAND = 0.6

        /** At or above this probability the signal escalates friction. */
        const val ESCALATION_THRESHOLD = 0.7
    }
}

/**
 * Outcome of a safety assessment.
 *
 * [Scored] carries a real calibrated probability. [Unknown] is the fail-safe default: the
 * gate must apply its normal policy, never treat it as approval.
 */
sealed class SafetySignal {
    data class Scored(val probability: Double) : SafetySignal()

    data class Unknown(
        val reason: String,
        val probability: Double? = null
    ) : SafetySignal()

    /**
     * True when the signal should escalate the confirmation tier.
     *
     * Only a genuine high probability escalates. [Unknown] deliberately returns false:
     * uncertainty is not evidence of danger, and escalating on every unknown would train
     * users to click through.
     */
    fun shouldEscalate(): Boolean =
        this is Scored && probability >= JevSafetyGate.ESCALATION_THRESHOLD

    /** True when a usable probability is available. */
    val isScored: Boolean get() = this is Scored
}