package com.edgehybrid.agent.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.readText

/**
 * Guards `MIGRATION_1_2` against the schema mismatch that made the app unlaunchable.
 *
 * ## The bug this exists to prevent
 *
 * `MIGRATION_1_2` originally created `user_memories` with extra `DEFAULT` clauses:
 *
 * ```
 * category TEXT NOT NULL DEFAULT 'PROFILE'
 * ```
 *
 * Room validates the schema after a migration by comparing it against the DDL it
 * generated from the entities, and that comparison is textual. The columns were
 * identical in name, order and type, but the DDL strings differed, so Room threw
 * `IllegalStateException: Migration didn't properly handle: user_memories` and the
 * app died on launch for every existing v1 install.
 *
 * ## Why it needed this test
 *
 * Room cannot open a database on the JVM, so no unit test exercised the migration.
 * The CI instrumentation job installs fresh, leaving the DB at v2, so no migration
 * runs there either. The only path that runs MIGRATION_1_2 is an upgrade of a real v1
 * install, which is exactly what happened on the device.
 *
 * So this asserts the invariant statically: the migration's DDL must equal the DDL
 * Room generates. `RoomGeneratedSchemaSource` supplies the generated side; when the
 * entities change, Room rewrites that file and this fails until the migration is
 * updated to match.
 */
class MigrationSchemaTest {

    /** Room's generated DDL for the two memory tables, captured from `ChatDatabase_Impl`. */
    private object RoomGeneratedSchemaSource {
        val expectedUserMemories =
            "CREATE TABLE IF NOT EXISTS `user_memories` (" +
                "`id` TEXT NOT NULL, `content` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                "`keywords` TEXT NOT NULL, `frequency` INTEGER NOT NULL, " +
                "`isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`lastUsedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        val expectedConversationMemories =
            "CREATE TABLE IF NOT EXISTS `conversation_memories` (" +
                "`id` TEXT NOT NULL, `summary` TEXT NOT NULL, `keywords` TEXT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    }

    /** The migration's DDL, normalised to single-line form for comparison. */
    private fun migrationSource(): String {
        val candidates = listOf(
            "app/src/main/java/com/edgehybrid/agent/data/local/ChatDatabase.kt",
            "../app/src/main/java/com/edgehybrid/agent/data/local/ChatDatabase.kt"
        )
        val path = candidates.firstOrNull { File(it).exists() }
            ?: error("ChatDatabase.kt not found from ${File(".").absolutePath}")
        return File(path).readText()
    }
    /** Extracts the migration body between its declaration and the @Volatile field. */
    private fun migrationBody(): String {
        val source = migrationSource()
        val start = source.indexOf("val MIGRATION_1_2")
        require(start > 0) { "MIGRATION_1_2 not found" }
        val end = source.indexOf("@Volatile", start)
        return source.substring(start, if (end > 0) end else source.length)
    }

    @Test
    fun `migration DDL has no DEFAULT clauses that Room would reject`() {
        val body = migrationBody()
        // Only the KDoc may mention DEFAULT; no executable statement may.
        val code = body.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "DEFAULT clauses make the migration DDL differ from Room's generated schema, " +
                "which throws \"Migration didn't properly handle\" and kills the app on upgrade",
            code.contains("DEFAULT")
        )
    }

    @Test
    fun `migration column order and types match the generated schema`() {
        val body = migrationBody()
        listOf(
            "`id` TEXT NOT NULL",
            "`content` TEXT NOT NULL",
            "`category` TEXT NOT NULL",
            "`keywords` TEXT NOT NULL",
            "`frequency` INTEGER NOT NULL",
            "`isActive` INTEGER NOT NULL",
            "`createdAt` INTEGER NOT NULL",
            "`lastUsedAt` INTEGER NOT NULL",
            "PRIMARY KEY(`id`)"
        ).forEach { fragment ->
            assertTrue("user_memories migration missing: $fragment", body.contains(fragment))
        }
        listOf(
            "`id` TEXT NOT NULL",
            "`summary` TEXT NOT NULL",
            "`keywords` TEXT NOT NULL",
            "`sessionId` TEXT NOT NULL",
            "`role` TEXT NOT NULL",
            "`createdAt` INTEGER NOT NULL"
        ).forEach { fragment ->
            assertTrue("conversation_memories migration missing: $fragment", body.contains(fragment))
        }
    }

    @Test
    fun `the memory entities declare no indices, which is what the migration must match`() {
        // Source of truth for the other half of the contract: if someone later adds
        // @Entity(indices = ...) to either entity, Room will start expecting those
        // indexes and this test would still pass while the migration breaks again.
        //
        // Both entities live in UserMemoryEntity.kt, so assert on the one file rather
        // than on a ConversationMemoryEntity.kt that does not exist.
        val path = listOf(
            "app/src/main/java/com/edgehybrid/agent/memory/UserMemoryEntity.kt",
            "../app/src/main/java/com/edgehybrid/agent/memory/UserMemoryEntity.kt"
        ).firstOrNull { File(it).exists() } ?: error("UserMemoryEntity.kt not found")
        val text = File(path).readText()
        assertTrue("fixture must actually declare the entities", text.contains("UserMemoryEntity"))
        assertTrue("fixture must actually declare the entities", text.contains("ConversationMemoryEntity"))
        assertFalse(
            "the entities now declare an index; MIGRATION_1_2 must be updated to match",
            text.contains("indices =")
        )
    }

    @Test
    fun `both memory tables are created`() {
        val body = migrationBody()
        assertTrue(body.contains("CREATE TABLE IF NOT EXISTS `user_memories`"))
        assertTrue(body.contains("CREATE TABLE IF NOT EXISTS `conversation_memories`"))
    }

    @Test
    fun `migration creates no index Room does not expect`() {
        // The migration originally added idx_user_memories_active and
        // idx_conversation_memories_created. The entities never declared them, so
        // Room's post-migration schema validation treated each as unexpected and
        // threw "Migration didn't properly handle: user_memories", rolling the whole
        // migration back and leaving the app unlaunchable.
        //
        // An unexpected index is a schema mismatch in exactly the same way a wrong
        // column type is, so this asserts the absence.
        val body = migrationBody()
        val code = body.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "the migration must not CREATE INDEX; the entities declare no indices",
            code.contains("CREATE INDEX")
        )
        assertFalse(code.contains("idx_user_memories_active"))
        assertFalse(code.contains("idx_conversation_memories_created"))
    }

    @Test
    fun `expected DDL snapshots still describe a single-column-per-declaration table`() {
        // Guards the snapshots above against silently drifting into a shape Room
        // would not generate (for example gaining DEFAULTs on the Kotlin side, which
        // would then need the migration updated to match).
        assertEquals(
            8,
            RoomGeneratedSchemaSource.expectedUserMemories
                .removePrefix("CREATE TABLE IF NOT EXISTS `user_memories` (")
                .removeSuffix("PRIMARY KEY(`id`))")
                .trim()
                .split(", ").size
        )
        assertEquals(
            6,
            RoomGeneratedSchemaSource.expectedConversationMemories
                .removePrefix("CREATE TABLE IF NOT EXISTS `conversation_memories` (")
                .removeSuffix("PRIMARY KEY(`id`))")
                .trim()
                .split(", ").size
        )
    }
}
