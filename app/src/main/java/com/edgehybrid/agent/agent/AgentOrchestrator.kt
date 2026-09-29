package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.data.model.ChatRoles
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.tool.ToolCatalog
import com.edgehybrid.agent.tool.ToolGateway
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

interface AgentLoop {
    fun streamChat(history: List<ChatMessage>): Flow<AgentStreamEvent>

    fun continueAfterDisconnect(
        history: List<ChatMessage>,
        partialText: String
    ): Flow<AgentStreamEvent>
}

@Singleton
class AgentOrchestrator @Inject constructor(
    private val inferenceEngine: InferenceEngine,
    private val toolGateway: ToolGateway,
    private val policy: AgentPolicy,
    private val clock: MonotonicClock,
    private val suspendDelay: SuspendDelay
) : AgentLoop {

    override fun streamChat(history: List<ChatMessage>): Flow<AgentStreamEvent> =
        executeAgent(history, continuationText = null)

    override fun continueAfterDisconnect(
        history: List<ChatMessage>,
        partialText: String
    ): Flow<AgentStreamEvent> = executeAgent(
        history = history,
        continuationText = partialText
    )

    private fun executeAgent(
        history: List<ChatMessage>,
        continuationText: String?
    ): Flow<AgentStreamEvent> = flow {
        try {
            executeLoop(history, continuationText)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            emit(AgentStreamEvent.Failed(exception.toUserMessage()))
        }
    }

    private suspend fun FlowCollector<AgentStreamEvent>.executeLoop(
        history: List<ChatMessage>,
        continuationText: String?
    ) {
        val workingHistory = history.toMutableList()
        val hasSystemPrompt = workingHistory.any { it.role == ChatRoles.SYSTEM }
        if (!hasSystemPrompt) {
            workingHistory.add(
                0,
                ChatMessage(
                    role = ChatRoles.SYSTEM,
                    content = """You are Edge Hybrid Agent, an intelligent, helpful, and highly knowledgeable mobile AI assistant running on a Samsung Galaxy device.
You can answer any questions, explain complex concepts, research companies, write code, brainstorm, and converse naturally on any topic.
You also have access to native device tools and cloud skills (such as checking current weather, converting units, setting timers, or controlling device hardware).
If a user asks a general question or asks about a company, place, or concept, answer it thoroughly, accurately, and conversationally using your broad general knowledge. Only call a tool when specifically needed."""
                )
            )
        }
        if (continuationText != null) {
            if (continuationText.isNotEmpty()) {
                workingHistory += ChatMessage(
                    role = ChatRoles.ASSISTANT,
                    content = continuationText
                )
            }
            workingHistory += ChatMessage(
                role = ChatRoles.USER,
                content = CONTINUATION_INSTRUCTION
            )
        }

        val catalog = toolGateway.loadCatalog()
        val aggregateUsage = UsageAccumulator()
        val emittedAssistantText = StringBuilder()
        val startedAtNanos = clock.nowNanos()
        var firstTokenAtNanos: Long? = null
        var streamRecoveryCount = 0
        var iteration = 0

        while (iteration < policy.maxIterations) {
            iteration += 1
            val turnStartedAtNanos = clock.nowNanos()
            val currentTurnText = StringBuilder()
            var completedTurn: ModelTurn? = null

            try {
                inferenceEngine.streamChat(
                    messages = workingHistory.toList(),
                    tools = catalog.definitions
                ).collect { cloudEvent ->
                    when (cloudEvent) {
                        is CloudStreamEvent.AssistantDelta -> {
                            if (firstTokenAtNanos == null) {
                                firstTokenAtNanos = turnStartedAtNanos
                            }
                            currentTurnText.append(cloudEvent.text)
                            emittedAssistantText.append(cloudEvent.text)
                            emit(AgentStreamEvent.AssistantDelta(cloudEvent.text))
                        }

                        is CloudStreamEvent.TurnCompleted -> {
                            completedTurn = cloudEvent.turn
                            if (
                                firstTokenAtNanos == null &&
                                cloudEvent.turn.timeToFirstTokenMs != null
                            ) {
                                firstTokenAtNanos = turnStartedAtNanos +
                                    TimeUnit.MILLISECONDS.toNanos(
                                        cloudEvent.turn.timeToFirstTokenMs
                                    )
                            }
                        }
                    }
                }
            } catch (disconnected: AgentDisconnectedException) {
                val partialText = currentTurnText
                    .toString()
                    .ifEmpty { disconnected.partialText }

                if (
                    iteration >= policy.maxIterations ||
                    streamRecoveryCount >= policy.maxStreamRecoveries
                ) {
                    emit(
                        AgentStreamEvent.RecoveryRequired(
                            partialText = emittedAssistantText.toString(),
                            message = "The connection dropped before the response completed."
                        )
                    )
                    return
                }

                val delayMs = policy.backoffFor(streamRecoveryCount)
                streamRecoveryCount += 1

                emit(
                    AgentStreamEvent.Recovering(
                        attempt = streamRecoveryCount,
                        delayMs = delayMs,
                        partialText = emittedAssistantText.toString()
                    )
                )

                if (partialText.isNotEmpty()) {
                    workingHistory += ChatMessage(
                        role = ChatRoles.ASSISTANT,
                        content = partialText
                    )
                }
                workingHistory += ChatMessage(
                    role = ChatRoles.USER,
                    content = CONTINUATION_INSTRUCTION
                )

                suspendDelay.wait(delayMs)
                continue
            }

            val turn = completedTurn
                ?: throw IllegalStateException("Inference stream ended without a model turn")
            aggregateUsage.add(turn.usage)
            streamRecoveryCount = 0

            if (turn.toolCalls.isNotEmpty()) {
                if (iteration >= policy.maxIterations) {
                    emit(
                        AgentStreamEvent.Failed(
                            "The agent reached its ${policy.maxIterations}-step limit " +
                                "before producing a final answer."
                        )
                    )
                    return
                }

                workingHistory += ChatMessage(
                    role = ChatRoles.ASSISTANT,
                    content = turn.content.takeIf(String::isNotEmpty),
                    toolCalls = turn.toolCalls
                )

                executeToolCalls(turn.toolCalls, catalog, workingHistory)
                continue
            }

            workingHistory += ChatMessage(
                role = ChatRoles.ASSISTANT,
                content = turn.content
            )

            val totalGenerationTimeMs = TimeUnit.NANOSECONDS.toMillis(
                (clock.nowNanos() - startedAtNanos).coerceAtLeast(0L)
            )
            val ttftMs = firstTokenAtNanos?.let { firstToken ->
                TimeUnit.NANOSECONDS.toMillis(
                    (firstToken - startedAtNanos).coerceAtLeast(0L)
                )
            }

            emit(
                AgentStreamEvent.Completed(
                    finalText = turn.content,
                    usage = aggregateUsage.snapshot(),
                    telemetry = GenerationTelemetry(
                        timeToFirstTokenMs = ttftMs,
                        totalGenerationTimeMs = totalGenerationTimeMs
                    )
                )
            )
            return
        }

        emit(
            AgentStreamEvent.Failed(
                "The agent reached its ${policy.maxIterations}-step limit."
            )
        )
    }

    private suspend fun FlowCollector<AgentStreamEvent>.executeToolCalls(
        toolCalls: List<ModelToolCall>,
        catalog: ToolCatalog,
        workingHistory: MutableList<ChatMessage>
    ) {
        toolCalls.forEach { call ->
            emit(
                AgentStreamEvent.ToolExecutionStarted(
                    callId = call.id,
                    toolName = call.function.name
                )
            )
        }

        val outcomes = coroutineScope {
            toolCalls
                .map { call -> async { toolGateway.execute(call, catalog) } }
                .awaitAll()
        }

        toolCalls.forEachIndexed { index, call ->
            val outcome = outcomes[index]
            emit(
                AgentStreamEvent.ToolExecutionCompleted(
                    callId = call.id,
                    toolName = call.function.name,
                    succeeded = !outcome.isError
                )
            )
            workingHistory += ChatMessage(
                role = ChatRoles.TOOL,
                content = outcome.content,
                name = call.function.name,
                toolCallId = call.id
            )
        }
    }

    private fun Exception.toUserMessage(): String =
        when (this) {
            is ProviderHttpException -> when (statusCode) {
                401, 403 -> "The cloud provider rejected the application credentials."
                429 -> "The cloud provider is rate limiting requests. Try again shortly."
                503 -> "The cloud provider is temporarily overloaded. Try again shortly."
                else -> "The cloud provider returned HTTP $statusCode."
            }

            is AgentDisconnectedException ->
                "The network connection was interrupted. Retry to continue."

            is java.io.IOException ->
                "The network is unavailable. Check connectivity and retry."

            else -> "The agent could not complete the request."
        }

    private class UsageAccumulator {
        private var promptTokens = 0L
        private var completionTokens = 0L
        private var everyTurnReportedUsage = true
        private var sawTurn = false

        fun add(usage: TokenUsage) {
            sawTurn = true
            promptTokens += usage.promptTokens
            completionTokens += usage.completionTokens
            everyTurnReportedUsage =
                everyTurnReportedUsage && usage.providerReported
        }

        fun snapshot(): TokenUsage = TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            providerReported = sawTurn && everyTurnReportedUsage
        )
    }

    companion object {
        private const val CONTINUATION_INSTRUCTION =
            "Continue the interrupted assistant response from exactly where it stopped. " +
                "Do not repeat text that was already produced. Preserve any answer already " +
                "completed and return only the missing continuation."
    }
}