package com.edgehybrid.agent.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgehybrid.agent.agent.AgentLoop
import com.edgehybrid.agent.agent.AgentStreamEvent
import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.data.model.ChatRoles
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val isGenerating: Boolean = false,
    val errorMessage: String? = null
)

data class ChatMessageUi(
    val id: String,
    val role: ChatMessageRole,
    val content: String,
    val deliveryState: MessageDeliveryState,
    val toolActivities: List<ToolActivityUi> = emptyList(),
    val recoveryMessage: String? = null
)

enum class ChatMessageRole {
    USER,
    ASSISTANT
}

enum class MessageDeliveryState {
    STREAMING,
    COMPLETE,
    RECOVERY_REQUIRED,
    FAILED
}

enum class ToolActivityStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}

data class ToolActivityUi(
    val callId: String,
    val toolName: String,
    val status: ToolActivityStatus
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoop: AgentLoop
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = mutableUiState.asStateFlow()

    private var generationJob: Job? = null
    private var pendingRetry: PendingRetry? = null

    fun send(userText: String) {
        val normalizedText = userText.trim()
        if (normalizedText.isEmpty() || mutableUiState.value.isGenerating) {
            return
        }

        val history = mutableUiState.value.messages
            .filter { it.deliveryState == MessageDeliveryState.COMPLETE }
            .mapNotNull(ChatMessageUi::toHistoryMessage)
            .toMutableList()

        val userMessage = ChatMessageUi(
            id = UUID.randomUUID().toString(),
            role = ChatMessageRole.USER,
            content = normalizedText,
            deliveryState = MessageDeliveryState.COMPLETE
        )
        history += ChatMessage(
            role = ChatRoles.USER,
            content = normalizedText
        )

        mutableUiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isGenerating = true,
                errorMessage = null
            )
        }

        startAgent(
            baseHistory = history,
            continuationText = null
        )
    }

    fun retryRecovery() {
        val retry = pendingRetry ?: return
        if (mutableUiState.value.isGenerating) {
            return
        }

        mutableUiState.update { state ->
            state.copy(
                isGenerating = true,
                errorMessage = null,
                messages = state.messages.map { message ->
                    if (message.id == retry.assistantMessageId) {
                        message.copy(
                            deliveryState = MessageDeliveryState.STREAMING,
                            recoveryMessage = null
                        )
                    } else {
                        message
                    }
                }
            )
        }

        startAgent(
            baseHistory = retry.baseHistory,
            continuationText = retry.partialText
        )
    }

    private fun startAgent(
        baseHistory: List<ChatMessage>,
        continuationText: String?
    ) {
        val assistantMessageId = UUID.randomUUID().toString()
        pendingRetry = PendingRetry(
            baseHistory = baseHistory,
            assistantMessageId = assistantMessageId,
            partialText = continuationText.orEmpty()
        )

        mutableUiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessageUi(
                    id = assistantMessageId,
                    role = ChatMessageRole.ASSISTANT,
                    content = "",
                    deliveryState = MessageDeliveryState.STREAMING
                )
            )
        }

        generationJob = viewModelScope.launch {
            try {
                val events = if (continuationText == null) {
                    agentLoop.streamChat(baseHistory)
                } else {
                    agentLoop.continueAfterDisconnect(
                        history = baseHistory,
                        partialText = continuationText
                    )
                }

                events.collect { event ->
                    handleAgentEvent(assistantMessageId, event)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.e("ChatViewModel", "Agent stream failed", exception)
                showFailure(
                    assistantMessageId = assistantMessageId,
                    message = "The agent could not complete this request."
                )
            }
        }
    }

    private fun handleAgentEvent(
        assistantMessageId: String,
        event: AgentStreamEvent
    ) {
        when (event) {
            is AgentStreamEvent.AssistantDelta ->
                appendAssistantDelta(assistantMessageId, event.text)

            is AgentStreamEvent.ToolExecutionStarted ->
                updateMessage(assistantMessageId) { message ->
                    message.copy(
                        toolActivities = message.toolActivities + ToolActivityUi(
                            callId = event.callId,
                            toolName = event.toolName,
                            status = ToolActivityStatus.RUNNING
                        )
                    )
                }

            is AgentStreamEvent.ToolExecutionCompleted ->
                updateMessage(assistantMessageId) { message ->
                    message.copy(
                        toolActivities = message.toolActivities.map { activity ->
                            if (activity.callId == event.callId) {
                                activity.copy(
                                    status = if (event.succeeded) {
                                        ToolActivityStatus.SUCCEEDED
                                    } else {
                                        ToolActivityStatus.FAILED
                                    }
                                )
                            } else {
                                activity
                            }
                        }
                    )
                }

            is AgentStreamEvent.Recovering ->
                updateMessage(assistantMessageId) { message ->
                    message.copy(
                        deliveryState = MessageDeliveryState.STREAMING,
                        recoveryMessage = null
                    )
                }

            is AgentStreamEvent.RecoveryRequired -> {
                pendingRetry = pendingRetry?.copy(partialText = event.partialText)
                updateMessage(assistantMessageId) { message ->
                    message.copy(
                        deliveryState = MessageDeliveryState.RECOVERY_REQUIRED,
                        recoveryMessage = event.message
                    )
                }
                mutableUiState.update { state ->
                    state.copy(isGenerating = false)
                }
            }

            is AgentStreamEvent.Completed -> {
                updateMessage(assistantMessageId) { message ->
                    val content = if (
                        message.content.isBlank() &&
                        event.finalText.isNotBlank()
                    ) {
                        event.finalText
                    } else {
                        message.content
                    }

                    message.copy(
                        content = content,
                        deliveryState = MessageDeliveryState.COMPLETE,
                        recoveryMessage = null
                    )
                }
                pendingRetry = null
                mutableUiState.update { state ->
                    state.copy(
                        isGenerating = false,
                        errorMessage = null
                    )
                }
            }

            is AgentStreamEvent.Failed ->
                showFailure(assistantMessageId, event.message)
        }
    }

    private fun appendAssistantDelta(
        assistantMessageId: String,
        delta: String
    ) {
        updateMessage(assistantMessageId) { message ->
            message.copy(content = message.content + delta)
        }
    }

    private fun updateMessage(
        messageId: String,
        transform: (ChatMessageUi) -> ChatMessageUi
    ) {
        mutableUiState.update { state ->
            val index = state.messages.indexOfFirst { it.id == messageId }
            if (index < 0) {
                state
            } else {
                state.copy(
                    messages = state.messages.toMutableList().apply {
                        this[index] = transform(this[index])
                    }
                )
            }
        }
    }

    private fun showFailure(
        assistantMessageId: String,
        message: String
    ) {
        updateMessage(assistantMessageId) { assistantMessage ->
            assistantMessage.copy(
                content = assistantMessage.content.ifBlank { message },
                deliveryState = MessageDeliveryState.FAILED,
                recoveryMessage = null
            )
        }
        pendingRetry = null
        mutableUiState.update { state ->
            state.copy(
                isGenerating = false,
                errorMessage = message
            )
        }
    }

    private data class PendingRetry(
        val baseHistory: List<ChatMessage>,
        val assistantMessageId: String,
        val partialText: String
    )
}

private fun ChatMessageUi.toHistoryMessage(): ChatMessage? {
    if (content.isBlank()) {
        return null
    }

    return when (role) {
        ChatMessageRole.USER -> ChatMessage(
            role = ChatRoles.USER,
            content = content
        )

        ChatMessageRole.ASSISTANT -> ChatMessage(
            role = ChatRoles.ASSISTANT,
            content = content
        )
    }
}