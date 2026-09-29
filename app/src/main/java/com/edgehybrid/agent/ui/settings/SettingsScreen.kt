package com.edgehybrid.agent.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgehybrid.agent.data.local.LessonEntity
import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.data.local.SecureKeyStore
import kotlinx.coroutines.launch

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
    var selectedModel by remember { mutableStateOf(keyStore.getSelectedCloudModel()) }
    var jevEnabled by remember { mutableStateOf(keyStore.isJevRoutingEnabled()) }
    var localFallbackEnabled by remember { mutableStateOf(keyStore.isLocalFallbackEnabled()) }

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
            item {
                Text(
                    text = "API Credentials (Encrypted on Device)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                OutlinedTextField(
                    value = openRouterKey,
                    onValueChange = { openRouterKey = it },
                    label = { Text("OpenRouter API Key") },
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
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini Direct API Key") },
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

            item {
                Button(
                    onClick = {
                        keyStore.setOpenRouterApiKey(openRouterKey)
                        keyStore.setTypeSafeApiKey(typeSafeKey)
                        keyStore.setGeminiApiKey(geminiKey)
                        keyStore.setGroqApiKey(groqKey)
                        keyStore.setSelectedCloudModel(selectedModel)
                        keyStore.setJevRoutingEnabled(jevEnabled)
                        keyStore.setLocalFallbackEnabled(localFallbackEnabled)
                        savedSnackbar = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Configuration")
                }
            }

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
                    Text(
                        "No lessons recorded yet. The agent logs rules automatically when failures or false positives occur.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
