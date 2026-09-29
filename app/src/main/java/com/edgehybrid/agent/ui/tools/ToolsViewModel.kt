package com.edgehybrid.agent.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgehybrid.agent.nativeactions.ActionConfirmation
import com.edgehybrid.agent.nativeactions.NativeActionHandler
import com.edgehybrid.agent.tool.ToolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ToolsUiState(
    val availableTools: List<String> = emptyList(),
    val pendingConfirmation: ActionConfirmation? = null,
    val lastExecutionResult: String? = null,
    val isExecuting: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val nativeActionHandler: NativeActionHandler
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    init {
        refreshTools()
    }

    fun refreshTools() {
        val tools = toolRegistry.listAllTools()
        _uiState.value = _uiState.value.copy(availableTools = tools)
    }

    fun executeTool(name: String, argumentsJson: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExecuting = true, error = null)
            val result = toolRegistry.executeTool(name, argumentsJson)
            result.fold(
                onSuccess = { res ->
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        lastExecutionResult = res
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isExecuting = false,
                        error = err.message ?: "Execution failed"
                    )
                }
            )
        }
    }

    fun confirmAction(confirmation: ActionConfirmation) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExecuting = true, pendingConfirmation = null)
            val success = nativeActionHandler.sendSms(confirmation)
            _uiState.value = _uiState.value.copy(
                isExecuting = false,
                lastExecutionResult = "{\"action_confirmed\": true, \"success\": $success}"
            )
        }
    }

    fun cancelConfirmation() {
        _uiState.value = _uiState.value.copy(pendingConfirmation = null)
    }
}
