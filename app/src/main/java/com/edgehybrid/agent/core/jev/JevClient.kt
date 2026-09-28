package com.edgehybrid.agent.core.jev

import com.edgehybrid.agent.data.local.SecureKeyStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class JevClassificationRequest(
    val prompt: String,
    val task: String = "INTENT_ROUTING",
    val context: String? = null
)

@Serializable
data class JevClassificationResponse(
    val route: String, // "LOCAL", "CLOUD_FAST", "CLOUD_REASONING", "TOOL_EXECUTION"
    val confidence: Double = 0.0,
    val riskScore: Int = 0, // 0 to 100
    val reasoning: String = "",
    val suggestedTools: List<String> = emptyList()
)

@Serializable
data class JevReviewRequest(
    val prompt: String,
    val generatedCodeOrResponse: String,
    val knownLessons: List<String> = emptyList()
)

@Serializable
data class JevReviewResponse(
    val passes: Boolean,
    val score: Int, // 0 to 100
    val detectedFlaws: List<String> = emptyList(),
    val reflectionLesson: String? = null
)

/**
 * Client for TypeSafe AI JEV / System 1 fast decision engine.
 */
@Singleton
class JevClient @Inject constructor(
    private val keyStore: SecureKeyStore
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    /**
     * Rapid System 1 classification to decide routing between on-device LiteRT and Cloud LLM.
     */
    suspend fun classifyRoute(userPrompt: String, historySnippet: String? = null): JevClassificationResponse {
        val apiKey = keyStore.getTypeSafeApiKey()
        if (apiKey.isBlank()) {
            // Heuristic fallback if TypeSafe key is not configured
            return heuristicRoute(userPrompt)
        }

        return try {
            val response: HttpResponse = httpClient.post(TYPESAFE_JEV_ENDPOINT) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("X-Client", "EdgeHybridAgent-Android")
                setBody(
                    JevClassificationRequest(
                        prompt = userPrompt,
                        task = "INTENT_ROUTING",
                        context = historySnippet
                    )
                )
            }
            if (response.status.value in 200..299) {
                response.body<JevClassificationResponse>()
            } else {
                heuristicRoute(userPrompt)
            }
        } catch (e: Exception) {
            heuristicRoute(userPrompt)
        }
    }

    /**
     * Post-generation quality & false-positive review
     */
    suspend fun reviewResponse(
        prompt: String,
        output: String,
        lessons: List<String>
    ): JevReviewResponse {
        val apiKey = keyStore.getTypeSafeApiKey()
        if (apiKey.isBlank()) {
            return heuristicReview(output)
        }

        return try {
            val response: HttpResponse = httpClient.post("$TYPESAFE_JEV_ENDPOINT/review") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("X-Client", "EdgeHybridAgent-Android")
                setBody(
                    JevReviewRequest(
                        prompt = prompt,
                        generatedCodeOrResponse = output,
                        knownLessons = lessons
                    )
                )
            }
            if (response.status.value in 200..299) {
                response.body<JevReviewResponse>()
            } else {
                heuristicReview(output)
            }
        } catch (e: Exception) {
            heuristicReview(output)
        }
    }

    private fun heuristicRoute(prompt: String): JevClassificationResponse {
        val lower = prompt.lowercase()
        val wordCount = prompt.split("\\s+".toRegex()).size

        // Simple local keywords
        val isSimpleLocal = wordCount <= 12 && (
            lower.startsWith("hi") || lower.startsWith("hello") ||
            lower.startsWith("what time") || lower.startsWith("calculate") ||
            lower.startsWith("summarize this:")
        )

        val requiresReasoning = lower.contains("architect") ||
            lower.contains("debug") || lower.contains("refactor") ||
            lower.contains("compare") || lower.contains("solve") ||
            wordCount > 60

        val route = when {
            isSimpleLocal -> "LOCAL"
            requiresReasoning -> "CLOUD_REASONING"
            else -> "CLOUD_FAST"
        }

        return JevClassificationResponse(
            route = route,
            confidence = 0.85,
            riskScore = if (lower.contains("delete") || lower.contains("rm ") || lower.contains("drop table")) 85 else 10,
            reasoning = "Heuristic classification based on token length and semantic intent"
        )
    }

    private fun heuristicReview(output: String): JevReviewResponse {
        val flaws = mutableListOf<String>()
        val lower = output.lowercase()

        if (lower.contains("todo:") || lower.contains("// todo") || lower.contains("# todo")) {
            flaws.add("Contains unresolved TODO placeholders")
        }
        if (lower.contains("insert your api key") || lower.contains("your_api_key_here")) {
            flaws.add("Contains dummy credentials or placeholder tokens")
        }
        if (output.trim().length < 20) {
            flaws.add("Response too short or truncated")
        }

        val score = (100 - flaws.size * 30).coerceIn(10, 100)
        return JevReviewResponse(
            passes = flaws.isEmpty(),
            score = score,
            detectedFlaws = flaws,
            reflectionLesson = if (flaws.isNotEmpty()) "Avoid returning placeholders like TODO or placeholder keys" else null
        )
    }

    companion object {
        private const val TYPESAFE_JEV_ENDPOINT = "https://api.typesafe.ai/v1/jev"
    }
}
