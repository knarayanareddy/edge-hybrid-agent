package com.edgehybrid.agent.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgehybrid.agent.mcp.McpAuthCatalog
import com.edgehybrid.agent.mcp.McpAuthMode

/**
 * MCP server management.
 *
 * Reached from the Tools tab. Every row can be enabled, tested against the live endpoint,
 * and connected via OAuth or a personal access token. Nothing here dials a server unless
 * the user pressed Test, and nothing enables a server without an explicit toggle.
 */
@Composable
fun McpServersRoute(
    viewModel: McpServersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    McpServersScreen(
        state = state,
        onToggle = viewModel::setEnabled,
        onTest = viewModel::testConnection,
        onSaveToken = viewModel::saveToken,
        onClearCredentials = viewModel::clearToken,
        onConnectOAuth = viewModel::connectWithOAuth,
        onUseReadOnly = viewModel::useReadOnlyEndpoint,
        onAddCustom = viewModel::addCustomServer,
        onRemoveCustom = viewModel::removeCustomServer,
        onDismissMessage = viewModel::dismissMessage,
        onDismissTokenDialog = viewModel::dismissTokenDialog,
        onTokenDialogChange = viewModel::updateTokenDraft
    )
}

@Composable
private fun McpServersScreen(
    state: McpUiState,
    onToggle: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
    onSaveToken: (String, String) -> Unit,
    onClearCredentials: (String) -> Unit,
    onConnectOAuth: (String) -> Unit,
    onUseReadOnly: (String) -> Unit,
    onAddCustom: (String, String, String) -> Unit,
    onRemoveCustom: (String) -> Unit,
    onDismissMessage: () -> Unit,
    onDismissTokenDialog: () -> Unit,
    onTokenDialogChange: (String) -> Unit
) {
    var tokenDialogFor by remember { mutableStateOf<String?>(null) }
    var tokenDraft by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("MCP Servers", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${state.enabledCount} of ${state.servers.size} enabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a custom MCP server")
            }
        }

        state.message?.let { message ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.servers, key = { it.entry.id }) { server ->
                McpServerCard(
                    server = server,
                    onToggle = { enabled -> onToggle(server.entry.id, enabled) },
                    onTest = { onTest(server.entry.id) },
                    onEnterToken = {
                        tokenDraft = ""
                        tokenDialogFor = server.entry.id
                    },
                    onConnectOAuth = { onConnectOAuth(server.entry.id) },
                    onUseReadOnly = { onUseReadOnly(server.entry.id) },
                    onClearCredentials = { onClearCredentials(server.entry.id) },
                    onRemoveCustom = { onRemoveCustom(server.entry.id) }
                )
            }
        }
    }

    // Personal access token dialog. The value is typed here and stored encrypted by the
    // ViewModel; it is never sent to the model or written into logs.
    tokenDialogFor?.let { serverId ->
        AlertDialog(
            onDismissRequest = {
                tokenDialogFor = null
                onDismissTokenDialog()
            },
            title = { Text("Access token") },
            text = {
                Column {
                    Text(
                        "Paste a personal access token for this server. It is stored in the " +
                            "Android keystore and never shown to the model.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = {
                            tokenDraft = it
                            onTokenDialogChange(it)
                        },
                        singleLine = true,
                        label = { Text("Token") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveToken(serverId, tokenDraft)
                    tokenDialogFor = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    tokenDialogFor = null
                    onDismissTokenDialog()
                }) { Text("Cancel") }
            }
        )
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { id, name, url ->
                onAddCustom(id, name, url)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun McpServerCard(
    server: McpServerUi,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEnterToken: () -> Unit,
    onConnectOAuth: () -> Unit,
    onUseReadOnly: () -> Unit,
    onClearCredentials: () -> Unit,
    onRemoveCustom: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        server.entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        server.entry.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = server.entry.enabled, onCheckedChange = onToggle)
                if (!server.entry.isCurated) {
                    IconButton(onClick = onRemoveCustom) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove server")
                    }
                }
            }

            if (server.entry.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    server.entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onTest, enabled = server.probeState !is ProbeState.Running) {
                    Text("Test")
                }

                when (server.authMode) {
                    // Only servers that genuinely take a bearer token get the token button.
                    McpAuthMode.BEARER_TOKEN -> TextButton(onClick = onEnterToken) {
                        Text(if (server.hasToken) "Replace token" else "Add token")
                    }

                    McpAuthMode.DYNAMIC_REGISTRATION -> Text(
                        "Sign-in not supported yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    McpAuthMode.NONE -> Unit
                }

                // Linear publishes a genuinely narrower endpoint, so offering it here is a
                // real permission reduction.
                McpAuthCatalog.forId(server.entry.id)?.readOnlyUrl
                    ?.takeIf { server.entry.url != it }
                    ?.let { TextButton(onClick = onUseReadOnly) {
                        Text("Use read-only")
                    } }

                if (server.hasToken || server.isOAuthConnected) {
                    TextButton(onClick = onClearCredentials) { Text("Clear") }
                }
            }

            when (val probe = server.probeState) {
                ProbeState.Idle -> Unit
                ProbeState.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(14.dp).height(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Testing…", style = MaterialTheme.typography.bodySmall)
                }

                is ProbeState.Ok -> Text(
                    "Connected to ${probe.serverName} — ${probe.toolCount} tools",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                is ProbeState.NeedsAuth -> Text(
                    probe.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )

                is ProbeState.Failed -> Text(
                    probe.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }

    val idValid = id.isNotBlank() && id.matches(Regex("^[a-z0-9][a-z0-9_-]*$"))
    val urlValid = url.startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP server") },
        text = {
            Column {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it.trim().lowercase() },
                    label = { Text("Id (lowercase)") },
                    isError = id.isNotEmpty() && !idValid,
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("https:// endpoint") },
                    isError = !urlValid,
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Servers start disabled. Enable the row after adding it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(id, name.ifBlank { id }, url) },
                enabled = idValid && urlValid
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}