package com.edgehybrid.agent.ui.chat

import com.edgehybrid.agent.agent.AgentLoop
import com.edgehybrid.agent.agent.AgentStreamEvent
import com.edgehybrid.agent.agent.GenerationTelemetry
import com.edgehybrid.agent.agent.TokenUsage
import com.edgehybrid.agent.data.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `mid stream network failure shows recovery chip and retry continues same bubble`() = runTest {
        val agentLoop = RecoveringAgentLoop()
        val viewModel = ChatViewModel(agentLoop)
        // Drain init{} coroutines (loadChatHistory with null chatDao is a no-op, but drain anyway)
        advanceUntilIdle()

        viewModel.send("What's the weather in Tokyo?")
        advanceUntilIdle()

        val beforeRetry = viewModel.uiState.value
        val assistantBeforeRetry = beforeRetry.messages.last()

        assertFalse(beforeRetry.isGenerating)
        assertEquals(
            MessageDeliveryState.RECOVERY_REQUIRED,
            assistantBeforeRetry.deliveryState
        )
        assertEquals("Tokyo is ", assistantBeforeRetry.content)
        assertEquals(
            "The connection dropped before the response completed.",
            assistantBeforeRetry.recoveryMessage
        )

        viewModel.retryRecovery()
        advanceUntilIdle()

        val afterRetry = viewModel.uiState.value
        val assistantAfterRetry = afterRetry.messages.last()

        assertFalse(afterRetry.isGenerating)
        assertNull(assistantAfterRetry.recoveryMessage)
        assertEquals(
            MessageDeliveryState.COMPLETE,
            assistantAfterRetry.deliveryState
        )
        assertEquals("Tokyo is 20°C, or 68°F.", assistantAfterRetry.content)
        assertEquals(1, afterRetry.messages.count { it.role == ChatMessageRole.USER })
        assertEquals(2, afterRetry.messages.size)
    }

    private class RecoveringAgentLoop : AgentLoop {
        override fun streamChat(history: List<ChatMessage>): Flow<AgentStreamEvent> =
            flow {
                emit(AgentStreamEvent.AssistantDelta("Tokyo is "))
                emit(
                    AgentStreamEvent.RecoveryRequired(
                        partialText = "Tokyo is ",
                        message = "The connection dropped before the response completed."
                    )
                )
            }

        override fun continueAfterDisconnect(
            history: List<ChatMessage>,
            partialText: String
        ): Flow<AgentStreamEvent> = flow {
            emit(AgentStreamEvent.AssistantDelta("20°C, or 68°F."))
            emit(
                AgentStreamEvent.Completed(
                    finalText = "20°C, or 68°F.",
                    usage = TokenUsage(
                        promptTokens = 20,
                        completionTokens = 9,
                        providerReported = true
                    ),
                    telemetry = GenerationTelemetry(
                        timeToFirstTokenMs = 14,
                        totalGenerationTimeMs = 31
                    )
                )
            )
        }
    }
}

class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}