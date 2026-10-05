package com.edgehybrid.agent.skills

/**
 * A skill the user can create, edit, and delete from inside the app.
 *
 * Two kinds exist, and the distinction is load-bearing:
 *
 *  - [Kind.SCRIPT] — JavaScript evaluated inside the headless sandbox. Good for
 *    deterministic computation the model cannot do reliably (parsing, base64,
 *    date math). Runs with no tokens.
 *  - [Kind.PROCEDURE] — prose the model reads before acting. Good for anything
 *    requiring judgment, where the value *is* the reasoning: how to migrate a
 *    schema safely, what to check before a risky action. No code executes.
 *
 * The app originally shipped only [Kind.SCRIPT], which is why three bundled
 * scripts was the whole catalogue. Prose skills are the larger half of what makes
 * an agent useful and they were simply missing.
 */
data class UserSkill(
    val id: String,
    val name: String,
    val description: String,
    val kind: Kind,
    val body: String,
    val enabled: Boolean = true,
    val origin: Origin = Origin.USER,
    val updatedAt: Long = 0L
) {
    enum class Kind { SCRIPT, PROCEDURE }

    enum class Origin { BUNDLED, USER }

    /**
     * Token budget for a procedure skill. This is a cost control, not a quality
     * one: prose is re-sent on every turn, so an unbounded body is a latency and
     * spend bug that only shows up in production.
     */
    fun estimatedTokens(): Int = (body.length + description.length) / 4

    /** True when the skill is too large to inject every turn. */
    fun exceedsBudget(maxChars: Int = MAX_PROCEDURE_CHARS): Boolean =
        kind == Kind.PROCEDURE && body.length > maxChars

    companion object {
        /** ~8k characters ≈ 2k tokens. Enough for a real procedure. */
        const val MAX_PROCEDURE_CHARS = 8000
        const val MAX_SCRIPT_CHARS = 120_000
    }
}

/** Why a skill was rejected. Surfaced verbatim in the editor. */
sealed class SkillValidationError(val message: String) {
    object BlankName : SkillValidationError("Skill needs a name")
    object NameTooLong : SkillValidationError("Name must be 200 characters or fewer")
    object BlankBody : SkillValidationError("Skill body is empty")
    object IdMismatch : SkillValidationError("Skill id must match [a-z0-9_-] and be 64 characters or fewer")
    data class BodyTooLarge(val max: Int, val actual: Int) :
        SkillValidationError("Body is $actual characters; limit is $max")
    object ScriptMustDefineRun : SkillValidationError(
        "A script skill must define window.__edgeRun(input, origins)"
    )
}

/**
 * Pure validation. Separated from storage and from the UI so it is unit-testable
 * without a device — the earlier skill work had no such seam, which is part of
 * why nothing about skill authoring was verified.
 */
object SkillValidator {

    private val ID_PATTERN = Regex("^[a-z0-9_-]{1,64}$")

    fun validate(skill: UserSkill): List<SkillValidationError> {
        val errors = mutableListOf<SkillValidationError>()

        if (skill.name.isBlank()) errors += SkillValidationError.BlankName
        if (skill.name.length > 200) errors += SkillValidationError.NameTooLong

        if (!ID_PATTERN.matches(skill.id)) errors += SkillValidationError.IdMismatch

        if (skill.body.isBlank()) {
            errors += SkillValidationError.BlankBody
        } else {
            val limit = when (skill.kind) {
                UserSkill.Kind.SCRIPT -> UserSkill.MAX_SCRIPT_CHARS
                UserSkill.Kind.PROCEDURE -> UserSkill.MAX_PROCEDURE_CHARS
            }
            if (skill.body.length > limit) {
                errors += SkillValidationError.BodyTooLarge(limit, skill.body.length)
            }
        }

        // A script skill without the entry point loads fine and then silently does
        // nothing — the host document calls __edgeRun and finds nothing, so the
        // caller sees a timeout rather than an error. Catch it at write time.
        if (skill.kind == UserSkill.Kind.SCRIPT && skill.body.isNotBlank()) {
            if (!skill.body.contains("__edgeRun")) {
                errors += SkillValidationError.ScriptMustDefineRun
            }
        }

        return errors
    }

    fun isValid(skill: UserSkill): Boolean = validate(skill).isEmpty()

    /**
     * Derives a stable, filesystem-safe id from a display name.
     *
     * Two rules that pull in opposite directions, and the resolution:
     *
     *  - "Web Extract" (spaces) must produce a separator -> `web_extract`
     *  - "api-contract-design" (already kebab) must survive unchanged, because a
     *    Hermes skill's id in its frontmatter is its kebab name, and rewriting the
     *    hyphens means the id no longer matches the document it came from.
     *
     * So: spaces and illegal characters become `_`; an existing hyphen is left
     * alone. Runs of separators collapse to one. Deterministic, so re-saving the
     * same name never creates a duplicate.
     */
    fun slugify(name: String): String =
        name.trim().lowercase()
            // Spaces and underscores become `_`; an existing hyphen is preserved.
            // Done in two steps because a single character class cannot express
            // "space yes, hyphen no" — the earlier version collapsed hyphens too,
            // which made an imported kebab id disagree with its own frontmatter.
            .replace(' ', '_')
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .replace(Regex("_{2,}"), "_")
            .trim('_')
            .take(64)
            .ifEmpty { "skill" }

    /**
     * Parses a SKILL.md-style document with YAML-ish frontmatter. This is the
     * format Hermes uses, so an existing skill library can be pasted in unchanged.
     *
     * Tolerates the real-world variants: `>-` folded blocks, quoted values, and
     * frontmatter that is simply absent.
     */
    fun parseMarkdown(raw: String): UserSkill {
        val text = raw.trim()
        if (!text.startsWith("---")) {
            return UserSkill(
                id = "imported_skill",
                name = "Imported skill",
                description = "",
                kind = UserSkill.Kind.PROCEDURE,
                body = text
            )
        }

        val end = text.indexOf("\n---", 3)
        if (end < 0) {
            return UserSkill("imported_skill", "Imported skill", "", UserSkill.Kind.PROCEDURE, text)
        }

        val front = text.substring(3, end)
        val body = text.substring(end + 4).trimStart('\n')

        fun field(key: String): String? {
            val line = front.lines().firstOrNull { it.trimStart().startsWith("$key:") }
                ?: return null
            var value = line.substringAfter("$key:").trim()
            if (value == ">" || value == ">-") {
                // Folded block: take following indented lines.
                val idx = front.lines().indexOf(line)
                val collected = mutableListOf<String>()
                for (l in front.lines().drop(idx + 1)) {
                    if (l.isBlank()) continue
                    if (!l.startsWith(" ") && !l.startsWith("\t")) break
                    collected += l.trim()
                }
                return collected.joinToString(" ").trim()
            }
            return value.trim('"', '\'')
        }

        val name = field("name") ?: "Imported skill"
        return UserSkill(
            id = slugify(name),
            name = name,
            description = field("description") ?: "",
            kind = UserSkill.Kind.PROCEDURE,
            body = body,
            origin = UserSkill.Origin.USER
        )
    }
}