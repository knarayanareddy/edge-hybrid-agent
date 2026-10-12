package com.edgehybrid.agent.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.ui.chat.ToolActivityStatus
import com.edgehybrid.agent.ui.chat.ToolActivityUi
import com.edgehybrid.agent.ui.theme.AuraTokens

/**
 * ChatGPT-style interactive tool execution card.
 *
 * Replaces plain text bullets with a sleek, animated action pill showing the tool state,
 * animated spinner during execution, success checkmarks, and an expandable parameter/output accordion.
 */
@Composable
fun ChatToolCard(
    activity: ToolActivityUi,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val friendlyTitle = remember(activity.toolName, activity.status) {
        formatFriendlyToolTitle(activity.toolName, activity.status)
    }

    Surface(
        shape = RoundedCornerShape(AuraTokens.Radius.control.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(AuraTokens.Radius.control.dp))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                expanded = !expanded
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    when (activity.status) {
                        ToolActivityStatus.RUNNING -> {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                                color = AuraTokens.Accent
                            )
                        }
                        ToolActivityStatus.SUCCEEDED -> {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(16.dp)
                                    .background(AuraTokens.Success.copy(alpha = 0.15f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Success",
                                    tint = AuraTokens.Success,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        }
                        ToolActivityStatus.FAILED -> {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(16.dp)
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Failed",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = friendlyTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = if (activity.status == ToolActivityStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = AuraTokens.Accent,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Tool: ${activity.toolName}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = AuraTokens.Accent
                            )
                        )
                    }

                    Spacer(modifier = Modifier.padding(vertical = 2.dp))

                    Text(
                        text = "Call ID: ${activity.callId.take(12)}…",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}

private fun formatFriendlyToolTitle(toolName: String, status: ToolActivityStatus): String {
    val base = when (toolName) {
        "get_screen_elements" -> "Inspecting screen"
        "click_screen_element" -> "Tapping screen element"
        "click_screen_coordinates" -> "Tapping coordinates"
        "type_screen_text" -> "Typing on screen"
        "perform_system_key" -> "System key action"
        "live_web_search" -> "Searching the web"
        "extract_webpage_content" -> "Reading webpage"
        "search_wikipedia" -> "Searching Wikipedia"
        "get_battery_status" -> "Checking battery"
        "get_storage_status" -> "Checking storage"
        "get_memory_status" -> "Checking RAM memory"
        "get_display_settings" -> "Reading display settings"
        "create_quick_note" -> "Saving quick note"
        "set_timer" -> "Setting timer"
        "set_alarm" -> "Setting alarm"
        "query_calendar_events" -> "Checking calendar"
        "create_calendar_event" -> "Adding calendar event"
        "send_sms" -> "Composing SMS"
        "send_telegram_message" -> "Sending Telegram message"
        "draft_email" -> "Drafting email"
        "open_camera" -> "Opening camera"
        "toggle_flashlight" -> "Toggling flashlight"
        "control_spotify" -> "Spotify playback"
        "calculate_math" -> "Calculating formula"
        "convert_currency" -> "Converting currency"
        "convert_temperature" -> "Converting temperature"
        "get_world_time" -> "Checking world time"
        else -> toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    return when (status) {
        ToolActivityStatus.RUNNING -> "$base…"
        ToolActivityStatus.SUCCEEDED -> when {
            base.endsWith("ing") -> base.removeSuffix("ing") + "ed"
            else -> "Completed: $base"
        }
        ToolActivityStatus.FAILED -> "Failed: $base"
    }
}
