package com.edgehybrid.agent.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.ui.theme.AuraTokens

@Composable
fun CanvasInspectorScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSegment by remember { mutableIntStateOf(0) }
    var patchAccepted by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AuraTokens.SurfaceLight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Segmented Control: Code | Preview | Diff
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(100.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("Code", "Preview", "Diff •").forEachIndexed { index, title ->
                val isSelected = selectedSegment == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(
                            if (isSelected) AuraTokens.SurfaceSunkenLight else Color.Transparent,
                            RoundedCornerShape(100.dp)
                        )
                        .clickable { selectedSegment = index },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) AuraTokens.TextPrimaryLight else AuraTokens.TextSecondaryLight
                    )
                }
            }
        }

        // Code Editor Window (macOS style)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(20.dp), spotColor = Color(0x0C000000))
                .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(20.dp))
                .border(1.dp, AuraTokens.SurfaceSunkenLight, RoundedCornerShape(20.dp))
                .padding(14.dp)
        ) {
            // macOS dots and breadcrumb
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFFF5F56), CircleShape))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFFFBD2E), CircleShape))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFF27C93F), CircleShape))
                }
                Text(
                    text = "CacheWorker > fetchWithFallback()",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AuraTokens.TextSecondaryLight
                )
                Text(
                    text = "UTF-8  Spaces: 2",
                    fontSize = 10.sp,
                    color = AuraTokens.TextTertiaryLight
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Code lines
            val codeLines = listOf(
                "8" to "export class ResilientEdgeCache {",
                "9" to "  private readonly tier: StorageBucket;",
                "10" to "  private metrics: TelemetryClient;",
                "11" to "",
                "12" to "  async retrieve(key: string): Promise<CacheEntry> {",
                "13" to "    const controller = new AbortController();",
                "14" to "    const timer = setTimeout(() => controller.abort(), 250);",
                "15" to "    try {",
                "16" to "      return await this.tier.hydrate(key, {",
                "17" to "        signal: controller.signal,",
                "18" to "        origin: \"lhr-edge-node\"",
                "19" to "      });"
            )

            codeLines.forEach { (lineNum, lineContent) ->
                val isLine14 = lineNum == "14"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isLine14) AuraTokens.AccentSubtle.copy(alpha = 0.4f) else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(vertical = 2.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lineNum.padStart(2, ' '),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isLine14) AuraTokens.AccentHover else AuraTokens.BorderLight,
                        modifier = Modifier.width(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = lineContent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            isLine14 -> AuraTokens.ErrorContainer
                            lineContent.contains("export class") || lineContent.contains("async") -> AuraTokens.AccentHover
                            lineContent.contains("private") || lineContent.contains("const") -> AuraTokens.Accent
                            else -> AuraTokens.TextPrimaryLight
                        },
                        fontWeight = if (isLine14) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Apple Intelligence Patch Card
            if (!patchAccepted) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(16.dp), spotColor = Color(0x10000000))
                        .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(16.dp))
                        .border(1.dp, AuraTokens.AccentSubtle, RoundedCornerShape(16.dp))
                        .padding(14.dp)
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
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Patch",
                                    tint = AuraTokens.AccentHover,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = "Apple Intelligence",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AuraTokens.TextPrimaryLight
                            )
                            Box(
                                modifier = Modifier
                                    .background(AuraTokens.AccentSubtle, RoundedCornerShape(100.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Patch +1 / -0",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AuraTokens.Accent
                                )
                            }
                        }
                        Text(text = "Line 14", fontSize = 11.sp, color = AuraTokens.TextTertiaryLight)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Added 250ms fallback timeout to pre-empt edge cold-start latency spikes. Guarantees p99 response times stay sub-40ms.",
                        fontSize = 12.sp,
                        color = AuraTokens.TextSecondaryLight,
                        lineHeight = 17.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { patchAccepted = true },
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Text("Dismiss", color = AuraTokens.TextPrimaryLight)
                        }
                        Button(
                            onClick = { patchAccepted = true },
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(100.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AuraTokens.Accent)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Accept Change")
                        }
                    }
                }
            }
        }

        // Metric Telemetry Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                title = "Cold Start p99",
                value = "38ms",
                delta = "↓ 64% faster",
                deltaColor = Color(0xFF1B873F),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Memory Budget",
                value = "14.2 MB",
                delta = "Limit 128 MB",
                deltaColor = AuraTokens.TextTertiaryLight,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Compilation",
                value = "Clean",
                delta = "0 warnings",
                deltaColor = AuraTokens.TextTertiaryLight,
                modifier = Modifier.weight(1f)
            )
        }

        // Action Buttons: Run in Sandbox & Logs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(100.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AuraTokens.SurfaceSunkenLight)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AuraTokens.Accent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Run in Sandbox", color = AuraTokens.Accent, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = { },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(100.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AuraTokens.SurfaceSunkenLight)
            ) {
                Icon(Icons.Default.Description, contentDescription = null, tint = AuraTokens.TextSecondaryLight, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Logs", color = AuraTokens.TextSecondaryLight, fontWeight = FontWeight.SemiBold)
            }
        }

        // Bottom Prompt Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(100.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Voice", tint = AuraTokens.AccentHover, modifier = Modifier.size(20.dp))
                Text(
                    text = "Ask Aura to optimize or modify cache...",
                    fontSize = 13.sp,
                    color = AuraTokens.TextTertiaryLight
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(AuraTokens.Accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    delta: String,
    deltaColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(16.dp), spotColor = Color(0x06000000))
            .background(AuraTokens.SurfaceSunkenLight, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column {
            Text(text = title, fontSize = 10.sp, color = AuraTokens.TextTertiaryLight)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AuraTokens.TextPrimaryLight)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = delta, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = deltaColor)
        }
    }
}
