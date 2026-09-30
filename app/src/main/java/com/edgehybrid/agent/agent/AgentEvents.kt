package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.model.ModelToolCall

data class TokenUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val providerReported: Boolean
)

data class GenerationTelemetry(
    val timeToFirstTokenMs: Long?,
    val totalGenerationTimeMs: Long
)

data class ModelTurn(
    val content: String,
    val toolCalls: List<ModelToolCall>,
    val finishReason: String?,
    val usage: TokenUsage,
    val timeToFirstTokenMs: Long?,
    val generationTimeMs: Long
)

sealed interface CloudStreamEvent {
    data class AssistantDelta(val text: String) : CloudStreamEvent

    data class TurnCompleted(
        val turn: ModelTurn
    ) : CloudStreamEvent
}

sealed interface AgentStreamEvent {
    data class AssistantDelta(val text: String) : AgentStreamEvent

    data class ToolExecutionStarted(
        val callId: String,
        val toolName: String
    ) : AgentStreamEvent

    data class ToolExecutionCompleted(
        val callId: String,
        val toolName: String,
        val succeeded: Boolean
    ) : AgentStreamEvent

    /**
     * A tool call needs explicit user approval before it can run. The collector must
     * suspend the agent loop until the user responds.
     */
    data class ConfirmationRequired(
        val callId: String,
        val confirmation: com.edgehybrid.agent.nativeactions.ActionConfirmation
    ) : AgentStreamEvent

    /** The user approved the pending action; execution may proceed. */
    data class ConfirmationApproved(
        val callId: String
    ) : AgentStreamEvent

    /** The user declined (or the request expired); the tool must not run. */
    data class ConfirmationDeclined(
        val callId: String,
        val reason: String
    ) : AgentStreamEvent

    data class Recovering(
        val attempt: Int,
        val delayMs: Long,
        val partialText: String
    ) : AgentStreamEvent

    data class RecoveryRequired(
        val partialText: String,
        val message: String
    ) : AgentStreamEvent

    data class Completed(
        val finalText: String,
        val usage: TokenUsage,
        val telemetry: GenerationTelemetry
    ) : AgentStreamEvent

    data class Failed(
        val message: String
    ) : AgentStreamEvent
}