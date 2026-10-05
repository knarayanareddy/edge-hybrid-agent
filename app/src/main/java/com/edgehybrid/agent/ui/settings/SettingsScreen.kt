package com.edgehybrid.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.data.local.LessonEntity
import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.data.local.SecureKeyStore
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.foundation.layout.Arrangement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    keyStore: SecureKeyStore,
    lessonsDao: LessonsDao,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var openRouterKey by remember { mutableStateOf(keyStore.getOpenRouterApiKey()) }
    var typeSafeKey by remember { mutableStateOf(keyStore.getTypeSafeApiKey()) }
    var geminiKey by remember { mutableStateOf(keyStore.getGeminiApiKey()) }
    var groqKey by remember { mutableStateOf(keyStore.getGroqApiKey()) }
    var telegramBotToken by remember { mutableStateOf(keyStore.getTelegramBotToken()) }
    var telegramChatId by remember { mutableStateOf(keyStore.getTelegramChatId()) }
    var selectedModel by remember { mutableStateOf(keyStore.getSelectedCloudModel()) }
    var jevEnabled by remember { mutableStateOf(keyStore.isJevRoutingEnabled()) }
    var localFallbackEnabled by remember { mutableStateOf(keyStore.isLocalFallbackEnabled()) }

    /** "openrouter" | "google_ai_studio" */
    var preferredProvider by remember { mutableStateOf(keyStore.getPreferredProvider()) }

    var lessons by remember { mutableStateOf<List<LessonEntity>>(emptyList()) }
    var savedSnackbar by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lessons = lessonsDao.getActiveLessons(15)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Models") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = {
            if (savedSnackbar) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { savedSnackbar = false }) { Text("OK") }
                    }
                ) {
                    Text("Settings saved successfully!")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Provider Picker ────────────────────────────────────────────
            item {
                Text(
                    text = "Inference Provider",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Choose where your prompts are sent",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                ProviderSegmentedButtons(
                    selectedProvider = preferredProvider,
                    onProviderSelected = { preferredProvider = it }
                )
            }

            // ── Contextual model hint ──────────────────────────────────────
            item {
                if (preferredProvider == SecureKeyStore.PROVIDER_GOOGLE_AI_STUDIO) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Google AI Studio mode",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Calls generativelanguage.googleapis.com directly using your Gemini API key — no OpenRouter credits needed. " +
                                "Recommended model: gemini-2.5-flash or gemini-2.0-flash-exp",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "OpenRouter mode",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                "Routes to 300+ models via openrouter.ai. Requires credits. " +
                                "Default model: google/gemini-3.8-flash",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── Model ID ──────────────────────────────────────────────────
            item {
                OutlinedTextField(
                    value = selectedModel,
                    onValueChange = { selectedModel = it },
                    label = { Text("Model ID") },
                    placeholder = {
                        Text(
                            if (preferredProvider == SecureKeyStore.PROVIDER_GOOGLE_AI_STUDIO)
                                "e.g. gemini-2.5-flash"
                            else
                                "e.g. google/gemini-3.8-flash"
                        )
                    },
                    supportingText = {
                        Text(
                            if (preferredProvider == SecureKeyStore.PROVIDER_GOOGLE_AI_STUDIO)
                                "No provider prefix for Google AI Studio models"
                            else
                                "Use provider/model-name format for OpenRouter"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── API Credentials ───────────────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "API Credentials (Encrypted on Device)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key (Google AI Studio)") },
                    placeholder = { Text("AIza...") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Get yours free at aistudio.google.com/apikey") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = openRouterKey,
                    onValueChange = { openRouterKey = it },
                    label = { Text("OpenRouter API Key") },
                    placeholder = { Text("sk-or-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = typeSafeKey,
                    onValueChange = { typeSafeKey = it },
                    label = { Text("TypeSafe JEV API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = groqKey,
                    onValueChange = { groqKey = it },
                    label = { Text("Groq Whisper API Key (Free STT)") },
                    placeholder = { Text("gsk_...") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Used for Whisper Large meeting transcription (free at console.groq.com)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── External Integrations ──────────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "External Integrations",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Telegram bot messaging and meeting notes dispatch",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                OutlinedTextField(
                    value = telegramBotToken,
                    onValueChange = { telegramBotToken = it },
                    label = { Text("Telegram Bot Token (Optional)") },
                    placeholder = { Text("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Create via @BotFather on Telegram to send direct bot messages") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = telegramChatId,
                    onValueChange = { telegramChatId = it },
                    label = { Text("Telegram Default Chat ID (Optional)") },
                    placeholder = { Text("e.g. 987654321 or @yourchannel") },
                    supportingText = { Text("Your user ID or channel where the agent should send summaries") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Routing Toggles ────────────────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Inference & Routing",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("JEV System 1 Smart Routing", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Intelligently routes prompts to on-device LiteRT or cloud models",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = jevEnabled, onCheckedChange = { jevEnabled = it })
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Local LiteRT Engine Fallback", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Allows running lightweight tasks offline via MediaPipe/LiteRT",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = localFallbackEnabled, onCheckedChange = { localFallbackEnabled = it })
                }
            }

            // ── Save ───────────────────────────────────────────────────────
            item {
                Button(
                    onClick = {
                        keyStore.setOpenRouterApiKey(openRouterKey)
                        keyStore.setTypeSafeApiKey(typeSafeKey)
                        keyStore.setGeminiApiKey(geminiKey)
                        keyStore.setGroqApiKey(groqKey)
                        keyStore.setTelegramBotToken(telegramBotToken)
                        keyStore.setTelegramChatId(telegramChatId)
                        keyStore.setSelectedCloudModel(selectedModel)
                        keyStore.setJevRoutingEnabled(jevEnabled)
                        keyStore.setLocalFallbackEnabled(localFallbackEnabled)
                        keyStore.setPreferredProvider(preferredProvider)
                        savedSnackbar = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Configuration")
                }
            }

            // ── Lessons Ledger ─────────────────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Self-Correcting Lessons Ledger (${lessons.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Rules auto-learned by the agent to prevent repeating errors",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (lessons.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Psychology,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "No corrections learned yet",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                            Text(
                                text = "When a tool call fails, you decline an action, or the " +
                                    "provider rejects a request, the agent writes a rule here and " +
                                    "carries it into every later prompt. Transient failures such as " +
                                    "timeouts and rate limits are ignored so a bad network moment " +
                                    "cannot permanently change its behaviour.",
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(lessons) { lesson ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Rule: ${lesson.rule}",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Category: ${lesson.category} • Occurrences: ${lesson.frequency}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Provider picker ──────────────────────────────────────────────────────────

@Composable
private fun ProviderSegmentedButtons(
    selectedProvider: String,
    onProviderSelected: (String) -> Unit
) {
    val options = listOf(
        SecureKeyStore.PROVIDER_OPENROUTER to "OpenRouter",
        SecureKeyStore.PROVIDER_GOOGLE_AI_STUDIO to "Google AI Studio"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    ) {
        options.forEachIndexed { index, (key, label) ->
            val isSelected = selectedProvider == key
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable { onProviderSelected(key) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                )
            }
            // Divider between items
            if (index < options.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }
    }
}
