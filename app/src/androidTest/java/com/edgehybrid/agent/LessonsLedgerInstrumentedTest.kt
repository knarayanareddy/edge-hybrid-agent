package com.edgehybrid.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edgehybrid.agent.data.local.ChatDatabase
import com.edgehybrid.agent.learning.LessonClassifier
import com.edgehybrid.agent.learning.LessonsLedgerManager
import com.edgehybrid.agent.learning.PromptConstraintInjector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the self-correcting ledger actually records and replays.
 *
 * ## Why this cannot be a JVM test
 *
 * Every layer below touches Room, which needs a real Android SQLite. The JVM suite
 * proves the classifier's decisions and the prompt assembler in isolation, which is
 * why it was green while the ledger had never once written a row on a device.
 *
 * ## Why the earlier "0 rows" readings were not evidence
 *
 * A host-side `sqlite3` insert into the pulled database proves nothing: the app
 * holds its own connection and WAL, and on next open it overwrites the file. The
 * row appeared to vanish, which looked like the app deleting it. It was never
 * written where the app could see it. Anything that mutates app state has to go
 * through the app.
 */
@RunWith(AndroidJUnit4::class)
class LessonsLedgerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aDurableToolErrorIsRecordedAndReachesTheNextPrompt() = runBlocking {
        val dao = ChatDatabase.getInstance(context).lessonsDao()
        val manager = LessonsLedgerManager(dao)
        val injector = PromptConstraintInjector(manager)

        val rule = "PROBE ${System.currentTimeMillis()}: durable tool rule"
        manager.recordDefect(rule, "TOOL_USE", "probe:toolverify")

        val active = dao.getActiveLessons(50)
        assertTrue(
            "the recorded rule must be readable back from the DAO",
            active.any { it.rule == rule }
        )

        val prompt = injector.injectConstraints("BASE PROMPT")
        assertTrue(
            "the recorded rule must appear in the next system prompt; this is the " +
                "seam that was never previously proven end to end",
            prompt.contains(rule)
        )
        assertTrue("the base prompt must survive injection", prompt.contains("BASE PROMPT"))

        // Clean up so repeated runs do not accumulate.
        active.filter { it.rule == rule }.forEach { dao.markResolved(it.id) }
    }

    @Test
    fun aTransientToolErrorIsNotRecorded() = runBlocking {
        val dao = ChatDatabase.getInstance(context).lessonsDao()
        val manager = LessonsLedgerManager(dao)
        val before = dao.getActiveLessons(100).size

        // A timeout says nothing durable about how the agent should behave, so
        // recording it would permanently degrade every later turn.
        val verdict = LessonClassifier.onToolError("weather", "Request timeout after 30s", true)
        assertFalse("a timeout must not become a permanent rule", verdict.shouldRecord)

        if (verdict.shouldRecord) manager.recordDefect(verdict.rule, verdict.category, verdict.triggerPattern)
        assertEquals(before, dao.getActiveLessons(100).size)
    }

    @Test
    fun anUnconfiguredLedgerStillProducesAValidPrompt() = runBlocking {
        val dao = ChatDatabase.getInstance(context).lessonsDao()
        val injector = PromptConstraintInjector(LessonsLedgerManager(dao))
        val prompt = injector.injectConstraints("BASE PROMPT")
        assertTrue("the base prompt must always survive", prompt.contains("BASE PROMPT"))
        assertFalse(
            "an empty ledger must not emit a dangling or empty section heading",
            prompt.contains("## none")
        )
    }
}
