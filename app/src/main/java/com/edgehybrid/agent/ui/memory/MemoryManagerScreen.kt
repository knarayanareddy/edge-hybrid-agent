package com.edgehybrid.agent.ui.memory

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.memory.UserMemoryDao
import com.edgehybrid.agent.memory.UserMemoryEntity
import com.edgehybrid.agent.ui.theme.AuraTokens
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagerScreen(
    userMemoryDao: UserMemoryDao,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val memories by userMemoryDao.observeActiveMemories().collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var showAddDialog by remember { mutableStateOf(false) }
    var continuousMemoryEnabled by remember { mutableStateOf(true) }

    val categories = listOf("ALL", "PREFERENCE", "PROFILE", "PROJECT", "DECISION")

    val filteredMemories = remember(memories, searchQuery, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        memories.filter { mem ->
            val matchesCat = selectedCategory == "ALL" || mem.category.equals(selectedCategory, ignoreCase = true)
            val matchesQuery = q.isEmpty() || mem.content.lowercase().contains(q) || mem.keywords.lowercase().contains(q)
            matchesCat && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Memory & Personalization",
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
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Memory")
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
                // On-device privacy stats banner
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AuraTokens.SurfaceSunkenLight,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(AuraTokens.AccentSubtle, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Psychology,
                                        contentDescription = null,
                                        tint = AuraTokens.Accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "On-Device Neural Memory",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        color = AuraTokens.TextPrimaryLight
                                    )
                                    Text(
                                        text = "${memories.size} facts retained locally",
                                        fontSize = 12.sp,
                                        color = AuraTokens.TextSecondaryLight
                                    )
                                }
                            }

                            Switch(
                                checked = continuousMemoryEnabled,
                                onCheckedChange = { continuousMemoryEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AuraTokens.Accent
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Memories update automatically during conversations to personalize responses. Everything is stored privately in encrypted Room SQLite on your device.",
                            fontSize = 12.sp,
                            color = AuraTokens.TextSecondaryLight,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            // Search Bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(100.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = AuraTokens.TextTertiaryLight,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search memories...",
                                color = AuraTokens.TextTertiaryLight,
                                fontSize = 14.sp
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = AuraTokens.TextPrimaryLight,
                                fontSize = 14.sp
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Category Chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = selectedCategory == cat
                        Surface(
                            shape = RoundedCornerShape(100.dp),
                            color = if (isSelected) AuraTokens.SurfaceRaisedLight else AuraTokens.SurfaceSunkenLight,
                            modifier = Modifier.clickable { selectedCategory = cat }
                        ) {
                            Text(
                                text = cat,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) AuraTokens.Accent else AuraTokens.TextSecondaryLight,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Memory List
            if (filteredMemories.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No memories found. Add one or converse with the agent!",
                            color = AuraTokens.TextTertiaryLight,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                items(filteredMemories, key = { it.id }) { mem ->
                    MemoryItemCard(
                        memory = mem,
                        onDelete = {
                            coroutineScope.launch {
                                userMemoryDao.deactivate(mem.id, System.currentTimeMillis())
                            }
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showAddDialog) {
        AddMemoryDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { content, cat, keywords ->
                coroutineScope.launch {
                    userMemoryDao.upsert(
                        UserMemoryEntity(
                            id = "mem_" + UUID.randomUUID().toString().take(8),
                            content = content,
                            category = cat,
                            keywords = keywords,
                            frequency = 1,
                            isActive = true,
                            createdAt = System.currentTimeMillis(),
                            lastUsedAt = System.currentTimeMillis()
                        )
                    )
                    showAddDialog = false
                }
            }
        )
    }
}

@Composable
fun MemoryItemCard(
    memory: UserMemoryEntity,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AuraTokens.SurfaceSunkenLight,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = AuraTokens.AccentSubtle
                    ) {
                        Text(
                            text = memory.category,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuraTokens.Accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    if (memory.frequency > 1) {
                        Text(
                            text = "Affirmed ${memory.frequency}x",
                            fontSize = 11.sp,
                            color = AuraTokens.TextTertiaryLight
                        )
                    }
                }

                Text(
                    text = memory.content,
                    fontSize = 14.sp,
                    color = AuraTokens.TextPrimaryLight,
                    lineHeight = 19.sp
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Delete memory",
                    tint = AuraTokens.TextTertiaryLight,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("PREFERENCE") }
    var keywords by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Fact / Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Fact (e.g. 'I develop with Kotlin & Rust')") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it.uppercase() },
                    label = { Text("Category (PREFERENCE, PROFILE, PROJECT, DECISION)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Keywords for retrieval (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = content.isNotBlank(),
                onClick = { onAdd(content.trim(), category.trim(), keywords.trim()) }
            ) {
                Text("Remember")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
