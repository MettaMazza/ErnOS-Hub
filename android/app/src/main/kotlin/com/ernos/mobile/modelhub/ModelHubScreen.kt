package com.ernos.mobile.modelhub

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ModelHubScreen
 *
 * Browse HuggingFace Hub for GGUF models.
 * Displays model cards with size and quantization metadata, and lets the
 * user download any GGUF file directly to the device's external storage
 * where [ChatViewModel.loadModel] can pick it up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHubScreen(
    onBack:      () -> Unit,
    onModelPath: (String) -> Unit = {},
    vm:          ModelHubViewModel = viewModel(),
) {
    val isSearching  by vm.isSearching
    val searchError  by vm.searchError
    val searchQuery  by vm.searchQuery
    var queryDraft   by remember(searchQuery) { mutableStateOf(searchQuery) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Hub") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Search bar ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value         = queryDraft,
                    onValueChange = { queryDraft = it },
                    modifier      = Modifier.weight(1f),
                    label         = { Text("Search models") },
                    singleLine    = true,
                    leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search(queryDraft) }),
                )
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            // ── Error banner ──────────────────────────────────────────────────
            AnimatedVisibility(visible = searchError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color    = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text     = searchError ?: "",
                        modifier = Modifier.padding(12.dp),
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                        style    = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ── Result count ──────────────────────────────────────────────────
            if (!isSearching && vm.models.isNotEmpty()) {
                Text(
                    text     = "${vm.models.size} models found",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Model list ────────────────────────────────────────────────────
            if (vm.models.isEmpty() && !isSearching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = if (searchError != null) "Search failed — check your connection"
                                else "No models found. Try a different query.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier       = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 4.dp
                    ),
                ) {
                    items(vm.models, key = { it.modelId }) { model ->
                        ModelCard(
                            model       = model,
                            downloads   = vm.downloads,
                            onDownload  = { file -> vm.downloadFile(model, file) },
                            onCancel    = { file -> vm.cancelDownload("${model.modelId}/${file.name}") },
                            onLoadModel = onModelPath,
                        )
                    }
                }
            }
        }
    }
}

// ── Model card ─────────────────────────────────────────────────────────────────

@Composable
private fun ModelCard(
    model:       HuggingFaceModel,
    downloads:   Map<String, DownloadState>,
    onDownload:  (GgufFile) -> Unit,
    onCancel:    (GgufFile) -> Unit,
    onLoadModel: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        onClick   = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = model.modelId,
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text  = "by ${model.author}  •  ↓${formatCount(model.downloads)}  •  ♥${model.likes}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text  = "${model.ggufFiles.size} GGUF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Expandable file list
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    if (model.ggufFiles.isEmpty()) {
                        Text(
                            text  = "No GGUF files found in this repo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        model.ggufFiles.forEach { file ->
                            val key   = "${model.modelId}/${file.name}"
                            val state = downloads[key]
                            GgufFileRow(
                                file       = file,
                                state      = state,
                                onDownload = { onDownload(file) },
                                onCancel   = { onCancel(file) },
                                onLoad     = { state?.localPath?.let(onLoadModel) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GgufFileRow(
    file:       GgufFile,
    state:      DownloadState?,
    onDownload: () -> Unit,
    onCancel:   () -> Unit,
    onLoad:     () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = file.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = "${file.quant}  •  ${file.sizeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (state?.status) {
                DownloadStatus.RUNNING -> {
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Cancel, "Cancel", tint = MaterialTheme.colorScheme.error)
                    }
                }
                DownloadStatus.DONE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint     = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onLoad) { Text("Load") }
                    }
                }
                DownloadStatus.ERROR -> {
                    IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.CloudDownload, "Retry", tint = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.CloudDownload, "Download",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // Progress bar
        if (state?.status == DownloadStatus.RUNNING) {
            LinearProgressIndicator(
                progress  = { state.progress },
                modifier  = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                text  = "${"%.0f".format(state.progress * 100)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Error message
        if (state?.status == DownloadStatus.ERROR && state.error != null) {
            Text(
                text  = "Error: ${state.error}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000     -> "%.1fK".format(n / 1_000f)
    else           -> n.toString()
}
