package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.nativeactions.ActionConfirmation
import com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry
import com.edgehybrid.agent.nativeactions.ActionRiskTier
import com.edgehybrid.agent.nativeactions.ConfirmationDetail
import com.edgehybrid.agent.tool.SkillLoader
import com.edgehybrid.agent.tool.ToolGateway
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Single choke point through which every model-authored tool call must pass.
 *
 * The gate runs **before** [SkillLoader.execute] and before any Android intent is
 * dispatched. It is fail-closed in three independent ways:
 *
 *  1. [ToolConfirmationPolicy] returns [ActionRiskTier.CONFIRM] for any tool that is not
 *     an explicitly allowlisted read-only tool, so a newly added tool is gated by default.
 *  2. The tool's arguments are only serialized into a display record; the executable
 *     closure is held by [ActionConfirmationRegistry] and is unreachable without a
 *     user-approved confirmation id.
 *  3. If the user declines, the prompt times out, or the caller fails to answer, the
 *     action never runs. There is no code path where a declined or unanswered
 *     confirmation falls through to execution.
 */
@Singleton
class ConfirmationGate @Inject constructor(
    private val policy: ToolConfirmationPolicy,
    private val registry: ActionConfirmationRegistry,
    private val skillLoader: SkillLoader,
    private val toolGateway: ToolGateway,
    private val jevSafetyGate: com.edgehybrid.agent.jev.JevSafetyGate
) {

    /**
     * Outcome of gating a single tool call.
     *
     * [execute] is non-null only when the call was auto-approved or the user approved it.
     */
    sealed interface GateResult {
        /** The call may proceed. [execute] performs the tool. */
        data class Approved(
            val confirmation: ActionConfirmation?,
            val execute: suspend () -> String
        ) : GateResult

        /** The call must not run. [reason] is fed back to the model. */
        data class Rejected(val reason: String) : GateResult

        /**
         * The call requires user approval. The caller must show [confirmation] and resume
         * the agent loop with [approved] set accordingly.
         */
        data class NeedsApproval(val confirmation: ActionConfirmation) : GateResult
    }

    /**
     * Gates a tool call.
     *
     * @param isRemote true for a tool discovered on an MCP server. Remote tools are
     *   never auto-approved and always go through the catalog-aware gateway.
     */
    fun gate(call: ModelToolCall, isRemote: Boolean = false): GateResult {
        val toolName = call.function.name
        val tier = if (isRemote) ActionRiskTier.CONFIRM else policy.tierFor(toolName)

        val execute: suspend () -> String = if (isRemote) {
            { toolGateway.execute(call, toolGateway.loadCatalog()).content }
        } else {
            { skillLoader.execute(call).content }
        }

        if (tier == ActionRiskTier.AUTO_APPROVE) {
            return GateResult.Approved(
                confirmation = null,
                execute = execute
            )
        }

        val confirmation = registry.register(
            tool = toolName,
            title = titleFor(toolName, isRemote),
            summary = summaryFor(toolName, isRemote),
            tier = tier,
            details = detailsFor(toolName, call.function.arguments),
            warning = warningFor(tier, isRemote),
            run = execute
        )
        return GateResult.NeedsApproval(confirmation)
    }

    /**
     * Runs a call the user just approved. Consumes the pending entry, so a replayed
     * confirmation id is a no-op.
     */
    suspend fun runApproved(
        call: ModelToolCall,
        confirmationId: String
    ): GateResult {
        val run = registry.consume(confirmationId)
            ?: return GateResult.Rejected("This action is no longer valid. Please ask again.")

        // Jev supplies a calibrated second opinion on arguments the local policy has never
        // seen. It is advisory and can only escalate, never downgrade: a high score adds a
        // warning to the result the model sees, and an Unknown signal leaves the user's
        // approval untouched. The gate remains the sole authority on consent.
        val signal = runCatching {
            jevSafetyGate.assess(call.function.name, call.function.arguments.toString())
        }.getOrElse { com.edgehybrid.agent.jev.SafetySignal.Unknown("assessment failed") }

        val execute: suspend () -> String = {
            val output = run()
            if (signal.shouldEscalate()) {
                // Wrapped, not replaced: the tool still ran, but the model is told the
                // action was independently assessed as destructive.
                buildJsonObject {
                    put("output", output)
                    put(
                        "jev_warning",
                        "Independent risk assessment flagged this action as destructive " +
                            "(confidence ${"%.2f".format((signal as com.edgehybrid.agent.jev.SafetySignal.Scored).probability)})."
                    )
                }.toString()
            } else {
                output
            }
        }

        return GateResult.Approved(confirmation = null, execute = execute)
    }

    private fun titleFor(toolName: String, isRemote: Boolean = false): String = when {
        isRemote -> "Run external tool: $toolName"
        else -> when (toolName) {
        "send_sms" -> "Send SMS"
        "send_telegram_message" -> "Send Telegram message"
        "draft_email" -> "Send email"
        "initiate_phone_call" -> "Place phone call"
        "set_alarm" -> "Set alarm"
        "set_timer" -> "Set timer"
        "create_quick_note" -> "Save note"
        "create_calendar_event" -> "Create calendar event"
        "open_camera" -> "Open camera"
        "toggle_flashlight" -> "Toggle flashlight"
        "control_spotify" -> "Control Spotify"
        "register_mcp_server" -> "Register external MCP server"
        "stage_and_test_tool" -> "Install and test a new tool"
        "save_staged_tool" -> "Install a new tool"
        "open_display_settings" -> "Open display settings"
        "open_battery_settings" -> "Open battery settings"
        "open_modes_and_routines" -> "Open Modes and Routines"
        "open_permission_manager" -> "Open app permissions"
        "open_notification_settings" -> "Open notification settings"
        "open_spen_settings" -> "Open S Pen settings"
        "open_security_settings" -> "Open security settings"
        "open_wifi_settings" -> "Open Wi-Fi settings"
        "open_storage_settings" -> "Open storage settings"
        "open_app_settings" -> "Open app settings"
        "set_media_volume" -> "Change media volume"
        "launch_app" -> "Open an app"
        "open_gallery" -> "Open gallery"
        "share_text" -> "Share text"
        else -> "Run ${toolName.replace('_', ' ')}"
        }
    }

    private fun summaryFor(toolName: String, isRemote: Boolean = false): String = when {
        isRemote ->
            "This runs a tool on an external server. The app cannot verify what it does."
        else -> when (toolName) {
            "send_sms" -> "This will open your messaging app with the message ready to send."
            "send_telegram_message" -> "This will send a message over Telegram."
            "draft_email" -> "This will open an email to a recipient."
            "initiate_phone_call" -> "This will open the phone dialer."
            "set_alarm" -> "This will open the clock app to schedule an alarm."
            "set_timer" -> "This will open the clock app to start a timer."
            "create_quick_note" -> "This will save a note on this device."
            "create_calendar_event" -> "This will add an event to your calendar."
            "open_camera" -> "This will open the camera."
            "toggle_flashlight" -> "This will turn the flashlight on or off."
            "control_spotify" -> "This will open Spotify."
            "register_mcp_server" -> "This will register an external server and store its credentials."
            "stage_and_test_tool" -> "This will install a new tool into the agent permanently."
            "save_staged_tool" -> "This will install a new tool into the agent permanently."
            "open_display_settings" -> "This will open the display settings screen."
            "open_battery_settings" -> "This will open the battery settings screen."
            "open_modes_and_routines" -> "This will open Modes and Routines."
            "open_permission_manager" -> "This will open this app's permission settings."
            "open_notification_settings" -> "This will open notification settings."
            "open_spen_settings" -> "This will open S Pen settings."
            "open_security_settings" -> "This will open security settings."
            "open_wifi_settings" -> "This will open Wi-Fi settings."
            "open_storage_settings" -> "This will open storage settings."
            "open_app_settings" -> "This will open the settings page for an app."
            "set_media_volume" -> "This will change the media playback volume."
            "launch_app" -> "This will open an app on your phone."
            "open_gallery" -> "This will open your photo gallery."
            "share_text" -> "This will open the share sheet with the given text."
            else -> "The agent wants to run this action."
        }
    }

    private fun warningFor(tier: ActionRiskTier, isRemote: Boolean = false): String? = when {
        isRemote ->
            "This tool lives on a third-party server. Its side effects cannot be undone " +
                "or verified by this app."
        tier == ActionRiskTier.CONFIRM_STRICT ->
            "This action contacts another person or service. Check the recipient and the " +
                "exact content before continuing."
        else -> null
    }

    /**
     * Builds the dialog's parameter list.
     *
     * For strict tools the full recipient and message body are rendered, so the user sees
     * precisely what will leave the device. Values are truncated to keep the dialog
     * readable; the registry holds the untruncated value used for execution.
     */
    private fun detailsFor(toolName: String, arguments: JsonObject): List<ConfirmationDetail> {
        val args = arguments

        fun str(key: String): String? =
            (args[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.contentOrNullSafe()

        val details = mutableListOf<ConfirmationDetail>()

        when (toolName) {
            "send_sms" -> {
                str("phone")?.let { details += ConfirmationDetail("To (phone)", it) }
                str("message")?.let { details += ConfirmationDetail("Message", it.preview(400)) }
            }

            "send_telegram_message" -> {
                // The bot token and configured chat are pinned by the host; the model
                // cannot choose the destination, so only the content is shown here.
                str("message")?.let { details += ConfirmationDetail("Message", it.preview(400)) }
                details += ConfirmationDetail(
                    "Destination",
                    "Your configured Telegram chat (fixed by the app)"
                )
            }

            "draft_email" -> {
                str("recipient")?.let { details += ConfirmationDetail("To (email)", it) }
                str("subject")?.let { details += ConfirmationDetail("Subject", it.preview(200)) }
                str("body")?.let { details += ConfirmationDetail("Body", it.preview(400)) }
            }

            "initiate_phone_call" -> {
                str("phone_number")?.let { details += ConfirmationDetail("Phone number", it) }
                str("phone")?.let { details += ConfirmationDetail("Phone number", it) }
            }

            "set_alarm" -> {
                str("hour")?.let { h -> str("minutes")?.let { m ->
                    details += ConfirmationDetail("Time", "%02d:%02d".format(h.toIntOrNull() ?: 0, m.toIntOrNull() ?: 0))
                } }
                str("message")?.let { details += ConfirmationDetail("Label", it.preview(120)) }
            }

            "set_timer" -> {
                str("seconds")?.let { details += ConfirmationDetail("Duration", "$it seconds") }
                str("message")?.let { details += ConfirmationDetail("Label", it.preview(120)) }
            }

            "create_quick_note" -> {
                str("title")?.let { details += ConfirmationDetail("Title", it.preview(160)) }
                str("content")?.let { details += ConfirmationDetail("Content", it.preview(400)) }
            }

            "create_calendar_event" -> {
                str("title")?.let { details += ConfirmationDetail("Title", it.preview(160)) }
                str("location")?.let { details += ConfirmationDetail("Location", it.preview(160)) }
                str("description")?.let { details += ConfirmationDetail("Description", it.preview(240)) }
                str("duration_minutes")?.let { details += ConfirmationDetail("Duration", "$it minutes") }
            }

            "control_spotify" -> {
                str("query")?.let { details += ConfirmationDetail("Search", it.preview(160)) }
            }

            "register_mcp_server" -> {
                str("name")?.let { details += ConfirmationDetail("Name", it.preview(120)) }
                str("url")?.let { details += ConfirmationDetail("Server URL", it.preview(300)) }
                // The bearer token is intentionally never rendered or returned to the model.
                details += ConfirmationDetail("Credentials", "Stored in the encrypted keystore")
            }

            "save_staged_tool", "stage_and_test_tool" -> {
                str("name")?.let { details += ConfirmationDetail("Tool name", it.preview(120)) }
                str("action_template")?.let {
                    details += ConfirmationDetail("Behaviour", it.preview(240))
                }
            }

            "launch_app", "open_app_settings" -> {
                str("package")?.let { details += ConfirmationDetail("App package", it.preview(160)) }
            }

            "set_media_volume" -> {
                str("level")?.let { details += ConfirmationDetail("Volume level", "$it%") }
            }

            "share_text" -> {
                str("text")?.let { details += ConfirmationDetail("Text", it.preview(400)) }
            }

            "list_installed_apps" -> {
                str("query")?.let { details += ConfirmationDetail("Filter", it.preview(120)) }
            }

            else -> {
                args.forEach { (key, value) ->
                    if (key == "bearer_token" || key == "api_key" || key == "token") return@forEach
                    val text = when (value) {
                        is JsonPrimitive -> value.content
                        is JsonArray -> "[${value.size} items]"
                        is JsonObject -> "[object]"
                        else -> value.toString()
                    }
                    details += ConfirmationDetail(key, text.preview(200))
                }
            }
        }
        return details
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

    private fun String.preview(max: Int): String =
        if (length <= max) this else take(max) + "…"
}

/** Convenience: JSON error payload returned to the model when a call is refused. */
internal fun declinedContent(reason: String): String =
    buildJsonObject {
        put("status", "declined")
        put("error", reason)
        put("instruction", "Do not retry this action. Tell the user what happened instead.")
    }.toString()
