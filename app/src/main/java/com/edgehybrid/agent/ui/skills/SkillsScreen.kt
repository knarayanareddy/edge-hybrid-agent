package com.edgehybrid.agent.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement as RowArrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.mcp.McpServerEntry
import com.edgehybrid.agent.mcp.McpServerRegistry
import com.edgehybrid.agent.tool.SkillLoader

/**
 * Shows what the agent can actually do.
 *
 * Both lists come from the live singletons the agent loop itself uses — the tool
 * definitions from [SkillLoader.listTools] and the server list from [McpServerRegistry].
 * This screen deliberately does not maintain its own copy of either, so it cannot drift
 * out of step with what the agent will really dispatch.
 *
 * MCP servers are toggled in Settings (Tools → MCP Servers); this screen is read-only for
 * them so there is exactly one place a server can be enabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    skillLoader: SkillLoader,
    mcpRegistry: McpServerRegistry,
    userSkillStore: com.edgehybrid.agent.skills.UserSkillStore,
    onNavigateBack: () -> Unit
) {
    // Tool discovery is a suspend call (it may query MCP servers), so it is loaded in an
    // effect rather than captured in `remember`, which would freeze an empty list.
    var tools by remember { mutableStateOf<List<ToolDefinition>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(skillLoader) {
        tools = runCatching { skillLoader.listTools() }
            .getOrElse { failure ->
                loadError = failure.message ?: "Could not load tools"
                emptyList()
            }
    }

    // The user's own skills. Read from the same store the sandbox enforces, so a
    // skill shown here is a skill the agent can actually run — the screen does not
    // keep a parallel list that can drift.
    LaunchedEffect(userSkillStore) { userSkillStore.load() }
    val userSkills by userSkillStore.skills.collectAsStateWithLifecycle()
    var editorFor by remember { mutableStateOf<com.edgehybrid.agent.skills.UserSkill?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var saveErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val catalog = remember(mcpRegistry) { mcpRegistry.catalog() }
    val servers = catalog.servers
    val enabledCount = catalog.enabledCount

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools & MCP Servers") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            loadError?.let { error ->
                item { Text("Could not load tools: $error", color = MaterialTheme.colorScheme.error) }
            }

            item {
                Column {
                    Text(
                        text = "Available tools (${tools.size})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Everything the agent may call. Anything that changes your " +
                            "phone asks for confirmation first.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (tools.isEmpty() && loadError == null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "No tools loaded yet.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                Text(
                    "Skills",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (editorOpen && editorFor != null) {
                item {
                    SkillEditorDialog(
                        initial = editorFor!!,
                        onDismiss = { editorOpen = false },
                        onSave = { candidate ->
                            scope.launch {
                                val errs = userSkillStore.save(candidate)
                                if (errs.isEmpty()) {
                                    editorOpen = false
                                    saveErrors = emptyList()
                                } else {
                                    saveErrors = errs.map { it.message }
                                }
                            }
                        }
                    )
                }
            }

            items(userSkills, key = { it.id }) { skill ->
                UserSkillRow(
                    skill = skill,
                    onToggle = { enabled ->
                        scope.launch { userSkillStore.setEnabled(skill.id, enabled) }
                    },
                    onEdit = { editorFor = skill; editorOpen = true },
                    onDelete = { scope.launch { userSkillStore.delete(skill.id) } }
                )
            }

            if (saveErrors.isNotEmpty()) {
                item {
                    Text(
                        saveErrors.joinToString("\n"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item {
                TextButton(onClick = {
                    editorFor = com.edgehybrid.agent.skills.UserSkill(
                        id = "new_skill",
                        name = "",
                        description = "",
                        kind = com.edgehybrid.agent.skills.UserSkill.Kind.PROCEDURE,
                        body = ""
                    )
                    saveErrors = emptyList()
                    editorOpen = true
                }) { Text("+ New skill") }
            }

            item {
                Text(
                    "Tools",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(tools, key = { it.function.name }) { tool ->
                ToolRow(tool)
            }

            item {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "MCP servers ($enabledCount of ${servers.size} enabled)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Managed in Tools → MCP Servers. Disabled servers contribute " +
                            "no tools.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (servers.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "No MCP servers configured.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            items(servers, key = { it.id }) { server ->
                McpServerRow(server)
            }
        }
    }
}

@Composable
private fun ToolRow(tool: ToolDefinition) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = tool.function.name,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
            val description = tool.function.description
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun McpServerRow(server: McpServerEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = server.name,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (server.enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (server.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Text(
                text = server.url,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (server.description.isNotBlank()) {
                Text(
                    text = server.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UserSkillRow(
    skill: com.edgehybrid.agent.skills.UserSkill,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(skill.name.ifBlank { skill.id }, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${if (skill.kind == com.edgehybrid.agent.skills.UserSkill.Kind.SCRIPT) "Script" else "Procedure"}" +
                    if (skill.origin == com.edgehybrid.agent.skills.UserSkill.Origin.BUNDLED) " · bundled" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit ${skill.name}")
        }
        IconButton(onClick = { confirmDelete = true }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${skill.name}")
        }
        Switch(checked = skill.enabled, onCheckedChange = onToggle)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${skill.name}?") },
            text = {
                Text(
                    if (skill.origin == com.edgehybrid.agent.skills.UserSkill.Origin.BUNDLED)
                        "This is a bundled script. Deleting disables it until the app is reinstalled."
                    else
                        "The file is removed from app storage. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SkillEditorDialog(
    initial: com.edgehybrid.agent.skills.UserSkill,
    onDismiss: () -> Unit,
    onSave: (com.edgehybrid.agent.skills.UserSkill) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var body by remember { mutableStateOf(initial.body) }
    var kind by remember { mutableStateOf(initial.kind) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "New skill" else "Edit ${initial.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = RowArrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == com.edgehybrid.agent.skills.UserSkill.Kind.PROCEDURE,
                        onClick = { kind = com.edgehybrid.agent.skills.UserSkill.Kind.PROCEDURE },
                        label = { Text("Procedure") }
                    )
                    FilterChip(
                        selected = kind == com.edgehybrid.agent.skills.UserSkill.Kind.SCRIPT,
                        onClick = { kind = com.edgehybrid.agent.skills.UserSkill.Kind.SCRIPT },
                        label = { Text("Script") }
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("When the model should use this") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = {
                        Text(
                            if (kind == com.edgehybrid.agent.skills.UserSkill.Kind.SCRIPT)
                                "JavaScript — must define window.__edgeRun(input, origins)"
                            else "Instructions in Markdown"
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
                if (errors.isNotEmpty()) {
                    Text(
                        errors.joinToString("\n"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val candidate = initial.copy(
                    id = com.edgehybrid.agent.skills.SkillValidator.slugify(name),
                    name = name.trim(),
                    description = description.trim(),
                    kind = kind,
                    body = body,
                    updatedAt = System.currentTimeMillis()
                )
                val found = com.edgehybrid.agent.skills.SkillValidator.validate(candidate)
                if (found.isEmpty()) onSave(candidate) else errors = found.map { it.message }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
