package com.edgehybrid.agent.ui.scheduled

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.scheduled.ScheduleRecurrence
import com.edgehybrid.agent.scheduled.ScheduledTask
import com.edgehybrid.agent.scheduled.ScheduledTaskStore
import com.edgehybrid.agent.ui.theme.AuraTokens
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    taskStore: ScheduledTaskStore,
    onNavigateBack: () -> Unit,
    onRunTaskNow: (String) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val tasks by taskStore.tasks.collectAsState()

    val activeTasks = remember(tasks) { tasks.filter { it.isEnabled } }
    val presetTasks = remember(tasks) { tasks.filter { !it.isEnabled && it.isPreset } }

    var selectedTaskToConfigure by remember { mutableStateOf<ScheduledTask?>(null) }
    var showCreateCustomDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Scheduled",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateCustomDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create Custom Task")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AuraTokens.SurfaceLight
                )
            )
        },
        containerColor = AuraTokens.SurfaceLight
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Set up tasks once, and ChatGPT can keep working on them for you.",
                    fontSize = 14.sp,
                    color = AuraTokens.TextSecondaryLight,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // ── Active Tasks Section ─────────────────────────────────────
            if (activeTasks.isNotEmpty()) {
                item {
                    Text(
                        text = "ACTIVE TASKS (${activeTasks.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AuraTokens.Accent,
                        letterSpacing = 0.5.sp
                    )
                }

                items(activeTasks, key = { it.id }) { task ->
                    ActiveTaskCard(
                        task = task,
                        onToggle = { isEnabled ->
                            coroutineScope.launch {
                                taskStore.toggleTask(task.id, isEnabled)
                            }
                        },
                        onRunNow = { onRunTaskNow(task.id) },
                        onEdit = { selectedTaskToConfigure = task }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "BROWSE IDEAS & RECIPES",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AuraTokens.TextTertiaryLight,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // ── Presets Catalog ──────────────────────────────────────────
            items(presetTasks, key = { it.id }) { task ->
                PresetTaskCard(
                    task = task,
                    onClick = { selectedTaskToConfigure = task }
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ── Configure / Enable Task Modal ────────────────────────────────────
    selectedTaskToConfigure?.let { task ->
        ConfigureTaskDialog(
            task = task,
            onDismiss = { selectedTaskToConfigure = null },
            onSave = { updated ->
                coroutineScope.launch {
                    taskStore.saveTask(updated)
                    selectedTaskToConfigure = null
                }
            }
        )
    }

    // ── Create Custom Task Dialog ────────────────────────────────────────
    if (showCreateCustomDialog) {
        CreateCustomTaskDialog(
            onDismiss = { showCreateCustomDialog = false },
            onCreate = { newTask ->
                coroutineScope.launch {
                    taskStore.saveTask(newTask)
                    showCreateCustomDialog = false
                }
            }
        )
    }
}

@Composable
fun ActiveTaskCard(
    task: ScheduledTask,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onEdit() },
        color = AuraTokens.SurfaceSunkenLight,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(AuraTokens.SurfaceRaisedLight, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = task.iconEmoji, fontSize = 20.sp)
                    }
                    Column {
                        Text(
                            text = task.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AuraTokens.TextPrimaryLight
                        )
                        Text(
                            text = task.formattedSchedule(),
                            fontSize = 12.sp,
                            color = AuraTokens.Accent
                        )
                    }
                }

                Switch(
                    checked = task.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AuraTokens.Accent
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = task.description,
                fontSize = 13.sp,
                color = AuraTokens.TextSecondaryLight,
                lineHeight = 18.sp
            )

            task.lastResultSnippet?.let { snippet ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AuraTokens.SurfaceRaisedLight,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Last Result: $snippet",
                        fontSize = 11.sp,
                        color = AuraTokens.TextSecondaryLight,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onRunNow,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run Now", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun PresetTaskCard(
    task: ScheduledTask,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = AuraTokens.SurfaceSunkenLight,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(AuraTokens.SurfaceRaisedLight, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = task.iconEmoji, fontSize = 22.sp)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTokens.TextPrimaryLight
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = task.description,
                    fontSize = 13.sp,
                    color = AuraTokens.TextSecondaryLight,
                    lineHeight = 18.sp
                )
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(AuraTokens.SurfaceRaisedLight, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Set up",
                    tint = AuraTokens.TextSecondaryLight,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun ConfigureTaskDialog(
    task: ScheduledTask,
    onDismiss: () -> Unit,
    onSave: (ScheduledTask) -> Unit
) {
    var title by remember { mutableStateOf(task.title) }
    var prompt by remember { mutableStateOf(task.prompt) }
    var hour by remember { mutableIntStateOf(task.hour) }
    var minute by remember { mutableIntStateOf(task.minute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = task.iconEmoji, fontSize = 24.sp)
                Text(text = "Set up: ${task.title}")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = task.formattedSchedule(),
                    fontSize = 13.sp,
                    color = AuraTokens.Accent,
                    fontWeight = FontWeight.Medium
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Task Instructions") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Execution Time:", fontSize = 14.sp)
                    Text(
                        text = String.format("%02d:%02d", hour, minute),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AuraTokens.Accent
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        task.copy(
                            title = title,
                            prompt = prompt,
                            hour = hour,
                            minute = minute,
                            isEnabled = true
                        )
                    )
                }
            ) {
                Text("Enable Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CreateCustomTaskDialog(
    onDismiss: () -> Unit,
    onCreate: (ScheduledTask) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var selectedRecurrence by remember { mutableStateOf(ScheduleRecurrence.DAILY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Scheduled Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Short Description") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt / Instructions for Agent") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && prompt.isNotBlank(),
                onClick = {
                    val task = ScheduledTask(
                        id = "custom_" + UUID.randomUUID().toString().take(8),
                        title = title.trim(),
                        description = description.ifBlank { "Custom scheduled task" },
                        prompt = prompt.trim(),
                        recurrence = selectedRecurrence,
                        hour = 9,
                        minute = 0,
                        isEnabled = true,
                        iconEmoji = "⚡",
                        isPreset = false
                    )
                    onCreate(task)
                }
            ) {
                Text("Create & Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
