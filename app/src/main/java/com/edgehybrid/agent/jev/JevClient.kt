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
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TypeSafe AI JEV client managing risk assessments, response verification, and decision caching.
 */
@Singleton
class JevClient @Inject constructor(
    private val keyStore: SecureKeyStore,
    private val decisionCache: JevDecisionCache,
    private val riskClassifier: JevRiskClassifier
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000L
            connectTimeoutMillis = 5_000L
            socketTimeoutMillis = 10_000L
        }
    }

    suspend fun evaluateAction(
        actionName: String,
        prompt: String,
        parametersJson: String = ""
    ): JevEvaluationResponse = withContext(Dispatchers.IO) {
        // 1. Check local decision cache
        val cached = decisionCache.get(prompt)
        if (cached != null) {
            return@withContext cached
        }

        // 2. Perform rapid local risk classification
        val localClassification = riskClassifier.classifyAction(actionName, parametersJson)

        // If local classification indicates critical danger, return immediately
        if (localClassification.riskScore >= 90) {
            decisionCache.put(prompt, localClassification)
            return@withContext localClassification
        }

        // 3. If TypeSafe API key is available, query remote JEV endpoint
        val apiKey = keyStore.getTypeSafeApiKey()
        if (apiKey.isBlank()) {
            decisionCache.put(prompt, localClassification)
            return@withContext localClassification
        }

        try {
            val response: HttpResponse = httpClient.post(TYPESAFE_JEV_ENDPOINT) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("X-Client", "EdgeHybridAgent-Android")
                setBody(
                    JevEvaluationRequest(
                        prompt = prompt,
                        proposedAction = actionName,
                        context = mapOf("parameters" to parametersJson)
                    )
                )
            }

            if (response.status.value in 200..299) {
                val cloudEval = response.body<JevEvaluationResponse>()
                decisionCache.put(prompt, cloudEval)
                cloudEval
            } else {
                decisionCache.put(prompt, localClassification)
                localClassification
            }
        } catch (_: Exception) {
            decisionCache.put(prompt, localClassification)
            localClassification
        }
    }

    companion object {
        private const val TYPESAFE_JEV_ENDPOINT = "https://api.typesafe.ai/v1/jev/evaluate"
    }
}
