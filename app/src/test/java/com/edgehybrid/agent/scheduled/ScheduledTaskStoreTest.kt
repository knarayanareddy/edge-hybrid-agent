package com.edgehybrid.agent.scheduled

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScheduledTaskStoreTest {

    private lateinit var store: ScheduledTaskStore

    @Before
    fun setUp() {
        // Use no-arg constructor which uses in-memory fallback (null context)
        store = ScheduledTaskStore()
    }

    @Test
    fun testAll22PresetsLoadedByDefault() {
        val tasks = store.tasks.value
        assertEquals("Must load all 22 preset recipes", 22, tasks.size)

        // Verify key presets from screenshots
        val jobTask = tasks.firstOrNull { it.id == "preset_find_job" }
        assertNotNull("Job task preset must exist", jobTask)
        assertEquals("Find a job", jobTask?.title)
        assertEquals(ScheduleRecurrence.WEEKLY, jobTask?.recurrence)
        assertEquals(listOf(1, 4), jobTask?.daysOfWeek)
        assertEquals("💼", jobTask?.iconEmoji)
        assertFalse(jobTask!!.isEnabled)

        val weatherTask = tasks.firstOrNull { it.id == "preset_weather_plan" }
        assertNotNull("Weather task preset must exist", weatherTask)
        assertEquals(ScheduleRecurrence.DAILY, weatherTask?.recurrence)
        assertEquals("⛅", weatherTask?.iconEmoji)

        val languageTask = tasks.firstOrNull { it.id == "preset_learn_language" }
        assertNotNull("Language task preset must exist", languageTask)
        assertEquals("🗣️", languageTask?.iconEmoji)
    }

    @Test
    fun testToggleAndGetActiveTasks() = runTest {
        assertTrue("Initially no active tasks", store.getActiveTasks().isEmpty())

        store.toggleTask("preset_find_job", true)
        val active = store.getActiveTasks()
        assertEquals(1, active.size)
        assertEquals("Find a job", active.first().title)
        assertTrue(active.first().isEnabled)

        store.toggleTask("preset_find_job", false)
        assertTrue(store.getActiveTasks().isEmpty())
    }

    @Test
    fun testSaveCustomTask() = runTest {
        val custom = ScheduledTask(
            id = "custom_test_1",
            title = "Morning Standup Digest",
            description = "Summarize commit logs before 9am",
            prompt = "Review git commits from past 24h and draft standup update.",
            recurrence = ScheduleRecurrence.WEEKDAYS,
            hour = 8,
            minute = 45,
            isEnabled = true,
            iconEmoji = "☕"
        )
        store.saveTask(custom)

        val found = store.tasks.value.firstOrNull { it.id == "custom_test_1" }
        assertNotNull(found)
        assertEquals("Morning Standup Digest", found?.title)
        assertTrue(found!!.isEnabled)
        assertEquals("Every weekday at 08:45", found.formattedSchedule())
    }

    @Test
    fun testRecordRunOutcome() = runTest {
        store.toggleTask("preset_industry_news", true)
        val snippet = "Top news: Gemini 3.8 Flash released with sub-100ms latency on mobile."
        store.recordRun("preset_industry_news", snippet)

        val task = store.tasks.value.first { it.id == "preset_industry_news" }
        assertNotNull("lastRunTime must be populated", task.lastRunTime)
        assertEquals(snippet, task.lastResultSnippet)
    }
}
