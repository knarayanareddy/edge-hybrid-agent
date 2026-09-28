package com.edgehybrid.agent.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val imageUrlsJson: String? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val modelUsed: String? = null,
    val latencyMs: Long = 0L,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "lessons_ledger")
data class LessonEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val rule: String,
    val category: String, // "SYNTAX", "TOOL_USE", "TIMEOUT", "HALLUCINATION", "LIFECYCLE"
    val triggerPattern: String,
    val frequency: Int = 1,
    val isResolved: Boolean = false,
    val lastRecordedAt: Long = System.currentTimeMillis()
)
