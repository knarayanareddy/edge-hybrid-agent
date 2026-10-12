package com.edgehybrid.agent.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.edgehybrid.agent.ui.theme.AuraTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ChatGPT-style message action toolbar located beneath assistant responses.
 *
 * Provides instant one-tap actions: Copy to clipboard, Share, TTS voice readout, and Regenerate.
 */
@Composable
fun MessageActionBar(
    contentToCopy: String,
    onRegenerate: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp)
    ) {
        // 1. Copy Action
        ActionIcon(
            icon = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = "Copy message",
            tint = if (isCopied) AuraTokens.Success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Agent Response", contentToCopy)
                clipboard.setPrimaryClip(clip)
                isCopied = true
                scope.launch {
                    delay(2000)
                    isCopied = false
                }
            }
        )

        // 2. Share Action
        ActionIcon(
            icon = Icons.Default.Share,
            contentDescription = "Share",
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, contentToCopy)
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share response"))
            }
        )

        // 3. Audio Readout (TTS)
        if (onSpeak != null) {
            ActionIcon(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Read aloud",
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSpeak()
                }
            )
        }

        // 4. Regenerate Turn
        if (onRegenerate != null) {
            ActionIcon(
                icon = Icons.Default.Refresh,
                contentDescription = "Regenerate answer",
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRegenerate()
                }
            )
        }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    onClick: () -> Unit
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    )
}
