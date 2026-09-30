package com.edgehybrid.agent.jev

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Direct wire probe for the TypeSafe endpoint, used by [JevLiveApiTest].
 *
 * Deliberately does not go through [JevClient]: the live test is asserting that the
 * documented format is what the server actually accepts, so it must not depend on the
 * same parser it is validating. The key is passed in by the caller and never persisted.
 */
object JevWireProbe {

    /** Pinned to the documented model id. */
    private const val JEV_MODEL = "jev-latest"

    // encodeDefaults matches the production client: Jev's `model` field is required and
    // must never be omitted from the body.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** Raw response, so the test can assert on HTTP status as well as the body. */
    data class ProbeResult(val httpStatus: Int, val body: JsonObject) {
        fun answer(questionId: String): JevAnswer? = runCatching {
            body["answers"]?.jsonObject?.get(questionId)?.jsonObject?.let {
                json.decodeFromJsonElement(JevAnswer.serializer(), it)
            }
        }.getOrNull()
    }

    suspend fun evaluateNoul(apiKey: String, tool: String, arguments: String): ProbeResult =
        withContext(Dispatchers.IO) {
            val question = JevQuestion.noul(
                instructions = "Does this proposed device action delete or overwrite user " +
                    "data, change state that cannot be undone, place a call, send a message, " +
                    "make a payment, or transmit private data to an external service?",
                yesMeans = "The action has destructive, irreversible, or outward-facing effects.",
                noMeans = "The action is read-only or its effect is local and reversible."
            )

            val state = buildJsonObject {
                put("tool", JsonPrimitive(tool))
                put("arguments", JsonPrimitive(arguments))
            }

            post(
                apiKey = apiKey,
                questions = mapOf(JevSafetyGate.SAFETY to question),
                state = state
            )
        }

    suspend fun evaluateChoice(
        apiKey: String,
        prompt: String,
        candidates: Map<String, String>
    ): ProbeResult = withContext(Dispatchers.IO) {
        val question = JevQuestion.choice(
            instructions = "Which tools are needed to satisfy this request? " +
                "Choose only tools that are directly required.",
            options = candidates
        )

        val state = buildJsonObject {
            put("request", JsonPrimitive(prompt))
        }

        post(
            apiKey = apiKey,
            questions = mapOf(JevClient.SELECT_TOOLS to question),
            state = state
        )
    }

    private fun post(
        apiKey: String,
        questions: Map<String, JevQuestion>,
        state: JsonObject
    ): ProbeResult {
        val body = json.encodeToString(
            JevRequest.serializer(),
            JevRequest(state = state, model = JEV_MODEL, questions = questions)
        )

        val connection = (URL(JevClient.EVALUATION_ENDPOINT).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            ProbeResult(
                httpStatus = status,
                body = json.parseToJsonElement(text).jsonObject
            )
        } finally {
            connection.disconnect()
        }
    }
}