package com.edgehybrid.agent.jev

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class RiskLevel {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL
}

@Serializable
data class JevEvaluationRequest(
    val prompt: String,
    val proposedAction: String? = null,
    val context: Map<String, String> = emptyMap()
)

@Serializable
data class JevEvaluationResponse(
    val verdict: String,
    val riskScore: Int,
    val isFalsePositive: Boolean = false,
    val reasons: List<String> = emptyList(),
    val suggestedConstraints: List<String> = emptyList()
) {
    val riskLevel: RiskLevel
        get() = when {
            riskScore >= 90 -> RiskLevel.CRITICAL
            riskScore >= 70 -> RiskLevel.HIGH
            riskScore >= 40 -> RiskLevel.MODERATE
            else -> RiskLevel.LOW
        }
}
