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