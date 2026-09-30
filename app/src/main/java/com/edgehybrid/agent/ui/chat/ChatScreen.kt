package com.edgehybrid.agent.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.provider.OpenableColumns
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgehybrid.agent.R
import com.edgehybrid.agent.hardware.vision.ImageCompressor
import com.edgehybrid.agent.hardware.vision.MultimodalPromptEnricher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatRoute(
    viewModel: ChatViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChatScreen(
        state = state,
        onSend = { text, imageDataUrl -> viewModel.send(text, imageDataUrl) },
        onCancel = viewModel::cancelGeneration,
        onClearChat = viewModel::clearChat,
        onNewChat = viewModel::createNewSession,
        onSelectSession = viewModel::selectSession,
        onDeleteSession = viewModel::deleteSession,
        onToggleDrawer = viewModel::toggleSessionDrawer,
        onRetry = viewModel::retryRecovery,
        onTranscribeAudio = viewModel::transcribeMeetingAudio,
        onOpenSettings = onOpenSettings
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String, String?) -> Unit,
    onCancel: () -> Unit = {},
    onClearChat: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onSelectSession: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onToggleDrawer: (Boolean?) -> Unit = {},
    onRetry: () -> Unit,
    onTranscribeAudio: (ByteArray, String, (String) -> Unit, (String) -> Unit) -> Unit = { _, _, _, _ -> },
    onOpenSettings: () -> Unit = {}
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var attachedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var attachedDataUrl by remember { mutableStateOf<String?>(null) }
    var attachedAudioName by remember { mutableStateOf<String?>(null) }
    var attachedAudioBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isTranscribingAudio by remember { mutableStateOf(false) }
    var audioTranscriptionError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val promptEnricher = remember { MultimodalPromptEnricher(ImageCompressor()) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Activity launcher for Meeting Audio file picker
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(it)?.use { stream ->
                        stream.readBytes()
                    }
                    var fileName = "meeting_recording.m4a"
                    context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                        val colIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (colIdx >= 0 && cursor.moveToFirst()) {
                            fileName = cursor.getString(colIdx) ?: fileName
                        }
                    }
                    withContext(Dispatchers.Main) {
                        attachedAudioBytes = bytes
                        attachedAudioName = fileName
                        audioTranscriptionError = null
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        audioTranscriptionError = "Failed to open audio: ${e.message}"
                    }
                }
            }
        }
    }

    // Activity launcher for Gallery photo picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, it))
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                    }
                    val dataUrl = promptEnricher.createVisionDataUrl(bitmap)
                    withContext(Dispatchers.Main) {
                        attachedBitmap = bitmap
                        attachedDataUrl = dataUrl
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Activity launcher for Camera photo capture
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val dataUrl = promptEnricher.createVisionDataUrl(it)
                    withContext(Dispatchers.Main) {
                        attachedBitmap = it
                        attachedDataUrl = dataUrl
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Activity launcher for Speech-to-Text
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                draft = if (draft.isBlank()) spoken else "$draft $spoken"
            }
        }
    }

    val lastMessageSignature = state.messages.lastOrNull()?.let { message ->
        "${message.id}:${message.content.length}:${message.deliveryState}:${message.recoveryMessage}"
    }

    LaunchedEffect(lastMessageSignature, state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onToggleDrawer(true) }) {
                        Icon(Icons.Default.Share, contentDescription = "Conversations Switcher")
                    }
                },
                title = {
                    val activeSession = state.sessions.firstOrNull { it.id == state.currentSessionId }
                    Column(modifier = Modifier.clickable { onToggleDrawer(true) }) {
                        Text(
                            text = activeSession?.title ?: "Edge Hybrid Agent",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Text(
                            text = "Multimodal Vision • Groq Whisper • 30 Tools",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Default.Add, contentDescription = "New Chat")
                    }
                    IconButton(onClick = onClearChat) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Chat History")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Check, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            if (state.isGenerating) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.messages.isEmpty()) {
                com.edgehybrid.agent.ui.chats.ChatsLandingView(
                    sessions = state.sessions,
                    onSelectSession = onSelectSession,
                    onStartNewChat = onNewChat,
                    onLaunchCamera = {
                        onSend("Please open the camera viewfinder", null)
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = state.messages,
                        key = ChatMessageUi::id
                    ) { message ->
                        MessageBubble(
                            message = message,
                            onRetry = onRetry
                        )
                    }
                }
            }

            state.errorMessage?.let { errorMessage ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = errorMessage,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            HorizontalDivider()

            MessageComposer(
                draft = draft,
                enabled = !state.isGenerating,
                isGenerating = state.isGenerating,
                attachedBitmap = attachedBitmap,
                attachedAudioName = attachedAudioName,
                isTranscribingAudio = isTranscribingAudio,
                audioTranscriptionError = audioTranscriptionError,
                onCancel = onCancel,
                onRemoveAttachment = {
                    attachedBitmap = null
                    attachedDataUrl = null
                },
                onPickPhoto = { photoPickerLauncher.launch("image/*") },
                onPickAudio = { audioPickerLauncher.launch("audio/*") },
                onTranscribeAudio = {
                    val bytes = attachedAudioBytes
                    val name = attachedAudioName ?: "meeting.m4a"
                    if (bytes != null) {
                        isTranscribingAudio = true
                        audioTranscriptionError = null
                        onTranscribeAudio(
                            bytes,
                            name,
                            { transcript ->
                                isTranscribingAudio = false
                                val prefix = if (draft.isNotBlank()) "$draft\n\n" else ""
                                draft = "${prefix}Meeting Recording Transcription:\n\"\"\"\n$transcript\n\"\"\"\n\nPlease analyze and summarize this meeting with key decisions and action items."
                                attachedAudioBytes = null
                                attachedAudioName = null
                            },
                            { err ->
                                isTranscribingAudio = false
                                audioTranscriptionError = err
                            }
                        )
                    }
                },
                onRemoveAudio = {
                    attachedAudioBytes = null
                    attachedAudioName = null
                    audioTranscriptionError = null
                },
                onTakePhoto = { cameraLauncher.launch(null) },
                onStartSpeechToText = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Edge Hybrid Agent...")
                    }
                    try {
                        speechLauncher.launch(intent)
                    } catch (_: ActivityNotFoundException) {}
                },
                onDraftChanged = { draft = it },
                onSend = {
                    if ((draft.isNotBlank() || attachedDataUrl != null) && !state.isGenerating) {
                        val textToSend = if (draft.isBlank() && attachedDataUrl != null) {
                            "Analyze this image in detail and describe what you see."
                        } else {
                            draft
                        }
                        onSend(textToSend, attachedDataUrl)
                        draft = ""
                        attachedBitmap = null
                        attachedDataUrl = null
                    }
                }
            )
        }

        if (state.isSessionDrawerOpen) {
            ModalBottomSheet(
                onDismissRequest = { onToggleDrawer(false) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Conversations",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Button(
                            onClick = onNewChat,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Chat")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                    ) {
                        items(state.sessions, key = { it.id }) { session ->
                            val isSelected = session.id == state.currentSessionId
                            Surface(
                                onClick = { onSelectSession(session.id) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onDeleteSession(session.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Delete chat",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessageUi,
    onRetry: () -> Unit
) {
    val isUser = message.role == ChatMessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .widthIn(max = 620.dp)
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
            ) {
                // If message has an image attached, render thumbnail preview
                if (!message.imageDataUrl.isNullOrBlank()) {
                    val bitmap = remember(message.imageDataUrl) {
                        try {
                            val base64Data = message.imageDataUrl.substringAfter("base64,")
                            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                if (message.toolActivities.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    message.toolActivities.forEach { activity ->
                        ToolActivityRow(activity)
                    }
                }

                if (
                    message.deliveryState == MessageDeliveryState.RECOVERY_REQUIRED &&
                    !stateIsGenerating(message)
                ) {
                    Spacer(modifier = Modifier.height(10.dp))
                    RecoveryAction(
                        message = message.recoveryMessage
                            ?: stringResource(R.string.recovery_required),
                        onRetry = onRetry
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolActivityRow(activity: ToolActivityUi) {
    val label = when (activity.status) {
        ToolActivityStatus.RUNNING ->
            stringResource(R.string.tool_running, activity.toolName)

        ToolActivityStatus.SUCCEEDED ->
            stringResource(R.string.tool_completed, activity.toolName)

        ToolActivityStatus.FAILED ->
            stringResource(R.string.tool_failed, activity.toolName)
    }

    Text(
        text = "• $label",
        style = MaterialTheme.typography.bodySmall,
        color = if (activity.status == ToolActivityStatus.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@Composable
private fun RecoveryAction(
    message: String,
    onRetry: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onRetry
            ) {
                Text(text = stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun MessageComposer(
    draft: String,
    enabled: Boolean,
    isGenerating: Boolean = false,
    attachedBitmap: Bitmap?,
    attachedAudioName: String? = null,
    isTranscribingAudio: Boolean = false,
    audioTranscriptionError: String? = null,
    onCancel: () -> Unit = {},
    onRemoveAttachment: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickAudio: () -> Unit = {},
    onTranscribeAudio: () -> Unit = {},
    onRemoveAudio: () -> Unit = {},
    onTakePhoto: () -> Unit,
    onStartSpeechToText: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Attached Image Thumbnail Preview Bar
            if (attachedBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = attachedBitmap.asImageBitmap(),
                            contentDescription = "Image preview",
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Photo attached (Vision / OCR ready)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onRemoveAttachment,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove photo",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Attached Audio Meeting Recording Preview Bar
            if (attachedAudioName != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎙️", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachedAudioName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = if (isTranscribingAudio) "Transcribing with Groq Whisper Large..." else "Groq Whisper Large v3 ready",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        if (isTranscribingAudio) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        } else {
                            Button(
                                onClick = onTranscribeAudio,
                                modifier = Modifier.height(32.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Transcribe", fontSize = 11.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onRemoveAudio,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove audio",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Audio Transcription Error Banner
            if (audioTranscriptionError != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Text(
                        text = audioTranscriptionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Input Row with Action Icons & Send Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Gallery Attachment Icon
                IconButton(
                    onClick = onPickPhoto,
                    enabled = enabled,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attach photo from gallery",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Meeting Recording Audio Attachment Icon
                IconButton(
                    onClick = onPickAudio,
                    enabled = enabled,
                    modifier = Modifier.size(36.dp)
                ) {
                    Text("🎙️", fontSize = 18.sp)
                }

                // Microphone / Speech-to-Text Icon
                IconButton(
                    onClick = onStartSpeechToText,
                    enabled = enabled,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Speak to agent (Voice Input)",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChanged,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = {
                        Text(
                            text = if (attachedBitmap != null) "Ask about this photo or request OCR..." else stringResource(R.string.chat_input_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if ((draft.isNotBlank() || attachedBitmap != null) && enabled) {
                                onSend()
                            }
                        }
                    )
                )

                if (isGenerating) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Stop")
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = enabled && (draft.isNotBlank() || attachedBitmap != null)
                    ) {
                        Text(text = stringResource(R.string.send))
                    }
                }
            }
        }
    }
}

private fun stateIsGenerating(message: ChatMessageUi): Boolean =
    message.deliveryState == MessageDeliveryState.STREAMING

private fun stateIsGeneratingFromButton(enabled: Boolean): Boolean =
    !enabled