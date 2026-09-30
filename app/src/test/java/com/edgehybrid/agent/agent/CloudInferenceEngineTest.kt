package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.data.model.ChatRoles
import com.edgehybrid.agent.data.model.ToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudInferenceEngineTest {

    // NOTE: All SSE chunk JSON must be single-line strings.
    // SseFrameDecoder treats every newline as an SSE event boundary —
    // multi-line JSON would be split into broken partial frames.

    @Test
    fun `parses tool call with nested arguments and usage`() = runTest {
        // Three SSE chunks: text delta, complete tool call, finish+usage
        val chunks = listOf(
            """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"I'll check that. "},"finish_reason":null}]}""",
            """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-weather-tokyo","type":"function","function":{"name":"get_current_weather","arguments":"{\"city\":\"Tokyo\",\"unit\":{\"system\":\"metric\"},\"details\":{\"depth\":{\"days\":3}},\"alerts\":[{\"kind\":\"storm\",\"level\":2}]}"}}]},"finish_reason":null}]}""",
            """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":41,"completion_tokens":29,"total_tokens":70}}"""
        )

        val client = mockClient(responseBody = sseResponse(chunks))
        val engine = makeEngine(client)

        val events = engine.streamChat(
            messages = listOf(ChatMessage(role = ChatRoles.USER, content = "Weather in Tokyo?")),
            tools = emptyList()
        ).toList()

        val text = events
            .filterIsInstance<CloudStreamEvent.AssistantDelta>()
            .joinToString("") { it.text }

        val turn = events
            .filterIsInstance<CloudStreamEvent.TurnCompleted>()
            .single()
            .turn

        val call = turn.toolCalls.single()

        assertEquals("I'll check that. ", text)
        assertEquals("call-weather-tokyo", call.id)
        assertEquals("get_current_weather", call.function.name)
        assertEquals("Tokyo", call.function.arguments["city"]?.jsonPrimitive?.content)
        assertEquals(
            "metric",
            call.function.arguments["unit"]
                ?.jsonObject?.get("system")?.jsonPrimitive?.content
        )
        assertEquals(
            3,
            call.function.arguments["details"]
                ?.jsonObject?.get("depth")
                ?.jsonObject?.get("days")
                ?.jsonPrimitive?.content?.toInt()
        )
        assertEquals(
            2,
            call.function.arguments["alerts"]
                ?.jsonArray?.first()
                ?.jsonObject?.get("level")
                ?.jsonPrimitive?.content?.toInt()
        )
        assertEquals(41L, turn.usage.promptTokens)
        assertEquals(29L, turn.usage.completionTokens)
        assertTrue(turn.usage.providerReported)
        assertTrue(turn.timeToFirstTokenMs != null)
        assertTrue(turn.generationTimeMs >= turn.timeToFirstTokenMs!!)
    }

    @Test
    fun `retries HTTP 429 and 503 with exponential backoff`() = runTest {
        listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable).forEach { status ->
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)
            val delays = mutableListOf<Long>()

            val client = HttpClient(
                MockEngine {
                    val currentAttempt = attempts.incrementAndGet()
                    if (currentAttempt <= 3) {
                        respond(
                            content = status.description,
                            status = status,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain")
                        )
                    } else {
                        respond(
                            content = sseResponse(listOf(
                                """{"choices":[{"index":0,"delta":{"content":"Recovered"},"finish_reason":"stop"}]}"""
                            )),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                        )
                    }
                }
            ) {
                expectSuccess = false
                install(ContentNegotiation) { json(testJson) }
            }

            val engine = CloudInferenceEngine(
                httpClient = client,
                json = testJson,
                settings = ProviderSettings(
                    baseUrl = "https://cloud.example/v1",
                    apiKey = null,
                    model = "test-model"
                ),
                policy = AgentPolicy(),
                clock = StepClock(),
                suspendDelay = RecordingDelay(delays)
            )

            val events = engine.streamChat(
                messages = listOf(ChatMessage(role = ChatRoles.USER, content = "Hello")),
                tools = emptyList()
            ).toList()

            assertEquals(4, attempts.get())
            assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
            assertEquals(
                "Recovered",
                events.filterIsInstance<CloudStreamEvent.AssistantDelta>()
                    .single()
                    .text
            )
        }
    }

    // --- Helpers ---

    private fun makeEngine(client: HttpClient): CloudInferenceEngine =
        CloudInferenceEngine(
            httpClient = client,
            json = testJson,
            settings = ProviderSettings(
                baseUrl = "https://cloud.example/v1",
                apiKey = "test-key",
                model = "test-model"
            ),
            policy = AgentPolicy(),
            clock = StepClock(),
            suspendDelay = RecordingDelay()
        )

    private fun mockClient(responseBody: String): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(testJson) }
        }

    /** Each chunk becomes a single `data: <json>` line, terminated by [DONE]. */
    private fun sseResponse(chunks: List<String>): String =
        chunks.joinToString(separator = "\n\n") { chunk -> "data: $chunk" } +
            "\n\ndata: [DONE]\n\n"

    private class StepClock : MonotonicClock {
        private var now = 0L
        override fun nowNanos(): Long = now.also { now += 1_000_000L }
    }

    private class RecordingDelay(
        private val delays: MutableList<Long> = mutableListOf()
    ) : SuspendDelay {
        override suspend fun wait(delayMs: Long) { delays += delayMs }
    }

    companion object {
        private val testJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}