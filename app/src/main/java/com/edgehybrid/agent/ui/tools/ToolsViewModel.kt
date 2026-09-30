package com.edgehybrid.agent.ui.tools

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgehybrid.agent.nativeactions.ActionConfirmation
import com.edgehybrid.agent.tool.ToolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ToolsUiState(
    val availableTools: List<String> = emptyList(),
    val pendingConfirmation: ActionConfirmation? = null,
    val lastExecutionResult: String? = null,
    val isExecuting: Boolean = false,
    val error: String? = null,
    /** Non-null when the selected tool cannot run until this permission is granted. */
    val requiredPermission: String? = null,
    /** Message shown alongside [requiredPermission]. */
    val pendingError: String? = null
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val toolRegistry: ToolRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    /** Last tool invocation, replayed once its permission has been granted. */
    private var lastInvocation: Pair<String, String>? = null

    init {
        refreshTools()
    }

    fun refreshTools() {
        val tools = toolRegistry.listAllTools()
        _uiState.value = _uiState.value.copy(availableTools = tools)
    }

    /**
     * Maps a tool to the runtime permission it needs, or null if it needs none.
     *
     * Declaring a permission in the manifest is not enough: a tool that touches a
     * protected provider or hardware is refused until the grant actually exists, and the
     * UI offers to request it.
     */
    fun permissionFor(toolName: String): String? = when (toolName) {
        "toggle_flashlight" -> Manifest.permission.CAMERA
        else -> null
    }

    /**
     * Runs a tool. If the tool requires approval the action is queued and
     * [pendingConfirmation] is populated; the action has not run yet.
     */
    fun executeTool(name: String, argumentsJson: String) {
        lastInvocation = name to argumentsJson
        run(name, argumentsJson)
    }

    /** Replays the last invocation, used after a permission grant. */
    fun retryLastTool() {
        val (name, args) = lastInvocation ?: return
        _uiState.value = _uiState.value.copy(requiredPermission = null, pendingError = null)
        run(name, args)
    }

    private fun run(name: String, argumentsJson: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isExecuting = true,
                error = null,
                requiredPermission = null,
                pendingError = null
            )
            when (val outcome = toolRegistry.executeToolSuspend(name, argumentsJson)) {
                is ToolRegistry.Outcome.Completed -> {
                    val parsed = ToolResultParser.parse(outcome.result)
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        lastExecutionResult = outcome.result,
                        requiredPermission = parsed.permission,
                        pendingError = parsed.message
                    )
                }

                is ToolRegistry.Outcome.AwaitingConfirmation ->
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        pendingConfirmation = outcome.confirmation
                    )
            }
        }
    }

    /** Records a permission denial so the user sees why the tool did not run. */
    fun onPermissionDenied(message: String) {
        _uiState.value = _uiState.value.copy(
            isExecuting = false,
            error = message,
            requiredPermission = null
        )
    }

    /** Executes the queued action. Runs only after the user has explicitly confirmed. */
    fun confirmAction() {
        val confirmation = _uiState.value.pendingConfirmation ?: return
        _uiState.value = _uiState.value.copy(
            isExecuting = true,
            pendingConfirmation = null
        )
        viewModelScope.launch {
            toolRegistry.executeConfirmed(confirmation.id)
                .onSuccess { result ->
                    val parsed = ToolResultParser.parse(result)
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        lastExecutionResult = result,
                        requiredPermission = parsed.permission,
                        pendingError = parsed.message
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        error = err.message ?: "The action could not be completed."
                    )
                }
        }
    }

    /** Dismisses the dialog without running anything. */
    fun cancelConfirmation() {
        _uiState.value = _uiState.value.copy(pendingConfirmation = null)
    }
}

/**
 * Minimal reader for the JSON a tool returns, so a "permission required" result can drive
 * the permission prompt instead of being rendered as a plain success.
 */
private object ToolResultParser {
    data class Parsed(val permission: String?, val message: String?)

    fun parse(resultJson: String): Parsed = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(resultJson)
            as? kotlinx.serialization.json.JsonObject
            ?: return Parsed(null, null)

        val permission = obj["permission_required"]?.let { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe()
        }?.takeIf { it.isNotBlank() }

        val message = obj["error"]?.let { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe()
        }?.takeIf { it.isNotBlank() }

        Parsed(permission, message)
    }.getOrDefault(Parsed(null, null))

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()
}
