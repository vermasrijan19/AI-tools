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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vermasrijan.pixelnpu.agent.OnDeviceGemmaAgent

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
        LoadStatus(state.load)

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
private fun LoadStatus(load: LoadState) {
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
                    Text("Could not load Gemma 4", style = MaterialTheme.typography.titleMedium)
                    Text(load.message, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Push a model with scripts/push-model.sh (to ${OnDeviceGemmaAgent.ADB_MODELS_DIR}) and restart the app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
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
