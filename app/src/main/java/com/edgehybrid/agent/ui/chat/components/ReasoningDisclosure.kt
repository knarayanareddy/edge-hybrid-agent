package com.edgehybrid.agent.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.ui.theme.AuraTokens

data class ParsedReasoningContent(
    val reasoningText: String?,
    val cleanedAnswerText: String
)

object ReasoningParser {
    /**
     * Extracts reasoning blocks (e.g. <think>...</think> from DeepSeek-R1 or o1/o3 styles).
     * Also supports unclosed <think> tags while generation is actively streaming.
     */
    fun parse(content: String): ParsedReasoningContent {
        val thinkRegex = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
        val match = thinkRegex.find(content)
        if (match != null) {
            val reasoning = match.groupValues[1].trim()
            val clean = content.replace(match.value, "").trim()
            return ParsedReasoningContent(reasoning.takeIf { it.isNotBlank() }, clean)
        }
        val unclosedRegex = Regex("<think>([\\s\\S]*)", RegexOption.IGNORE_CASE)
        val unclosedMatch = unclosedRegex.find(content)
        if (unclosedMatch != null) {
            val reasoning = unclosedMatch.groupValues[1].trim()
            val clean = content.substring(0, unclosedMatch.range.first).trim()
            return ParsedReasoningContent(reasoning.takeIf { it.isNotBlank() }, clean)
        }
        return ParsedReasoningContent(null, content)
    }
}

/**
 * OpenAI o1/o3 & DeepSeek-R1 style collapsible "Thought for X seconds" accordion widget.
 */
@Composable
fun ReasoningDisclosure(
    reasoningText: String,
    durationSeconds: Int = 3,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // Subtle pulsing animation for reasoning status
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Surface(
            shape = RoundedCornerShape(AuraTokens.Radius.pill.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier
                .clip(RoundedCornerShape(AuraTokens.Radius.pill.dp))
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    isExpanded = !isExpanded
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(if (isExpanded) 1f else alphaAnim)
                        .background(AuraTokens.Accent, CircleShape)
                )

                Text(
                    text = "Thought for ${durationSeconds}s",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse thought" else "Expand thought",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = AuraTokens.Accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Chain of Thought",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = AuraTokens.Accent
                            )
                        )
                    }

                    Spacer(modifier = Modifier.padding(vertical = 2.dp))

                    Text(
                        text = reasoningText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = FontStyle.Italic,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    )
                }
            }
        }
    }
}
