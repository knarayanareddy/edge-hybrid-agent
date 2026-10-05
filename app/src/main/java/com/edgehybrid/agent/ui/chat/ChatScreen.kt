package com.edgehybrid.agent.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgehybrid.agent.R
import com.edgehybrid.agent.hardware.vision.ImageCompressor
import com.edgehybrid.agent.hardware.vision.MultimodalPromptEnricher
import com.edgehybrid.agent.ui.components.ActionConfirmationDialog
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
        onConfirmAction = viewModel::confirmPendingAction,
        onDeclineAction = viewModel::declinePendingAction,
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
    onConfirmAction: () -> Unit = {},
    onDeclineAction: () -> Unit = {},
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
        containerColor = MaterialTheme.colorScheme.surface
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
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)
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

        // Blocking gate for any non-read-only agent action. The agent loop is suspended
        // until the user answers, so this must stay mounted while the action is pending.
        state.pendingConfirmation?.let { pending ->
            ActionConfirmationDialog(
                confirmation = pending.confirmation,
                onConfirm = onConfirmAction,
                onDismiss = onDeclineAction
            )
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
        // ChatGPT-style: the user gets a filled bubble, the assistant gets plain
        // text on the canvas. Two filled cards of different colours read as a form;
        // one filled and one bare reads as a conversation.
        //
        // Asymmetric corner radii point the bubble at its author — the tail corner
        // is squared so the bubble visually grows out of the side it belongs to.
        val bubbleShape = if (isUser) {
            RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomEnd = 6.dp, bottomStart = 20.dp
            )
        } else {
            RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomEnd = 20.dp, bottomStart = 6.dp
            )
        }
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            shape = bubbleShape,
            modifier = Modifier
                // Assistant text is a document: cap the measure so lines stay
                // readable, and let user bubbles stay narrow like real chat.
                .widthIn(max = 680.dp)
                .then(
                    if (isUser) Modifier.fillMaxWidth(0.86f) else Modifier.fillMaxWidth()
                )
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
                    // Assistant replies are markdown; user input is not. Rendering both
                    // through the same parser would italicise a user's `snake_case`
                    // and turn a typed `**` into stray styling.
                    Text(
                        text = if (isUser) {
                            AnnotatedString(message.content)
                        } else {
                            MarkdownRenderer.markdown(
                                message.content,
                                MaterialTheme.typography.bodyLarge.fontSize
                            )
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            // ChatGPT-sized measure: 1.55 on a 16sp base reads as
                            // set body copy rather than the 1.0 default, which is
                            // why dense assistant replies looked cramped.
                            lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.55f
                        )
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
    // Apple-style floating composer: a rounded field that sits ON the canvas with a
    // hairline ring, instead of a full-bleed raised slab. The removal of
    // `tonalElevation` is deliberate — tonal elevation tinted the whole bar, which
    // is the dated-Material tell. A 1dp outline plus a soft shadow reads cleaner
    // and separates the input from content without a colour block.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(26.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant
            ),
            shadowElevation = 2.dp,
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

                // Borderless on purpose. The enclosing pill is already the outline,
                // so an OutlinedTextField here rendered a second border inside it and
                // squeezed Send against the pill's rounded edge.
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChanged,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 4,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) {
                            Text(
                                text = if (attachedBitmap != null) {
                                    "Ask about this photo or request OCR..."
                                } else {
                                    stringResource(R.string.chat_input_hint)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        inner()
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if ((draft.isNotBlank() || attachedBitmap != null) && enabled) onSend()
                        }
                    )
                )


                // Compact and icon-led. The previous full-size Button was ~48dp tall
                // inside a 36dp row, so it broke out of the pill's rounded end and
                // pushed the layout wider than the screen.
                val canSend = enabled && (draft.isNotBlank() || attachedBitmap != null)
                if (isGenerating) {
                    FilledIconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop generating",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                            modifier = Modifier.size(18.dp)
                        )
                    }
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