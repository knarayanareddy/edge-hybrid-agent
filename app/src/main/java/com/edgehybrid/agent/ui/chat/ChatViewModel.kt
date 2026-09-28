package com.edgehybrid.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgehybrid.agent.core.inference.ChatMessage
import com.edgehybrid.agent.core.inference.CloudInferenceEngine
import com.edgehybrid.agent.core.inference.GenerationConfig
import com.edgehybrid.agent.core.inference.StreamChunk
import com.edgehybrid.agent.core.jev.JevDispatcher
import com.edgehybrid.agent.core.jev.RouteDecision
import com.edgehybrid.agent.core.tools.SkillLoader
import com.edgehybrid.agent.data.local.ChatDao
import com.edgehybrid.agent.data.local.ChatMessageEntity
import com.edgehybrid.agent.data.local.ChatSessionEntity
import com.edgehybrid.agent.data.local.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val currentSessionId: String? = null,
    val sessions: List<ChatSessionEntity> = emptyList(),
    val messages: List<ChatMessageEntity> = emptyList(),
    val streamingContent: String = "",
    val isStreaming: Boolean = false,
    val activeRouteReasoning: String = "",
    val activeModelName: String = "",
    val error: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatDao: ChatDao,
    private val cloudEngine: CloudInferenceEngine,
    private val jevDispatcher: JevDispatcher,
    private val skillLoader: SkillLoader,
    private val keyStore: SecureKeyStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
    }

    private fun loadSessions() {
        viewModelScope.launch {
            chatDao.getAllSessions().collect { sessions ->
                _uiState.value = _uiState.value.copy(sessions = sessions)
                if (_uiState.value.currentSessionId == null && sessions.isNotEmpty()) {
                    selectSession(sessions.first().id)
                } else if (sessions.isEmpty()) {
                    createNewSession("New Chat")
                }
            }
        }
    }

    fun selectSession(sessionId: String) {
        _uiState.value = _uiState.value.copy(currentSessionId = sessionId)
        viewModelScope.launch {
            chatDao.getMessagesForSession(sessionId).collect { messages ->
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }
    }

    fun createNewSession(title: String = "New Chat") {
        viewModelScope.launch {
            val session = ChatSessionEntity(
                id = UUID.randomUUID().toString(),
                title = title
            )
            chatDao.insertSession(session)
            selectSession(session.id)
        }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank() || _uiState.value.isStreaming) return
        val sessionId = _uiState.value.currentSessionId ?: return

        viewModelScope.launch {
            // 1. Record user message
            val userMsg = ChatMessageEntity(
                sessionId = sessionId,
                role = "user",
                content = userText
            )
            chatDao.insertMessage(userMsg)

            // 2. Fetch recent conversation context
            val history = chatDao.getMessagesListForSession(sessionId).map {
                ChatMessage(role = it.role, content = it.content)
            }

            // 3. JEV Dispatcher: Routing decision & safety check
            val dispatchResult = jevDispatcher.dispatch(userText, history)
            when (val decision = dispatchResult.route) {
                is RouteDecision.Reject -> {
                    val rejectMsg = ChatMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = "⚠️ ${decision.reason}"
                    )
                    chatDao.insertMessage(rejectMsg)
                    return@launch
                }
                is RouteDecision.LocalLiteRT -> {
                    // Local fallback / on-device execution
                    _uiState.value = _uiState.value.copy(
                        activeModelName = "Local LiteRT",
                        activeRouteReasoning = decision.reasoning
                    )
                }
                is RouteDecision.CloudModel -> {
                    _uiState.value = _uiState.value.copy(
                        activeModelName = decision.modelId,
                        activeRouteReasoning = decision.reasoning
                    )
                }
            }

            // 4. Stream response
            _uiState.value = _uiState.value.copy(
                isStreaming = true,
                streamingContent = "",
                error = null
            )

            val startTime = System.currentTimeMillis()
            val fullResponseBuilder = StringBuilder()

            try {
                val tools = skillLoader.getAllToolDefinitions()
                val flow = cloudEngine.streamChat(
                    messages = history,
                    config = GenerationConfig(
                        model = _uiState.value.activeModelName.ifEmpty { keyStore.getSelectedCloudModel() },
                        systemPrompt = "You are an intelligent edge-cloud hybrid agent. Follow all established guidelines strictly."
                    ),
                    tools = tools
                )

                flow.collect { chunk ->
                    when (chunk) {
                        is StreamChunk.Delta -> {
                            fullResponseBuilder.append(chunk.text)
                            _uiState.value = _uiState.value.copy(
                                streamingContent = fullResponseBuilder.toString()
                            )
                        }
                        is StreamChunk.Finished -> {
                            val latency = System.currentTimeMillis() - startTime
                            val responseText = fullResponseBuilder.toString()

                            // JEV Post-generation Review & Autonomous Learning
                            val review = jevDispatcher.reviewAndLearn(userText, responseText)

                            val assistantMsg = ChatMessageEntity(
                                sessionId = sessionId,
                                role = "assistant",
                                content = responseText,
                                modelUsed = _uiState.value.activeModelName,
                                latencyMs = latency
                            )
                            chatDao.insertMessage(assistantMsg)

                            _uiState.value = _uiState.value.copy(
                                isStreaming = false,
                                streamingContent = ""
                            )
                        }
                        is StreamChunk.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isStreaming = false,
                                error = chunk.message
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isStreaming = false,
                    error = e.message ?: "Unknown inference error"
                )
            }
        }
    }
}
