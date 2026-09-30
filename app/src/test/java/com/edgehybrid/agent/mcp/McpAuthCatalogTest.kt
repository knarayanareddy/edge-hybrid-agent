package com.edgehybrid.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curated auth requirements must reflect what vendors actually document, not guesses.
 *
 * This replaces an earlier test that asserted against `OAuthServerConfig`, a type holding
 * hand-written client ids and authorize/token endpoints that no vendor had issued for this
 * app. Those would have failed on first sign-in. The class is gone; the researched facts
 * live in [McpAuthCatalog].
 */
class McpAuthCatalogTest {

    @Test
    fun `every server declares an auth mode and an explanation`() {
        McpAuthCatalog.ALL.forEach { auth ->
            assertTrue(
                "${auth.serverId} must explain its auth requirement",
                auth.explanation.isNotBlank()
            )
        }
    }

    @Test
    fun `every read-only endpoint is https`() {
        McpAuthCatalog.ALL.forEach { auth ->
            auth.readOnlyUrl?.let { url ->
                assertTrue("${auth.serverId} read-only url must be https, was $url", url.startsWith("https://"))
            }
        }
    }

    @Test
    fun `Linear accepts a bearer token and publishes a narrower endpoint`() {
        val linear = McpAuthCatalog.forId("linear")
        assertNotNull(linear)
        assertEquals(McpAuthMode.BEARER_TOKEN, linear!!.mode)
        assertEquals("https://mcp.linear.app/mcp/readonly", linear.readOnlyUrl)
        assertEquals("read", linear.readOnlyScopeHint)
    }

    @Test
    fun `Notion and Figma are marked as needing dynamic registration`() {
        // Notion uses RFC 7591 DCR; Figma is catalog-approved only. Neither can be
        // onboarded with a hardcoded client id, which is why they are not BEARER_TOKEN.
        listOf("notion", "figma", "supabase").forEach { id ->
            val auth = McpAuthCatalog.forId(id)
            assertNotNull("$id should be declared", auth)
            assertEquals(
                "$id must not claim a simple bearer-token path",
                McpAuthMode.DYNAMIC_REGISTRATION,
                auth!!.mode
            )
            assertNull("$id must not advertise a read-only endpoint", auth.readOnlyUrl)
        }
    }

    @Test
    fun `no server declares a client secret`() {
        val declared = McpAuthCatalog::class.java.declaredFields.map { it.name }
        assertFalse(
            "McpAuthCatalog must not carry a secret field: $declared",
            declared.any { it.contains("secret", ignoreCase = true) }
        )
    }

    @Test
    fun `an unknown server has no auth entry`() {
        assertNull(McpAuthCatalog.forId("not-a-server"))
    }

    @Test
    fun `redirect uri is a private app scheme, not http`() {
        val uri = McpOAuthRedirectActivity.redirectUri()
        assertTrue("must be an app scheme, got $uri", uri.startsWith("edgehybrid://"))
        assertFalse("a web scheme would let any site trigger the redirect", uri.startsWith("http"))
    }

    @Test
    fun `credential keys are normalized`() {
        assertEquals("figma", McpServerKeys.forId("  Figma  "))
        assertEquals(McpServerKeys.forId(""), McpServerKeys.forId("   "))
    }
}