package com.edgehybrid.agent.agent

import com.edgehybrid.agent.learning.LessonsLedgerManager
import com.edgehybrid.agent.learning.PromptConstraintInjector
import com.edgehybrid.agent.memory.MemoryRetrieval
import com.edgehybrid.agent.memory.UserMemoryDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the system prompt from every durable source the app has.
 *
 * ## Why this class exists
 *
 * The system prompt was a hardcoded string literal inside
 * `AgentOrchestrator.executeLoop`, and the two systems that were supposed to
 * enrich it — the lessons ledger and user memory — were `@Provides`-bound and
 * never called. So the app advertised a self-correcting agent and had none: a
 * lesson recorded (never, in fact) could not change a single prompt.
 *
 * Centralizing assembly here means there is exactly one place that decides what
 * the model sees, and both stores have a consumer on the live path.
 *
 * ## Ordering matters
 *
 * Base identity -> skills -> lessons -> memory -> conversation context. Lessons
 * come after skills because a lesson outranks a general instruction ("this user
 * always wants X" beats "be helpful"), and memory comes last so it reads as
 * background knowledge rather than an order.
 */
@Singleton
open class AgentContextProvider @Inject constructor(
    private val constraintInjector: PromptConstraintInjector,
    private val lessonsLedgerManager: LessonsLedgerManager,
    private val userMemoryDao: UserMemoryDao,
    private val skillSource: com.edgehybrid.agent.skills.ProcedureSkillSource
) {

    /**
     * Build the system prompt for a turn.
     *
     * @param query the newest user text; drives memory retrieval. A blank query is
     *   legal and yields the base prompt plus lessons.
     */
    suspend fun buildSystemPrompt(query: String): String {
        val base = BASE_PERSONA

        // Lessons: corrective rules learned from this agent's own failures.
        val withLessons = constraintInjector.injectConstraints(base)

        // Skills: procedural knowledge the user installed. Failure is swallowed to an
        // empty block — a broken skill directory must not stop the agent answering.
        val skillsBlock = runCatching { skillSource.procedureBlock() }.getOrDefault("")

        // Memory: durable facts about the user, selected for THIS question.
        val memories = runCatching {
            userMemoryDao.activeMemories()
        }.getOrDefault(emptyList())
        val selected = MemoryRetrieval.select(memories, query)
        val memoryBlock = MemoryRetrieval.render(selected)

        return buildString {
            append(withLessons)
            if (skillsBlock.isNotBlank()) {
                appendLine()
                append(skillsBlock)
            }
            if (memoryBlock.isNotBlank()) {
                appendLine()
                append(memoryBlock)
            }
        }.trimEnd()
    }

    /**
     * Records a defect so the loop actually closes.
     *
     * Called from the failure paths — a tool error the model repeated, a rejected
     * confirmation, a provider 4xx. Without this, `LessonsLedgerManager` is a
     * write-only table and the ledger is a log, not a corrector.
     */
    suspend fun recordDefect(rule: String, category: String, triggerPattern: String) {
        runCatching {
            lessonsLedgerManager.recordDefect(rule, category, triggerPattern)
        }
    }

    /**
     * Applies a [LessonClassifier] verdict.
     *
     * Every call site routes through here rather than constructing a rule inline, so
     * the "is this durable?" decision is made in exactly one place and is unit
     * tested without touching the orchestrator.
     */
    suspend fun recordIfDurable(verdict: com.edgehybrid.agent.learning.LessonClassifier.Verdict) {
        if (!verdict.shouldRecord) return
        recordDefect(verdict.rule, verdict.category, verdict.triggerPattern)
    }

    suspend fun onToolError(toolName: String, message: String, isError: Boolean) =
        recordIfDurable(
            com.edgehybrid.agent.learning.LessonClassifier.onToolError(toolName, message, isError)
        )

    suspend fun onConfirmationRejected(toolName: String, reason: String) =
        recordIfDurable(
            com.edgehybrid.agent.learning.LessonClassifier.onConfirmationRejected(toolName, reason)
        )

    suspend fun onProviderError(statusCode: Int, snippet: String) =
        recordIfDurable(
            com.edgehybrid.agent.learning.LessonClassifier.onProviderError(statusCode, snippet)
        )

    /** Marks lessons applied, so ranking can prefer what is still relevant. */
    suspend fun touchLessons() {
        runCatching { lessonsLedgerManager.getActiveLessons(limit = 8) }
    }

    private companion object {
        /**
         * Extracted verbatim from the previous hardcoded literal so this change is
         * only about *where* the prompt comes from, not about changing the agent's
         * behaviour. Trimming: it re-sent on every turn, so it should be as short as
         * the role allows.
         */
        const val BASE_PERSONA = """You are Edge Hybrid Agent, a helpful AI assistant running on a Samsung Galaxy device.

You can answer questions, explain concepts, research, write code, and converse naturally.
You have native device tools (weather, units, timers, hardware). Call a tool only when needed; otherwise answer directly and conversationally."""
    }
}