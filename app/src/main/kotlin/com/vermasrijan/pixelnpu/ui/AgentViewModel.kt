package com.vermasrijan.pixelnpu.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vermasrijan.pixelnpu.agent.Accelerator
import com.vermasrijan.pixelnpu.agent.ModelDownloader
import com.vermasrijan.pixelnpu.agent.OnDeviceGemmaAgent
import com.vermasrijan.pixelnpu.agent.litert.ModelDownload
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.measureTimedValue

sealed interface LoadState {
    data class Loading(val trying: Accelerator?) : LoadState
    data class Ready(val agent: OnDeviceGemmaAgent) : LoadState

    /** @property offer A model file the user can download to fix this, if one is missing. */
    data class Failed(val message: String, val offer: ModelDownload?) : LoadState
}

sealed interface DownloadState {
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data class Failed(val message: String) : DownloadState
}

sealed interface TranscriptEntry {
    data class Prompt(val text: String) : TranscriptEntry
    data class ToolCall(val call: String) : TranscriptEntry
    data class Answer(val text: String, val took: Duration) : TranscriptEntry
    data class Error(val message: String) : TranscriptEntry
}

data class AgentUiState(
    val load: LoadState = LoadState.Loading(trying = null),
    val download: DownloadState? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val running: Boolean = false,
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var agent: OnDeviceGemmaAgent? = null
    private var downloadJob: Job? = null

    init {
        load()
    }

    private fun load() {
        _state.update { it.copy(load = LoadState.Loading(trying = null)) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val load = try {
                val loaded = OnDeviceGemmaAgent.load(app) { accelerator ->
                    _state.update { it.copy(load = LoadState.Loading(trying = accelerator)) }
                }
                agent = loaded
                LoadState.Ready(loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoadState.Failed(e.message ?: e.toString(), OnDeviceGemmaAgent.modelToDownload(app))
            }
            _state.update { it.copy(load = load) }
        }
    }

    /** Downloads the offered model into app storage, then loads the agent. */
    fun download(hfToken: String) {
        val offer = (_state.value.load as? LoadState.Failed)?.offer ?: return
        if (downloadJob?.isActive == true) return
        _state.update { it.copy(download = DownloadState.Running(0, offer.sizeBytes)) }
        downloadJob = viewModelScope.launch {
            try {
                val dir = OnDeviceGemmaAgent.downloadDir(getApplication())
                ModelDownloader.download(offer, dir, hfToken) { done, total ->
                    _state.update { it.copy(download = DownloadState.Running(done, total)) }
                }
                _state.update { it.copy(download = null) }
                load()
            } catch (e: CancellationException) {
                _state.update { it.copy(download = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(download = DownloadState.Failed(e.message ?: e.toString())) }
            }
        }
    }

    /** Stops the download; the partial file is kept so the next attempt resumes. */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun run(input: String) {
        val agent = agent ?: return
        if (input.isBlank() || _state.value.running) return
        append(TranscriptEntry.Prompt(input), running = true)
        viewModelScope.launch {
            val entry = try {
                val (answer, took) = measureTimedValue {
                    agent.run(input) { call -> append(TranscriptEntry.ToolCall(call)) }
                }
                TranscriptEntry.Answer(answer, took)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranscriptEntry.Error(e.message ?: e.toString())
            }
            append(entry, running = false)
        }
    }

    private fun append(entry: TranscriptEntry, running: Boolean? = null) = _state.update {
        it.copy(transcript = it.transcript + entry, running = running ?: it.running)
    }

    override fun onCleared() {
        // Closing waits for any in-flight native generation, so keep it off the main thread.
        agent?.let { thread(name = "gemma-close") { it.close() } }
    }
}
