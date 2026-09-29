package com.edgehybrid.agent.jev

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-execution risk classifier evaluating the danger level of proposed agent actions.
 */
@Singleton
class JevRiskClassifier @Inject constructor() {

    fun classifyAction(actionName: String, parametersJson: String = ""): JevEvaluationResponse {
        val lowerAction = actionName.lowercase()
        val lowerParams = parametersJson.lowercase()

        // Hard Critical Actions (>= 90): Destructive file deletions, wiping data, formatting
        val criticalPatterns = listOf("bulk_delete", "wipe", "rm -rf", "drop table", "factory_reset", "format_disk")
        if (criticalPatterns.any { lowerAction.contains(it) || lowerParams.contains(it) }) {
            return JevEvaluationResponse(
                verdict = "REJECT",
                riskScore = 95,
                isFalsePositive = false,
                reasons = listOf("Operation matches critical destructive risk patterns"),
                suggestedConstraints = listOf("Prohibit automated execution of bulk destruction commands")
            )
        }

        // High Risk Actions (>= 70): SMS sending, phone calls, calendar mutations
        val highRiskActions = listOf("send_sms", "create_calendar_event", "execute_payment", "modify_system_setting", "initiate_phone_call")
        if (highRiskActions.any { lowerAction.contains(it) }) {
            return JevEvaluationResponse(
                verdict = "CONFIRMATION_REQUIRED",
                riskScore = 75,
                isFalsePositive = false,
                reasons = listOf("Action incurs external communication or system state change"),
                suggestedConstraints = listOf("Require explicit interactive user confirmation before execution")
            )
        }

        // Moderate Risk Actions (40 - 69): External network requests via web_extract or MCP
        if (lowerAction.contains("web_extract") || lowerAction.contains("extract_webpage") || lowerAction.startsWith("mcp:")) {
            return JevEvaluationResponse(
                verdict = "ALLOW_MONITORED",
                riskScore = 45,
                isFalsePositive = false,
                reasons = listOf("External network communication"),
                suggestedConstraints = listOf("Enforce origin allowlists and 5000ms watchdog")
            )
        }

        // Low Risk Actions (< 40): Safe local computations, timers, notes, flashlight
        return JevEvaluationResponse(
            verdict = "ALLOW",
            riskScore = 15,
            isFalsePositive = false,
            reasons = listOf("Safe localized operation"),
            suggestedConstraints = emptyList()
        )
    }
}
