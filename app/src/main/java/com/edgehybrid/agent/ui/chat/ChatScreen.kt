package com.edgehybrid.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgehybrid.agent.R

@Composable
fun ChatRoute(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChatScreen(
        state = state,
        onSend = viewModel::send,
        onRetry = viewModel::retryRecovery
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onRetry: () -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

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
                title = {
                    Text(
                        text = stringResource(R.string.chat_title),
                        fontWeight = FontWeight.SemiBold
                    )
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
                onDraftChanged = { draft = it },
                onSend = {
                    if (draft.isNotBlank() && !state.isGenerating) {
                        onSend(draft)
                        draft = ""
                    }
                }
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
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.verticalScroll(rememberScrollState())
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
        style = MaterialTheme.typography.labelMedium,
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
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = {
                    Text(text = stringResource(R.string.chat_input_hint))
                },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (draft.isNotBlank() && enabled) {
                            onSend()
                        }
                    }
                )
            )

            Button(
                onClick = onSend,
                enabled = enabled && draft.isNotBlank()
            ) {
                if (stateIsGeneratingFromButton(enabled)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(text = stringResource(R.string.send))
                }
            }
        }
    }
}

private fun stateIsGenerating(message: ChatMessageUi): Boolean =
    message.deliveryState == MessageDeliveryState.STREAMING

private fun stateIsGeneratingFromButton(enabled: Boolean): Boolean = !enabled