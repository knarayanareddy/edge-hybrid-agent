package com.edgehybrid.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curated MCP catalogue must only contain endpoints that were actually probed, and
 * must never enable a server implicitly.
 */
class CuratedMcpServersTest {

    @Test
    fun `every curated endpoint is https`() {
        CuratedMcpServers.ALL.forEach { entry ->
            assertTrue(
                "${entry.id} must use https, was ${entry.url}",
                entry.url.startsWith("https://")
            )
        }
    }

    @Test
    fun `no curated server is enabled by default`() {
        CuratedMcpServers.ALL.forEach { entry ->
            assertFalse("${entry.id} must ship disabled", entry.enabled)
        }
    }

    @Test
    fun `ids are unique and url-safe`() {
        val ids = CuratedMcpServers.ALL.map { it.id }
        assertEquals("duplicate ids in curated list", ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertTrue("bad id: $id", id.matches(Regex("^[a-z0-9][a-z0-9_-]*$")))
        }
    }

    @Test
    fun `only probe-verified endpoints are shipped`() {
        // A server that could not be reached must not appear in the catalog, otherwise the
        // app ships a URL that is known not to work.
        val shipped = CuratedMcpServers.ALL.map { it.id }.toSet()
        val notReachable = setOf("stripe", "vercel", "huggingface", "atlassian", "gitmcp")
        assertTrue(
            "unreachable servers must not be shipped: ${shipped intersect notReachable}",
            (shipped intersect notReachable).isEmpty()
        )
    }

    @Test
    fun `auth-required servers are flagged`() {
        CuratedMcpServers.FIGMA.requiresApiKey.let { assertTrue("figma needs auth", it) }
        CuratedMcpServers.LINEAR.requiresApiKey.let { assertTrue("linear needs auth", it) }
        CuratedMcpServers.DEEPWIKI.requiresApiKey.let { assertFalse("deepwiki is open", it) }
    }

    @Test
    fun `no auth ids are a subset of all ids`() {
        assertTrue(
            CuratedMcpServers.NO_AUTH_IDS.all { it in CuratedMcpServers.IDS }
        )
    }

    @Test
    fun `credential keys are normalized`() {
        assertEquals("figma", McpServerKeys.forId("  Figma  "))
        assertEquals(McpServerKeys.forId(""), McpServerKeys.forId("   "))
    }
}