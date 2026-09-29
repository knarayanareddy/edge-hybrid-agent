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
    val errorMessage: String? = null,
    val currentSessionId: String = "default_chat_session",
    val sessions: List<com.edgehybrid.agent.data.local.ChatSessionEntity> = emptyList(),
    val isSessionDrawerOpen: Boolean = false
)

data class ChatMessageUi(
    val id: String,
    val role: ChatMessageRole,
    val content: String,
    val imageDataUrl: String? = null,
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
    private val agentLoop: AgentLoop,
    private val keyStore: com.edgehybrid.agent.data.local.SecureKeyStore? = null,
    private val groqWhisperService: com.edgehybrid.agent.data.remote.GroqWhisperService? = null,
    private val chatDao: com.edgehybrid.agent.data.local.ChatDao? = null
) : ViewModel() {

    companion object {
        private const val DEFAULT_SESSION_ID = "default_chat_session"
    }

    private val mutableUiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = mutableUiState.asStateFlow()

    private var generationJob: Job? = null
    private var pendingRetry: PendingRetry? = null

    init {
        observeSessions()
        loadChatHistory(DEFAULT_SESSION_ID)
    }

    private fun observeSessions() {
        viewModelScope.launch {
            chatDao?.getAllSessions()?.collect { sessionList ->
                mutableUiState.update { it.copy(sessions = sessionList) }
            }
        }
    }

    fun toggleSessionDrawer(isOpen: Boolean? = null) {
        mutableUiState.update {
            it.copy(isSessionDrawerOpen = isOpen ?: !it.isSessionDrawerOpen)
        }
    }

    fun createNewSession() {
        cancelGeneration()
        val newSessionId = UUID.randomUUID().toString()
        val newSession = com.edgehybrid.agent.data.local.ChatSessionEntity(
            id = newSessionId,
            title = "New Chat",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            try {
                chatDao?.insertSession(newSession)
                mutableUiState.update {
                    it.copy(
                        currentSessionId = newSessionId,
                        messages = emptyList(),
                        isSessionDrawerOpen = false
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to create new session", e)
            }
        }
    }

    fun selectSession(sessionId: String) {
        if (sessionId == mutableUiState.value.currentSessionId) {
            mutableUiState.update { it.copy(isSessionDrawerOpen = false) }
            return
        }
        cancelGeneration()
        mutableUiState.update {
            it.copy(
                currentSessionId = sessionId,
                isSessionDrawerOpen = false
            )
        }
        loadChatHistory(sessionId)
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                chatDao?.deleteSession(sessionId)
                if (mutableUiState.value.currentSessionId == sessionId) {
                    val remaining = mutableUiState.value.sessions.filter { it.id != sessionId }
                    if (remaining.isNotEmpty()) {
                        selectSession(remaining.first().id)
                    } else {
                        createNewSession()
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to delete session", e)
            }
        }
    }

    private fun loadChatHistory(sessionId: String) {
        viewModelScope.launch {
            try {
                chatDao?.let { dao ->
                    if (dao.getSessionById(sessionId) == null) {
                        dao.insertSession(
                            com.edgehybrid.agent.data.local.ChatSessionEntity(
                                id = sessionId,
                                title = if (sessionId == DEFAULT_SESSION_ID) "Main Chat" else "New Chat"
                            )
                        )
                    }
                    val entities = dao.getMessagesListForSession(sessionId)
                    val loaded = entities.map { entity ->
                        ChatMessageUi(
                            id = entity.id,
                            role = if (entity.role.equals("user", ignoreCase = true)) ChatMessageRole.USER else ChatMessageRole.ASSISTANT,
                            content = entity.content,
                            imageDataUrl = entity.imageUrlsJson,
                            deliveryState = MessageDeliveryState.COMPLETE
                        )
                    }
                    mutableUiState.update {
                        it.copy(
                            currentSessionId = sessionId,
                            messages = loaded
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to restore persistent chat history", e)
            }
        }
    }

    private fun persistMessage(message: ChatMessageUi) {
        val sessionId = mutableUiState.value.currentSessionId
        viewModelScope.launch {
            try {
                chatDao?.let { dao ->
                    val existing = dao.getSessionById(sessionId)
                    if (existing == null) {
                        dao.insertSession(
                            com.edgehybrid.agent.data.local.ChatSessionEntity(
                                id = sessionId,
                                title = if (message.role == ChatMessageRole.USER) message.content.take(30).trim() else "Chat"
                            )
                        )
                    } else if (existing.title == "New Chat" && message.role == ChatMessageRole.USER && message.content.isNotBlank()) {
                        dao.updateSession(existing.copy(title = message.content.take(30).trim(), updatedAt = System.currentTimeMillis()))
                    }
                    dao.insertMessage(
                        com.edgehybrid.agent.data.local.ChatMessageEntity(
                            id = message.id,
                            sessionId = sessionId,
                            role = if (message.role == ChatMessageRole.USER) "user" else "assistant",
                            content = message.content,
                            imageUrlsJson = message.imageDataUrl,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to persist chat message", e)
            }
        }
    }

    fun cancelGeneration() {
        val job = generationJob
        if (job != null && job.isActive) {
            job.cancel()
            generationJob = null
        }
        var stoppedMsg: ChatMessageUi? = null
        mutableUiState.update { state ->
            val updated = state.messages.map { msg ->
                if (msg.deliveryState == MessageDeliveryState.STREAMING) {
                    val finalContent = if (msg.content.isBlank()) "[Generation stopped by user]" else "${msg.content}\n\n[Stopped by user]"
                    val stopped = msg.copy(
                        content = finalContent,
                        deliveryState = MessageDeliveryState.COMPLETE
                    )
                    stoppedMsg = stopped
                    stopped
                } else {
                    msg
                }
            }
            state.copy(
                messages = updated,
                isGenerating = false,
                errorMessage = null
            )
        }
        stoppedMsg?.let { persistMessage(it) }
    }

    fun clearChat() {
        val sessionId = mutableUiState.value.currentSessionId
        generationJob?.cancel()
        generationJob = null
        mutableUiState.update {
            it.copy(
                messages = emptyList(),
                isGenerating = false,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                chatDao?.deleteMessagesForSession(sessionId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to clear chat session", e)
            }
        }
    }

    fun transcribeMeetingAudio(
        audioBytes: ByteArray,
        fileName: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val groqKey = keyStore?.getGroqApiKey().orEmpty()
        if (groqKey.isBlank()) {
            onError("Groq API Key is not configured. Please open Settings and enter your free Groq API key from console.groq.com.")
            return
        }

        val whisper = groqWhisperService
        if (whisper == null) {
            onError("Groq Whisper service is not initialized.")
            return
        }

        viewModelScope.launch {
            val result = whisper.transcribeAudio(audioBytes, fileName, groqKey)
            result.onSuccess { text ->
                onSuccess(text)
            }.onFailure { err ->
                onError(err.message ?: "Audio transcription failed.")
            }
        }
    }

    fun send(userText: String, imageDataUrl: String? = null) {
        val normalizedText = userText.trim()
        if ((normalizedText.isEmpty() && imageDataUrl == null) || mutableUiState.value.isGenerating) {
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
            imageDataUrl = imageDataUrl,
            deliveryState = MessageDeliveryState.COMPLETE
        )
        persistMessage(userMessage)

        history += ChatMessage(
            role = ChatRoles.USER,
            content = normalizedText,
            imageDataUrl = imageDataUrl
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
                val userFeedback = when {
                    exception.message?.contains("401", ignoreCase = true) == true ->
                        "Invalid API Key. Please configure your key in Settings."
                    exception.message?.contains("429", ignoreCase = true) == true ->
                        "Rate limit exceeded. Please wait a moment."
                    !exception.message.isNullOrBlank() ->
                        exception.message ?: "The agent could not complete this request."
                    else -> "The agent could not complete this request."
                }
                showFailure(
                    assistantMessageId = assistantMessageId,
                    message = userFeedback
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
                var finishedMsg: ChatMessageUi? = null
                updateMessage(assistantMessageId) { message ->
                    val content = if (
                        message.content.isBlank() &&
                        event.finalText.isNotBlank()
                    ) {
                        event.finalText
                    } else {
                        message.content
                    }

                    val updated = message.copy(
                        content = content,
                        deliveryState = MessageDeliveryState.COMPLETE,
                        recoveryMessage = null
                    )
                    finishedMsg = updated
                    updated
                }
                finishedMsg?.let { persistMessage(it) }
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
        var failedMsg: ChatMessageUi? = null
        updateMessage(assistantMessageId) { assistantMessage ->
            val updated = assistantMessage.copy(
                content = assistantMessage.content.ifBlank { message },
                deliveryState = MessageDeliveryState.FAILED,
                recoveryMessage = null
            )
            failedMsg = updated
            updated
        }
        failedMsg?.let { persistMessage(it) }
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
    if (content.isBlank() && imageDataUrl == null) {
        return null
    }

    return when (role) {
        ChatMessageRole.USER -> ChatMessage(
            role = ChatRoles.USER,
            content = content,
            imageDataUrl = imageDataUrl
        )

        ChatMessageRole.ASSISTANT -> ChatMessage(
            role = ChatRoles.ASSISTANT,
            content = content
        )
    }
}