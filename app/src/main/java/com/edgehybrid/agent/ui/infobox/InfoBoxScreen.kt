package com.edgehybrid.agent.ui.infobox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.infobox.InfoBoxItem
import com.edgehybrid.agent.infobox.InfoBoxItemType
import com.edgehybrid.agent.infobox.InfoBoxStore
import com.edgehybrid.agent.ui.theme.AuraTokens
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoBoxScreen(
    infoBoxStore: InfoBoxStore,
    onNavigateBack: () -> Unit,
    onSendToChat: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val allItems by infoBoxStore.items.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var showManualAddDialog by remember { mutableStateOf(false) }
    var showOverviewPreviewDialog by remember { mutableStateOf(false) }

    val filterOptions = listOf("All", "Hyperlinks", "Snippets", "Articles", "Yesterday", "Today", "Starred")

    val yesterdayItems = remember(allItems) {
        infoBoxStore.getItemsFromYesterday()
    }
    val todayItems = remember(allItems) {
        infoBoxStore.getItemsFromToday()
    }

    val filteredItems = remember(allItems, searchQuery, selectedFilter) {
        allItems.filter { item ->
            val matchesFilter = when (selectedFilter) {
                "All" -> true
                "Hyperlinks" -> item.type == InfoBoxItemType.HYPERLINK
                "Snippets" -> item.type == InfoBoxItemType.TEXT_SNIPPET
                "Articles" -> item.type == InfoBoxItemType.ARTICLE_EXCERPT
                "Yesterday" -> {
                    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                    val yStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
                    item.dateString == yStr
                }
                "Today" -> {
                    val tStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    item.dateString == tStr
                }
                "Starred" -> item.isFavorite
                else -> true
            }

            val matchesSearch = if (searchQuery.isBlank()) true else {
                val q = searchQuery.trim().lowercase()
                item.title.lowercase().contains(q) ||
                        item.content.lowercase().contains(q) ||
                        (item.url?.lowercase()?.contains(q) == true) ||
                        (item.sourceApp?.lowercase()?.contains(q) == true)
            }

            matchesFilter && matchesSearch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Info Box",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Long-press & context menu capture hub",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showOverviewPreviewDialog = true }) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Daily Overview Briefing",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AuraTokens.SurfaceLight
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showManualAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add to Info Box")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Overview Status Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Daily Overview Ready",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Yesterday: ${yesterdayItems.size} items • Today: ${todayItems.size} items • Total: ${allItems.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Button(
                        onClick = {
                            val itemsToBrief = if (yesterdayItems.isNotEmpty()) yesterdayItems else allItems
                            val prompt = infoBoxStore.buildDailyDigestPrompt(itemsToBrief)
                            onSendToChat(prompt)
                        },
                        enabled = allItems.isNotEmpty(),
                        shape = RoundedCornerShape(100.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Synthesize", fontSize = 12.sp)
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search links, text, or sources...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filterOptions.forEach { filter ->
                    val isSelected = selectedFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                text = when (filter) {
                                    "Hyperlinks" -> "Links 🌐"
                                    "Snippets" -> "Snippets 📝"
                                    "Articles" -> "Articles 📑"
                                    "Starred" -> "Starred ⭐"
                                    else -> filter
                                },
                                fontSize = 12.sp
                            )
                        },
                        shape = RoundedCornerShape(100.dp)
                    )
                }
            }

            // Items List or Empty State
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matching items found" else "Your Info Box is empty",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "Try searching for a different keyword or reset filters."
                            } else {
                                "When browsing in Chrome, Samsung Internet, or reading any text on your phone, select text or long-press a link and choose 'Add to Info Box' to automatically store it here for daily AI digests."
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                ) {
                    items(
                        items = filteredItems,
                        key = { it.id }
                    ) { item ->
                        InfoBoxItemCard(
                            item = item,
                            onToggleFavorite = {
                                coroutineScope.launch {
                                    infoBoxStore.toggleFavorite(item.id)
                                }
                            },
                            onDeleteItem = {
                                coroutineScope.launch {
                                    infoBoxStore.deleteItem(item.id)
                                    Toast.makeText(context, "Item deleted", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onOpenUrl = { url ->
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }.onFailure {
                                    Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onCopyContent = { content ->
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Info Box", content))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onShareContent = { content ->
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, content)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(Intent.createChooser(intent, "Share captured item"))
                            },
                            onAnalyzeWithAgent = {
                                val prompt = "Please review and analyze this captured Info Box item:\n\n" +
                                        "Title: ${item.title}\n" +
                                        (if (!item.url.isNullOrBlank()) "URL: ${item.url}\n" else "") +
                                        "Type: ${item.type.name}\n" +
                                        "Captured: ${item.formattedDateTime()}\n\n" +
                                        "Content:\n${item.content}\n\n" +
                                        "Please provide key takeaways, background context, and relevant next steps."
                                onSendToChat(prompt)
                            }
                        )
                    }
                }
            }
        }
    }

    // Manual Add Dialog
    if (showManualAddDialog) {
        var manualText by remember { mutableStateOf("") }
        var manualTitle by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showManualAddDialog = false },
            title = { Text("Add to Info Box", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = manualTitle,
                        onValueChange = { manualTitle = it },
                        label = { Text("Title (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        label = { Text("Link or Text snippet *") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (manualText.isNotBlank()) {
                            coroutineScope.launch {
                                infoBoxStore.addFromText(
                                    rawText = manualText,
                                    sourceApp = "Manual Entry",
                                    preferredTitle = manualTitle.takeIf { it.isNotBlank() }
                                )
                                showManualAddDialog = false
                                Toast.makeText(context, "Added to Info Box", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = manualText.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Overview Preview Dialog
    if (showOverviewPreviewDialog) {
        val targetItems = if (yesterdayItems.isNotEmpty()) yesterdayItems else allItems
        val prompt = remember(targetItems) {
            infoBoxStore.buildDailyDigestPrompt(targetItems)
        }

        AlertDialog(
            onDismissRequest = { showOverviewPreviewDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Daily Overview Digest", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (yesterdayItems.isNotEmpty()) {
                            "Found ${yesterdayItems.size} items captured yesterday ready for executive synthesis."
                        } else {
                            "No items captured yesterday specifically. Ready to synthesize all ${allItems.size} captured items."
                        },
                        fontSize = 13.sp
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = prompt.take(350) + if (prompt.length > 350) "..." else "",
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverviewPreviewDialog = false
                        onSendToChat(prompt)
                    },
                    enabled = targetItems.isNotEmpty()
                ) {
                    Text("Brief with Agent")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverviewPreviewDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun InfoBoxItemCard(
    item: InfoBoxItem,
    onToggleFavorite: () -> Unit,
    onDeleteItem: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyContent: (String) -> Unit,
    onShareContent: (String) -> Unit,
    onAnalyzeWithAgent: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Type badge + Date + Star & Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = when (item.type) {
                            InfoBoxItemType.HYPERLINK -> MaterialTheme.colorScheme.primaryContainer
                            InfoBoxItemType.TEXT_SNIPPET -> MaterialTheme.colorScheme.secondaryContainer
                            InfoBoxItemType.ARTICLE_EXCERPT -> MaterialTheme.colorScheme.tertiaryContainer
                        },
                        shape = RoundedCornerShape(100.dp)
                    ) {
                        Text(
                            text = when (item.type) {
                                InfoBoxItemType.HYPERLINK -> "Link 🌐"
                                InfoBoxItemType.TEXT_SNIPPET -> "Snippet 📝"
                                InfoBoxItemType.ARTICLE_EXCERPT -> "Article 📑"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    item.sourceApp?.let { app ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Text(
                                text = app,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Star",
                            tint = if (item.isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDeleteItem,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Title
            Text(
                text = item.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // URL clickable
            if (!item.url.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenUrl(item.url) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open Link",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = item.url,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Content Preview / Full
            val isLong = item.content.length > 200
            val displayText = if (!expanded && isLong) item.content.take(190) + "..." else item.content

            Text(
                text = displayText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            if (isLong) {
                Text(
                    text = if (expanded) "Show less" else "Show more",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(vertical = 2.dp)
                )
            }

            // AI Summary badge if available
            item.aiSummary?.let { summary ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = summary,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Footer Timestamp & Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.formattedDateTime(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onCopyContent(item.content) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { onShareContent(item.url ?: item.content) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        onClick = onAnalyzeWithAgent,
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Ask AI",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
