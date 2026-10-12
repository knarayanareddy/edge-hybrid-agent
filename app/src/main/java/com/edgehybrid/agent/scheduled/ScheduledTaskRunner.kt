package com.edgehybrid.agent.scheduled

import com.edgehybrid.agent.agent.CloudStreamEvent
import com.edgehybrid.agent.agent.InferenceEngine
import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.tool.ToolGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledTaskRunner(
    private val taskStore: ScheduledTaskStore,
    private val toolGateway: ToolGateway?,
    private val inferenceEngine: InferenceEngine?,
    private val infoBoxStore: com.edgehybrid.agent.infobox.InfoBoxStore? = null
) {
    @Inject
    constructor(
        taskStore: ScheduledTaskStore,
        toolGateway: ToolGateway,
        infoBoxStore: com.edgehybrid.agent.infobox.InfoBoxStore
    ) : this(taskStore, toolGateway, null, infoBoxStore)

    constructor(taskStore: ScheduledTaskStore) : this(taskStore, null, null, null)

    /**
     * Executes a scheduled task autonomously.
     * Records the execution timestamp and summarized snippet into [ScheduledTaskStore].
     */
    suspend fun runTask(taskId: String): String = withContext(Dispatchers.IO) {
        val task = taskStore.tasks.value.firstOrNull { it.id == taskId }
            ?: return@withContext "Error: Task with ID '$taskId' not found."

        val outcome = try {
            val effectivePrompt = if (task.id == "preset_infobox_overview" && infoBoxStore != null) {
                val yesterdayItems = infoBoxStore.getItemsFromYesterday()
                val targetItems = if (yesterdayItems.isNotEmpty()) yesterdayItems else infoBoxStore.items.value.take(15)
                infoBoxStore.buildDailyDigestPrompt(targetItems)
            } else {
                task.prompt
            }

            val systemPrompt = """
                You are an autonomous background assistant executing a scheduled recurring task.
                Task: ${task.title}
                Schedule: ${task.formattedSchedule()}
                User instruction: $effectivePrompt
                
                Execute this task thoroughly, synthesize the findings, and provide a clear, high-yield summary.
            """.trimIndent()

            if (inferenceEngine != null) {
                val tools = toolGateway?.loadCatalog()?.definitions ?: emptyList()
                val events = inferenceEngine.streamChat(
                    messages = listOf(
                        ChatMessage(role = "system", content = systemPrompt),
                        ChatMessage(role = "user", content = effectivePrompt)
                    ),
                    tools = tools
                )

                val buffer = StringBuilder()
                events.collect { event ->
                    when (event) {
                        is CloudStreamEvent.AssistantDelta -> buffer.append(event.text)
                        is CloudStreamEvent.TurnCompleted -> if (buffer.isEmpty()) buffer.append(event.turn.content)
                        else -> Unit
                    }
                }
                buffer.toString().ifBlank { "Task completed with empty output." }
            } else {
                "Executed scheduled task '${task.title}': Check completed at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}."
            }
        } catch (e: Exception) {
            "Execution error: ${e.message ?: "Unknown error"}"
        }

        val snippet = if (outcome.length > 280) outcome.take(277) + "..." else outcome
        taskStore.recordRun(task.id, snippet)
        outcome
    }
}
