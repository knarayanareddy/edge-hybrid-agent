package com.edgehybrid.agent.core.jev

import com.edgehybrid.agent.core.inference.ChatMessage
import com.edgehybrid.agent.core.inference.InferenceEngine
import com.edgehybrid.agent.core.inference.StreamChunk
import com.edgehybrid.agent.data.local.LessonEntity
import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.data.local.SecureKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

sealed class RouteDecision {
    data class LocalLiteRT(val reasoning: String) : RouteDecision()
    data class CloudModel(val modelId: String, val reasoning: String) : RouteDecision()
    data class Reject(val reason: String) : RouteDecision()
}

data class DispatchResult(
    val route: RouteDecision,
    val injectedLessons: List<String>,
    val riskScore: Int
)

/**
 * System 1 dispatcher that coordinates routing, safety checks, and continuous learning.
 */
@Singleton
class JevDispatcher @Inject constructor(
    private val jevClient: JevClient,
    private val lessonsDao: LessonsDao,
    private val keyStore: SecureKeyStore
) {
    /**
     * Inspects the prompt and history, pulls relevant lessons from SQLite, and determines route.
     */
    suspend fun dispatch(
        prompt: String,
        recentHistory: List<ChatMessage> = emptyList()
    ): DispatchResult {
        // 1. Fetch active lessons to inject into prompt context
        val activeLessons = lessonsDao.getActiveLessons(limit = 8)
        val lessonStrings = activeLessons.map { it.rule }

        if (!keyStore.isJevRoutingEnabled()) {
            return DispatchResult(
                route = RouteDecision.CloudModel(
                    modelId = keyStore.getSelectedCloudModel(),
                    reasoning = "JEV routing disabled; defaulting to user-selected cloud model"
                ),
                injectedLessons = lessonStrings,
                riskScore = 0
            )
        }

        // 2. Classify with JEV
        val historySnippet = recentHistory.takeLast(3).joinToString("\n") { "${it.role}: ${it.content}" }
        val classification = jevClient.classifyRoute(prompt, historySnippet)

        // 3. High risk guardrail check
        if (classification.riskScore > 80) {
            val highRiskKeywords = listOf("rm -rf", "drop table", "factory reset", "format disk")
            if (highRiskKeywords.any { prompt.lowercase().contains(it) }) {
                return DispatchResult(
                    route = RouteDecision.Reject("Command blocked by JEV System 1 Safety Guardrail: destructive action detected."),
                    injectedLessons = lessonStrings,
                    riskScore = classification.riskScore
                )
            }
        }

        // 4. Determine Route
        val route = when (classification.route) {
            "LOCAL" -> {
                if (keyStore.isLocalFallbackEnabled()) {
                    RouteDecision.LocalLiteRT(classification.reasoning)
                } else {
                    RouteDecision.CloudModel(keyStore.getSelectedCloudModel(), classification.reasoning)
                }
            }
            "CLOUD_REASONING" -> {
                // Route to a deep reasoning model if complex
                RouteDecision.CloudModel("anthropic/claude-3.5-sonnet", classification.reasoning)
            }
            else -> {
                RouteDecision.CloudModel(keyStore.getSelectedCloudModel(), classification.reasoning)
            }
        }

        return DispatchResult(
            route = route,
            injectedLessons = lessonStrings,
            riskScore = classification.riskScore
        )
    }

    /**
     * Post-generation evaluation: checks for false-positives or flaws, records lessons if needed.
     */
    suspend fun reviewAndLearn(
        prompt: String,
        output: String
    ): JevReviewResponse {
        val activeLessons = lessonsDao.getActiveLessons(5).map { it.rule }
        val review = jevClient.reviewResponse(prompt, output, activeLessons)

        if (!review.passes && review.reflectionLesson != null) {
            // Save newly discovered lesson to prevent repeat mistakes
            lessonsDao.insertLesson(
                LessonEntity(
                    rule = review.reflectionLesson,
                    category = "SYSTEM1_REVIEW",
                    triggerPattern = prompt.take(60),
                    frequency = 1
                )
            )
        }

        return review
    }
}
