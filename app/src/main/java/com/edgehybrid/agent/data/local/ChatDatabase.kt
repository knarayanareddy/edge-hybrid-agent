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
        /**
         * v1 -> v2: adds the cross-app memory tables.
         *
         * The DDL below is a byte-for-byte copy of what Room generates for these
         * entities, and it must stay that way. Room validates the live schema after
         * every migration by comparing it to the generated one, and it compares the
         * DDL *text* - so an extra `DEFAULT 'PROFILE'` that looks harmless makes the
         * two differ and the app dies on launch with
         * "Migration didn't properly handle: user_memories".
         *
         * That failure is only observable at runtime: JVM unit tests cannot open a
         * Room database, and the CI instrumentation job starts from a clean install
         * where the DB is already at v2, so no migration ever runs there. Only an
         * upgrade of an existing v1 install exercises this path.
         *
         * If a column is added to these entities later, bump the version and add
         * MIGRATION_2_3 rather than editing this one, which has already shipped.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_memories` (" +
                        "`id` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`keywords` TEXT NOT NULL, " +
                        "`frequency` INTEGER NOT NULL, " +
                        "`isActive` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversation_memories` (" +
                        "`id` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`keywords` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                // No CREATE INDEX here on purpose.
                //
                // Room validates the whole schema after a migration, and an index it
                // does not expect is a mismatch just like a wrong column type. These
                // two indexes were declared in the migration but never on the
                // entities, so Room threw "Migration didn't properly handle:
                // user_memories" and rolled the migration back.
                //
                // If these are worth having, declare them with @Entity(indices = ...)
                // on UserMemoryEntity / ConversationMemoryEntity, which is what makes
                // Room expect them. Retrieval currently scans a table that holds a
                // user's facts - tens of rows, not millions - so the index would buy
                // nothing measurable.
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
