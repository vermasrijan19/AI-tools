package com.vermasrijan.pixelnpu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vermasrijan.pixelnpu.agent.litert.ModelDownload

@Composable
fun AgentScreen(viewModel: AgentViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Gemma 4 agent", style = MaterialTheme.typography.headlineSmall)
        LoadStatus(
            load = state.load,
            download = state.download,
            onDownload = viewModel::download,
            onCancelDownload = viewModel::cancelDownload,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.transcript) { TranscriptItem(it) }
        }

        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Is my phone running hot right now?") },
            )
            Button(
                onClick = {
                    viewModel.run(input.trim())
                    input = ""
                },
                enabled = state.load is LoadState.Ready && !state.running && input.isNotBlank(),
            ) { Text("Run") }
        }
    }
}

@Composable
private fun LoadStatus(
    load: LoadState,
    download: DownloadState?,
    onDownload: (hfToken: String) -> Unit,
    onCancelDownload: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (load) {
                is LoadState.Loading -> {
                    Text(load.trying?.let { "Loading Gemma 4 on $it…" } ?: "Looking for Gemma 4 models…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is LoadState.Ready -> {
                    val agent = load.agent
                    Text("Running on ${agent.accelerator}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${agent.modelFile.name} · loaded in ${agent.loadTime.inWholeMilliseconds} ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    agent.rejected.forEach {
                        Text("${it.accelerator} not used: ${it.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is LoadState.Failed -> {
                    if (load.offer != null) {
                        ModelDownloadPanel(load.offer, download, onDownload, onCancelDownload)
                    } else {
                        Text("Could not load Gemma 4", style = MaterialTheme.typography.titleMedium)
                        Text(load.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelDownloadPanel(
    offer: ModelDownload,
    download: DownloadState?,
    onDownload: (hfToken: String) -> Unit,
    onCancelDownload: () -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }
    val gigabytes = "%.1f GB".format(offer.sizeBytes / 1e9)

    Text("Download Gemma 4", style = MaterialTheme.typography.titleMedium)
    Text(
        "${offer.fileName} ($gigabytes) isn't on this phone yet. It downloads once from Hugging Face " +
            "into app storage; after that everything runs offline.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (download is DownloadState.Running) {
        KeepScreenOn()
        val fraction = if (download.totalBytes > 0) download.downloadedBytes.toFloat() / download.totalBytes else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "%.2f / %.2f GB".format(download.downloadedBytes / 1e9, download.totalBytes / 1e9),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onCancelDownload) { Text("Pause") }
        }
        Text(
            "Keep the app open; the screen stays on until the download finishes.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            "The model is gated: sign in at huggingface.co, accept the license on ${ModelDownload.REPO}, " +
                "then create a read token at huggingface.co/settings/tokens and paste it here.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Hugging Face token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        if (download is DownloadState.Failed) {
            Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { onDownload(token) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (download is DownloadState.Failed) "Retry download" else "Download ($gigabytes)")
        }
    }
    Text(
        "Or push the file with scripts/push-model.sh and restart the app.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun TranscriptItem(entry: TranscriptEntry) {
    when (entry) {
        is TranscriptEntry.Prompt -> Text("> ${entry.text}", style = MaterialTheme.typography.bodyLarge)
        is TranscriptEntry.ToolCall -> Text(
            "tool: ${entry.call}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        is TranscriptEntry.Answer -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(entry.text)
                Text(
                    "${entry.took.inWholeMilliseconds} ms",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        is TranscriptEntry.Error -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(entry.message, Modifier.padding(12.dp))
        }
    }
}
