package com.symbioza.daymind.state

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.symbioza.daymind.DayMindApplication
import com.symbioza.daymind.audio.RecordingService
import com.symbioza.daymind.config.AudioSettings
import com.symbioza.daymind.upload.SyncStatus
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val isRecording: Boolean = false,
    val pendingChunks: Int = 0,
    val syncMessage: String = "Waiting",
    val isSyncing: Boolean = false,
    val canPlayChunk: Boolean = false,
    val isPlayingBack: Boolean = false,
    val hasArchiveToShare: Boolean = false,
    val canShareChunk: Boolean = false,
    val chunks: List<ChunkSummary> = emptyList(),
    val vadThreshold: Int = 3500,
    val vadAggressiveness: Int = 2,
    val noiseGate: Float = 0.12f,
    val logEntries: List<String> = emptyList(),
    val transcripts: List<TranscriptSummary> = emptyList(),
    val summaryDate: String = "",
    val summaryText: String = "",
    val summaryUpdatedAt: Long? = null,
    val summaryError: String? = null,
    val isSummaryLoading: Boolean = false
)

data class ChunkSummary(
    val id: String,
    val createdAt: Long,
    val externalPath: String,
    val uploaded: Boolean
)

private data class BaseUiState(
    val isRecording: Boolean,
    val pendingChunks: Int,
    val syncStatus: SyncStatus,
    val latestChunkPath: String?,
    val latestExternalPath: String?
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (getApplication() as DayMindApplication).container
    private val audioSettingsFlow = MutableStateFlow(container.configRepository.getAudioSettings())
    private val summaryState = MutableStateFlow(SummaryUiState(date = currentUtcDate()))
    val snackbarFlow: SharedFlow<String> = container.logStore.events

    private val baseFlow = combine(
        container.recordingStateStore.isRecording,
        container.chunkRepository.pendingCount,
        container.syncStatusStore.status,
        container.chunkRepository.latestChunkPath,
        container.chunkRepository.latestExternalPath
    ) { recording, pending, syncStatus, latestChunkPath, latestExternal ->
        BaseUiState(
            isRecording = recording,
            pendingChunks = pending,
            syncStatus = syncStatus,
            latestChunkPath = latestChunkPath,
            latestExternalPath = latestExternal
        )
    }

    private val transcriptsFlow = container.transcriptStore.entriesFlow

    init {
        refreshSummary()
    }

    val uiState: StateFlow<UiState> = combine(
        baseFlow,
        container.chunkRepository.chunkList,
        container.chunkPlaybackManager.isPlaying,
        audioSettingsFlow,
        container.logStore.entries,
        transcriptsFlow,
        summaryState
    ) { base, chunks, isPlaying, audioSettings, logEntries, transcripts, summary ->
        UiState(
            isRecording = base.isRecording,
            pendingChunks = base.pendingChunks,
            syncMessage = base.syncStatus.message,
            isSyncing = base.syncStatus.isSyncing,
            canPlayChunk = !base.latestChunkPath.isNullOrBlank(),
            isPlayingBack = isPlaying,
            hasArchiveToShare = !base.syncStatus.latestArchivePath.isNullOrBlank(),
            canShareChunk = !base.latestExternalPath.isNullOrBlank(),
            chunks = chunks.map {
                ChunkSummary(
                    id = it.id,
                    createdAt = it.createdAt,
                    externalPath = it.externalPath,
                    uploaded = it.uploaded
                )
            },
            vadThreshold = audioSettings.vadThreshold,
            vadAggressiveness = audioSettings.vadAggressiveness,
            noiseGate = audioSettings.noiseGate,
            logEntries = logEntries,
            transcripts = transcripts.map {
                TranscriptSummary(
                    id = it.id,
                    timestamp = it.timestamp,
                    summary = it.summary,
                    fullText = it.fullText,
                    chunkId = it.chunkId,
                    srtPath = it.srtPath
                )
            },
            summaryDate = summary.date,
            summaryText = summary.text,
            summaryUpdatedAt = summary.updatedAt,
            summaryError = summary.error,
            isSummaryLoading = summary.isLoading
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState(
            summaryDate = summaryState.value.date,
            summaryText = summaryState.value.text,
            summaryUpdatedAt = summaryState.value.updatedAt,
            summaryError = summaryState.value.error,
            isSummaryLoading = summaryState.value.isLoading
        )
    )

    fun toggleRecording() {
        if (uiState.value.isRecording) {
            RecordingService.stop(getApplication())
        } else {
            RecordingService.start(getApplication())
        }
    }

    fun playLatestChunk() {
        val path = container.chunkRepository.latestChunkPath.value ?: return
        container.chunkPlaybackManager.play(File(path))
    }

    fun stopPlayback() {
        container.chunkPlaybackManager.stop()
    }

    fun syncNow() {
        container.enqueueManualSync()
        log("Manual sync triggered")
    }

    fun shareArchive() {
        val path = container.syncStatusStore.status.value.latestArchivePath ?: return
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/flac"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(
            Intent.createChooser(intent, "Share DayMind archive")
        )
        log("Shared archive ${file.name}", notify = true)
    }

    fun shareLastChunk() {
        val path = container.chunkRepository.latestExternalPath.value ?: return
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(
            Intent.createChooser(intent, "Share latest chunk")
        )
        log("Shared latest chunk", notify = true)
    }

    fun shareChunk(summary: ChunkSummary) {
        val file = File(summary.externalPath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(
            Intent.createChooser(intent, "Share chunk")
        )
        log("Shared chunk ${summary.id.take(6)}", notify = true)
    }

    fun shareTranscript(summary: TranscriptSummary) {
        val file = File(summary.srtPath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DayMind transcript ${summary.chunkId}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(
            Intent.createChooser(intent, "Share transcript")
        )
        log("Shared transcript ${summary.id.take(6)}", notify = true)
    }

    fun refreshSummary(force: Boolean = false) {
        if (summaryState.value.isLoading) return
        viewModelScope.launch {
            summaryState.update { it.copy(isLoading = true, error = null) }
            val result = runCatching {
                container.summaryRepository.fetchSummary(summaryState.value.date, force)
            }
            result.onSuccess { payload ->
                summaryState.update {
                    it.copy(
                        date = payload.date,
                        text = payload.markdown,
                        updatedAt = System.currentTimeMillis(),
                        error = null,
                        isLoading = false
                    )
                }
                log("Summary updated for ${payload.date}", notify = true)
            }.onFailure { error ->
                summaryState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Summary fetch failed"
                    )
                }
                log("Summary fetch failed: ${error.message ?: "unknown error"}", notify = true)
            }
        }
    }

    fun updateVadThreshold(value: Float) {
        val rounded = value.roundToInt().coerceIn(1500, 9000)
        audioSettingsFlow.update { it.copy(vadThreshold = rounded) }
        container.configRepository.saveVadThreshold(rounded)
    }

    fun updateVadAggressiveness(value: Float) {
        val rounded = value.roundToInt().coerceIn(0, 3)
        audioSettingsFlow.update { it.copy(vadAggressiveness = rounded) }
        container.configRepository.saveVadAggressiveness(rounded)
    }

    fun updateNoiseGate(value: Float) {
        val clamped = value.coerceIn(0f, 0.6f)
        audioSettingsFlow.update { it.copy(noiseGate = clamped) }
        container.configRepository.saveNoiseGate(clamped)
    }

    private fun log(message: String, notify: Boolean = false) {
        container.logStore.add(message, notify)
    }
}

data class TranscriptSummary(
    val id: String,
    val timestamp: Long,
    val summary: String,
    val fullText: String,
    val chunkId: String,
    val srtPath: String
)

private data class SummaryUiState(
    val date: String,
    val text: String = "",
    val updatedAt: Long? = null,
    val error: String? = null,
    val isLoading: Boolean = false
)

private fun currentUtcDate(): String = LocalDate.now(ZoneOffset.UTC).toString()
