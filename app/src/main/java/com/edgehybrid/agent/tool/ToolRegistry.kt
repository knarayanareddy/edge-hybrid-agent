package com.edgehybrid.agent.tool

import com.edgehybrid.agent.mcp.McpGateway
import com.edgehybrid.agent.nativeactions.ActionConfirmation
import com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry
import com.edgehybrid.agent.nativeactions.ActionRiskTier
import com.edgehybrid.agent.nativeactions.ConfirmationDetail
import com.edgehybrid.agent.nativeactions.NativeActionHandler
import com.edgehybrid.agent.nativeactions.NativeTool
import com.edgehybrid.agent.sandbox.ScriptSandbox
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unified tool registry for the manual Tools screen.
 *
 * Side-effecting tools return a **pending confirmation** instead of executing. The UI
 * shows [ActionConfirmationDialog]; the user answers, and only then does
 * [executeConfirmed] run the stored action. Arguments stay in the
 * [ActionConfirmationRegistry], so nothing on this path can be executed by forging a
 * record.
 */
@Singleton
class ToolRegistry @Inject constructor(
    private val nativeActionHandler: NativeActionHandler,
    private val scriptSandbox: ScriptSandbox,
    private val mcpGateway: McpGateway,
    private val confirmationRegistry: ActionConfirmationRegistry
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Bundled skill scripts. This list is the allowlist: nothing else may execute. */
    private val registeredJsSkills = setOf("calculator.js", "device_info.js", "web_extract.js")

    fun listAllTools(): List<String> {
        val nativeTools = NativeTool.entries.map { it.toolName }
        val jsSkills = registeredJsSkills.map { "skill:$it" }
        return nativeTools + jsSkills
    }

    /**
     * Outcome of a manual tool invocation.
     *
     * Exactly one of [result] and [pendingConfirmation] is set: when a confirmation is
     * required, [result] is null and the action has *not* run.
     */
    sealed interface Outcome {
        data class Completed(val result: String) : Outcome
        data class AwaitingConfirmation(val confirmation: ActionConfirmation) : Outcome
    }

    fun executeTool(name: String, argumentsJson: String): Outcome = runBlocking {
        executeToolSuspend(name, argumentsJson)
    }

    suspend fun executeToolSuspend(name: String, argumentsJson: String): Outcome {
        val argsObj = try {
            if (argumentsJson.isBlank()) JsonObject(emptyMap())
            else json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }

        return try {
            when {
                // ---- Read-only: executes immediately ----
                name == NativeTool.CREATE_QUICK_NOTE.toolName -> {
                    val title = argsObj["title"]?.jsonPrimitive?.contentOrNull ?: "Note"
                    val content = argsObj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    Outcome.Completed(runNoteCreation(title, content))
                }

                // ---- Side-effecting: require confirmation first ----
                name == NativeTool.SET_TIMER.toolName -> {
                    val seconds = argsObj["seconds"]?.jsonPrimitive?.intOrNull ?: 60
                    val message = argsObj["message"]?.jsonPrimitive?.contentOrNull ?: "Agent Timer"
                    Outcome.AwaitingConfirmation(
                        confirmationRegistry.register(
                            tool = name,
                            title = "Set timer",
                            summary = "This will start a countdown timer in the clock app.",
                            tier = ActionRiskTier.CONFIRM,
                            details = listOf(
                                ConfirmationDetail("Duration", "$seconds seconds"),
                                ConfirmationDetail("Label", message)
                            ),
                            run = {
                                val ok = nativeActionHandler.setTimer(seconds, message)
                                """{"timer_set": $ok, "seconds": $seconds}"""
                            }
                        )
                    )
                }

                name == NativeTool.CREATE_CALENDAR_EVENT.toolName -> {
                    val title = argsObj["title"]?.jsonPrimitive?.contentOrNull ?: "Meeting"
                    Outcome.AwaitingConfirmation(
                        confirmationRegistry.register(
                            tool = name,
                            title = "Create calendar event",
                            summary = "This will add an event to your calendar.",
                            tier = ActionRiskTier.CONFIRM,
                            details = listOf(ConfirmationDetail("Title", title)),
                            run = {
                                val ok = nativeActionHandler.createCalendarEvent(title)
                                """{"calendar_event_created": $ok, "title": "$title"}"""
                            }
                        )
                    )
                }

                name == NativeTool.SEND_SMS.toolName -> {
                    val phone = argsObj["phone"]?.jsonPrimitive?.contentOrNull ?: ""
                    val message = argsObj["message"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (phone.isBlank() || message.isBlank()) {
                        Outcome.Completed(
                            """{"error": "Both 'phone' and 'message' are required."}"""
                        )
                    } else {
                        Outcome.AwaitingConfirmation(
                            confirmationRegistry.register(
                                tool = name,
                                title = "Send SMS",
                                summary = "This will open your messaging app with this message " +
                                    "ready to send.",
                                tier = ActionRiskTier.CONFIRM_STRICT,
                                details = listOf(
                                    ConfirmationDetail("To (phone)", phone),
                                    ConfirmationDetail("Message", message)
                                ),
                                warning = "Your messaging app will open and you press send. " +
                                    "Check the number and text before continuing.",
                                run = {
                                    val ok = nativeActionHandler.sendSms(phone, message)
                                    """{"sms_composer_opened": $ok}"""
                                }
                            )
                        )
                    }
                }

                name == NativeTool.TOGGLE_FLASHLIGHT.toolName -> {
                    if (!nativeActionHandler.hasPermission(android.Manifest.permission.CAMERA)) {
                        Outcome.Completed(
                            """{"permission_required":"android.permission.CAMERA",""" +
                                """"error":"Camera permission is required to control the flashlight."}"""
                        )
                    } else {
                        Outcome.AwaitingConfirmation(
                            confirmationRegistry.register(
                                tool = name,
                                title = "Toggle flashlight",
                                summary = "This will turn the flashlight on or off.",
                                tier = ActionRiskTier.CONFIRM,
                                details = emptyList(),
                                run = {
                                    val ok = nativeActionHandler.toggleFlashlight()
                                    """{"flashlight_enabled": $ok}"""
                                }
                            )
                        )
                    }
                }

                // ---- Sandboxed JS skills ----
                name.startsWith("skill:") -> {
                    val scriptName = name.removePrefix("skill:")
                    if (scriptName !in registeredJsSkills) {
                        Outcome.Completed(
                            """{"error": "Unknown skill: ${escapeForJson(scriptName)}"}"""
                        )
                    } else {
                        val networkOrigins =
                            (argsObj["origins"] as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { it?.toString()?.trim('"') }
                                .orEmpty()
                        val result = scriptSandbox.executeScript(
                            scriptName = scriptName,
                            inputJson = argumentsJson,
                            networkOrigins = networkOrigins
                        )
                        Outcome.Completed(
                            result.fold(
                                onSuccess = { it },
                                onFailure = {
                                    """{"error": ${escapeForJson(it.message ?: "sandbox failed")}}"""
                                }
                            )
                        )
                    }
                }

                // ---- Remote MCP tools ----
                name.startsWith("mcp:") -> {
                    val parts = name.split(":")
                    if (parts.size < 3) {
                        Outcome.Completed("""{"error": "Invalid MCP tool identifier."}""")
                    } else {
                        Outcome.Completed(
                            mcpGateway.callTool(parts[1], parts[2], argsObj)
                                .getOrElse { """{"error": ${escapeForJson(it.message ?: "MCP call failed")}}""" }
                        )
                    }
                }

                else -> Outcome.Completed(
                    """{"error": "Unknown tool or skill: ${escapeForJson(name)}"}"""
                )
            }
        } catch (e: Exception) {
            Outcome.Completed("""{"error": ${escapeForJson(e.message ?: "execution failed")}}""")
        }
    }

    /**
     * Runs a previously-registered action after the user approved it.
     *
     * The registry entry is consumed atomically, so a repeated or replayed id is a no-op.
     */
    suspend fun executeConfirmed(confirmationId: String): Result<String> {
        val run = confirmationRegistry.consume(confirmationId)
            ?: return Result.failure(IllegalStateException("This action is no longer valid."))
        return runCatching { run() }
    }

    private suspend fun runNoteCreation(title: String, content: String): String {
        val id = nativeActionHandler.createQuickNote(title, content)
        return """{"note_id": $id, "status": "created"}"""
    }

    private fun escapeForJson(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
