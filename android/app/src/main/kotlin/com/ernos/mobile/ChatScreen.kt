package com.ernos.mobile

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm:             ChatViewModel = viewModel(),
    onOpenSettings: () -> Unit    = {},
    onOpenModelHub: () -> Unit    = {},
) {
    val modelStatus    by vm.modelStatus
    val statusMessage  by vm.statusMessage
    val isGenerating   by vm.isGenerating
    val glassesLive    by vm.glassesLive
    val glassesLabel   by vm.glassesLabel
    val glassesFrame   by vm.glassesFrame
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var showModelDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Default to app-specific external files dir — no runtime permission required
    // on Android 10+ (API 29+). Users can also enter any path manually.
    var modelPathText by remember {
        mutableStateOf(
            (context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath)
                + "/model.gguf"
        )
    }

    // Auto-scroll to bottom when messages change
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.size - 1)
        }
    }

    // Also scroll when the last message content changes (streaming)
    val lastContent = vm.messages.lastOrNull()?.content
    LaunchedEffect(lastContent) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ErnOS", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (modelStatus) {
                                ModelStatus.READY    -> Color(0xFF4CAF50)
                                ModelStatus.LOADING  -> MaterialTheme.colorScheme.secondary
                                ModelStatus.ERROR    -> MaterialTheme.colorScheme.error
                                else                 -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                actions = {
                    // ── Glasses Live toggle ──────────────────────────────────
                    IconButton(
                        onClick = {
                            if (glassesLive) vm.disableGlasses() else vm.enableGlasses()
                        }
                    ) {
                        Icon(
                            imageVector = if (glassesLive)
                                Icons.Default.Videocam
                            else
                                Icons.Default.VideocamOff,
                            contentDescription = if (glassesLive) "Glasses on" else "Glasses off",
                            tint = if (glassesLive) Color(0xFF4CAF50)
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ── Model Hub button ─────────────────────────────────────
                    IconButton(onClick = onOpenModelHub) {
                        Icon(
                            imageVector        = Icons.Default.Hub,
                            contentDescription = "Model Hub",
                        )
                    }
                    // ── Model load button ────────────────────────────────────
                    IconButton(onClick = { showModelDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Load model",
                        )
                    }
                    // ── Settings button ──────────────────────────────────────
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector        = Icons.Default.Settings,
                            contentDescription = "Settings",
                        )
                    }
                    // Loading indicator
                    AnimatedVisibility(visible = modelStatus == ModelStatus.LOADING) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
        bottomBar = {
            InputBar(
                text            = inputText,
                isGenerating    = isGenerating,
                modelReady      = modelStatus == ModelStatus.READY,
                onTextChange    = { inputText = it },
                onSend          = {
                    vm.sendMessage(inputText)
                    inputText = ""
                },
                onStop          = { vm.cancelGeneration() },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ── Glasses live frame thumbnail ─────────────────────────────────
            AnimatedVisibility(visible = glassesLive || glassesFrame != null) {
                GlassesFrameThumbnail(
                    frame  = glassesFrame,
                    label  = glassesLabel,
                    onStop = { vm.disableGlasses() },
                )
            }

            // ── Message list ─────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                if (vm.messages.isEmpty()) {
                    EmptyState(
                        modelStatus    = modelStatus,
                        onLoadModel    = { showModelDialog = true },
                        onOpenModelHub = onOpenModelHub,
                    )
                } else {
                    LazyColumn(
                        state           = listState,
                        contentPadding  = PaddingValues(vertical = 8.dp),
                        modifier        = Modifier.fillMaxSize(),
                    ) {
                        items(vm.messages, key = { it.id }) { msg ->
                            MessageBubble(message = msg)
                        }
                    }
                }
            }
        }
    }

    // ── Model path dialog ────────────────────────────────────────────────────
    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title   = { Text("Load GGUF Model") },
            text    = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the absolute path to your Qwen 3.5 GGUF file on the device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value        = modelPathText,
                        onValueChange = { modelPathText = it },
                        label        = { Text("Model path") },
                        singleLine   = false,
                        maxLines     = 3,
                        modifier     = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Default path is app-specific storage (no permission needed). " +
                        "Push the model: adb push model.gguf <path>",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showModelDialog = false
                    vm.loadModel(modelPathText.trim())
                }) { Text("Load") }
            },
            dismissButton = {
                TextButton(onClick = { showModelDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Glasses frame thumbnail ───────────────────────────────────────────────────

/**
 * A compact banner showing the latest camera frame from the Meta Ray-Ban glasses
 * and a label with the current connection state.  Tapping the close button
 * calls [onStop] to disable the glasses session.
 */
@Composable
private fun GlassesFrameThumbnail(
    frame:  com.ernos.mobile.glasses.GlassesFrame?,
    label:  String,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Camera thumbnail
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A2E)),
                contentAlignment = Alignment.Center,
            ) {
                if (frame != null) {
                    val bitmap = remember(frame.timestamp) {
                        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap             = bitmap.asImageBitmap(),
                            contentDescription = "Glasses POV",
                            modifier           = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                } else {
                    // Spinner while waiting for first frame
                    CircularProgressIndicator(
                        modifier    = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color       = Color(0xFF4CAF50),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "Ray-Ban Glasses",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text  = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (label.contains("live"))
                        Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (frame != null) {
                    Text(
                        text  = "${frame.width}×${frame.height}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // Stop glasses button
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector        = Icons.Default.BluetoothDisabled,
                    contentDescription = "Stop glasses",
                    tint               = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ── Message bubble ───────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.isUser
    Row(
        modifier         = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            AiAvatar()
            Spacer(Modifier.width(8.dp))
        }
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            shape    = RoundedCornerShape(
                topStart     = if (isUser) 16.dp else 4.dp,
                topEnd       = if (isUser) 4.dp  else 16.dp,
                bottomStart  = 16.dp,
                bottomEnd    = 16.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Main response text
                Text(
                    text  = message.content.ifBlank { if (message.isStreaming) "" else "…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Tool activity label (shown while ReAct loop is calling a tool)
                if (!isUser && message.toolActivity != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = message.toolActivity,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (message.isStreaming) {
                    StreamingCursor()
                }
            }
        }
    }
}

@Composable
private fun AiAvatar() {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = "E",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun StreamingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor_alpha",
    )
    Text(
        text  = "▍",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
    )
}

// ── Input bar ────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    text: String,
    isGenerating: Boolean,
    modelReady: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        tonalElevation = 4.dp,
        modifier       = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier          = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value         = text,
                onValueChange = onTextChange,
                placeholder   = {
                    Text(
                        if (!modelReady) "Load a model to start…" else "Message ErnOS…",
                    )
                },
                enabled       = modelReady && !isGenerating,
                maxLines      = 5,
                shape         = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }),
                modifier      = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )

            if (isGenerating) {
                IconButton(
                    onClick  = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                ) {
                    Icon(
                        imageVector        = Icons.Default.Stop,
                        contentDescription = "Stop generation",
                        tint               = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                IconButton(
                    onClick  = onSend,
                    enabled  = text.isNotBlank() && modelReady,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (text.isNotBlank() && modelReady)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                ) {
                    Icon(
                        imageVector        = Icons.Default.Send,
                        contentDescription = "Send",
                        tint               = if (text.isNotBlank() && modelReady)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(
    modelStatus:    ModelStatus,
    onLoadModel:    () -> Unit,
    onOpenModelHub: () -> Unit = {},
) {
    Column(
        modifier              = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center,
    ) {
        Text("🧠", style = MaterialTheme.typography.displayLarge)
        Text(
            text  = "ErnOS",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text  = "On-device AI — fully private",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (modelStatus != ModelStatus.READY) {
            // Primary action: browse & download from HuggingFace
            Button(
                onClick  = onOpenModelHub,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Icon(Icons.Default.Hub, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Browse & Download Models")
            }
            // Secondary action: load a local file
            TextButton(
                onClick  = onLoadModel,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Load local GGUF file")
            }
        }
    }
}
