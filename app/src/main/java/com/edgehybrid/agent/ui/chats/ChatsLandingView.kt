package com.edgehybrid.agent.ui.chats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.edgehybrid.agent.data.local.ChatSessionEntity
import com.edgehybrid.agent.ui.theme.AuraTokens
import java.util.Calendar

private fun getDynamicGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello"
    }
}

@Composable
fun ChatsLandingView(
    sessions: List<ChatSessionEntity>,
    onSelectSession: (String) -> Unit,
    onStartNewChat: () -> Unit,
    onLaunchCamera: () -> Unit,
    onSendPrompt: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val greeting = remember { getDynamicGreeting() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AuraTokens.SurfaceLight)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))

            // AI Status Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(AuraTokens.AccentSubtle)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(Color(0xFF22C55E), CircleShape)
                )
                Text(
                    text = "Gemini 3.8 Flash • Key-Routed Fallback Ready",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AuraTokens.Accent
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dynamic Greeting
            Text(
                text = greeting,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = AuraTokens.TextPrimaryLight,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "What would you like to explore or automate today?",
                fontSize = 15.sp,
                color = AuraTokens.TextSecondaryLight
            )
        }

        // Action Suggestions Grid (Functional)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Suggested Actions",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTokens.TextPrimaryLight
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActionCard(
                        icon = Icons.Default.Inbox,
                        iconTint = AuraTokens.Accent,
                        iconBg = AuraTokens.AccentSubtle,
                        title = "Info Box Digest",
                        subtitle = "Summarize saved links & notes",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSendPrompt("Summarize my recently added Info Box bookmarks and reading notes into a concise overview.")
                        }
                    )
                    ActionCard(
                        icon = Icons.Default.Search,
                        iconTint = Color(0xFF0D9488),
                        iconBg = Color(0xFFCCFBF1),
                        title = "Web Intelligence",
                        subtitle = "Search latest tech & news",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSendPrompt("Search the web for the latest major tech and world headlines today.")
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActionCard(
                        icon = Icons.Default.Speed,
                        iconTint = Color(0xFFE11D48),
                        iconBg = Color(0xFFFFE4E6),
                        title = "Device Status",
                        subtitle = "Check battery & diagnostics",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSendPrompt("Run a diagnostic check on my device status: battery percentage, storage, and Wi-Fi.")
                        }
                    )
                    ActionCard(
                        icon = Icons.Default.PhotoCamera,
                        iconTint = Color(0xFF7C3AED),
                        iconBg = Color(0xFFEDE9FE),
                        title = "Vision Camera",
                        subtitle = "Analyze image or object",
                        modifier = Modifier.weight(1f),
                        onClick = onLaunchCamera
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActionCard(
                        icon = Icons.Default.Schedule,
                        iconTint = Color(0xFFD97706),
                        iconBg = Color(0xFFFEF3C7),
                        title = "Scheduled Tasks",
                        subtitle = "View recurring automations",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSendPrompt("Show me my active scheduled tasks and automation routines.")
                        }
                    )
                    ActionCard(
                        icon = Icons.Default.Lightbulb,
                        iconTint = Color(0xFF2563EB),
                        iconBg = Color(0xFFDBEAFE),
                        title = "Deep Reasoning",
                        subtitle = "Plan a project or concept",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSendPrompt("Let's plan out a project architecture step-by-step. What details should we start with?")
                        }
                    )
                }
            }
        }

        // Recent Conversations List (Real Data)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Conversations",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraTokens.TextPrimaryLight
                    )
                    if (sessions.isNotEmpty()) {
                        Text(
                            text = "${sessions.size} Chats",
                            fontSize = 12.sp,
                            color = AuraTokens.TextTertiaryLight
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(16.dp), spotColor = Color(0x08000000))
                        .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(16.dp))
                ) {
                    if (sessions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forum,
                                    contentDescription = null,
                                    tint = AuraTokens.TextTertiaryLight,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "No previous conversations",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AuraTokens.TextSecondaryLight
                                )
                                Text(
                                    text = "Tap a suggestion above or type below to start.",
                                    fontSize = 12.sp,
                                    color = AuraTokens.TextTertiaryLight
                                )
                            }
                        }
                    } else {
                        sessions.take(5).forEachIndexed { index, session ->
                            RecentChatRow(
                                title = session.title.ifBlank { "Untitled Conversation" },
                                subtitle = "Tap to resume conversation",
                                onClick = { onSelectSession(session.id) }
                            )
                            if (index < sessions.take(5).lastIndex) {
                                androidx.compose.material3.HorizontalDivider(
                                    color = AuraTokens.SurfaceRaisedLight,
                                    modifier = Modifier.padding(start = 52.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Engine & Architecture Footer
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AuraTokens.SurfaceLight, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = AuraTokens.Accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Edge Hybrid Architecture",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AuraTokens.TextPrimaryLight
                        )
                    }
                    Text(
                        text = "On-Device & Cloud Synergy",
                        fontSize = 11.sp,
                        color = AuraTokens.TextTertiaryLight
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(96.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp), spotColor = Color(0x06000000))
            .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(iconBg, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTokens.TextPrimaryLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = AuraTokens.TextSecondaryLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RecentChatRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(AuraTokens.AccentSubtle, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Forum,
                    contentDescription = null,
                    tint = AuraTokens.Accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTokens.TextPrimaryLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = AuraTokens.TextSecondaryLight
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "Open",
            tint = AuraTokens.BorderLight,
            modifier = Modifier.size(18.dp)
        )
    }
}
