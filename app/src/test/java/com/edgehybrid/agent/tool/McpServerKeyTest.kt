package com.edgehybrid.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The MCP credential key must be derived identically at registration time and at call
 * time. If the two ever disagree, a stored token silently fails to authenticate, so the
 * derivation is pinned here.
 */
class McpServerKeyTest {

    @Test
    fun `key is the endpoint host`() {
        assertEquals("example.com", McpServerKey.forUrl("https://example.com/mcp"))
        assertEquals("example.com", McpServerKey.forUrl("https://example.com:8443/mcp"))
        assertEquals("mcp.internal.test", McpServerKey.forUrl("https://mcp.internal.test/v1"))
    }

    @Test
    fun `host casing and trailing whitespace are normalized away`() {
        assertEquals(
            McpServerKey.forUrl("https://Example.COM/mcp"),
            McpServerKey.forUrl("  https://example.com/mcp  ")
        )
    }

    @Test
    fun `unparseable urls fall back to the default key`() {
        assertEquals(McpServerKey.DEFAULT_KEY, McpServerKey.forUrl(""))
        assertEquals(McpServerKey.DEFAULT_KEY, McpServerKey.forUrl("not a url"))
        assertEquals(McpServerKey.DEFAULT_KEY, McpServerKey.forUrl("https://"))
    }

    @Test
    fun `different hosts produce different keys`() {
        assertNotEquals(
            McpServerKey.forUrl("https://a.example.com/mcp"),
            McpServerKey.forUrl("https://b.example.com/mcp")
        )
    }

    @Test
    fun `normalize lowercases and defaults blank names`() {
        assertEquals("my-server", McpServerKey.normalize("  My-Server "))
        assertEquals(McpServerKey.DEFAULT_KEY, McpServerKey.normalize("   "))
    }
}
