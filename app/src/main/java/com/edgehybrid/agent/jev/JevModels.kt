package com.edgehybrid.agent.jev

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * TypeSafe Jev request/response models.
 *
 * These follow the published Jev API (docs.typesafe.ai/api), endpoint
 * `POST https://api.typesafe.ai/v1/systemone`. A request carries `state`, a `model`, and a
 * map of typed `questions`; the response carries an `answers` map keyed by the same ids.
 *
 * Two details that are easy to get wrong, and that this app depends on:
 *
 *  - A **Noul** answer returns only `noul` (the yes probability). It has **no** separate
 *    `confidence` field, unlike Choice and Score. Treating `noul` as a confidence is a
 *    documented anti-pattern; with two outcomes the probability already carries both.
 *  - Choice and Score expose `confidence`, which is *not* the same as the top option's
 *    probability. A choice can be 0.85 probable at 0.78 confidence.
 */
@Serializable
data class JevRequest(
    /** Content to evaluate: a string, or structured state. */
    val state: JsonObject,
    /**
     * `"jev-latest"` per the docs.
     *
     * No Kotlin default on purpose. `model` is required by the API, and kotlinx omits a
     * property that equals its default unless `encodeDefaults` is set — so giving this a
     * default here would risk the field being silently dropped from the request body. The
     * default lives at the construction site instead.
     */
    val model: String,
    /** Typed questions keyed by caller-chosen ids. Answers come back under the same ids. */
    val questions: Map<String, JevQuestion>
) {
    companion object {
        const val JEV_MODEL = "jev-latest"
    }
}

/**
 * One typed question.
 *
 * Exactly one of [criteria] shapes is meaningful per [type]; all share `type` and
 * `instructions`. [criteria] is a free-form JSON object so each primitive can carry its own
 * rubric without a bespoke serializer.
 */
@Serializable
data class JevQuestion(
    val type: String,
    val instructions: String,
    val criteria: JsonObject? = null
) {
    companion object {
        const val TYPE_NOUL = "noul"
        const val TYPE_CHOICE = "choice"
        const val TYPE_SCORE = "score"

        /** Yes/no question. `criteria` may be `{"true": "...", "false": "..."}`. */
        fun noul(instructions: String, yesMeans: String? = null, noMeans: String? = null) =
            JevQuestion(
                type = TYPE_NOUL,
                instructions = instructions,
                criteria = buildJsonObject {
                    if (yesMeans != null) put("true", yesMeans)
                    if (noMeans != null) put("false", noMeans)
                }.takeIf { it.isNotEmpty() }
            )

        /**
         * Multi-option question.
         *
         * Cardinality is capped at 255 by Jev; higher counts are split into a score-then-
         * choose two-stage pass, which is deliberately not used here.
         */
        fun choice(instructions: String, options: Map<String, String>): JevQuestion {
            require(options.size in 1..MAX_CHOICE_OPTIONS) {
                "choice supports 1..255 options, got ${options.size}"
            }
            return JevQuestion(
                type = TYPE_CHOICE,
                instructions = instructions,
                criteria = buildJsonObject {
                    options.forEach { (option, description) -> put(option, description) }
                }
            )
        }

        /** Ordered-rubric question. */
        fun score(instructions: String, levels: List<Pair<String, String>>): JevQuestion {
            require(levels.size in 2..MAX_SCORE_LEVELS) {
                "score supports 2..10 levels, got ${levels.size}"
            }
            return JevQuestion(
                type = TYPE_SCORE,
                instructions = instructions,
                criteria = buildJsonObject {
                    putJsonObject("levels") {
                        levels.forEach { (level, description) -> put(level, description) }
                    }
                }
            )
        }

        const val MAX_CHOICE_OPTIONS = 255
        const val MAX_SCORE_LEVELS = 10
    }
}

/**
 * Response envelope. `answers` is keyed by the request's question ids.
 *
 * Extra fields are tolerated (the client is configured to ignore unknown keys) so a Jev
 * release that adds a field does not break parsing.
 */
@Serializable
data class JevResponse(
    val model: String? = null,
    val answers: Map<String, JevAnswer> = emptyMap(),
    val usage: JevUsage? = null
) {
    /** Answer for a question id, or null when the server omitted it. */
    fun answer(questionId: String): JevAnswer? = answers[questionId]
}

@Serializable
data class JevUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0
)

/**
 * One answer.
 *
 * The three primitives return different fields, all optional here so a single type can
 * carry any of them. Callers must use the accessors that guard against the wrong
 * primitive's shape rather than reading fields directly.
 */
@Serializable
data class JevAnswer(
    val type: String? = null,
    /** Noul: probability of "yes", 0..1. Absent for Choice and Score. */
    val noul: Double? = null,
    /** Choice: the highest-probability option. */
    val choice: String? = null,
    /** Score: the probability-weighted numeric score. */
    val score: Double? = null,
    /** Choice and Score: per-option / per-level probability distribution. */
    val probabilities: Map<String, Double>? = null,
    /** Choice and Score: decisiveness, 0..1. Not the same as the top option's probability. */
    val confidence: Double? = null,
    /** Score: level id to its description. */
    val legend: Map<String, String>? = null
) {
    /**
     * Noul yes-probability, or null when this is not a Noul answer.
     *
     * Guarded so a Choice answer can never be read as a probability.
     */
    fun noulProbability(): Double? =
        if (type == JevQuestion.TYPE_NOUL) noul else null

    /** Choice option, or null when this is not a Choice answer. */
    fun selectedOption(): String? =
        if (type == JevQuestion.TYPE_CHOICE) choice else null

    /** Choice or Score confidence, or null for Noul (which has none). */
    fun decisiveness(): Double? =
        if (type == JevQuestion.TYPE_NOUL) null else confidence

    /**
     * True when the answer should be treated as "the model has no idea".
     *
     * For a Noul, a probability near 0.5 means indifference rather than a weak yes. Without
     * this, a 0.52 would be treated as approval — the exact failure mode a calibrated
     * model is supposed to prevent.
     */
    fun isIndeterminate(threshold: Double = 0.6): Boolean {
        val band = threshold - 0.5
        return when (type) {
            JevQuestion.TYPE_NOUL -> {
                // Distance from 0.5 is the decisiveness signal for a two-outcome answer:
                // 0.52 is the model saying it has no idea, not a weak yes.
                val probability = noul ?: return true
                kotlin.math.abs(probability - 0.5) < band
            }

            JevQuestion.TYPE_CHOICE, JevQuestion.TYPE_SCORE ->
                (confidence ?: return true) < threshold

            else -> true
        }
    }
}

/**
 * Local, offline verdict produced by [JevRiskClassifier] when no API key is configured or
 * the Jev call fails. Kept distinct from [JevEvaluationResponse] so it is never confused
 * with a real calibrated answer.
 */
@Serializable
enum class RiskLevel {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL;

    companion object {
        fun fromScore(score: Int): RiskLevel = when {
            score >= 90 -> CRITICAL
            score >= 70 -> HIGH
            score >= 40 -> MODERATE
            else -> LOW
        }
    }
}

/** Legacy shape kept for the local classifier only; see [JevLocalAssessment]. */
@Serializable
data class JevEvaluationResponse(
    val verdict: String,
    val riskScore: Int,
    val isFalsePositive: Boolean = false,
    val reasons: List<String> = emptyList(),
    val suggestedConstraints: List<String> = emptyList()
) {
    val riskLevel: RiskLevel get() = RiskLevel.fromScore(riskScore)
}
