package com.edgehybrid.agent.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.data.local.LessonEntity
import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.ui.theme.AuraTokens
import kotlinx.coroutines.launch

data class MemoryFact(
    val id: String,
    val category: String,
    val meta: String,
    val text: String,
    val tagColor: Color,
    val tagBg: Color
)

@Composable
fun LibraryMemoryScreen(
    lessonsDao: LessonsDao,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var continuousMemoryEnabled by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    val defaultFacts = remember {
        mutableStateListOf(
            MemoryFact(
                id = "fact-1",
                category = "PREFERENCE",
                meta = "Yesterday • Chat #42",
                text = "Prefers TypeScript functional purity without mutations; enforces exhaustive pattern matching.",
                tagColor = AuraTokens.Primary,
                tagBg = AuraTokens.PrimaryFixed
            ),
            MemoryFact(
                id = "fact-2",
                category = "CONTEXT",
                meta = "Oct 14 • Project Kickoff",
                text = "Leading the Core Cloud SDK migration targeting Node 22 ESM compatibility and Edge workers.",
                tagColor = AuraTokens.Secondary,
                tagBg = AuraTokens.SecondaryFixed
            ),
            MemoryFact(
                id = "fact-3",
                category = "DIRECTIVE",
                meta = "Sep 28 • Global Setup",
                text = "Avoid boilerplate answers; prioritize benchmark graphs and sub-50ms execution paths.",
                tagColor = AuraTokens.Tertiary,
                tagBg = AuraTokens.TertiaryFixed
            )
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AuraTokens.Surface)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Title & Native Search Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Library & Memory",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = AuraTokens.OnSurface,
                    letterSpacing = (-0.5).sp
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(AuraTokens.SurfaceContainer, CircleShape)
                        .clickable { },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Item",
                        tint = AuraTokens.Primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Frosted Search Capsule
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(AuraTokens.SurfaceContainerHigh, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = AuraTokens.Outline,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Search knowledge, memories, files...",
                    color = AuraTokens.Outline,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice Search",
                    tint = AuraTokens.Outline,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Ambient Memory Stats Widget
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(20.dp), spotColor = Color(0x10000000))
                    .background(AuraTokens.SurfaceContainerLowest, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(AuraTokens.SecondaryFixed, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = "Neural Vector Index",
                                tint = AuraTokens.OnSecondaryFixed,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Neural Vector Index",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AuraTokens.OnSurface
                                )
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(AuraTokens.SecondaryContainer, CircleShape)
                                )
                            }
                            Text(
                                text = "1,428 embeddings indexed on-device",
                                fontSize = 13.sp,
                                color = AuraTokens.OnSurfaceVariant
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .background(AuraTokens.SurfaceContainerHigh, RoundedCornerShape(100.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Ready",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AuraTokens.Primary
                        )
                    }
                }
            }
        }

        // Section 1: Workspaces
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "WORKSPACES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraTokens.Outline,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Edit",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraTokens.Primary
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color(0x08000000))
                        .background(AuraTokens.SurfaceContainerLowest, RoundedCornerShape(18.dp))
                ) {
                    WorkspaceRow(
                        icon = Icons.Default.CloudDone,
                        iconBg = AuraTokens.PrimaryFixed,
                        iconTint = AuraTokens.Primary,
                        title = "Distributed Systems v3",
                        subtitle = "24 conversations • 8 files"
                    )
                    Divider(color = AuraTokens.SurfaceContainer, modifier = Modifier.padding(start = 56.dp))
                    WorkspaceRow(
                        icon = Icons.Default.Palette,
                        iconBg = AuraTokens.SecondaryFixed,
                        iconTint = AuraTokens.Secondary,
                        title = "Aura Design Tokens",
                        subtitle = "12 conversations • 19 files"
                    )
                    Divider(color = AuraTokens.SurfaceContainer, modifier = Modifier.padding(start = 56.dp))
                    WorkspaceRow(
                        icon = Icons.Default.MenuBook,
                        iconBg = AuraTokens.TertiaryFixed,
                        iconTint = AuraTokens.Tertiary,
                        title = "Attention & RAG Papers",
                        subtitle = "37 conversations • 34 PDFs"
                    )
                }
            }
        }

        // Section 2: Apple Intelligence Memory
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "APPLE INTELLIGENCE MEMORY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraTokens.Outline,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "On-Device Encrypted",
                        fontSize = 11.sp,
                        color = AuraTokens.OnSurfaceVariant
                    )
                }

                // Continuous Memory Toggle
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color(0x08000000))
                        .background(AuraTokens.SurfaceContainerLowest, RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(AuraTokens.SurfaceContainer, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AllInclusive,
                                    contentDescription = "Memory",
                                    tint = AuraTokens.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Continuous Memory",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AuraTokens.OnSurface
                                )
                                Text(
                                    text = "Sync knowledge across conversations",
                                    fontSize = 13.sp,
                                    color = AuraTokens.OnSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = continuousMemoryEnabled,
                            onCheckedChange = { continuousMemoryEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AuraTokens.Primary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Aura synthesizes persistent context locally. Insights are retained safely and can be pruned anytime.",
                        fontSize = 13.sp,
                        color = AuraTokens.OnSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Learned Facts List
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color(0x08000000))
                        .background(AuraTokens.SurfaceContainerLowest, RoundedCornerShape(18.dp))
                ) {
                    defaultFacts.forEachIndexed { index, fact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(fact.tagBg, RoundedCornerShape(100.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = fact.category,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = fact.tagColor
                                        )
                                    }
                                    Text(
                                        text = fact.meta,
                                        fontSize = 11.sp,
                                        color = AuraTokens.Outline
                                    )
                                }
                                Text(
                                    text = fact.text,
                                    fontSize = 14.sp,
                                    color = AuraTokens.OnSurface,
                                    lineHeight = 19.sp
                                )
                            }
                            IconButton(
                                onClick = { defaultFacts.remove(fact) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete",
                                    tint = AuraTokens.Outline,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (index < defaultFacts.lastIndex) {
                            Divider(color = AuraTokens.SurfaceContainer, modifier = Modifier.padding(start = 14.dp))
                        }
                    }
                }
            }
        }

        // Section 3: Recent Artifacts
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "RECENT ARTIFACTS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraTokens.Outline,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "See All (18)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraTokens.Primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ArtifactTile(
                        icon = Icons.Default.Code,
                        iconTint = AuraTokens.Primary,
                        iconBg = AuraTokens.PrimaryFixed,
                        tag = "TS",
                        title = "authMiddleware.ts",
                        subtitle = "JWT validation",
                        size = "4.2 KB",
                        time = "2h ago",
                        modifier = Modifier.weight(1f)
                    )
                    ArtifactTile(
                        icon = Icons.Default.Article,
                        iconTint = AuraTokens.Secondary,
                        iconBg = AuraTokens.SecondaryFixed,
                        tag = "MD",
                        title = "v2_architecture.md",
                        subtitle = "RFC doc",
                        size = "18.5 KB",
                        time = "Yesterday",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ArtifactTile(
                        icon = Icons.Default.BarChart,
                        iconTint = AuraTokens.Tertiary,
                        iconBg = AuraTokens.TertiaryFixed,
                        tag = "SVG",
                        title = "benchmarks_v3.svg",
                        subtitle = "Latency test",
                        size = "1.8 KB",
                        time = "Oct 16",
                        modifier = Modifier.weight(1f)
                    )
                    // Import File Placeholder Tile
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(140.dp)
                            .border(1.dp, AuraTokens.OutlineVariant, RoundedCornerShape(18.dp))
                            .background(AuraTokens.SurfaceContainerLowest.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                            .clickable { },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(AuraTokens.SurfaceContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = "Upload",
                                    tint = AuraTokens.Outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Import File",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AuraTokens.OnSurface
                            )
                            Text(
                                text = "PDF, Code, Media",
                                fontSize = 11.sp,
                                color = AuraTokens.OnSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
fun WorkspaceRow(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(iconBg, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTokens.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = AuraTokens.OnSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "Open",
            tint = AuraTokens.OutlineVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun ArtifactTile(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    tag: String,
    title: String,
    subtitle: String,
    size: String,
    time: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(140.dp)
            .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color(0x06000000))
            .background(AuraTokens.SurfaceContainerLowest, RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(iconBg, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .background(AuraTokens.SurfaceContainerHigh, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = tag,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AuraTokens.OnSurfaceVariant
                    )
                }
            }

            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTokens.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = AuraTokens.OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = size, fontSize = 10.sp, color = AuraTokens.Outline)
                Text(text = time, fontSize = 10.sp, color = AuraTokens.Outline)
            }
        }
    }
}
