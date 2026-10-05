package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.data.model.ChatRoles
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.tool.ToolCatalog
import com.edgehybrid.agent.tool.ToolExecutionOutcome
import com.edgehybrid.agent.tool.ToolGateway
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    private val confirmationGate: ConfirmationGate,
    private val confirmationCoordinator: ConfirmationCoordinator,
    private val policy: AgentPolicy,
    private val clock: MonotonicClock,
    private val suspendDelay: SuspendDelay,
    // Both were previously provided by Hilt and called by nobody: the lessons
    // ledger had a full DAO-backed implementation that no code path ever read or
    // wrote, so the "self-correcting" claim was inert. They are constructor deps
    // precisely so they cannot be omitted again without a compile error.
    private val contextProvider: AgentContextProvider
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
            // A provider 4xx means the REQUEST was wrong, which is durable and
            // teachable: a bad model id or a malformed body stays wrong until
            // something changes. 5xx/429 are the server being briefly unwell and are
            // dropped by the classifier, so a provider blip cannot permanently
            // degrade the agent's behaviour.
            (exception as? ProviderHttpException)?.let { http ->
                contextProvider.onProviderError(http.statusCode, http.responseSnippet)
            }
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
            val lastUserText = workingHistory.lastOrNull { it.role == ChatRoles.USER }?.content.orEmpty()
            workingHistory.add(
                0,
                ChatMessage(
                    role = ChatRoles.SYSTEM,
                    content = contextProvider.buildSystemPrompt(lastUserText)
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

        // Jev narrows the tool payload for the request. It is an optimization only:
        // ToolCatalog.prunedFor keeps dispatch intact, and an unavailable or unsure
        // selection returns the full catalog.
        val fullCatalog = toolGateway.loadCatalog()
        val intentPrompt = workingHistory.lastOrNull { it.role == ChatRoles.USER }?.content.orEmpty()
        val catalog = if (intentPrompt.isBlank()) {
            fullCatalog
        } else {
            toolGateway.pruneCatalogFor(intentPrompt, fullCatalog)
        }
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

    /**
     * Executes model-authored tool calls, forcing every non-read-only call through the
     * confirmation gate before it can touch the device.
     *
     * Calls are run **sequentially**, not with `async`/`awaitAll`, because a confirmation
     * request suspends this coroutine while it waits for a UI response. Running them
     * concurrently would interleave several dialogs and let approvals race. Read-only
     * calls are still safe to run inline; the order of side effects is now the order the
     * model requested, which is also the order the user saw and approved.
     */
    private suspend fun FlowCollector<AgentStreamEvent>.executeToolCalls(
        toolCalls: List<ModelToolCall>,
        catalog: ToolCatalog,
        workingHistory: MutableList<ChatMessage>
    ) {
        for (call in toolCalls) {
            emit(
                AgentStreamEvent.ToolExecutionStarted(
                    callId = call.id,
                    toolName = call.function.name
                )
            )

            val outcome = runGatedToolCall(call, catalog)

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

    /**
     * Gates one tool call, suspending for user approval when required.
     *
     * Fail-closed: any decline, timeout, or unexpected state produces an error outcome
     * and the tool never runs.
     */
    private suspend fun FlowCollector<AgentStreamEvent>.runGatedToolCall(
        call: ModelToolCall,
        catalog: ToolCatalog
    ): ToolExecutionOutcome {
        // Remote MCP tools can have arbitrary side effects on a server we do not
        // control, so they are always confirmed: there is no auto-approve tier for them.
        val isRemote = call.function.name in catalog.remoteToolNames

        val gated = confirmationGate.gate(call, isRemote)

        return when (gated) {
            is ConfirmationGate.GateResult.Approved -> runApproved(
                toolName = call.function.name,
                execute = gated.execute
            )

            is ConfirmationGate.GateResult.Rejected -> {
                // The user declined. This is the most durable signal the agent gets:
                // the action is unwanted, not broken. Previously discarded entirely.
                contextProvider.onConfirmationRejected(call.function.name, gated.reason)
                ToolExecutionOutcome(
                    content = declinedContent(gated.reason),
                    isError = true
                )
            }

            is ConfirmationGate.GateResult.NeedsApproval -> {
                emit(
                    AgentStreamEvent.ConfirmationRequired(
                        callId = call.id,
                        confirmation = gated.confirmation
                    )
                )

                val approved = confirmationCoordinator.requestApproval(
                    callId = call.id,
                    confirmation = gated.confirmation
                )

                if (!approved) {
                    emit(
                        AgentStreamEvent.ConfirmationDeclined(
                            callId = call.id,
                            reason = "The user declined this action."
                        )
                    )
                    return ToolExecutionOutcome(
                        content = declinedContent("The user declined this action."),
                        isError = true
                    )
                }

                emit(AgentStreamEvent.ConfirmationApproved(callId = call.id))

                val released = confirmationGate.runApproved(
                    call = call,
                    confirmationId = gated.confirmation.id
                )
                when (released) {
                    is ConfirmationGate.GateResult.Approved -> runApproved(
                        toolName = call.function.name,
                        execute = released.execute
                    )

                    is ConfirmationGate.GateResult.Rejected -> {
                        // Declined AFTER the user was prompted, which is the stronger
                        // signal: they saw the explanation and still said no.
                        contextProvider.onConfirmationRejected(call.function.name, released.reason)
                        ToolExecutionOutcome(
                            content = declinedContent(released.reason),
                            isError = true
                        )
                    }

                    is ConfirmationGate.GateResult.NeedsApproval -> ToolExecutionOutcome(
                        content = declinedContent("This action is no longer valid."),
                        isError = true
                    )
                }
            }
        }
    }

    private suspend fun runApproved(
        toolName: String,
        execute: suspend () -> String
    ): ToolExecutionOutcome = try {
        ToolExecutionOutcome(content = execute(), isError = false)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // Never learn from a cancellation: the user navigated away, nothing failed.
        throw cancelled
    } catch (exception: Exception) {
        // Every tool failure funnels through here, so this is the one place that
        // has to feed the ledger. The classifier drops transient causes, so a
        // network blip does not become a permanent rule in the system prompt.
        val message = exception.message ?: "Tool failed"
        contextProvider.onToolError(toolName, message, isError = true)
        ToolExecutionOutcome(
            content = buildJsonObject { put("error", message) }.toString(),
            isError = true
        )
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