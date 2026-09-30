package com.edgehybrid.agent.tool

import com.edgehybrid.agent.agent.ConfirmationGate
import com.edgehybrid.agent.agent.ToolConfirmationPolicy
import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolCallFunction
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.jev.JevSafetyGate
import com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared graph builders for instrumentation tests.
 *
 * Centralised so several test classes construct the same objects the same way, and so a
 * change to a production constructor breaks in one obvious place instead of in every test.
 */
object TestMcpGraph {

    /** Counts executions so a test can assert a side effect ran exactly once. */
    class CountingSkillLoader(
        private val result: String = """{"sent":true}"""
    ) : SkillLoader {
        var executionCount = 0
            private set

        override suspend fun listTools(): List<ToolDefinition> = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "send_sms",
                    description = "Send an SMS",
                    parameters = buildJsonObject { put("type", "object") }
                )
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "get_battery_status",
                    description = "Read battery state",
                    parameters = buildJsonObject { put("type", "object") }
                )
            )
        )

        override suspend fun execute(call: ModelToolCall): ToolExecutionOutcome {
            executionCount++
            return ToolExecutionOutcome(content = result, isError = false)
        }
    }

    fun smsLoader(): CountingSkillLoader = CountingSkillLoader()

    /** A tool call exactly as an LLM would emit it, with model-chosen arguments. */
    fun modelSuppliedSms(phone: String, body: String): ModelToolCall = ModelToolCall(
        id = "call_test_1",
        function = ToolCallFunction(
            name = "send_sms",
            arguments = buildJsonObject {
                put("phone", phone)
                put("message", body)
            }
        )
    )

    fun gate(
        loader: SkillLoader,
        registry: ActionConfirmationRegistry = ActionConfirmationRegistry()
    ): ConfirmationGate {
        val mcpClient = EmptyEnabledMcp()
        return ConfirmationGate(
            policy = ToolConfirmationPolicy(),
            registry = registry,
            skillLoader = loader,
            toolGateway = ToolGateway(
                skillLoader = loader,
                mcpRouter = emptyMcpRouterForTest(),
                mcpClient = mcpClient,
                jevClient = NoJevForInstrumentation
            ),
            jevSafetyGate = JevSafetyGate(NoJevForInstrumentation)
        )
    }
}
