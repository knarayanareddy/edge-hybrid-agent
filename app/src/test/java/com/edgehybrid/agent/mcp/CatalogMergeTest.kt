package com.edgehybrid.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge logic for the curated and user-defined catalogues.
 *
 * These assertions guard a bug that reached device before any unit test caught it:
 * `catalog()` computed `knownIds` as the user ids **unioned with the curated ids**, so
 * `filterNot { it.id in knownIds }` removed every curated server from its own catalogue.
 * On the JVM the object was never exercised, so the catalogue looked fine; on a device it
 * returned zero servers.
 *
 * The tests below exercise the merge *rule* directly, since the registry itself needs a
 * Context and the keystore.
 */
class CatalogMergeTest {

    /** Mirrors `McpServerRegistry.catalog`'s merge step. */
    private fun merge(
        userServers: List<McpServerEntry>,
        curated: List<McpServerEntry> = CuratedMcpServers.ALL
    ): List<McpServerEntry> {
        val userIds = userServers.map { it.id }.toSet()
        return userServers + curated.filterNot { it.id in userIds }
    }

    @Test
    fun `curated servers appear when there are no user servers`() {
        val merged = merge(userServers = emptyList())

        assertEquals(
            "a fresh install must still see every curated server",
            CuratedMcpServers.ALL.size,
            merged.size
        )
        CuratedMcpServers.ALL.forEach { curated ->
            assertTrue(
                "curated server ${curated.id} was filtered out of its own catalogue",
                merged.any { it.id == curated.id }
            )
        }
    }

    @Test
    fun `a user server does not duplicate a curated one`() {
        // A user server that shadows a curated id must replace it, not appear twice.
        val shadow = McpServerEntry(
            id = "linear",
            name = "My Linear",
            url = "https://linear.internal.example.org/mcp"
        )

        val merged = merge(userServers = listOf(shadow))

        assertEquals(
            "the shadowing entry should replace the curated one",
            CuratedMcpServers.ALL.size,
            merged.size
        )
        assertEquals(1, merged.count { it.id == "linear" })
        assertEquals("https://linear.internal.example.org/mcp", merged.first { it.id == "linear" }.url)
    }

    @Test
    fun `an unrelated user server is added alongside the curated set`() {
        val custom = McpServerEntry("my_server", "Mine", "https://mine.example.org/mcp")

        val merged = merge(userServers = listOf(custom))

        assertEquals(CuratedMcpServers.ALL.size + 1, merged.size)
        assertTrue(merged.any { it.id == "my_server" })
        assertTrue(
            "every curated id must survive alongside a user server",
            CuratedMcpServers.IDS.all { id -> merged.any { it.id == id } }
        )
    }

    @Test
    fun `the merge result is never empty on a fresh install`() {
        // The exact regression: the old rule produced an empty list here.
        assertFalse(merge(emptyList()).isEmpty())
    }

    @Test
    fun `curated entries carry their probed endpoint and category`() {
        merge(emptyList()).filter { it.isCurated }.forEach { entry ->
            assertTrue("${entry.id} must be https", entry.url.startsWith("https://"))
            assertTrue("${entry.id} needs a category", entry.category.isNotBlank())
            assertNotNull(entry.name)
            assertTrue("${entry.id} must not ship enabled", !entry.enabled)
        }
    }
}