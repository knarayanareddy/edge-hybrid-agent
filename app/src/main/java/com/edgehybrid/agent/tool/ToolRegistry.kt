package com.edgehybrid.agent.tool

import com.edgehybrid.agent.mcp.McpGateway
import com.edgehybrid.agent.nativeactions.NativeActionHandler
import com.edgehybrid.agent.nativeactions.NativeTool
import com.edgehybrid.agent.sandbox.ScriptSandbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified tool registry orchestrating Native system actions, sandboxed JS skills, and remote MCP tools.
 */
@Singleton
class ToolRegistry @Inject constructor(
    private val nativeActionHandler: NativeActionHandler,
    private val scriptSandbox: ScriptSandbox,
    private val mcpGateway: McpGateway
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val registeredJsSkills = listOf("calculator.js", "device_info.js", "web_extract.js")

    fun listAllTools(): List<String> {
        val nativeTools = NativeTool.entries.map { it.toolName }
        val jsSkills = registeredJsSkills.map { "skill:$it" }
        return nativeTools + jsSkills
    }

    suspend fun executeTool(name: String, argumentsJson: String): Result<String> {
        return try {
            val argsObj = try {
                if (argumentsJson.isBlank()) JsonObject(emptyMap())
                else json.parseToJsonElement(argumentsJson).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }

            when {
                // Native System Actions
                name == NativeTool.SET_TIMER.toolName -> {
                    val seconds = argsObj["seconds"]?.jsonPrimitive?.intOrNull ?: 60
                    val message = argsObj["message"]?.jsonPrimitive?.contentOrNull ?: "Agent Timer"
                    val success = nativeActionHandler.setTimer(seconds, message)
                    Result.success("{\"timer_set\": $success, \"seconds\": $seconds, \"message\": \"$message\"}")
                }

                name == NativeTool.CREATE_NOTE.toolName -> {
                    val title = argsObj["title"]?.jsonPrimitive?.contentOrNull ?: "Note"
                    val content = argsObj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    val id = nativeActionHandler.createQuickNote(title, content)
                    Result.success("{\"note_id\": $id, \"title\": \"$title\", \"status\": \"created\"}")
                }

                name == NativeTool.CREATE_CALENDAR_EVENT.toolName -> {
                    val title = argsObj["title"]?.jsonPrimitive?.contentOrNull ?: "Meeting"
                    val success = nativeActionHandler.createCalendarEvent(title)
                    Result.success("{\"calendar_event_created\": $success, \"title\": \"$title\"}")
                }

                name == NativeTool.SEND_SMS.toolName -> {
                    val phone = argsObj["phone"]?.jsonPrimitive?.contentOrNull ?: ""
                    val message = argsObj["message"]?.jsonPrimitive?.contentOrNull ?: ""
                    val confirmation = nativeActionHandler.prepareSms(phone, message)
                    Result.success("{\"confirmation_required\": true, \"confirmation_id\": \"${confirmation.id}\", \"phone\": \"$phone\"}")
                }

                name == NativeTool.TOGGLE_FLASHLIGHT.toolName -> {
                    val state = nativeActionHandler.toggleFlashlight()
                    Result.success("{\"flashlight_state\": $state}")
                }

                // Headless JS Skills
                name.startsWith("skill:") -> {
                    val scriptName = name.removePrefix("skill:")
                    val networkOrigins = (argsObj["origins"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    scriptSandbox.executeScript(scriptName, argumentsJson, networkOrigins)
                }

                // Remote MCP Tools (format: mcp:serverId:toolName)
                name.startsWith("mcp:") -> {
                    val parts = name.split(":")
                    if (parts.size >= 3) {
                        val serverId = parts[1]
                        val toolName = parts[2]
                        mcpGateway.callTool(serverId, toolName, argsObj)
                    } else {
                        Result.failure(IllegalArgumentException("Invalid MCP tool identifier: $name"))
                    }
                }

                else -> Result.failure(IllegalArgumentException("Unknown tool or skill: $name"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
