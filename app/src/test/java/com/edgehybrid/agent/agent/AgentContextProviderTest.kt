package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.local.LessonEntity
import com.edgehybrid.agent.learning.LessonsLedgerManager
import com.edgehybrid.agent.learning.PromptConstraintInjector
import com.edgehybrid.agent.memory.UserMemoryDao
import com.edgehybrid.agent.memory.UserMemoryEntity
import com.edgehybrid.agent.skills.ProcedureSkillSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test that was missing, and the reason the self-correcting claim was false.
 *
 * `LessonsLedgerManager` had a complete DAO-backed implementation, and
 * `PromptConstraintInjector` assembled a constraint block from it — both were
 * `@Provides`-bound, both compiled, both were unit-tested *in isolation*, and
 * **neither was ever called from the agent's execution path**. The suite was green
 * while the feature did nothing.
 *
 * These tests go through `AgentContextProvider`, which is what the orchestrator
 * actually calls, so an unwired provider now fails here instead of shipping.
 */
class AgentContextProviderTest {

    private class FakeLessonDao : com.edgehybrid.agent.data.local.LessonsDao {
        // Entity fields are `val`, so mutating one means replacing the row.
        val rows = mutableListOf<LessonEntity>()

        override suspend fun getActiveLessons(limit: Int): List<LessonEntity> =
            rows.filter { !it.isResolved }.sortedByDescending { it.frequency }.take(limit)

        override fun getAllLessonsFlow() = kotlinx.coroutines.flow.flowOf(rows.toList())

        override suspend fun insertLesson(lesson: LessonEntity) {
            rows.removeAll { it.id == lesson.id }
            rows += lesson
        }

        override suspend fun incrementFrequency(id: String, timestamp: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(
                frequency = rows[i].frequency + 1,
                lastRecordedAt = timestamp
            )
        }

        override suspend fun markResolved(id: String) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(isResolved = true)
        }
    }

    private fun provider(lessons: FakeLessonDao, memories: List<UserMemoryEntity> = emptyList()) =
        AgentContextProvider(
            constraintInjector = PromptConstraintInjector(LessonsLedgerManager(lessons)),
            lessonsLedgerManager = LessonsLedgerManager(lessons),
            userMemoryDao = NoMemoryDao(memories),
            skillSource = object : ProcedureSkillSource {
                override suspend fun procedureBlock(): String = ""
            }
        )

    @Test
    fun `a recorded lesson actually reaches the system prompt`() = runTest {
        val dao = FakeLessonDao()
        val provider = provider(dao)

        // Record through the same entry point the orchestrator uses.
        provider.recordDefect(
            rule = "Always ask before sending an SMS",
            category = "TOOL_USE",
            triggerPattern = "sms tool invoked without confirmation"
        )

        val prompt = provider.buildSystemPrompt("send a message to my mum")
        assertTrue(
            "the recorded lesson is MISSING from the prompt — the ledger is inert",
            prompt.contains("Always ask before sending an SMS")
        )
        assertTrue("category must be shown", prompt.contains("TOOL_USE"))
    }

    @Test
    fun `with no lessons the prompt has no empty constraint heading`() = runTest {
        val prompt = provider(FakeLessonDao()).buildSystemPrompt("hello")
        assertFalse(prompt.contains("MANDATORY CONSTRAINTS"))
        assertTrue("base persona must survive", prompt.contains("Edge Hybrid Agent"))
    }

    @Test
    fun `memory reaches the prompt and is scoped to the query`() = runTest {
        val memories = listOf(
            UserMemoryEntity(
                id = "1", content = "Deploys to Fly.io, not Vercel",
                category = "PROJECT", keywords = "fly deploy hosting", lastUsedAt = System.currentTimeMillis()
            ),
            UserMemoryEntity(
                id = "2", content = "Prefers dark mode", category = "PREFERENCE",
                keywords = "editor theme", lastUsedAt = System.currentTimeMillis()
            )
        )
        val prompt = provider(FakeLessonDao(), memories).buildSystemPrompt("where do we deploy?")
        assertTrue(prompt.contains("Fly.io"))
        assertFalse("irrelevant memory should not be injected", prompt.contains("dark mode"))
    }

    @Test
    fun `a lesson outranks a memory in the assembled prompt`() = runTest {
        val dao = FakeLessonDao()
        val provider = provider(
            dao,
            listOf(
                UserMemoryEntity(
                    id = "1", content = "Deploys to Fly.io", category = "PROJECT",
                    keywords = "deploy fly", lastUsedAt = System.currentTimeMillis()
                )
            )
        )
        provider.recordDefect("Never deploy without asking", "TOOL_USE", "deploy invoked directly")

        val prompt = provider.buildSystemPrompt("deploy the app")
        val lessonAt = prompt.indexOf("Never deploy without asking")
        val memoryAt = prompt.indexOf("Deploys to Fly.io")
        assertTrue(lessonAt >= 0 && memoryAt >= 0)
        assertTrue("lessons must precede memory", lessonAt < memoryAt)
    }

    @Test
    fun `a blank query still yields a usable prompt`() = runTest {
        val prompt = provider(FakeLessonDao()).buildSystemPrompt("")
        assertTrue(prompt.contains("Edge Hybrid Agent"))
    }
}