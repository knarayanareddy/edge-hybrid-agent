package com.edgehybrid.agent.learning

import com.edgehybrid.agent.data.local.LessonEntity
import com.edgehybrid.agent.data.local.LessonsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LessonsLedgerManagerTest {

    private lateinit var fakeDao: FakeLessonsDao
    private lateinit var manager: LessonsLedgerManager
    private lateinit var injector: PromptConstraintInjector

    class FakeLessonsDao : LessonsDao {
        val list = mutableListOf<LessonEntity>()

        override suspend fun getActiveLessons(limit: Int): List<LessonEntity> {
            return list.filter { !it.isResolved }.take(limit)
        }

        override fun getAllLessonsFlow(): Flow<List<LessonEntity>> {
            return flowOf(list.toList())
        }

        override suspend fun insertLesson(lesson: LessonEntity) {
            list.add(lesson)
        }

        override suspend fun incrementFrequency(id: String, timestamp: Long) {
            val idx = list.indexOfFirst { it.id == id }
            if (idx != -1) {
                list[idx] = list[idx].copy(frequency = list[idx].frequency + 1, lastRecordedAt = timestamp)
            }
        }

        override suspend fun markResolved(id: String) {
            val idx = list.indexOfFirst { it.id == id }
            if (idx != -1) {
                list[idx] = list[idx].copy(isResolved = true)
            }
        }
    }

    @Before
    fun setUp() {
        fakeDao = FakeLessonsDao()
        manager = LessonsLedgerManager(fakeDao)
        injector = PromptConstraintInjector(manager)
    }

    @Test
    fun testRecordDefectAndIncrementFrequency() = runTest {
        val lesson1 = manager.recordDefect("Never use dummy tokens", "SECURITY", "dummy_token")
        assertNotNull(lesson1.id)
        assertEquals(1, fakeDao.list.size)

        // Recording identical rule increments frequency
        val lesson2 = manager.recordDefect("Never use dummy tokens", "SECURITY", "dummy_token")
        assertEquals(2, lesson2.frequency)
        assertEquals(1, fakeDao.list.size)
    }

    @Test
    fun testPromptConstraintInjector() = runTest {
        manager.recordDefect("Always check network connection before calling cloud API", "NETWORK", "HttpException")
        val basePrompt = "You are an AI assistant."
        val augmented = injector.injectConstraints(basePrompt)

        assertTrue(augmented.contains("MANDATORY CONSTRAINTS FROM PAST LESSONS"))
        assertTrue(augmented.contains("Always check network connection"))
    }
}
