package com.edgehybrid.agent.agent

import com.edgehybrid.agent.jev.JevEvaluator
import com.edgehybrid.agent.jev.JevOutcome
import com.edgehybrid.agent.jev.JevRequest

/**
 * A Jev evaluator that is not configured, matching a fresh install with no API key.
 *
 * Used by tests that are not exercising Jev: pruning returns nothing and the safety signal
 * is Unknown, so the agent behaves exactly as it would without a key.
 */
object NoJev : JevEvaluator {
    override fun isConfigured(): Boolean = false

    override suspend fun evaluate(request: JevRequest): JevOutcome =
        JevOutcome.Unavailable("Jev not configured in tests")

    override suspend fun selectTools(
        prompt: String,
        candidates: Map<String, String>,
        limit: Int
    ): List<String> = emptyList()
}
