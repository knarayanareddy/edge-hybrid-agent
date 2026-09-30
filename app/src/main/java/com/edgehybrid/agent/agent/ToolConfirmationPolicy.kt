package com.edgehybrid.agent.agent

import com.edgehybrid.agent.nativeactions.ActionRiskTier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a proposed tool call may run unattended, needs a dialog, or needs a
 * strict dialog that renders the full payload.
 *
 * This table is the security boundary and is defined **host-side only**. It is keyed on
 * exact tool-name equality, never on substring matching against model-supplied text, so
 * a model cannot name its way around a tier by embedding a sensitive word in a
 * parameter. Any tool that is not explicitly listed as safe falls through to
 * [ActionRiskTier.CONFIRM], so adding a new tool to the catalog is fail-closed.
 */
@Singleton
class ToolConfirmationPolicy @Inject constructor() {

    fun tierFor(toolName: String): ActionRiskTier = when {
        toolName in SAFE_READ_ONLY_TOOLS -> ActionRiskTier.AUTO_APPROVE
        toolName in STRICT_TOOLS -> ActionRiskTier.CONFIRM_STRICT
        // Everything else, including unknown and future tools, requires a dialog.
        else -> ActionRiskTier.CONFIRM
    }

    /**
     * True when the call may execute without any user interaction.
     *
     * Note the tool name for MCP and sandbox skills is prefixed (`mcp:`, `skill:`). Those
     * are never auto-approved: an MCP tool can have arbitrary side effects on a remote
     * server, and a skill script is untrusted code.
     */
    fun isAutoApproved(toolName: String): Boolean = tierFor(toolName) == ActionRiskTier.AUTO_APPROVE

    private companion object {
        val SAFE_READ_ONLY_TOOLS = setOf(
            "get_current_weather",
            "convert_temperature",
            "search_wikipedia",
            "convert_currency",
            "calculate_math",
            "get_world_time",
            "get_device_status",
            "list_quick_notes",
            "query_calendar_events",
            "search_contacts",
            "live_web_search",
            "extract_webpage_content",
            "plan_transit_journey",
            "list_custom_tools",
            "list_mcp_servers",
            // System queries: read device state only, no side effect, no permission needed.
            "get_battery_status",
            "get_storage_status",
            "get_memory_status",
            "get_display_settings",
            "get_media_volume",
            "get_network_status",
            "get_camera_hardware",
            "list_installed_apps",
            "get_device_details",
            "get_samsung_capabilities"
        )

        val STRICT_TOOLS = setOf(
            "send_sms",
            "send_telegram_message",
            "draft_email",
            "initiate_phone_call",
            // Untrusted code: a skill script is authored content that runs in the
            // WebView sandbox and can attempt network access.
            "skill:calculator.js",
            "skill:device_info.js",
            "skill:web_extract.js"
        )
    }
}
