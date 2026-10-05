package com.edgehybrid.agent.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        LessonEntity::class,
        NoteEntity::class,
        VectorChunkEntity::class,
        // Cross-app memory. Version bumped 1 -> 2 below, so an existing install gets
        // a real migration instead of a destructive recreate that would erase every
        // lesson the (previously unused) ledger held.
        com.edgehybrid.agent.memory.UserMemoryEntity::class,
        com.edgehybrid.agent.memory.ConversationMemoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun lessonsDao(): LessonsDao
    abstract fun noteDao(): NoteDao
    abstract fun vectorDao(): VectorDao
    abstract fun userMemoryDao(): com.edgehybrid.agent.memory.UserMemoryDao

    companion object {
        private const val DB_NAME = "edge_hybrid_agent.db"

        /**
         * v1 -> v2 adds the two memory tables.
         *
         * Written out explicitly rather than relying on a destructive fallback:
         * `fallbackToDestructiveMigration()` would drop the `lessons` table, which is
         * the only place anything the "self-correcting" feature has learned lives.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS user_memories (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "content TEXT NOT NULL, " +
                        "category TEXT NOT NULL DEFAULT 'PROFILE', " +
                        "keywords TEXT NOT NULL DEFAULT '', " +
                        "frequency INTEGER NOT NULL DEFAULT 1, " +
                        "isActive INTEGER NOT NULL DEFAULT 1, " +
                        "createdAt INTEGER NOT NULL, " +
                        "lastUsedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversation_memories (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "summary TEXT NOT NULL, " +
                        "keywords TEXT NOT NULL DEFAULT '', " +
                        "sessionId TEXT NOT NULL DEFAULT '', " +
                        "role TEXT NOT NULL DEFAULT 'exchange', " +
                        "createdAt INTEGER NOT NULL)"
                )
                // Retrieval scans active rows by frequency then recency.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_user_memories_active " +
                        "ON user_memories (isActive, frequency, lastUsedAt)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_conversation_memories_created " +
                        "ON conversation_memories (createdAt)"
                )
            }
        }

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    DB_NAME
                )
                    // Explicit migration, NOT destructive fallback.
                    //
                    // `fallbackToDestructiveMigration()` was here while the DB sat at
                    // version 1, so it never fired. Adding the memory tables bumped the
                    // version to 2 and it would have silently deleted the `lessons`
                    // table on first upgrade — destroying every recorded correction in
                    // the one store the self-correcting feature depends on.
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
