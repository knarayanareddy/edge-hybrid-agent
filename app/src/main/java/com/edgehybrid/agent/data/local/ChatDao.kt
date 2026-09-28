package com.edgehybrid.agent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getMessagesListForSession(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)
}

@Dao
interface LessonsDao {
    @Query("SELECT * FROM lessons_ledger WHERE isResolved = 0 ORDER BY frequency DESC, lastRecordedAt DESC LIMIT :limit")
    suspend fun getActiveLessons(limit: Int = 10): List<LessonEntity>

    @Query("SELECT * FROM lessons_ledger ORDER BY lastRecordedAt DESC")
    fun getAllLessonsFlow(): Flow<List<LessonEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLesson(lesson: LessonEntity)

    @Query("UPDATE lessons_ledger SET frequency = frequency + 1, lastRecordedAt = :timestamp WHERE id = :id")
    suspend fun incrementFrequency(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE lessons_ledger SET isResolved = 1 WHERE id = :id")
    suspend fun markResolved(id: String)
}
