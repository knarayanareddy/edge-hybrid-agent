package com.edgehybrid.agent.learning

import com.edgehybrid.agent.tool.SkillExecutionException

/**
 * Decides whether a failure is worth turning into a durable rule.
 *
 * ## Why this exists
 *
 * The naive version of "record a lesson on failure" is worse than no ledger at
 * all. Every transient blip becomes a permanent instruction in the system prompt:
 * a dropped network call records "avoid the weather API", the user then never gets
 * weather again and never learns why, because the rule is injected silently and
 * unconditionally.
 *
 * So only **class-shaped** failures — the ones with a durable, teachable rule — are
 * recorded:
 *
 *  - a **tool misuse**: the model called a tool with arguments it does not accept.
 *    The rule is "check the schema", which stays true forever.
 *  - a **blocked action**: the user declined a confirmation. The rule is "ask
 *    before doing X", which is exactly what the gate already enforces — recording it
 *    teaches the model not to *propose* it.
 *  - a **provider rejection**: a 4xx means the request itself was wrong (bad model
 *    id, malformed body), which is durable. A 5xx or 429 means the server was
 *    briefly unwell, which is not, so those are deliberately ignored.
 *
 * Everything else is dropped silently. Recording less is the correct bias: a
 * missing lesson costs one mistake, a wrong permanent lesson corrupts every
 * subsequent turn.
 */
object LessonClassifier {

    /** Outcome of inspecting a failure. */
    data class Verdict(
        val shouldRecord: Boolean,
        val rule: String = "",
        val category: String = "",
        val triggerPattern: String = ""
    ) {
        companion object {
            val IGNORE = Verdict(shouldRecord = false)
        }
    }

    private const val CATEGORY_TOOL_USE = "TOOL_USE"
    private const val CATEGORY_HALLUCINATION = "HALLUCINATION"

    /**
     * Transient server-side conditions. These say nothing durable about how the
     * agent should behave, and a rule derived from them is what turns a ledger into
     * a liability.
     */
    private val TRANSIENT_MARKERS = listOf(
        "timeout", "timed out", "connection reset", "connection refused",
        "eof", "broken pipe", "503", "502", "504", "429", "too many requests",
        "rate limit", "service unavailable", "bad gateway", "gateway timeout",
        "unavailable", "try again"
    )

    /** Client-side rejections: the request was wrong, and that is teachable. */
    private val DURABLE_HTTP = listOf(
        "400", "401", "403", "404", "422", "unsupported", "invalid",
        "unknown model", "no such tool", "not found", "missing",
        "malformed", "required"
    )

    /**
     * A tool call failed. `isError` is the loader's own verdict; the message tells us
     * whether the cause is durable.
     */
    fun onToolError(toolName: String, message: String, isError: Boolean): Verdict {
        val lower = message.lowercase()

        if (TRANSIENT_MARKERS.any { lower.contains(it) }) return Verdict.IGNORE

        val durable = DURABLE_HTTP.any { lower.contains(it) } ||
            lower.contains("unsupported skill") ||
            lower.contains("is required") ||
            lower.contains("unknown") ||
            lower.contains("not supported")

        if (!durable) return Verdict.IGNORE

        return Verdict(
            shouldRecord = true,
            // Stated as a positive, checkable instruction. A rule phrased as a
            // prohibition the model cannot verify is useless; "read the schema
            // first" is actionable.
            rule = "Before calling the '$toolName' tool, check its parameters against the " +
                "declared schema. Recent failure: ${message.take(120)}",
            category = CATEGORY_TOOL_USE,
            triggerPattern = "tool:$toolName error"
        )
    }

    /**
     * The user declined a confirmation for `toolName`.
     *
     * Recorded because the useful signal is not "the call failed" but "the user does
     * not want this done without asking". That is a preference worth carrying forward.
     */
    fun onConfirmationRejected(toolName: String, reason: String): Verdict {
        if (toolName.isBlank()) return Verdict.IGNORE
        return Verdict(
            shouldRecord = true,
            rule = "Ask before performing '$toolName'. The user declined this action " +
                "when it was attempted without confirmation.",
            category = CATEGORY_TOOL_USE,
            triggerPattern = "gate:declined $toolName"
        )
    }

    /**
     * The provider rejected the request.
     *
     * `statusCode` decides: 4xx is the request's fault and durable, 5xx/429 are
     * transient and dropped. `retryLimit` distinguishes a genuine rejection from the
     * same status returned after exhausting retries.
     */
    fun onProviderError(statusCode: Int, snippet: String, toolHint: String = ""): Verdict {
        if (statusCode in 500..599 || statusCode == 429) return Verdict.IGNORE
        if (statusCode !in 400..499) return Verdict.IGNORE

        val detail = snippet.lowercase()
        // Even inside a 4xx, a timeout-flavoured body is still transient.
        if (TRANSIENT_MARKERS.any { detail.contains(it) }) return Verdict.IGNORE

        val cause = when {
            detail.contains("model") -> "The model id was rejected. Use a model id that exists in the provider's catalogue."
            detail.contains("api key") || detail.contains("unauthorized") -> "Authentication failed. Ask the user for a valid API key instead of retrying."
            detail.contains("context length") || detail.contains("too long") -> "The request exceeded the model's context. Shorten prior history before retrying."
            detail.contains("rate") -> "Rate limited. Back off before retrying."
            else -> "The provider rejected the request as malformed ($statusCode). Check the request shape."
        }
        return Verdict(
            shouldRecord = true,
            rule = cause,
            category = CATEGORY_HALLUCINATION,
            triggerPattern = "provider:$statusCode $toolHint".trim()
        )
    }

    /** Convenience for callers that only have a Throwable. */
    fun onException(throwable: Throwable, context: String = ""): Verdict {
        val message = throwable.message ?: return Verdict.IGNORE
        return when (throwable) {
            is SkillExecutionException -> onToolError(context, message, isError = true)
            else -> onToolError(context, message, isError = true)
        }
    }
}