package com.edgehybrid.agent.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserMemoryDao {

    /**
     * Active memories ordered by usefulness.
     *
     * Ranking by frequency then recency mirrors what [MemoryRetrieval] does, so the
     * cold-query fallback in Kotlin and the SQL ordering agree. Relevance filtering
     * still happens in Kotlin because it depends on the query.
     */
    @Query("SELECT * FROM user_memories WHERE isActive = 1 ORDER BY frequency DESC, lastUsedAt DESC")
    suspend fun activeMemories(): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memories WHERE isActive = 1 ORDER BY frequency DESC, lastUsedAt DESC")
    fun observeActiveMemories(): Flow<List<UserMemoryEntity>>

    @Query("SELECT * FROM user_memories WHERE id = :id")
    suspend fun byId(id: String): UserMemoryEntity?

    /**
     * Upsert keyed on id, but see [remember] — the natural key is the content, so a
     * restated fact increments frequency instead of appearing twice.
     */
    @Upsert
    suspend fun upsert(memory: UserMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(memory: UserMemoryEntity)

    @Query("UPDATE user_memories SET frequency = frequency + 1, lastUsedAt = :now WHERE id = :id")
    suspend fun increment(id: String, now: Long)

    @Query("UPDATE user_memories SET isActive = 0, lastUsedAt = :now WHERE id = :id")
    suspend fun deactivate(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM user_memories WHERE isActive = 1")
    suspend fun activeCount(): Int

    @Query("DELETE FROM user_memories")
    suspend fun clear()

    // ---- conversation memory ------------------------------------------

    @Query("SELECT * FROM conversation_memories ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentConversations(limit: Int): List<ConversationMemoryEntity>

    @Upsert
    suspend fun upsertConversation(memory: ConversationMemoryEntity)

    @Query("DELETE FROM conversation_memories WHERE createdAt < :cutoff")
    suspend fun pruneConversations(cutoff: Long)
}