package com.edgehybrid.agent.jev

import com.edgehybrid.agent.data.local.SecureKeyStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Client for the TypeSafe Jev "System One" evaluation endpoint.
 *
 * Endpoint and body shape follow the published API (docs.typesafe.ai/api):
 * `POST https://api.typesafe.ai/v1/systemone` with `{state, model, questions}` and an
 * `{model, answers, usage}` response.
 *
 * The previous version of this class posted a hand-rolled
 * `{prompt, proposedAction, context}` body to `/v1/jev/evaluate`, an endpoint that does not
 * exist. Nothing consumed the result, so it was never caught.
 *
 * Design constraints, all deliberate:
 *  - **Fail open on absence, fail closed on configuration.** With no API key the client is
 *    simply not used; callers fall back to their own policy. But if a key *is* configured
 *    and Jev fails, callers are told it failed rather than being handed a fabricated zero.
 *  - **Never invent an answer.** On failure this returns [JevOutcome.Unavailable]; it does
 *    not synthesize a probability, because a made-up score in a safety path is worse than no
 *    score at all.
 *  - **No cached decisions on the safety path.** [JevDecisionCache] is keyed on prompt text,
 *    which is wrong for gating: two different actions can share a prompt. Caching is only
 *    used for tool *selection*, where a miss is harmless.
 */
/**
 * Seam over the Jev evaluation endpoint.
 *
 * The safety gate and the pruning path depend on this rather than the concrete client, so
 * both can be tested deterministically without a keystore or a network call.
 */
interface JevEvaluator {
    /** True when a Jev API key is configured and calls will actually be made. */
    fun isConfigured(): Boolean

    suspend fun evaluate(request: JevRequest): JevOutcome

    suspend fun selectTools(
        prompt: String,
        candidates: Map<String, String>,
        limit: Int
    ): List<String>
}

