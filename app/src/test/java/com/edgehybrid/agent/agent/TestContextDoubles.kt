package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.local.LessonEntity
import com.edgehybrid.agent.memory.ConversationMemoryEntity
import com.edgehybrid.agent.memory.UserMemoryDao
import com.edgehybrid.agent.memory.UserMemoryEntity
import com.edgehybrid.agent.skills.ProcedureSkillSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Null-object doubles for prompt-assembly dependencies.
 *
 * Every method returns the empty case, which is the safe default: a provider with
 * no lessons and no memories still yields a valid base prompt. Tests that care
 * about content supply their own DAO instead of mutating these.
 */
object NoLessonsDao : com.edgehybrid.agent.data.local.LessonsDao {
    override suspend fun getActiveLessons(limit: Int): List<LessonEntity> = emptyList()
    override fun getAllLessonsFlow(): Flow<List<LessonEntity>> = flowOf(emptyList())
    override suspend fun insertLesson(lesson: LessonEntity) = Unit
    override suspend fun incrementFrequency(id: String, timestamp: Long) = Unit
    override suspend fun markResolved(id: String) = Unit
}

class NoMemoryDao(
    private val rows: List<UserMemoryEntity> = emptyList()
) : UserMemoryDao {
    override suspend fun activeMemories(): List<UserMemoryEntity> = rows
    override fun observeActiveMemories(): Flow<List<UserMemoryEntity>> = flowOf(rows)
    override suspend fun byId(id: String): UserMemoryEntity? = rows.find { it.id == id }
    override suspend fun upsert(memory: UserMemoryEntity) = Unit
    override suspend fun insertIfAbsent(memory: UserMemoryEntity) = Unit
    override suspend fun increment(id: String, now: Long) = Unit
    override suspend fun deactivate(id: String, now: Long) = Unit
    override suspend fun activeCount(): Int = rows.size
    override suspend fun clear() = Unit
    override suspend fun recentConversations(limit: Int): List<ConversationMemoryEntity> = emptyList()
    override suspend fun upsertConversation(memory: ConversationMemoryEntity) = Unit
    override suspend fun pruneConversations(cutoff: Long) = Unit
}

object NoSkills : ProcedureSkillSource {
    override suspend fun procedureBlock(): String = ""
}
