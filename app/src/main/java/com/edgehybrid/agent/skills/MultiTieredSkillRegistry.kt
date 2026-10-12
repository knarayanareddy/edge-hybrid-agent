package com.edgehybrid.agent.skills

import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ToolDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-Tiered, Complex & Nuanced Skill & Tool Architecture (ChatGPT / Assistant Grade).
 *
 * ## Why Multi-Tiered:
 * Dumping dozens of tools flatly into a single system prompt causes:
 * 1. Severe token overhead and prompt caching invalidation.
 * 2. Parameter confusion and hallucinated tool calls.
 * 3. Context overflow on mobile on-device SLMs (Snapdragon NPU 2k-4k token limit).
 *
 * ## The 4 Tiers:
 * - **Tier 1 (Domain Classification)**: Tools grouped into functional domains. Dynamic intent
 *   matching activates only relevant domains per turn.
 * - **Tier 2 (Progressive Frontmatter Hydration)**: System prompt receives lightweight index;
 *   full tool schemas and procedural playbooks are loaded on-demand via [activate_skill].
 * - **Tier 3 (Stateful Execution & Safety Gating)**: Multi-phase lifecycle with timeout guards,
 *   fail-closed confirmation gating, and pointer truncation for large payloads.
 * - **Tier 4 (Self-Correction & Dynamic Extension)**: Error feedback diagnostics allow the agent
 *   to self-correct, and dynamic tools can be staged in sandbox before permanent installation.
 */
