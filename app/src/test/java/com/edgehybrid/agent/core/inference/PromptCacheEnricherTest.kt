package com.edgehybrid.agent.core.inference

import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.data.model.ChatRoles
import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheEnricherTest {

    @Test
    fun `sortToolDefinitions orders tools alphabetically by name`() {
        val tools = listOf(
            ToolDefinition(function = FunctionDefinition("zebra_tool", "desc", buildJsonObject {})),
            ToolDefinition(function = FunctionDefinition("apple_tool", "desc", buildJsonObject {})),
            ToolDefinition(function = FunctionDefinition("middle_tool", "desc", buildJsonObject {}))
        )

        val sorted = PromptCacheEnricher.sortToolDefinitions(tools)
        assertEquals("apple_tool", sorted[0].function.name)
        assertEquals("middle_tool", sorted[1].function.name)
        assertEquals("zebra_tool", sorted[2].function.name)
    }

    @Test
    fun `attachTelemetryToTail places volatile information only at the message end`() {
        val basePrompt = "What is my battery level?"
        val enriched = PromptCacheEnricher.attachTelemetryToTail(
            userText = basePrompt,
            batteryPercent = 88,
            networkType = "WIFI_6E",
            timestampEpochMs = 1700000000000L
        )

        assertTrue(enriched.startsWith("What is my battery level?"))
        assertTrue(enriched.contains("<!-- mobile_telemetry_tail: Battery: 88%, Network: WIFI_6E, TimeMs: 1700000000000 -->"))
    }

    @Test
    fun `prepareCachedMessages preserves exact system prompt prefix`() {
        val system = "You are a helpful assistant."
        val history = listOf(
            ChatMessage(ChatRoles.USER, "Hello"),
            ChatMessage(ChatRoles.ASSISTANT, "Hi! How can I help you?")
        )

        val messages = PromptCacheEnricher.prepareCachedMessages(
            systemPrompt = system,
            history = history,
            latestUserQuery = "Show my calendar",
            batteryPercent = 95
        )

        assertEquals(4, messages.size)
        assertEquals(ChatRoles.SYSTEM, messages[0].role)
        assertEquals("You are a helpful assistant.", messages[0].content)
        assertEquals(ChatRoles.USER, messages[1].role)
        assertEquals("Hello", messages[1].content)
        assertEquals(ChatRoles.ASSISTANT, messages[2].role)
        assertEquals("Hi! How can I help you?", messages[2].content)
        assertEquals(ChatRoles.USER, messages[3].role)
        assertTrue(messages[3].content!!.startsWith("Show my calendar"))
    }
}
