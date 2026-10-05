package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.model.ChatCompletionRequest
import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ProviderUsage
import com.edgehybrid.agent.data.model.StreamOptions
import com.edgehybrid.agent.data.model.ToolCallFunction
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.data.model.toApiMessage
import com.edgehybrid.agent.network.SseFrameDecoder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.preparePost
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

interface InferenceEngine {
    fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<CloudStreamEvent>
}

data class ProviderSettings(
    val baseUrl: String,
    val apiKey: String?,
    val model: String
) {
    init {
        require(baseUrl.isNotBlank()) { "Cloud base URL cannot be blank" }
        require(model.isNotBlank()) { "Cloud model cannot be blank" }
    }

    val completionUrl: String
        get() = "${baseUrl.trimEnd('/')}/chat/completions"
}

class AgentDisconnectedException(
    val partialText: String,
    cause: Throwable
) : IOException("The inference stream disconnected", cause)

class ProviderHttpException(
    val statusCode: Int,
    val responseSnippet: String
) : RuntimeException("Cloud provider returned HTTP $statusCode")

class ProviderProtocolException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

@Singleton
class CloudInferenceEngine @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
    private val settings: ProviderSettings,
    private val policy: AgentPolicy,
    private val clock: MonotonicClock,
    private val suspendDelay: SuspendDelay,
    private val keyStore: com.edgehybrid.agent.data.local.SecureKeyStore? = null
) : InferenceEngine {

    override fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<CloudStreamEvent> = flow {
        val startedAtNanos = clock.nowNanos()
        val accumulator = TurnAccumulator(clock, startedAtNanos)
        val hasTools = tools.isNotEmpty()
        val effectiveModel = keyStore?.getSelectedCloudModel()?.takeIf { it.isNotBlank() } ?: settings.model
        val request = ChatCompletionRequest(
            model = effectiveModel,
            messages = messages.map(ChatMessage::toApiMessage),
            tools = if (hasTools) tools else null,
            toolChoice = if (hasTools) "auto" else null
        )

        executeWithProviderRetries(request, accumulator) { delta ->
            emit(CloudStreamEvent.AssistantDelta(delta))
        }

        val completedAtNanos = clock.nowNanos()
        val turn = accumulator.finish(completedAtNanos)
        emit(CloudStreamEvent.TurnCompleted(turn))
    }

    private suspend fun executeWithProviderRetries(
        request: ChatCompletionRequest,
        accumulator: TurnAccumulator,
        onDelta: suspend (String) -> Unit
    ) {
        var retryCount = 0

        var currentKeyIndex = 0
        // Resolve provider, key and URL from SecureKeyStore (overrides build-time config)
        val provider = keyStore?.getPreferredProvider()
            ?: com.edgehybrid.agent.data.local.SecureKeyStore.PROVIDER_OPENROUTER
        val isGoogleAiStudio =
            provider == com.edgehybrid.agent.data.local.SecureKeyStore.PROVIDER_GOOGLE_AI_STUDIO

        val effectiveApiKey: String? = if (isGoogleAiStudio) {
            keyStore?.getGeminiApiKey()?.takeIf { it.isNotBlank() }
        } else {
            keyStore?.getOpenRouterApiKey(currentKeyIndex)?.takeIf { it.isNotBlank() } ?: settings.apiKey
        }

        val effectiveBaseUrl: String = if (isGoogleAiStudio) {
            com.edgehybrid.agent.data.local.SecureKeyStore.DEFAULT_GOOGLE_AI_STUDIO_ENDPOINT
        } else {
            keyStore?.getCustomEndpoint()?.takeIf { it.isNotBlank() } ?: settings.baseUrl
        }

        // FIX: Google's OpenAI-compatible endpoint ignores a `?key=` query parameter and
        // answers `HTTP 400 {"error":{"message":"Missing or invalid Authorization header."}}`
        // for EVERY request. Verified live against
        // https://generativelanguage.googleapis.com/v1beta/openai/chat/completions:
        //   ?key=VALUE  -> 400 "Missing or invalid Authorization header."  (key never read)
        //   Bearer VALUE -> 400 "Please pass a valid API key"             (key accepted, rejected as invalid)
        // The second message proves the header form is what actually delivers the credential.
        // `?key=` belongs to the NATIVE endpoint (.../models/{model}:generateContent?key=...),
        // which this app does not use.
        val effectiveUrl: String = "${effectiveBaseUrl.trimEnd('/')}/chat/completions"

        while (true) {
            try {
                httpClient.preparePost(effectiveUrl) {
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Text.EventStream)
                    // Every OpenAI-compatible endpoint, including Google's, authenticates
                    // with an Authorization: Bearer header.
                    effectiveApiKey
                        ?.takeIf(String::isNotBlank)
                        ?.let { key ->
                            header(HttpHeaders.Authorization, "Bearer $key")
                        }
                    setBody(request)
                }.execute { response ->
                    val statusCode = response.status.value

                    if (statusCode == 429 || statusCode == 503) {
                        val exponentialDelay = policy.backoffFor(retryCount)
                        val providerDelay = response.retryAfterMillis() ?: 0L
                        throw RetryableProviderStatus(
                            statusCode = statusCode,
                            delayMs = maxOf(exponentialDelay, providerDelay)
                        )
                    }

                    if (statusCode !in 200..299) {
                        val bodySnippet = runCatching {
                            response.bodyAsText()
                        }.getOrDefault("")
                            .replace('\n', ' ')
                            .take(512)

                        android.util.Log.e("CloudInferenceEngine", "HTTP $statusCode: $bodySnippet")
                        throw ProviderHttpException(
                            statusCode = statusCode,
                            responseSnippet = bodySnippet
                        )
                    }

                    if (response.hasContentType(ContentType.Application.Json)) {
                        val chunk = try {
                            response.body<CompletionChunk>()
                        } catch (exception: SerializationException) {
                            throw ProviderProtocolException(
                                "Provider returned malformed completion JSON",
                                exception
                            )
                        }

                        val previousLength = accumulator.contentLength
                        accumulator.accept(chunk)
                        emitNewContent(previousLength, accumulator, onDelta)
                    } else {
                        readEventStream(response, accumulator, onDelta)
                    }
                }
                return
            } catch (retryable: RetryableProviderStatus) {
                if (retryCount >= policy.providerMaxRetries) {
                    throw ProviderHttpException(
                        statusCode = retryable.statusCode,
                        responseSnippet = "Retry limit exhausted"
                    )
                }

                // On 429: try the next OpenRouter key before giving up.
                // This is the only way to recover from a per-key daily limit.
                // On 429: rotate to the next OpenRouter key before spending the retry
                // budget. This is the only recovery from a per-key daily limit.
                //
                // The sentinel is -1 (non-null Int) for "pool spent", so the check must
                // be `>= 0`. A `!= null` test is always true for a live store and
                // would wrap onto a negative index, re-read a blank key, and loop.
                if (retryable.statusCode == 429) {
                    val next = keyStore?.getNextOpenRouterKeyIndex(currentKeyIndex) ?: -1
                    if (next >= 0) {
                        currentKeyIndex = next
                        // A fresh key gets the full retry budget, not the remainder.
                        retryCount = 0
                        continue
                    }
                }
                // No spare key: fall through to the normal backoff. A 429 with a single
                // key may still clear within the retry window, and 503 always needs the
                // backoff regardless of key state.

                suspendDelay.wait(retryable.delayMs)
                retryCount += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (disconnected: AgentDisconnectedException) {
                throw disconnected
            } catch (protocol: ProviderProtocolException) {
                throw protocol
            } catch (transport: Exception) {
                if (transport.isTransportFailure()) {
                    throw AgentDisconnectedException(
                        partialText = accumulator.text(),
                        cause = transport
                    )
                }
                throw transport
            }
        }
    }

    private suspend fun emitNewContent(
        previousLength: Int,
        accumulator: TurnAccumulator,
        onDelta: suspend (String) -> Unit
    ) {
        val currentText = accumulator.text()
        if (currentText.length > previousLength) {
            onDelta(currentText.substring(previousLength))
        }
    }

    private suspend fun readEventStream(
        response: HttpResponse,
        accumulator: TurnAccumulator,
        onDelta: suspend (String) -> Unit
    ) {
        val channel = response.bodyAsChannel()
        val decoder = SseFrameDecoder()
        var receivedDoneMarker = false

        try {
            while (true) {
                val line = channel.readUTF8Line() ?: break
                val frame = decoder.accept(line) ?: continue

                if (consumeFrame(frame, accumulator, onDelta)) {
                    receivedDoneMarker = true
                    break
                }
            }

            if (!receivedDoneMarker) {
                decoder.close()?.let { trailingFrame ->
                    if (consumeFrame(trailingFrame, accumulator, onDelta)) {
                        receivedDoneMarker = true
                    }
                }
            }

            if (!receivedDoneMarker && accumulator.finishReason == null) {
                throw AgentDisconnectedException(
                    partialText = accumulator.text(),
                    cause = IOException("SSE ended before a finish reason or [DONE] marker")
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (disconnected: AgentDisconnectedException) {
            throw disconnected
        } catch (protocol: ProviderProtocolException) {
            throw protocol
        } catch (transport: Exception) {
            throw AgentDisconnectedException(
                partialText = accumulator.text(),
                cause = transport
            )
        }
    }

    private suspend fun consumeFrame(
        frame: String,
        accumulator: TurnAccumulator,
        onDelta: suspend (String) -> Unit
    ): Boolean {
        if (frame.trim() == "[DONE]") {
            return true
        }

        if (frame.isBlank()) {
            return false
        }

        val chunk = try {
            json.decodeFromString<CompletionChunk>(frame)
        } catch (exception: SerializationException) {
            throw ProviderProtocolException(
                "Provider returned a malformed SSE JSON frame",
                exception
            )
        }

        val previousLength = accumulator.contentLength
        accumulator.accept(chunk)
        emitNewContent(previousLength, accumulator, onDelta)
        return false
    }
}

private class RetryableProviderStatus(
    val statusCode: Int,
    val delayMs: Long
) : Throwable()

private class TurnAccumulator(
    private val clock: MonotonicClock,
    private val startedAtNanos: Long
) {
    private val content = StringBuilder()
    private val partialCalls = sortedMapOf<Int, PartialToolCall>()
    private var promptTokens: Int? = null
    private var completionTokens: Int? = null
    private var firstTokenAtNanos: Long? = null

    var finishReason: String? = null
        private set

    val contentLength: Int
        get() = content.length

    fun text(): String = content.toString()

    fun accept(chunk: CompletionChunk) {
        chunk.usage?.let(::acceptUsage)

        chunk.choices.forEach { choice ->
            finishReason = choice.finishReason ?: finishReason

            choice.delta?.let { delta ->
                if (!delta.content.isNullOrEmpty()) {
                    markGeneratedToken()
                    content.append(delta.content)
                }

                delta.toolCalls.forEach { toolCall ->
                    val index = toolCall.index ?: 0
                    val partial = partialCalls.getOrPut(index) { PartialToolCall() }
                    partial.merge(toolCall)
                    markGeneratedToken()
                }
            }

            choice.message?.let { message ->
                if (!message.content.isNullOrEmpty()) {
                    markGeneratedToken()
                    content.append(message.content)
                }

                message.toolCalls.orEmpty().forEachIndexed { index, toolCall ->
                    partialCalls
                        .getOrPut(index) { PartialToolCall() }
                        .merge(
                            StreamToolCallDelta(
                                index = index,
                                id = toolCall.id,
                                type = toolCall.type,
                                function = StreamFunctionDelta(
                                    name = toolCall.function.name,
                                    arguments = toolCall.function.arguments
                                )
                            )
                        )
                    markGeneratedToken()
                }
            }

            if (!choice.text.isNullOrEmpty()) {
                markGeneratedToken()
                content.append(choice.text)
            }
        }
    }

    fun finish(completedAtNanos: Long): ModelTurn {
        val calls = partialCalls.map { (index, partial) ->
            partial.toModelCall(index)
        }

        if (finishReason == "tool_calls" && calls.isEmpty()) {
            throw ProviderProtocolException(
                "Provider ended with finish_reason=tool_calls but emitted no tool calls"
            )
        }

        if (content.isEmpty() && calls.isEmpty()) {
            throw ProviderProtocolException("Provider completed without any generated output")
        }

        val ttftMs = firstTokenAtNanos?.let { firstTokenAt ->
            TimeUnit.NANOSECONDS.toMillis(
                (firstTokenAt - startedAtNanos).coerceAtLeast(0L)
            )
        }

        val generationTimeMs = TimeUnit.NANOSECONDS.toMillis(
            (completedAtNanos - startedAtNanos).coerceAtLeast(0L)
        )

        val usage = if (promptTokens != null && completionTokens != null) {
            TokenUsage(
                promptTokens = promptTokens!!.toLong(),
                completionTokens = completionTokens!!.toLong(),
                providerReported = true
            )
        } else {
            TokenUsage(
                promptTokens = promptTokens?.toLong() ?: 0L,
                completionTokens = completionTokens?.toLong() ?: 0L,
                providerReported = false
            )
        }

        return ModelTurn(
            content = content.toString(),
            toolCalls = calls,
            finishReason = finishReason,
            usage = usage,
            timeToFirstTokenMs = ttftMs,
            generationTimeMs = generationTimeMs
        )
    }

    private fun acceptUsage(usage: ProviderUsage) {
        usage.promptTokens?.let { promptTokens = it }
        usage.completionTokens?.let { completionTokens = it }
    }

    private fun markGeneratedToken() {
        if (firstTokenAtNanos == null) {
            firstTokenAtNanos = clock.nowNanos()
        }
    }
}

private class PartialToolCall {
    var id: String? = null
    var type: String = "function"
    var name: String? = null
    val arguments = StringBuilder()

    fun merge(delta: StreamToolCallDelta) {
        if (!delta.id.isNullOrBlank()) {
            if (id.isNullOrBlank()) {
                id = delta.id
            } else if (id != delta.id) {
                throw ProviderProtocolException(
                    "Provider changed the ID of a streamed tool call"
                )
            }
        }

        if (!delta.type.isNullOrBlank()) {
            type = delta.type
        }

        val incomingName = delta.function?.name
        if (!incomingName.isNullOrBlank()) {
            val currentName = name
            name = when {
                currentName.isNullOrBlank() -> incomingName
                currentName == incomingName -> currentName
                incomingName.startsWith(currentName) -> incomingName
                !currentName.endsWith(incomingName) -> currentName + incomingName
                else -> currentName
            }
        }

        delta.function?.arguments?.let(arguments::append)
    }

    fun toModelCall(index: Int): ModelToolCall {
        val callId = id?.takeIf(String::isNotBlank)
            ?: throw ProviderProtocolException(
                "Streamed tool call at index $index did not include an ID"
            )

        val functionName = name?.takeIf(String::isNotBlank)
            ?: throw ProviderProtocolException(
                "Streamed tool call $callId did not include a function name"
            )

        val parsedArguments = if (arguments.isBlank()) {
            JsonObject(emptyMap())
        } else {
            try {
                Json.decodeFromString<JsonObject>(arguments.toString())
            } catch (exception: Exception) {
                throw ProviderProtocolException(
                    "Tool call $callId contains invalid JSON arguments",
                    exception
                )
            }
        }

        return ModelToolCall(
            id = callId,
            type = type.ifBlank { "function" },
            function = ToolCallFunction(
                name = functionName,
                arguments = parsedArguments
            )
        )
    }
}

@Serializable
private data class CompletionChunk(
    val choices: List<CompletionChoice> = emptyList(),
    val usage: ProviderUsage? = null
)

@Serializable
private data class CompletionChoice(
    val index: Int = 0,
    @SerialName("finish_reason")
    val finishReason: String? = null,
    val delta: CompletionDelta? = null,
    val message: CompletionMessage? = null,
    val text: String? = null
)

@Serializable
private data class CompletionDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<StreamToolCallDelta> = emptyList()
)

@Serializable
private data class StreamToolCallDelta(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: StreamFunctionDelta? = null
)

@Serializable
private data class StreamFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
private data class CompletionMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<CompletionMessageToolCall> = emptyList()
)

@Serializable
private data class CompletionMessageToolCall(
    val id: String,
    val type: String = "function",
    val function: CompletionMessageFunction
)

@Serializable
private data class CompletionMessageFunction(
    val name: String,
    val arguments: String
)

private fun HttpResponse.retryAfterMillis(): Long? {
    val retryAfter = headers[HttpHeaders.RetryAfter]?.trim()?.takeIf(String::isNotEmpty)
        ?: return null

    retryAfter.toLongOrNull()?.let { seconds ->
        return seconds
            .coerceAtLeast(0L)
            .let { seconds ->
                if (seconds > Long.MAX_VALUE / 1_000L) {
                    Long.MAX_VALUE
                } else {
                    seconds * 1_000L
                }
            }
    }

    return runCatching {
        val retryAt = ZonedDateTime.parse(
            retryAfter,
            DateTimeFormatter.RFC_1123_DATE_TIME
        ).toInstant().toEpochMilli()

        (retryAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }.getOrNull()
}

private fun HttpResponse.hasContentType(expected: ContentType): Boolean {
    val rawContentType = headers[HttpHeaders.ContentType] ?: return false
    return runCatching {
        ContentType.parse(rawContentType).match(expected)
    }.getOrDefault(false)
}

private fun Throwable.isTransportFailure(): Boolean =
    this is IOException ||
        this is HttpRequestTimeoutException ||
        this is java.net.SocketTimeoutException ||
        this is java.net.UnknownHostException ||
        this is java.nio.channels.UnresolvedAddressException