@Singleton
class MultiTieredSkillRegistry(
    private val userSkillStore: UserSkillStore? = null
) {
    enum class SkillDomain(val title: String, val description: String) {
        DEVICE_OS("Device & Screen OS", "Hardware status, accessibility screen automation, and One UI settings"),
        WEB_RESEARCH("Web & Deep Research", "Live web search, Wikipedia, and webpage extraction"),
        PRODUCTIVITY("Personal Productivity", "Calendar events, quick notes, timers, alarms, and messaging"),
        SANDBOX_CODE("Sandbox & Compute", "Isolated JavaScript execution, math, and unit conversion"),
        EXTERNAL_MCP("External MCP Servers", "Remote Model Context Protocol tools and integrations"),
        USER_PLAYBOOK("Procedural Playbooks", "User-authored multi-step workflow playbooks and procedures")
    }

    @Serializable
    data class SkillDescriptor(
        val name: String,
        val domain: SkillDomain,
        val summary: String,
        val isReadOnly: Boolean,
        val requiresBiometricOrConfirmation: Boolean = !isReadOnly
    )

    private val domainTools = ConcurrentHashMap<SkillDomain, MutableSet<String>>()
    private val registeredDescriptors = ConcurrentHashMap<String, SkillDescriptor>()

    init {
        registerBuiltInDomains()
    }

    private fun registerBuiltInDomains() {
        // Device OS Domain
        register(SkillDescriptor("get_battery_status", SkillDomain.DEVICE_OS, "Query battery level, temperature, and charging state", true))
        register(SkillDescriptor("get_storage_status", SkillDomain.DEVICE_OS, "Query internal and SD storage capacity", true))
        register(SkillDescriptor("get_memory_status", SkillDomain.DEVICE_OS, "Query RAM memory pressure", true))
        register(SkillDescriptor("get_display_settings", SkillDomain.DEVICE_OS, "Query screen resolution and refresh rate", true))
        register(SkillDescriptor("get_screen_elements", SkillDomain.DEVICE_OS, "Inspect visible UI elements on phone screen", true))
        register(SkillDescriptor("click_screen_element", SkillDomain.DEVICE_OS, "Tap a specific UI element on phone screen", false))
        register(SkillDescriptor("click_screen_coordinates", SkillDomain.DEVICE_OS, "Tap screen coordinates (x, y)", false))
        register(SkillDescriptor("type_screen_text", SkillDomain.DEVICE_OS, "Input text into focused screen element", false))
        register(SkillDescriptor("perform_system_key", SkillDomain.DEVICE_OS, "Trigger system Back, Home, or Recents", false))

        // Web Research Domain
        register(SkillDescriptor("live_web_search", SkillDomain.WEB_RESEARCH, "Real-time web search for recent information", true))
        register(SkillDescriptor("search_wikipedia", SkillDomain.WEB_RESEARCH, "Search encyclopedic knowledge on Wikipedia", true))
        register(SkillDescriptor("extract_webpage_content", SkillDomain.WEB_RESEARCH, "Read and clean text from HTTP/HTTPS URLs", true))
        register(SkillDescriptor("plan_transit_journey", SkillDomain.WEB_RESEARCH, "Public transit routes and schedules", true))

        // Productivity Domain
        register(SkillDescriptor("create_quick_note", SkillDomain.PRODUCTIVITY, "Save a secure local note", false))
        register(SkillDescriptor("list_quick_notes", SkillDomain.PRODUCTIVITY, "Read stored local notes", true))
        register(SkillDescriptor("create_calendar_event", SkillDomain.PRODUCTIVITY, "Add a Google Calendar event", false))
        register(SkillDescriptor("query_calendar_events", SkillDomain.PRODUCTIVITY, "Query calendar events", true))
        register(SkillDescriptor("set_timer", SkillDomain.PRODUCTIVITY, "Start an on-device countdown timer", false))
        register(SkillDescriptor("set_alarm", SkillDomain.PRODUCTIVITY, "Schedule an alarm in clock app", false))
        register(SkillDescriptor("send_sms", SkillDomain.PRODUCTIVITY, "Draft SMS in messaging app", false))

        // Sandbox & Compute Domain
        register(SkillDescriptor("calculate_math", SkillDomain.SANDBOX_CODE, "Evaluate formulas and math expressions", true))
        register(SkillDescriptor("convert_currency", SkillDomain.SANDBOX_CODE, "Convert international exchange rates", true))
        register(SkillDescriptor("convert_temperature", SkillDomain.SANDBOX_CODE, "Convert temperature units", true))
        register(SkillDescriptor("get_world_time", SkillDomain.SANDBOX_CODE, "Query world city timezones", true))
    }

    fun register(descriptor: SkillDescriptor) {
        registeredDescriptors[descriptor.name] = descriptor
        domainTools.computeIfAbsent(descriptor.domain) { ConcurrentHashMap.newKeySet() }.add(descriptor.name)
    }

    /**
     * Tier 1: Classifies query intent and returns matching skill domains.
     * Always includes standard compute and device queries as baseline.
     */
    fun classifyDomainsForQuery(query: String): Set<SkillDomain> {
        val q = query.lowercase(Locale.ROOT)
        val activeDomains = mutableSetOf(SkillDomain.SANDBOX_CODE) // baseline

        if (q.contains("screen") || q.contains("click") || q.contains("tap") || q.contains("app") ||
            q.contains("battery") || q.contains("ram") || q.contains("volume") || q.contains("phone") ||
            q.contains("settings") || q.contains("display")
        ) {
            activeDomains.add(SkillDomain.DEVICE_OS)
        }

        if (q.contains("search") || q.contains("google") || q.contains("web") || q.contains("wikipedia") ||
            q.contains("url") || q.contains("http") || q.contains("news") || q.contains("train")
        ) {
            activeDomains.add(SkillDomain.WEB_RESEARCH)
        }

        if (q.contains("note") || q.contains("calendar") || q.contains("event") || q.contains("timer") ||
            q.contains("alarm") || q.contains("sms") || q.contains("text") || q.contains("remind")
        ) {
            activeDomains.add(SkillDomain.PRODUCTIVITY)
        }

        if (q.contains("mcp") || q.contains("server") || q.contains("github") || q.contains("slack")) {
            activeDomains.add(SkillDomain.EXTERNAL_MCP)
        }

        // If specific intent was detected, return targeted domains; otherwise fallback to all
        return if (activeDomains.size > 1) activeDomains else SkillDomain.entries.toSet()
    }

    /**
     * Tier 2: Builds progressive skill catalog for system prompt injection.
     * Contains only lightweight metadata to preserve prompt cache and mobile SLM tokens.
     */
    fun buildProgressiveCatalog(): String = buildString {
        appendLine("### MULTI-TIERED SKILL DOMAINS:")
        SkillDomain.entries.forEach { domain ->
            val count = domainTools[domain]?.size ?: 0
            if (count > 0) {
                appendLine("- **${domain.title}** (${domain.name}): ${domain.description} [$count tools]")
            }
        }
        appendLine("\nCall `activate_skill(name)` or the relevant domain tool directly when needed.")
    }

    /**
     * Activates full procedure playbook for user-defined procedural skills on demand.
     */
    suspend fun getPlaybookContent(skillName: String): String? {
        val skill = userSkillStore?.get(skillName) ?: return null
        return buildString {
            appendLine("# Procedure Playbook: ${skill.name}")
            appendLine("Description: ${skill.description}")
            appendLine("Body:\n${skill.body}")
        }
    }

    /**
     * Generates the `activate_skill` tool definition so the model can hydrate playbooks dynamically.
     */
    fun getActivationToolDefinition(): ToolDefinition = ToolDefinition(
        function = FunctionDefinition(
            name = "activate_skill",
            description = "Hydrate and load the full multi-step procedural playbook for a specific skill on demand.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("skill_name", buildJsonObject {
                        put("type", "string")
                        put("description", "Unique identifier of the skill playbook to load.")
                    })
                })
                put("required", buildJsonArray {
                    add("skill_name")
                })
                put("additionalProperties", false)
            }
        )
    )
}
