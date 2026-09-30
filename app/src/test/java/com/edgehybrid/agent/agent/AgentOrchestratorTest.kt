package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.data.model.ChatRoles
import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolCallFunction
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.tool.BuiltInSkillLoader
import com.edgehybrid.agent.tool.McpCallResult
import com.edgehybrid.agent.tool.McpClient
import com.edgehybrid.agent.tool.SkillExecutionException
import com.edgehybrid.agent.tool.SkillLoader
import com.edgehybrid.agent.tool.ToolExecutionOutcome
import com.edgehybrid.agent.tool.ToolGateway
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOrchestratorTest {

    @Test
    fun `weather and conversion execute recursively and final response completes`() = runTest {
        val engine = WeatherConversionScriptedEngine()
        val skillLoader = RecordingSkillLoader()
        val orchestrator = createOrchestrator(
            engine = engine,
            skillLoader = skillLoader
        )

        val events = orchestrator.streamChat(
            listOf(
                ChatMessage(
                    role = ChatRoles.USER,
                    content = "What's the weather in Tokyo and convert that to Fahrenheit?"
                )
            )
        ).toList()

        val completed = events
            .filterIsInstance<AgentStreamEvent.Completed>()
            .single()

        assertEquals(3, engine.requests.size)
        assertEquals(
            listOf("get_current_weather", "convert_temperature"),
            skillLoader.executedTools
        )
        assertEquals(2, events.filterIsInstance<AgentStreamEvent.ToolExecutionStarted>().size)

        val toolMessages = engine.requests.last().filter { it.role == ChatRoles.TOOL }
        assertEquals(2, toolMessages.size)
        assertEquals("call-weather", toolMessages[0].toolCallId)
        assertEquals("call-convert", toolMessages[1].toolCallId)
        assertTrue(toolMessages[0].content!!.contains("temperature_c"))
        assertTrue(toolMessages[1].content!!.contains("68"))
        assertTrue(completed.finalText.contains("68°F"))
    }

    @Test
    fun `mid stream disconnect requests continuation and finishes in same run`() = runTest {
        val delays = mutableListOf<Long>()
        val engine = FlakyDisconnectEngine()
        val orchestrator = createOrchestrator(
            engine = engine,
            skillLoader = RecordingSkillLoader(),
            delay = RecordingDelay(delays)
        )

        val events = orchestrator.streamChat(
            listOf(
                ChatMessage(
                    role = ChatRoles.USER,
                    content = "Continue the weather answer"
                )
            )
        ).toList()

        assertEquals(2, engine.requests.size)
        assertEquals(listOf(1_000L), delays)
        assertTrue(events.any { it is AgentStreamEvent.Recovering })
        assertFalse(events.any { it is AgentStreamEvent.RecoveryRequired })
        assertTrue(
            events.filterIsInstance<AgentStreamEvent.Completed>()
                .single()
                .finalText
                .contains("68°F")
        )

        val hiddenContinuation = engine.requests[1].last()
        assertEquals(ChatRoles.USER, hiddenContinuation.role)
        assertTrue(hiddenContinuation.content!!.contains("Continue the interrupted"))
    }

    private fun createOrchestrator(
        engine: InferenceEngine,
        skillLoader: SkillLoader,
        delay: SuspendDelay = RecordingDelay()
    ): AgentOrchestrator =
        AgentOrchestrator(
            inferenceEngine = engine,
            toolGateway = ToolGateway(
                skillLoader = skillLoader,
                mcpClient = DisabledMcpClient
            ),
            policy = AgentPolicy(),
            clock = AtomicStepClock(),
            suspendDelay = delay
        )

    private class WeatherConversionScriptedEngine : InferenceEngine {
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>
        ): Flow<CloudStreamEvent> = flow {
            requests += messages.toList()
            val toolIds = messages
                .filter { it.role == ChatRoles.TOOL }
                .mapNotNull { it.toolCallId }

            when {
                "call-weather" !in toolIds -> emit(
                    CloudStreamEvent.TurnCompleted(
                        ModelTurn(
                            content = "",
                            toolCalls = listOf(
                                toolCall(
                                    id = "call-weather",
                                    name = BuiltInSkillLoader.WEATHER_TOOL_NAME,
                                    arguments = buildJsonObject {
                                        put("location", "Tokyo")
                                    }
                                )
                            ),
                            finishReason = "tool_calls",
                            usage = TokenUsage(20, 8, true),
                            timeToFirstTokenMs = 7,
                            generationTimeMs = 9
                        )
                    )
                )

                "call-convert" !in toolIds -> emit(
                    CloudStreamEvent.TurnCompleted(
                        ModelTurn(
                            content = "",
                            toolCalls = listOf(
                                toolCall(
                                    id = "call-convert",
                                    name = BuiltInSkillLoader.CONVERSION_TOOL_NAME,
                                    arguments = buildJsonObject {
                                        put("value", 20)
                                        put("from", "C")
                                        put("to", "F")
                                    }
                                )
                            ),
                            finishReason = "tool_calls",
                            usage = TokenUsage(30, 7, true),
                            timeToFirstTokenMs = 6,
                            generationTimeMs = 8
                        )
                    )
                )

                else -> {
                    emit(CloudStreamEvent.AssistantDelta("Tokyo is 20°C, which is 68°F."))
                    emit(
                        CloudStreamEvent.TurnCompleted(
                            ModelTurn(
                                content = "Tokyo is 20°C, which is 68°F.",
                                toolCalls = emptyList(),
                                finishReason = "stop",
                                usage = TokenUsage(40, 12, true),
                                timeToFirstTokenMs = 5,
                                generationTimeMs = 9
                            )
                        )
                    )
                }
            }
        }
    }

    private class FlakyDisconnectEngine : InferenceEngine {
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>
        ): Flow<CloudStreamEvent> = flow {
            requests += messages.toList()
            if (requests.size == 1) {
                emit(CloudStreamEvent.AssistantDelta("Tokyo is "))
                throw AgentDisconnectedException(
                    partialText = "Tokyo is ",
                    cause = IOException("airplane mode")
                )
            }

            emit(CloudStreamEvent.AssistantDelta("20°C, which is 68°F."))
            emit(
                CloudStreamEvent.TurnCompleted(
                    ModelTurn(
                        content = "20°C, which is 68°F.",
                        toolCalls = emptyList(),
                        finishReason = "stop",
                        usage = TokenUsage(15, 8, true),
                        timeToFirstTokenMs = 4,
                        generationTimeMs = 8
                    )
                )
            )
        }
    }

    private class RecordingSkillLoader : SkillLoader {
        val executedTools = mutableListOf<String>()

        override suspend fun listTools(): List<ToolDefinition> =
            listOf(
                ToolDefinition(
                    function = FunctionDefinition(
                        name = BuiltInSkillLoader.WEATHER_TOOL_NAME,
                        description = "Weather",
                        parameters = buildJsonObject { put("type", "object") }
                    )
                ),
                ToolDefinition(
                    function = FunctionDefinition(
                        name = BuiltInSkillLoader.CONVERSION_TOOL_NAME,
                        description = "Temperature conversion",
                        parameters = buildJsonObject { put("type", "object") }
                    )
                )
            )

        override suspend fun execute(call: ModelToolCall): ToolExecutionOutcome {
            executedTools += call.function.name
            return when (call.function.name) {
                BuiltInSkillLoader.WEATHER_TOOL_NAME -> ToolExecutionOutcome(
                    content = """{"temperature_c":20.0}""",
                    isError = false
                )

                BuiltInSkillLoader.CONVERSION_TOOL_NAME -> ToolExecutionOutcome(
                    content = """{"value":20.0,"from":"C","to":"F","result":68.0}""",
                    isError = false
                )

                else -> throw SkillExecutionException("Unknown test skill")
            }
        }
    }

    private class AtomicStepClock : MonotonicClock {
        private val now = AtomicLong(0L)

        override fun nowNanos(): Long =
            now.getAndIncrement() * 1_000_000L
    }

    private class RecordingDelay(
        private val delays: MutableList<Long> = mutableListOf()
    ) : SuspendDelay {
        override suspend fun wait(delayMs: Long) {
            delays += delayMs
        }
    }

    private object DisabledMcpClient : McpClient {
        override suspend fun listTools(): List<ToolDefinition> = emptyList()

        override suspend fun callTool(call: ModelToolCall): McpCallResult =
            throw IllegalStateException("MCP is disabled in this test")
    }

    companion object {
        fun toolCall(
            id: String,
            name: String,
            arguments: kotlinx.serialization.json.JsonObject
        ): ModelToolCall =
            ModelToolCall(
                id = id,
                function = ToolCallFunction(
                    name = name,
                    arguments = arguments
                )
            )
    }
}