package com.edgehybrid.agent.core.tools

import com.edgehybrid.agent.core.inference.ToolDefinition
import com.edgehybrid.agent.core.inference.ToolParameterProperty
import com.edgehybrid.agent.core.inference.ToolParameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedSkill(
    val name: String,
    val description: String,
    val instructions: String,
    val tools: List<ToolDefinition>,
    val skillDir: File
)

/**
 * Loads skills from on-device directories (e.g. app internal storage /skills/ directory),
 * parsing SKILL.md YAML frontmatter and markdown body, extracting tool definitions.
 */
@Singleton
class SkillLoader @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }
    private val activeSkills = mutableMapOf<String, LoadedSkill>()

    fun getActiveSkills(): List<LoadedSkill> = activeSkills.values.toList()

    fun getAllToolDefinitions(): List<ToolDefinition> {
        return activeSkills.values.flatMap { it.tools }
    }

    /**
     * Parses a SKILL.md file with YAML frontmatter like:
     * ---
     * name: web-search
     * description: Searches the web for query
     * ---
     * Detailed instructions...
     */
    fun loadSkillFromDirectory(dir: File): LoadedSkill? {
        val skillFile = File(dir, "SKILL.md")
        if (!skillFile.exists() || !skillFile.canRead()) return null

        val content = skillFile.readText()
        val frontmatterRegex = Regex("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n([\\s\\S]*)")
        val match = frontmatterRegex.find(content)

        val (name, description, instructions) = if (match != null) {
            val frontmatter = match.groupValues[1]
            val body = match.groupValues[2]

            var parsedName = dir.name
            var parsedDesc = ""

            frontmatter.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("name:")) {
                    parsedName = trimmed.removePrefix("name:").trim()
                } else if (trimmed.startsWith("description:")) {
                    parsedDesc = trimmed.removePrefix("description:").trim()
                }
            }
            Triple(parsedName, parsedDesc, body)
        } else {
            Triple(dir.name, "Custom skill: ${dir.name}", content)
        }

        // Generate default tool definition for this skill
        val skillTool = ToolDefinition(
            name = "skill_${name.replace("-", "_")}",
            description = description.ifEmpty { "Executes the $name skill workflow" },
            parameters = ToolParameters(
                properties = mapOf(
                    "input" to ToolParameterProperty(
                        type = "string",
                        description = "Arguments or context needed by the skill"
                    )
                ),
                required = listOf("input")
            )
        )

        val loaded = LoadedSkill(
            name = name,
            description = description,
            instructions = instructions,
            tools = listOf(skillTool),
            skillDir = dir
        )

        activeSkills[name] = loaded
        return loaded
    }

    /**
     * Scans an internal or external directory for skills.
     */
    fun scanDirectory(rootDir: File): Int {
        if (!rootDir.exists() || !rootDir.isDirectory) return 0
        var count = 0
        rootDir.listFiles()?.forEach { child ->
            if (child.isDirectory && File(child, "SKILL.md").exists()) {
                val skill = loadSkillFromDirectory(child)
                if (skill != null) count++
            }
        }
        return count
    }

    /**
     * Executes a tool invoked by the LLM.
     */
    suspend fun executeTool(toolName: String, argumentsJson: String): String {
        val skill = activeSkills.values.firstOrNull { s ->
            s.tools.any { it.name == toolName }
        } ?: return "Error: Tool '$toolName' is not registered."

        return "Executed skill '${skill.name}' with input: $argumentsJson. (Local Android Execution)"
    }
}
