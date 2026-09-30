package com.edgehybrid.agent.ui.tools

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgehybrid.agent.ui.components.ActionConfirmationDialog
import androidx.compose.foundation.layout.Row
import com.edgehybrid.agent.ui.mcp.McpServersRoute

/**
 * Manual tool console.
 *
 * Running a tool here does **not** execute it immediately. Side-effecting tools queue an
 * [com.edgehybrid.agent.nativeactions.ActionConfirmation] and this screen renders the
 * blocking [ActionConfirmationDialog]; the action runs only if the user confirms.
 */
@Composable
fun ToolsRoute(
    viewModel: ToolsViewModel = hiltViewModel()
) {
    var section by rememberSaveable { mutableStateOf(ToolsSection.DEVICE) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ToolsSection.entries.forEach { candidate ->
            val selected = candidate == section
            if (selected) {
                Button(onClick = { section = candidate }) {
                    Text(
                        when (candidate) {
                            ToolsSection.DEVICE -> "Device Tools"
                            ToolsSection.MCP -> "MCP Servers"
                        }
                    )
                }
            } else {
                OutlinedButton(onClick = { section = candidate }) {
                    Text(
                        when (candidate) {
                            ToolsSection.DEVICE -> "Device Tools"
                            ToolsSection.MCP -> "MCP Servers"
                        }
                    )
                }
            }
        }
    }

    when (section) {
        ToolsSection.DEVICE -> DeviceToolsContent(viewModel)
        ToolsSection.MCP -> McpServersRoute()
    }
}

private enum class ToolsSection { DEVICE, MCP }

@Composable
private fun DeviceToolsContent(viewModel: ToolsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A tool that needs a protected permission is refused until the user grants it, so
    // the request is surfaced here rather than being discovered as a silent failure.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val stillMissing = granted.filterValues { !it }.keys
        if (stillMissing.isNotEmpty()) {
            viewModel.onPermissionDenied(
                "Permission denied: ${stillMissing.joinToString()}. The tool was not run."
            )
        } else {
            viewModel.retryLastTool()
        }
    }

    ToolsScreen(
        state = state,
        onSelectTool = viewModel::executeTool,
        onConfirm = viewModel::confirmAction,
        onDismiss = viewModel::cancelConfirmation,
        onRequestPermission = { permission ->
            if (ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.retryLastTool()
            } else {
                permissionLauncher.launch(arrayOf(permission))
            }
        }
    )
}

@Composable
fun ToolsScreen(
    state: ToolsUiState,
    onSelectTool: (String, String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRequestPermission: (String) -> Unit = {}
) {
    var selectedTool by rememberSaveable { mutableStateOf("") }
    var arguments by rememberSaveable { mutableStateOf("{}") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Device Tools",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Side-effecting tools always ask before they run.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.availableTools) { tool ->
                val isSelected = tool == selectedTool
                Surface(
                    onClick = { selectedTool = tool },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = tool,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }

        HorizontalDivider()

        OutlinedTextField(
            value = arguments,
            onValueChange = { arguments = it },
            label = { Text("Arguments (JSON)") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onSelectTool(selectedTool, arguments) },
            enabled = selectedTool.isNotBlank() && !state.isExecuting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isExecuting) "Running…" else "Run tool")
        }

        OutlinedButton(
            onClick = onDismiss,
            enabled = state.pendingConfirmation != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel pending action")
        }

        state.lastExecutionResult?.let { result ->
            Text(
                text = "Result",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        state.requiredPermission?.let { permission ->
            HorizontalDivider()
            Text(
                text = "Permission needed",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = state.pendingError
                    ?: "This tool needs the ${permission.substringAfterLast('.')} permission to work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { onRequestPermission(permission) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant permission")
            }
        }
    }

    // Blocking gate. Stays mounted while the action is pending so the user must answer
    // before the tool can run.
    state.pendingConfirmation?.let { confirmation ->
        ActionConfirmationDialog(
            confirmation = confirmation,
            onConfirm = onConfirm,
            onDismiss = onDismiss
        )
    }
}