@Singleton
class JevClient @Inject constructor(
    private val keyStore: SecureKeyStore
) : JevEvaluator {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // Jev reports 70–500 ms end to end, so a 5 s ceiling is generous while still
            // bounding the hot path. A slow call must not stall a confirmation dialog.
            requestTimeoutMillis = 5_000L
            connectTimeoutMillis = 2_000L
            socketTimeoutMillis = 5_000L
        }
    }

    /** Serializes requests so a burst of tool calls cannot stampede the endpoint. */
    private val requestMutex = Mutex()

    /** True when a Jev API key is configured, i.e. when Jev calls will actually be made. */
    override fun isConfigured(): Boolean = runCatching {
        keyStore.getTypeSafeApiKey().isNotBlank()
    }.getOrDefault(false)

    /**
     * Evaluates [request] against Jev.
     *
     * @return [JevOutcome.Answers] on success, [JevOutcome.Unavailable] when no key is
     *   configured or the call failed. It never returns a partially-populated answer set
     *   as if it were complete.
     */
    override suspend fun evaluate(request: JevRequest): JevOutcome = withContext(Dispatchers.IO) {
        val apiKey = runCatching { keyStore.getTypeSafeApiKey() }.getOrDefault("")
        if (apiKey.isBlank()) {
            return@withContext JevOutcome.Unavailable("No Jev API key configured")
        }

        // One evaluation at a time: Jev is a per-call cost, and concurrent calls from a
        // tool loop would add latency without adding information.
        requestMutex.withLock {
            try {
                val response: HttpResponse = httpClient.post(EVALUATION_ENDPOINT) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $apiKey")
                    setBody(request)
                }

                when {
                    response.status == HttpStatusCode.TooManyRequests ||
                        response.status.value == OVERLOADED -> JevOutcome.Unavailable(
                        "Jev is rate limited or overloaded"
                    )

                    response.status.value !in 200..299 -> JevOutcome.Unavailable(
                        "Jev returned HTTP ${response.status.value}"
                    )

                    else -> {
                        val body: JevResponse = response.body()
                        if (body.answers.isEmpty()) {
                            JevOutcome.Unavailable("Jev returned no answers")
                        } else {
                            JevOutcome.Answers(body)
                        }
                    }
                }
            } catch (io: IOException) {
                JevOutcome.Unavailable("Jev request failed: ${io.message}")
            } catch (failure: Exception) {
                JevOutcome.Unavailable("Jev request failed: ${failure.message}")
            }
        }
    }

    /**
     * Asks Jev which tools are relevant to [prompt], returning at most [limit] names.
     *
     * Used to prune the tool payload sent to the chat model. Falls back to the unpruned set
     * whenever Jev is unavailable or unsure, because hiding a tool the model needed is
     * worse than sending a larger payload.
     *
     * @param candidates tool names and one-line descriptions to choose between. Must be
     *   non-empty and within Jev's 255-option cardinality cap.
     */
    override suspend fun selectTools(
        prompt: String,
        candidates: Map<String, String>,
        limit: Int
    ): List<String> {
        val bounded = candidates.entries.take(JevQuestion.MAX_CHOICE_OPTIONS)
        if (bounded.isEmpty()) return emptyList()

        val question = JevQuestion.choice(
            instructions = "Which tools are needed to satisfy this request? " +
                "Choose only tools that are directly required.",
            options = bounded.associate { it.key to it.value }
        )

        val state = kotlinx.serialization.json.buildJsonObject {
            put("request", kotlinx.serialization.json.JsonPrimitive(prompt))
        }

        val request = JevRequest(
            state = state,
            model = JevRequest.JEV_MODEL,
            questions = mapOf(SELECT_TOOLS to question)
        )

        return when (val outcome = evaluate(request)) {
            is JevOutcome.Unavailable -> emptyList()

            is JevOutcome.Answers -> {
                val answer = outcome.body.answer(SELECT_TOOLS) ?: return emptyList()
                val selected = answer.selectedOption() ?: return emptyList()

                // A Choice returns one option; the app needs a few. The published
                // distribution is what makes a short list possible: take the highest
                // probability options, but only while they carry real mass.
                val ranked = answer.probabilities
                    ?.entries
                    ?.sortedByDescending { it.value }
                    ?.filter { it.value >= TOOL_SELECTION_FLOOR }
                    ?.map { it.key }
                    ?.filter { it in bounded.associate { it.key to it.value } }
                    ?: listOf(selected)

                val kept = LinkedHashSet<String>()
                ranked.forEach { name ->
                    if (kept.size < limit.coerceAtLeast(1)) kept.add(name)
                }
                // The top choice is always kept, even below the floor: if Jev picked it,
                // dropping it would discard the model's best guess.
                if (kept.isEmpty()) kept.add(selected)

                // Never prune away the tool the model is most likely to want if it was also
                // the explicitly selected option.
                if (selected in bounded.associate { it.key to it.value }) kept.add(selected)
                kept.toList()
            }
        }
    }

    companion object {
        /**
         * The documented evaluation endpoint.
         *
         * This was `/v1/jev/evaluate`, which does not exist.
         */
        const val EVALUATION_ENDPOINT = "https://api.typesafe.ai/v1/systemone"

        const val SELECT_TOOLS = "relevant_tools"

        /** Non-standard status TypeSafe documents for a temporarily overloaded service. */
        const val OVERLOADED = 529

        /**
         * Probability below which a tool is not worth sending.
         *
         * 0.10 keeps the payload lean without discarding a plausible second choice; the
         * caller still gets the unpruned set on any failure.
         */
        const val TOOL_SELECTION_FLOOR = 0.10
    }
}

/**
 * Result of a Jev call.
 *
 * [Unavailable] is a first-class outcome, not an error to swallow: callers decide whether
 * to fall back to their own policy, and a missing answer is never silently replaced with a
 * default probability.
 */
sealed class JevOutcome {
    data class Answers(val body: JevResponse) : JevOutcome()

    data class Unavailable(val reason: String) : JevOutcome()

    val isAvailable: Boolean get() = this is Answers

    /** Answers when available, else null. */
    fun answersOrNull(): JevResponse? = (this as? Answers)?.body
}