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
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn

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
    val noiseGate: Float = 0.12f
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
    val syncStatus: com.symbioza.daymind.upload.SyncStatus,
    val latestChunkPath: String?,
    val latestExternalPath: String?
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (getApplication() as DayMindApplication).container
    private val audioSettingsFlow = MutableStateFlow(container.configRepository.getAudioSettings())

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

    val uiState: StateFlow<UiState> = combine(
        baseFlow,
        container.chunkRepository.chunkList,
        container.chunkPlaybackManager.isPlaying,
        audioSettingsFlow
    ) { base, chunks, isPlaying, audioSettings ->
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
            noiseGate = audioSettings.noiseGate
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState()
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
}
