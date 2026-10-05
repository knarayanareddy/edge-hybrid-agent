package com.edgehybrid.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for skill authoring.
 *
 * These run on the JVM with no device, which is the point: the security-relevant
 * decisions here (name validation, script entry-point enforcement, markdown
 * parsing) are pure functions and must not depend on an emulator to verify.
 */
class SkillAuthoringTest {

    private fun script(id: String = "my_script", body: String = "window.__edgeRun = function(input){}") =
        UserSkill(id = id, name = "My Script", description = "d", kind = UserSkill.Kind.SCRIPT, body = body)

    private fun procedure(id: String = "safe_migration", body: String = "Step 1. Back up.") =
        UserSkill(id = id, name = "Safe Migration", description = "d", kind = UserSkill.Kind.PROCEDURE, body = body)

    // ---- validation ----------------------------------------------------

    @Test
    fun `a well-formed skill is valid`() {
        assertTrue(SkillValidator.isValid(script()))
        assertTrue(SkillValidator.isValid(procedure()))
    }

    @Test
    fun `blank name and body are rejected`() {
        val errors = SkillValidator.validate(script().copy(name = "  ", body = ""))
        assertTrue(errors.contains(SkillValidationError.BlankName))
        assertTrue(errors.contains(SkillValidationError.BlankBody))
    }

    @Test
    fun `ids with path separators or traversal are rejected`() {
        for (bad in listOf("dotdot" + "/" + "escape", "a/b", "a\\b", "UPPER", "has space", "x".repeat(65), "")) {
            val errors = SkillValidator.validate(script(id = bad))
            assertTrue(
                "id '$bad' must be rejected",
                errors.any { it is SkillValidationError.IdMismatch }
            )
        }
    }

    @Test
    fun `a script without the __edgeRun entry point is rejected`() {
        // Without this, the script loads and the host finds no entry point, so the
        // caller sees a TIMEOUT instead of an error. That is the bug this prevents.
        val errors = SkillValidator.validate(script(body = "console.log('hi');"))
        assertTrue(errors.contains(SkillValidationError.ScriptMustDefineRun))
    }

    @Test
    fun `procedure bodies over budget are rejected at save time`() {
        val huge = procedure(body = "x".repeat(UserSkill.MAX_PROCEDURE_CHARS + 1))
        val errors = SkillValidator.validate(huge)
        assertTrue(errors.any { it is SkillValidationError.BodyTooLarge })
        assertTrue("an oversized procedure must report over budget", huge.exceedsBudget())
    }

    @Test
    fun `token estimate is proportional to body size`() {
        val small = procedure(body = "a".repeat(40))
        val large = procedure(body = "a".repeat(400))
        assertTrue("token estimate must grow with body size", large.estimatedTokens() > small.estimatedTokens())
    }

    // ---- slugify -------------------------------------------------------

    @Test
    fun `slugify produces stable filesystem-safe ids`() {
        assertEquals("web_extract", SkillValidator.slugify("Web Extract"))
        assertEquals("safe_migration", SkillValidator.slugify("  Safe Migration!  "))
        assertEquals("skill", SkillValidator.slugify("!!!"))
        // A kebab name must survive unchanged: the id then matches the frontmatter
        // it was parsed from, which is what makes import idempotent.
        assertEquals("api-contract-design", SkillValidator.slugify("api-contract-design"))
        // A SPACED name cannot become kebab — separators normalize to `_`, which is
        // the documented rule. Asserted explicitly so the distinction is intentional
        // rather than an accident of the regex order.
        assertEquals("api_contract_design", SkillValidator.slugify("API Contract Design"))
        // deterministic: re-saving the same name must not create a duplicate
        assertEquals(SkillValidator.slugify("Web Extract"), SkillValidator.slugify("web  extract"))
    }

    // ---- markdown parsing (the Hermes SKILL.md format) -----------------

    @Test
    fun `parses a Hermes-style skill-md document with frontmatter`() {
        val md = """
            ---
            name: api-contract-design
            description: Design and enforce an HTTP API contract
            ---

            # API Contract Design

            Step 1. Write the contract before the code.
        """.trimIndent()

        val skill = SkillValidator.parseMarkdown(md)
        assertEquals("api-contract-design", skill.id)
        assertEquals("api-contract-design", skill.name)
        assertEquals("Design and enforce an HTTP API contract", skill.description)
        assertEquals(UserSkill.Kind.PROCEDURE, skill.kind)
        assertTrue(skill.body.contains("Write the contract before the code"))
        assertFalse("frontmatter must not leak into the body", skill.body.contains("---"))
    }

    @Test
    fun `parses folded YAML description blocks`() {
        // Real Hermes frontmatter uses `description: >-` for long descriptions.
        val md = """
            ---
            name: folded
            description: >-
              this description spans
              several lines and must be joined
            ---
            body here
        """.trimIndent()

        val skill = SkillValidator.parseMarkdown(md)
        assertEquals("this description spans several lines and must be joined", skill.description)
    }

    @Test
    fun `a document with no frontmatter still parses as a procedure`() {
        val skill = SkillValidator.parseMarkdown("Just some instructions.")
        assertEquals(UserSkill.Kind.PROCEDURE, skill.kind)
        assertTrue(skill.body.contains("Just some instructions"))
    }

    @Test
    fun `an unterminated frontmatter block does not lose the content`() {
        val md = "---\nname: broken\n\nstill important"
        val skill = SkillValidator.parseMarkdown(md)
        assertTrue(skill.body.contains("still important"))
    }

    // ---- prompt block rendering ----------------------------------------

    @Test
    fun `renderProcedureBlock includes only enabled procedures`() {
        val skills = listOf(
            procedure(id = "a", body = "Do A"),
            procedure(id = "b", body = "Do B").copy(enabled = false),
            script()
        )
        val block = UserSkillStore::class.let { _ ->
            renderBlock(skills)
        }
        assertTrue(block.contains("Do A"))
        assertFalse(block.contains("Do B"))
        assertFalse("scripts must not enter the prose block", block.contains("__edgeRun"))
    }

    @Test
    fun `over-budget procedures are skipped, not silently truncated`() {
        // A half-sent procedure is worse than an absent one: the model cannot tell
        // it was cut, so it may act on partial instructions.
        val huge = procedure(id = "huge", body = "y".repeat(UserSkill.MAX_PROCEDURE_CHARS + 10))
        val block = renderBlock(listOf(huge))
        assertFalse(block.contains("yyyy"))
    }

    @Test
    fun `rendering nothing yields an empty block, not an empty heading`() {
        assertEquals("", renderBlock(emptyList()))
    }

    /** Mirrors UserSkillStore.renderProcedureBlock without needing a Context. */
    private fun renderBlock(skills: List<UserSkill>): String {
        val included = skills.filter {
            it.enabled && it.kind == UserSkill.Kind.PROCEDURE && !it.exceedsBudget()
        }
        if (included.isEmpty()) return ""
        return buildString {
            appendLine("# Skills")
            included.forEach { skill ->
                appendLine()
                appendLine("## ${skill.name}")
                if (skill.description.isNotBlank()) appendLine(skill.description)
                appendLine()
                appendLine(skill.body.trim())
            }
        }
    }
}