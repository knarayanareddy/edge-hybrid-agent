package com.edgehybrid.agent.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.edgehybrid.agent.plugins.PluginEntry
import com.edgehybrid.agent.plugins.PluginPermissionStore
import com.edgehybrid.agent.plugins.PluginRegistry

@Composable
internal fun OpenApiIntegrationsSection(
    registry: PluginRegistry,
    permissions: PluginPermissionStore,
    query: String
) {
    var refresh by remember { mutableStateOf(0) }
    var showInstall by remember { mutableStateOf(false) }
    var credentialFor by remember { mutableStateOf<PluginEntry?>(null) }
    val entries = remember(registry, refresh) { registry.listPlugins() }
    val filtered = entries.filter { entry ->
        query.isBlank() || listOf(entry.name, entry.description, entry.id, entry.baseUrl)
            .any { it.contains(query, ignoreCase = true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("OpenAPI integrations (${entries.size})", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Add an HTTPS OpenAPI service. Calls ask before running unless you change its permission.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showInstall = true }) { Text("+ Add") }
        }

        if (filtered.isEmpty()) {
            Text(
                if (entries.isEmpty()) "No OpenAPI integrations installed yet." else "No integrations match your search.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        filtered.forEach { entry ->
            IntegrationCard(
                entry = entry,
                hasCredential = registry.hasPluginToken(entry.id),
                trust = permissions.getTrustLevel(entry.id),
                onToggle = { registry.setEnabled(entry.id, it); refresh++ },
                onCredential = { credentialFor = entry },
                onPermission = { permissions.setTrustLevel(entry.id, it); refresh++ },
                onRemove = { registry.removePlugin(entry.id); permissions.revoke(entry.id); refresh++ }
            )
        }
    }

    if (showInstall) {
        InstallOpenApiDialog(
            onDismiss = { showInstall = false },
            onInstall = { id, name, description, spec, baseUrl, token ->
                registry.registerOpenApi(
                    id,
                    name,
                    description,
                    spec,
                    baseUrl.trim().takeIf { it.isNotBlank() },
                    token.takeIf { it.isNotBlank() }
                )
                showInstall = false
                refresh++
            }
        )
    }
    credentialFor?.let { entry ->
        CredentialDialog(
            entry = entry,
            onDismiss = { credentialFor = null },
            onSave = { token -> registry.setPluginToken(entry.id, token); credentialFor = null; refresh++ }
        )
    }
}

@Composable
private fun IntegrationCard(
    entry: PluginEntry,
    hasCredential: Boolean,
    trust: PluginPermissionStore.TrustLevel,
    onToggle: (Boolean) -> Unit,
    onCredential: () -> Unit,
    onPermission: (PluginPermissionStore.TrustLevel) -> Unit,
    onRemove: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(entry.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = entry.isEnabled, onCheckedChange = onToggle)
            }
            if (entry.description.isNotBlank()) Text(entry.description, style = MaterialTheme.typography.bodySmall)
            Text(
                if (!entry.isEnabled) "Disabled · not available to the agent"
                else if (entry.requiresAuth && entry.authType == "unsupported") "Enabled · this OpenAPI auth scheme is unsupported"
                else if (entry.requiresAuth && !hasCredential) "Enabled · credential required"
                else "Enabled · ${if (hasCredential) "credential stored securely" else "no credential required"}", 
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.requiresAuth && !hasCredential) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (entry.authType == "api_key_query" && entry.requiresAuth) {
                Text("This API key is sent in the URL query by the service's OpenAPI definition.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCredential) {
                    Text(if (hasCredential) "Update credential" else when (entry.authType) {
                        "api_key_header", "api_key_query" -> "Add API key"
                        else -> "Add credential"
                    })
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            Text("Permission", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterChip(trust == PluginPermissionStore.TrustLevel.ASK_EVERY_TIME,
                    { onPermission(PluginPermissionStore.TrustLevel.ASK_EVERY_TIME) }, label = { Text("Ask every time") })
                FilterChip(trust == PluginPermissionStore.TrustLevel.ALWAYS_ALLOW,
                    { onPermission(PluginPermissionStore.TrustLevel.ALWAYS_ALLOW) }, label = { Text("Always allow") })
                FilterChip(trust == PluginPermissionStore.TrustLevel.DENY,
                    { onPermission(PluginPermissionStore.TrustLevel.DENY) }, label = { Text("Block") })
            }
        }
    }
}

@Composable
private fun InstallOpenApiDialog(
    onDismiss: () -> Unit,
    onInstall: (String, String, String, String, String, String) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var spec by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add OpenAPI integration") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Install from an OpenAPI JSON document. Only public HTTPS API hosts are accepted.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(id, { id = it }, label = { Text("Integration ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("HTTPS base URL override (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("Bearer token or API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(spec, { spec = it }, label = { Text("OpenAPI JSON") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 5, maxLines = 8, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { onInstall(id.trim(), name.trim(), description.trim(), spec, baseUrl.trim(), token) }
                    .onFailure { error = it.message ?: "Could not install integration" }
            }) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CredentialDialog(entry: PluginEntry, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var token by remember(entry.id) { mutableStateOf("") }
    val label = when (entry.authType) {
        "api_key_header" -> "API key for ${entry.authParameterName} header"
        "api_key_query" -> "API key for ${entry.authParameterName} query parameter"
        else -> "Bearer token"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Credential · ${entry.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The credential is stored in encrypted device storage and is never shown to the model.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(token, { token = it }, label = { Text("$label (blank removes it)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(token) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
