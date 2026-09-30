package com.edgehybrid.agent.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
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