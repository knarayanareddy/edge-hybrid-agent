package com.edgehybrid.agent.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.library.LibraryItem
import com.edgehybrid.agent.library.LibraryItemType
import com.edgehybrid.agent.library.LibraryStore
import com.edgehybrid.agent.ui.theme.AuraTokens
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    libraryStore: LibraryStore,
    onNavigateBack: () -> Unit,
    onChatWithItem: (LibraryItem) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val allItems by libraryStore.items.collectAsState()

    val tabs = listOf("Suggested", "Favorites", "Folders", "Images", "Documents", "Code")
    var selectedTab by remember { mutableStateOf("Suggested") }
    var searchQuery by remember { mutableStateOf("") }
    var showAddFileDialog by remember { mutableStateOf(false) }

    val filteredItems = remember(allItems, selectedTab, searchQuery) {
        libraryStore.getFilteredItems(selectedTab, searchQuery)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Library",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AuraTokens.SurfaceLight
                )
            )
        },
        bottomBar = {
            // ChatGPT bottom bar: Search pill + '+' button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Search Pill
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(100.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = AuraTokens.TextTertiaryLight,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search",
                                color = AuraTokens.TextTertiaryLight,
                                fontSize = 15.sp
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = AuraTokens.TextPrimaryLight,
                                fontSize = 15.sp
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Add Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(AuraTokens.SurfaceSunkenLight, CircleShape)
                        .clickable { showAddFileDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add files",
                        tint = AuraTokens.TextPrimaryLight,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        containerColor = AuraTokens.SurfaceLight
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Horizontal Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(
                                if (isSelected) AuraTokens.SurfaceRaisedLight
                                else Color.Transparent
                            )
                            .clickable { selectedTab = tab }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) AuraTokens.TextPrimaryLight else AuraTokens.TextSecondaryLight
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Content Area
            if (filteredItems.isEmpty()) {
                // Empty state matching screenshot
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = AuraTokens.TextPrimaryLight,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Add your files",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AuraTokens.TextPrimaryLight
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Ask questions, get summaries, and more with your files.",
                            fontSize = 14.sp,
                            color = AuraTokens.TextSecondaryLight,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = { showAddFileDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AuraTokens.SurfaceRaisedLight,
                                contentColor = AuraTokens.TextPrimaryLight
                            ),
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Upload / Add File", fontSize = 13.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        LibraryItemRow(
                            item = item,
                            onToggleFavorite = {
                                coroutineScope.launch { libraryStore.toggleFavorite(item.id) }
                            },
                            onDelete = {
                                coroutineScope.launch { libraryStore.deleteItem(item.id) }
                            },
                            onChat = { onChatWithItem(item) }
                        )
                    }
                }
            }
        }
    }

    if (showAddFileDialog) {
        AddLibraryFileDialog(
            onDismiss = { showAddFileDialog = false },
            onAdd = { newItem ->
                coroutineScope.launch {
                    libraryStore.addItem(newItem)
                    showAddFileDialog = false
                }
            }
        )
    }
}

@Composable
fun LibraryItemRow(
    item: LibraryItem,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onChat: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChat() },
        color = AuraTokens.SurfaceSunkenLight,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(AuraTokens.SurfaceRaisedLight, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (item.type) {
                        LibraryItemType.DOCUMENT -> Icons.Default.Description
                        LibraryItemType.CODE -> Icons.Outlined.Code
                        LibraryItemType.IMAGE -> Icons.Default.Image
                        LibraryItemType.FOLDER -> Icons.Default.Folder
                    },
                    contentDescription = null,
                    tint = AuraTokens.Accent,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTokens.TextPrimaryLight
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = item.formattedSize(),
                        fontSize = 12.sp,
                        color = AuraTokens.TextTertiaryLight
                    )
                    if (item.summary.isNotBlank()) {
                        Text(text = "•", fontSize = 12.sp, color = AuraTokens.TextTertiaryLight)
                        Text(
                            text = item.summary,
                            fontSize = 12.sp,
                            color = AuraTokens.TextSecondaryLight,
                            maxLines = 1
                        )
                    }
                }
            }

            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (item.isFavorite) AuraTokens.Accent else AuraTokens.TextTertiaryLight,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun AddLibraryFileDialog(
    onDismiss: () -> Unit,
    onAdd: (LibraryItem) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var textContent by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(LibraryItemType.DOCUMENT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add File to Library") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File Name (e.g. project_notes.md)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Summary / Topic") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = textContent,
                    onValueChange = { textContent = it },
                    label = { Text("Document Content (indexed for on-device RAG)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val item = LibraryItem(
                        id = "file_" + UUID.randomUUID().toString().take(8),
                        name = name.trim(),
                        type = selectedType,
                        sizeBytes = textContent.toByteArray().size.toLong().coerceAtLeast(128),
                        summary = summary.ifBlank { "Added to library" },
                        textContent = textContent,
                        createdAt = System.currentTimeMillis()
                    )
                    onAdd(item)
                }
            ) {
                Text("Add & Index")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
