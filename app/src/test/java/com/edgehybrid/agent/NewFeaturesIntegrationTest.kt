package com.edgehybrid.agent

import com.edgehybrid.agent.agent.AgentLoop
import com.edgehybrid.agent.agent.AgentStreamEvent
import com.edgehybrid.agent.data.local.ChatDao
import com.edgehybrid.agent.data.local.ChatMessageEntity
import com.edgehybrid.agent.data.local.ChatSessionEntity
import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.ui.chat.ChatMessageRole
import com.edgehybrid.agent.ui.chat.ChatViewModel
import com.edgehybrid.agent.ui.chat.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class NewFeaturesIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    class FakeChatDao : ChatDao {
        val sessions = mutableListOf<ChatSessionEntity>()
        val messages = mutableListOf<ChatMessageEntity>()

        override fun getAllSessions(): Flow<List<ChatSessionEntity>> = flowOf(sessions.toList())

        override suspend fun getSessionById(sessionId: String): ChatSessionEntity? =
            sessions.find { it.id == sessionId }

        override suspend fun insertSession(session: ChatSessionEntity) {
            sessions.removeIf { it.id == session.id }
            sessions.add(session)
        }

        override suspend fun updateSession(session: ChatSessionEntity) {
            insertSession(session)
        }

        override suspend fun deleteSession(sessionId: String) {
            sessions.removeIf { it.id == sessionId }
            messages.removeIf { it.sessionId == sessionId }
        }

        override fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>> =
            flowOf(messages.filter { it.sessionId == sessionId })

        override suspend fun getMessagesListForSession(sessionId: String): List<ChatMessageEntity> =
            messages.filter { it.sessionId == sessionId }

        override suspend fun insertMessage(message: ChatMessageEntity) {
            messages.removeIf { it.id == message.id }
            messages.add(message)
        }

        override suspend fun deleteMessagesForSession(sessionId: String) {
            messages.removeIf { it.sessionId == sessionId }
        }
    }

    private class SimpleEchoAgentLoop : AgentLoop {
        override fun streamChat(history: List<ChatMessage>): Flow<AgentStreamEvent> = flow {
            emit(AgentStreamEvent.AssistantDelta("Echo: " + (history.lastOrNull()?.content ?: "")))
            emit(AgentStreamEvent.Complete(history.lastOrNull()?.content ?: "", null, null))
        }
    }

    @Test
    fun testMultiChatSessionSwitchingAndContextIsolation() = runTest {
        val fakeDao = FakeChatDao()
        val agentLoop = SimpleEchoAgentLoop()
        val viewModel = ChatViewModel(agentLoop = agentLoop, keyStore = null, chatDao = fakeDao)

        // 1. Initial default session created
        val session1Id = viewModel.uiState.value.currentSessionId
        assertTrue(session1Id.isNotBlank())

        // Send a message in Session 1
        viewModel.send("Plan a trip to Japan")
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals("Plan a trip to Japan", viewModel.uiState.value.messages[0].content)

        // Verify message persisted in fakeDao for session 1
        val session1Messages = fakeDao.getMessagesListForSession(session1Id)
        assertTrue(session1Messages.isNotEmpty())
        assertEquals("Plan a trip to Japan", session1Messages[0].content)

        // 2. Create a New Session (Session 2)
        viewModel.createNewSession()
        advanceUntilIdle()

        val session2Id = viewModel.uiState.value.currentSessionId
        assertNotEquals(session1Id, session2Id)
        // Session 2 should have clean slate (0 messages)
        assertEquals(0, viewModel.uiState.value.messages.size)

        // Send a message in Session 2
        viewModel.send("Open camera to scan QR code")
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals("Open camera to scan QR code", viewModel.uiState.value.messages[0].content)

        // 3. Switch back to Session 1
        viewModel.selectSession(session1Id)
        advanceUntilIdle()

        // Context check: Must contain Session 1 messages ONLY, zero bleed from Session 2!
        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals("Plan a trip to Japan", viewModel.uiState.value.messages[0].content)

        // 4. Switch back to Session 2
        viewModel.selectSession(session2Id)
        advanceUntilIdle()

        // Context check: Must contain Session 2 messages ONLY!
        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals("Open camera to scan QR code", viewModel.uiState.value.messages[0].content)

        // 5. Test Cancel Generation
        viewModel.cancelGeneration()
        assertFalse(viewModel.uiState.value.isGenerating)
    }
}
