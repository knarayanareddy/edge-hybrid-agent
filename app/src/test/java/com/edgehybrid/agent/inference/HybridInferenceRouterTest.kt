package com.edgehybrid.agent.inference

import com.edgehybrid.agent.core.inference.ChatMessage
import com.edgehybrid.agent.core.inference.CompletionResult
import com.edgehybrid.agent.core.inference.GenerationConfig
import com.edgehybrid.agent.core.inference.InferenceEngine
import com.edgehybrid.agent.core.inference.StreamChunk
import com.edgehybrid.agent.core.inference.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridInferenceRouterTest {

    class FakeEngine(override val engineName: String, override val requiresNetwork: Boolean) : InferenceEngine {
        var callCount = 0

        override fun generate(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            config: GenerationConfig
        ): Flow<StreamChunk> = flowOf(StreamChunk.TextDelta("Output from $engineName"))

        override suspend fun complete(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            config: GenerationConfig
        ): CompletionResult {
            callCount++
            return CompletionResult(
                content = "Response from $engineName",
                toolCalls = null,
                finishReason = "stop",
                model = engineName
            )
        }

        override suspend fun isAvailable(): Boolean = true
    }

    @Test
    fun testOfflineFallbackToLocalEngine() = runTest {
        val cloud = FakeEngine("Cloud-GPT-4", true)
        val local = FakeEngine("LocalLiteRT", false)

        val isOnline = false
        val activeEngine = if (isOnline) cloud else local

        assertEquals("LocalLiteRT", activeEngine.engineName)
        val result = activeEngine.complete(listOf(ChatMessage(role = "user", content = "Hello")))
        assertNotNull(result.content)
        assertTrue(result.content!!.contains("LocalLiteRT"))
        assertEquals(1, local.callCount)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun testOnlineRoutesToCloudEngine() = runTest {
        val cloud = FakeEngine("Cloud-GPT-4", true)
        val local = FakeEngine("LocalLiteRT", false)

        val isOnline = true
        val activeEngine = if (isOnline) cloud else local

        assertEquals("Cloud-GPT-4", activeEngine.engineName)
        val result = activeEngine.complete(listOf(ChatMessage(role = "user", content = "Hello")))
        assertNotNull(result.content)
        assertTrue(result.content!!.contains("Cloud-GPT-4"))
        assertEquals(1, cloud.callCount)
        assertEquals(0, local.callCount)
    }
}
