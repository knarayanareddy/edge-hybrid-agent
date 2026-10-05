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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.data.local.ChatSessionEntity
import com.edgehybrid.agent.ui.theme.AuraTokens

@Composable
fun ChatsLandingView(
    sessions: List<ChatSessionEntity>,
    onSelectSession: (String) -> Unit,
    onStartNewChat: () -> Unit,
    onLaunchCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AuraTokens.SurfaceLight)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            // Greeting
            Text(
                text = "Good morning, Elena",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = AuraTokens.TextPrimaryLight,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "What would you like to create today?",
                fontSize = 15.sp,
                color = AuraTokens.TextSecondaryLight
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Rounded Prompt Capsule
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .shadow(4.dp, RoundedCornerShape(100.dp), spotColor = Color(0x10000000))
                    .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(100.dp))
                    .clickable { onStartNewChat() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(AuraTokens.AccentSubtle, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "New",
                            tint = AuraTokens.Accent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "Ask Aura, draft Swift, sum...",
                        color = AuraTokens.TextTertiaryLight,
                        fontSize = 14.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(AuraTokens.SurfaceRaisedLight, CircleShape)
                            .clickable { onLaunchCamera() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "Camera",
                            tint = AuraTokens.TextSecondaryLight,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(AuraTokens.Accent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Intelligence Actions 2x2 Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Intelligence Actions",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraTokens.TextPrimaryLight
                    )
                    Text(
                        text = "Customize",
                        fontSize = 13.sp,
                        color = AuraTokens.Accent,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IntelligenceActionCard(
                        icon = Icons.Default.Edit,
                        iconTint = AuraTokens.AccentHover,
                        iconBg = AuraTokens.AccentSubtle,
                        badge = "●",
                        badgeColor = AuraTokens.AccentHover,
                        title = "Writing Tools",
                        subtitle = "Rewrite tone &...",
                        modifier = Modifier.weight(1f),
                        onClick = onStartNewChat
                    )
                    IntelligenceActionCard(
                        icon = Icons.Default.Hub,
                        iconTint = AuraTokens.Accent,
                        iconBg = AuraTokens.AccentSubtle,
                        badge = "98ms",
                        badgeColor = AuraTokens.Accent,
                        title = "Architecture",
                        subtitle = "Inspect distributed...",
                        modifier = Modifier.weight(1f),
                        onClick = onStartNewChat
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IntelligenceActionCard(
                        icon = Icons.Default.Code,
                        iconTint = AuraTokens.Danger,
                        iconBg = AuraTokens.AccentSubtle,
                        badge = "</>",
                        badgeColor = AuraTokens.Danger,
                        title = "Xcode Copilot",
                        subtitle = "SwiftUI & TypeScript...",
                        modifier = Modifier.weight(1f),
                        onClick = onStartNewChat
                    )
                    IntelligenceActionCard(
                        icon = Icons.Default.Palette,
                        iconTint = AuraTokens.TextSecondaryLight,
                        iconBg = AuraTokens.SurfaceSunkenLight,
                        badge = "Canvas",
                        badgeColor = AuraTokens.AccentHover,
                        title = "Visual Tokens",
                        subtitle = "Wireframes & iOS...",
                        modifier = Modifier.weight(1f),
                        onClick = onStartNewChat
                    )
                }
            }
        }

        // Context Briefing Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(20.dp), spotColor = Color(0x0C000000))
                    .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(AuraTokens.AccentSubtle, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = AuraTokens.Accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "Context Briefing",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AuraTokens.TextPrimaryLight
                        )
                    }
                    Text(text = "Just now", fontSize = 11.sp, color = AuraTokens.TextTertiaryLight)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AuraTokens.SurfaceLight, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(AuraTokens.Accent, CircleShape))
                            Text(
                                text = "Swift Concurrency Refactor",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AuraTokens.TextPrimaryLight
                            )
                        }
                        Text(
                            text = "Aura isolated 3 potential actor race conditions inside AsyncSequenceStream.swift and drafted modern async-await wrappers.",
                            fontSize = 12.sp,
                            color = AuraTokens.TextSecondaryLight,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Suggested: Apply patch",
                        fontSize = 12.sp,
                        color = AuraTokens.TextSecondaryLight
                    )
                    Button(
                        onClick = { },
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AuraTokens.AccentSubtle),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = "Review Diffs",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AuraTokens.Accent
                        )
                    }
                }
            }
        }

        // Recent Focus Sessions List
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Focus",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraTokens.TextPrimaryLight
                    )
                    Text(
                        text = "${sessions.size.coerceAtLeast(4)} Active Sessions",
                        fontSize = 12.sp,
                        color = AuraTokens.TextTertiaryLight
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(20.dp), spotColor = Color(0x08000000))
                        .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(20.dp))
                ) {
                    if (sessions.isEmpty()) {
                        RecentFocusRow(
                            icon = Icons.Default.Code,
                            iconBg = AuraTokens.AccentSubtle,
                            iconTint = AuraTokens.Accent,
                            title = "ResilientEdgeCache.ts",
                            subtitle = "Distributed KV Tier • Modified 14m ago",
                            onClick = onStartNewChat
                        )
                        Divider(color = AuraTokens.SurfaceRaisedLight, modifier = Modifier.padding(start = 56.dp))
                        RecentFocusRow(
                            icon = Icons.Default.Edit,
                            iconBg = AuraTokens.AccentSubtle,
                            iconTint = AuraTokens.AccentHover,
                            title = "Product Design Sprint",
                            subtitle = "Spatial Canvas • Modified 1h ago",
                            onClick = onStartNewChat
                        )
                        Divider(color = AuraTokens.SurfaceRaisedLight, modifier = Modifier.padding(start = 56.dp))
                        RecentFocusRow(
                            icon = Icons.Default.Speed,
                            iconBg = AuraTokens.AccentSubtle,
                            iconTint = AuraTokens.Danger,
                            title = "Distributed KV Store",
                            subtitle = "Latency benchmark telemetry • Yesterday",
                            onClick = onStartNewChat
                        )
                        Divider(color = AuraTokens.SurfaceRaisedLight, modifier = Modifier.padding(start = 56.dp))
                        RecentFocusRow(
                            icon = Icons.Default.Psychology,
                            iconBg = AuraTokens.SurfaceSunkenLight,
                            iconTint = AuraTokens.TextSecondaryLight,
                            title = "Cognitive Model Tuning",
                            subtitle = "Custom system prompt • 2 days ago",
                            onClick = onStartNewChat
                        )
                    } else {
                        sessions.take(6).forEachIndexed { index, session ->
                            RecentFocusRow(
                                icon = if (index % 2 == 0) Icons.Default.Code else Icons.Default.Speed,
                                iconBg = if (index % 2 == 0) AuraTokens.AccentSubtle else AuraTokens.AccentSubtle,
                                iconTint = if (index % 2 == 0) AuraTokens.Accent else AuraTokens.AccentHover,
                                title = session.title,
                                subtitle = "Session • Active Context",
                                onClick = { onSelectSession(session.id) }
                            )
                            if (index < sessions.take(6).lastIndex) {
                                Divider(color = AuraTokens.SurfaceRaisedLight, modifier = Modifier.padding(start = 56.dp))
                            }
                        }
                    }
                }
            }
        }

        // Bottom Neural Cache Chip
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AuraTokens.SurfaceLight, RoundedCornerShape(16.dp))
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
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            tint = AuraTokens.Accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Local Neural Cache: 1.84 GB Encrypted",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AuraTokens.TextPrimaryLight
                        )
                    }
                    Text(
                        text = "Private Cloud Compute",
                        fontSize = 11.sp,
                        color = AuraTokens.TextTertiaryLight
                    )
                }
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
fun IntelligenceActionCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    badge: String,
    badgeColor: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .height(118.dp)
            .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color(0x06000000))
            .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(18.dp))
            .clickable { onClick() }
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
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = badgeColor
                )
            }

            Column {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTokens.TextPrimaryLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = AuraTokens.TextSecondaryLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun RecentFocusRow(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
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
