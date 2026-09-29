package com.edgehybrid.agent.learning

import com.edgehybrid.agent.data.local.LessonEntity
import com.edgehybrid.agent.data.local.LessonsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages persistent storage and resolution of agent reflection lessons and safety constraints.
 */
@Singleton
class LessonsLedgerManager @Inject constructor(
    private val lessonsDao: LessonsDao
) {

    fun observeAllLessons(): Flow<List<LessonEntity>> = lessonsDao.getAllLessonsFlow()

    suspend fun getActiveLessons(limit: Int = 10): List<LessonEntity> = withContext(Dispatchers.IO) {
        lessonsDao.getActiveLessons(limit)
    }

    suspend fun recordDefect(
        rule: String,
        category: String,
        triggerPattern: String
    ): LessonEntity = withContext(Dispatchers.IO) {
        val active = lessonsDao.getActiveLessons(50)
        val existing = active.find { it.rule.equals(rule, ignoreCase = true) }

        if (existing != null) {
            lessonsDao.incrementFrequency(existing.id)
            existing.copy(frequency = existing.frequency + 1)
        } else {
            val newLesson = LessonEntity(
                id = UUID.randomUUID().toString(),
                rule = rule,
                category = category,
                triggerPattern = triggerPattern,
                frequency = 1,
                isResolved = false,
                lastRecordedAt = System.currentTimeMillis()
            )
            lessonsDao.insertLesson(newLesson)
            newLesson
        }
    }

    suspend fun resolveLesson(lessonId: String) = withContext(Dispatchers.IO) {
        lessonsDao.markResolved(lessonId)
    }
}
