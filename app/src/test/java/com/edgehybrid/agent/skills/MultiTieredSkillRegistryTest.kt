package com.edgehybrid.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Multi-Tiered Skill & Tool Architecture.
 *
 * Verifies Tier 1 (Intent Domain Classification), Tier 2 (Progressive Frontmatter Hydration),
 * and dynamic activation schemas.
 */
class MultiTieredSkillRegistryTest {

    @Test
    fun `domain classification correctly identifies device OS queries`() {
        val registry = MultiTieredSkillRegistry()
        val domains = registry.classifyDomainsForQuery("What is my battery level and can you tap the screen?")
        assertTrue(domains.contains(MultiTieredSkillRegistry.SkillDomain.DEVICE_OS))
    }

    @Test
    fun `domain classification correctly identifies web research queries`() {
        val registry = MultiTieredSkillRegistry()
        val domains = registry.classifyDomainsForQuery("Search Google for latest news about Dutch trains")
        assertTrue(domains.contains(MultiTieredSkillRegistry.SkillDomain.WEB_RESEARCH))
    }

    @Test
    fun `domain classification correctly identifies productivity queries`() {
        val registry = MultiTieredSkillRegistry()
        val domains = registry.classifyDomainsForQuery("Create a note and set a timer for 10 minutes")
        assertTrue(domains.contains(MultiTieredSkillRegistry.SkillDomain.PRODUCTIVITY))
    }

    @Test
    fun `domain classification returns all domains when query is generic`() {
        val registry = MultiTieredSkillRegistry()
        val domains = registry.classifyDomainsForQuery("Hello who are you?")
        assertEquals(MultiTieredSkillRegistry.SkillDomain.entries.toSet(), domains)
    }

    @Test
    fun `progressive catalog produces compact domain index without schema bloat`() {
        val registry = MultiTieredSkillRegistry()
        val catalog = registry.buildProgressiveCatalog()
        assertTrue(catalog.contains("MULTI-TIERED SKILL DOMAINS"))
        assertTrue(catalog.contains("Device & Screen OS"))
        assertTrue(catalog.contains("Web & Deep Research"))
        assertTrue(catalog.contains("Personal Productivity"))
        assertTrue(catalog.contains("activate_skill"))
    }

    @Test
    fun `activation tool definition has correct name and parameter schema`() {
        val registry = MultiTieredSkillRegistry()
        val toolDef = registry.getActivationToolDefinition()
        assertEquals("activate_skill", toolDef.function.name)
        assertNotNull(toolDef.function.parameters)
        assertTrue(toolDef.function.parameters.toString().contains("skill_name"))
    }

    @Test
    fun `custom skill registration associates tool with correct domain`() {
        val registry = MultiTieredSkillRegistry()
        val customDescriptor = MultiTieredSkillRegistry.SkillDescriptor(
            name = "custom_benchmark_runner",
            domain = MultiTieredSkillRegistry.SkillDomain.SANDBOX_CODE,
            summary = "Run device compute benchmark",
            isReadOnly = true
        )
        registry.register(customDescriptor)
        val catalog = registry.buildProgressiveCatalog()
        assertTrue(catalog.contains("Sandbox & Compute"))
    }
}
